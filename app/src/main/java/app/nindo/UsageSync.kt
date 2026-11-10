package app.nindo

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Imports app usage from Android's own history (Usage Access) into the tracker,
 * so Today and Activity are filled even for time the watcher wasn't running:
 * the last 7 days on first run, then any gap since the last sync.
 *
 * Time the watcher was running is skipped, so nothing is counted twice.
 */
object UsageSync {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    private const val BACKFILL_DAYS = 7
    private const val MAX_SESSION_MS = 4 * HOUR
    /** Read a bit before the window so an app already open at its start is seen. */
    private const val LOOKBACK_MS = 3 * HOUR

    // UsageEvents.Event types (literals: some constants are newer than minSdk 26).
    private const val RESUMED = 1            // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
    private const val SCREEN_OFF = 16        // SCREEN_NON_INTERACTIVE
    private const val KEYGUARD_SHOWN = 17
    private const val SHUTDOWN = 26          // DEVICE_SHUTDOWN

    private val lock = Any()
    private val exec = Executors.newSingleThreadExecutor()

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            ctx.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else mode == AppOpsManager.MODE_ALLOWED
    }

    /** Opens Usage Access, on Nindō's own page where the phone supports it. */
    fun openSettings(ctx: Context) {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(direct) }.recoverCatching { ctx.startActivity(list) }
    }

    @Volatile private var lastAsyncAt = 0L

    fun syncAsync(ctx: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAsyncAt < 60_000) return
        lastAsyncAt = now
        val app = ctx.applicationContext
        exec.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { sync(app) }
        }
    }

    /** Returns the number of app opens imported. */
    fun sync(ctx: Context): Int {
        if (!hasAccess(ctx)) return 0
        synchronized(lock) { return syncLocked(ctx) }
    }

    private fun syncLocked(ctx: Context): Int {
        val store = GuardApp.store
        val db = GuardApp.db
        val now = System.currentTimeMillis()
        val watching = UrlWatcherService.running.value
        val firstRun = store.usageSyncedUntil == 0L

        val from = if (firstRun) startOfDay(now - (BACKFILL_DAYS - 1) * DAY)
            else maxOf(store.usageSyncedUntil, store.watcherAliveAt)
        var to = if (watching && store.watcherStartedAt > 0) minOf(now, store.watcherStartedAt) else now
        // First run on a phone where the watcher already logged something: import only before that.
        if (firstRun) db.firstEventTs(Kind.OPEN)?.let { to = minOf(to, it) }

        val opens = if (to > from) importRange(ctx, from, to) else 0
        store.usageSyncedUntil = now
        return opens
    }

    private fun importRange(ctx: Context, from: Long, to: Long): Int {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val skip = setOf("com.android.systemui", "android") + Apps.imePackages(ctx)
        val neutral = Apps.homePackages(ctx) + ctx.packageName

        data class Session(val pkg: String, val start: Long, val end: Long)
        val sessions = ArrayList<Session>()
        var cur: String? = null
        var start = 0L
        fun close(at: Long) {
            cur?.let { p ->
                val end = minOf(at, start + MAX_SESSION_MS)
                if (end > start) sessions += Session(p, start, end)
            }
            cur = null
        }

        val events = usm.queryEvents(from - LOOKBACK_MS, to)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                RESUMED -> {
                    val p = e.packageName ?: continue
                    if (p in skip || p == cur) continue
                    close(e.timeStamp)
                    if (p !in neutral) { cur = p; start = e.timeStamp }
                }
                SCREEN_OFF, KEYGUARD_SHOWN, SHUTDOWN -> close(e.timeStamp)
            }
        }
        close(to)

        // Labels first (PackageManager calls), then short write batches so the watcher, which
        // writes on the main thread, never waits more than a few milliseconds for the database.
        val labels = sessions.map { it.pkg }.distinct().associateWith { Apps.label(ctx, it) }
        var opens = 0
        for (batch in sessions.chunked(60)) {
            db().inTransaction {
                for (s in batch) {
                    // Time: only the part inside the window, split at local hour boundaries.
                    var t = maxOf(s.start, from)
                    val b = minOf(s.end, to)
                    while (t < b) {
                        val chunkEnd = minOf(b, nextHour(t))
                        db().addAppTimeAt(s.pkg, t, chunkEnd - t)
                        t = chunkEnd
                    }
                    // Open: only if the app was opened inside the window.
                    if (s.start in from until to) {
                        val dur = s.end - s.start
                        db().addOpenAt(s.pkg, s.start)
                        db().eventAt(s.start, Kind.OPEN, s.pkg, labels.getValue(s.pkg),
                            "Used ${fmtDuration(dur)} · from Android history", dur)
                        opens++
                    }
                }
            }
            Thread.sleep(8)
        }
        return opens
    }

    private fun db() = GuardApp.db

    private fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nextHour(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.HOUR_OF_DAY, 1)
    }.timeInMillis
}

/** Helpers for the accessibility "watcher". */
object Watcher {
    fun component(ctx: Context) = ComponentName(ctx, UrlWatcherService::class.java)

    /** True when the switch is on in Settings, even if the service hasn't connected yet. */
    fun enabledInSettings(ctx: Context): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = component(ctx)
        return list.split(':').any { ComponentName.unflattenFromString(it) == cn }
    }

    /** Opens Accessibility settings, scrolled to Nindō where the phone supports it. */
    fun openSettings(ctx: Context) {
        val key = component(ctx).flattenToString()
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", key)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    fun openAppInfo(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
