package app.nindo

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ============================================================ notifications

object Notify {
    const val CH_FOCUS = "focus"
    const val CH_REPORT = "report"
    const val CH_ALERT = "alert"
    const val ID_FOCUS = 10
    const val ID_REPORT = 11
    const val ID_SHARE = 12
    const val ID_DONE = 13

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_FOCUS, "Focus sessions", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REPORT, "Weekly reports", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Partner messages", NotificationManager.IMPORTANCE_HIGH))
    }

    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(ctx: Context, id: Int, n: NotificationCompat.Builder) {
        if (!allowed(ctx)) return
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n.build()) }
    }

    fun cancel(ctx: Context, id: Int) = ctx.getSystemService(NotificationManager::class.java).cancel(id)

    fun openApp(ctx: Context, report: Boolean = false): PendingIntent = PendingIntent.getActivity(
        ctx, if (report) 2 else 1,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_REPORT, report),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun builder(ctx: Context, channel: String) = NotificationCompat.Builder(ctx, channel)
        .setSmallIcon(R.drawable.ic_stat_shield)
        .setColor(0xFF4F46E5.toInt())
}

/** Lets notifications and alarms ask the UI to open a screen. */
object UiNav {
    val openReport = MutableStateFlow(false)
    /** Guard tab page: 0 Rules, 1 Focus, 2 Protect. */
    val guardPage = MutableStateFlow(0)
}

// ======================================================== accountability partner

object Partner {
    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("nindo:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun checkPin(pin: String) = GuardApp.store.partnerPinHash?.let { it == hash(pin) } ?: false

    fun canSms(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun owner() = GuardApp.store.ownerName.ifBlank { "Your friend" }

    /** Sends [text] to the partner: by SMS when enabled, otherwise via a "Share" notification. */
    fun send(ctx: Context, text: String) {
        val s = GuardApp.store
        if (!s.hasPartner) return
        val body = "Nindō · ${owner()}\n$text"
        if (s.partnerAutoSms && s.partnerPhone.isNotBlank() && canSms(ctx)) {
            val ok = runCatching {
                val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                sms.sendMultipartTextMessage(s.partnerPhone, null, sms.divideMessage(body), null, null)
            }.isSuccess
            if (ok) return
        }
        val share = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body),
            "Send to ${s.partnerName}")
        val pi = PendingIntent.getActivity(ctx, body.hashCode(), share, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Notify.post(ctx, Notify.ID_SHARE, Notify.builder(ctx, Notify.CH_ALERT)
            .setContentTitle("Tap to send to ${s.partnerName}")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true))
    }

    /**
     * Records a protection being weakened and tells the partner (at most once per [gapMs] per [key]).
     * The timeline entry is written even without a partner.
     */
    fun alert(ctx: Context, key: String, text: String, gapMs: Long = 10 * 60_000L) {
        val s = GuardApp.store
        if (System.currentTimeMillis() - s.lastAlertAt(key) < gapMs) return
        s.markAlert(key)
        runCatching { GuardApp.db.event(Kind.TAMPER, null, text) }
        if (s.partnerAlerts) send(ctx, "Nindō alert: $text")
    }
}

// ============================================================== focus sessions

object Focus {
    const val ACTION_END = "app.nindo.FOCUS_END"
    const val ACTION_STOP = "app.nindo.FOCUS_STOP"
    const val ACTION_START = "app.nindo.FOCUS_START"

    fun canStop(s: Store = GuardApp.store) = !s.focusLocked && !s.strict

    fun start(ctx: Context, minutes: Int, locked: Boolean = false) {
        val s = GuardApp.store
        val now = System.currentTimeMillis()
        s.focusStart = now
        s.focusLocked = locked
        s.focusUntil = now + minutes * 60_000L
        GuardApp.db.event(Kind.FOCUS, null, "Focus started", "$minutes min" + if (locked) " · locked" else "")
        rearm(ctx)
    }

    /** Stops early; returns false when the session is locked. */
    fun stop(ctx: Context): Boolean {
        val s = GuardApp.store
        if (!s.focusActive) return true
        if (!canStop(s)) return false
        val dur = System.currentTimeMillis() - s.focusStart
        GuardApp.db.event(Kind.FOCUS, null, "Focus ended early", fmtDuration(dur), dur)
        clear(ctx)
        return true
    }

    /** Records completion if the session's time is up. Safe to call often. */
    fun check(ctx: Context) {
        val s = GuardApp.store
        if (s.focusUntil == 0L || s.focusActive) return
        val dur = s.focusUntil - s.focusStart
        GuardApp.db.event(Kind.FOCUS, null, "Focus session complete", fmtDuration(dur), dur)
        clear(ctx)
        Notify.post(ctx, Notify.ID_DONE, Notify.builder(ctx, Notify.CH_FOCUS)
            .setContentTitle("Focus session complete")
            .setContentText("${fmtDuration(dur)} of focus. That's your nindō — keep moving forward.")
            .setContentIntent(Notify.openApp(ctx)).setAutoCancel(true))
    }

    private fun clear(ctx: Context) {
        val s = GuardApp.store
        s.focusUntil = 0; s.focusLocked = false
        Notify.cancel(ctx, Notify.ID_FOCUS)
        alarm(ctx)?.let { ctx.getSystemService(AlarmManager::class.java).cancel(it) }
        refresh(ctx)
    }

    private fun alarm(ctx: Context, create: Boolean = false): PendingIntent? = PendingIntent.getBroadcast(
        ctx, 20, Intent(ctx, FeatureReceiver::class.java).setAction(ACTION_END),
        PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE),
    )

    /** Re-creates the countdown notification and end alarm (after start, reboot or update). */
    fun rearm(ctx: Context) {
        val s = GuardApp.store
        if (!s.focusActive) { check(ctx); return }
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.focusUntil, alarm(ctx, true)!!)
        val b = Notify.builder(ctx, Notify.CH_FOCUS)
            .setContentTitle("Focus session")
            .setContentText(if (s.focusLocked) "Locked until ${fmtClock(s.focusUntil)}" else "Until ${fmtClock(s.focusUntil)}")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(s.focusUntil)
            .setTimeoutAfter(s.focusUntil - System.currentTimeMillis())
            .setContentIntent(Notify.openApp(ctx))
        if (canStop(s)) b.addAction(0, "Stop", PendingIntent.getBroadcast(ctx, 21,
            Intent(ctx, FeatureReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        Notify.post(ctx, Notify.ID_FOCUS, b)
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        GuardWidget.updateAll(ctx)
        runCatching { android.service.quicksettings.TileService.requestListeningState(ctx, ComponentName(ctx, FocusTileService::class.java)) }
    }
}

fun fmtClock(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

fun fmtUntil(ts: Long): String {
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(ts)) == it.format(Date()) }
    return if (sameDay) fmtClock(ts) else SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ts))
}

// ================================================================= strict mode

object Strict {
    /** System screens that could switch Nindō off or uninstall it. */
    val GUARDED = setOf(
        "com.android.settings", "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.samsung.android.packageinstaller", "com.miui.packageinstaller", "com.miui.securitycenter",
        "com.android.vpndialogs", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.samsung.android.lool", "com.coloros.safecenter", "com.oplus.safecenter", "com.android.settings.intelligence",
    )

    fun start(ctx: Context, durationMs: Long, partnerCanEnd: Boolean) {
        val s = GuardApp.store
        s.strictPartnerCanEnd = partnerCanEnd && s.partnerPinHash != null
        s.strictUntil = System.currentTimeMillis() + durationMs
        GuardApp.db.event(Kind.STRICT, null, "Strict mode on", "until ${fmtUntil(s.strictUntil)}")
        if (s.hasPartner && s.partnerAlerts) Partner.send(ctx, "Strict mode is on until ${fmtUntil(s.strictUntil)}." +
            if (s.strictPartnerCanEnd) " Only your partner PIN can end it early." else "")
    }

    /** Ends strict mode early with the partner's PIN. The PIN is then used up. */
    fun endWithPin(ctx: Context, pin: String): Boolean {
        val s = GuardApp.store
        if (!s.strictPartnerCanEnd || !Partner.checkPin(pin)) return false
        s.strictUntil = 0
        s.partnerPinHash = null
        s.strictPartnerCanEnd = false
        GuardApp.db.event(Kind.STRICT, null, "Strict mode ended early", "with partner PIN")
        Partner.send(ctx, "Strict mode was ended early with your PIN. Please set a new partner PIN next time.")
        return true
    }

    fun admin(ctx: Context) = ComponentName(ctx, GuardAdmin::class.java)
    fun isAdmin(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(ctx))
    fun adminIntent(ctx: Context) = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Stops Nindō from being uninstalled until you turn this off. Nindō asks for no other device powers.")
}

/** Device admin with no policies: its only effect is that the app can't be uninstalled while it's active. */
class GuardAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off lets Nindō be uninstalled." + if (GuardApp.store.hasPartner) " Your partner will be told." else ""

    override fun onDisabled(context: Context, intent: Intent) {
        Partner.alert(context, "admin_off", "Nindō's uninstall protection was turned off.", 0)
    }
}

// ===================================================================== streaks

object Streaks {
    data class Result(val current: Int, val best: Int, val todayOk: Boolean, val todayMs: Long, val goalMs: Long)

    /**
     * [days] oldest first, the last entry being today. A day counts when it was tracked
     * (some screen time) and stayed within the goal. Today only counts once it's over.
     */
    fun compute(days: List<Pair<String, Long>>, goalMs: Long): Result {
        if (days.isEmpty()) return Result(0, 0, true, 0, goalMs)
        fun ok(ms: Long) = ms in 1..goalMs
        val past = days.dropLast(1).map { it.second }
        val todayMs = days.last().second
        var current = 0
        for (ms in past.asReversed()) { if (ok(ms)) current++ else break }
        var best = 0; var run = 0
        for (ms in past) { if (ok(ms)) { run++; best = maxOf(best, run) } else run = 0 }
        return Result(current, maxOf(best, current), todayMs <= goalMs, todayMs, goalMs)
    }

    fun load(): Result = compute(GuardApp.db.lastDays(180), GuardApp.store.screenGoalMin * 60_000L)
}

// ============================================================== weekly reports

data class WeekReport(
    val from: String, val to: String,
    val screenMs: Long, val prevScreenMs: Long,
    val opens: Int, val scrollMs: Long,
    val blocks: Int, val nudges: Int, val focusMs: Long, val tamper: Int,
    val topApps: List<AppStat>,
    val daily: List<Pair<String, Long>>,
    val streak: Streaks.Result,
    val reels: Int = 0,
) {
    val changePct: Int? get() = if (prevScreenMs > 0) (((screenMs - prevScreenMs) * 100) / prevScreenMs).toInt() else null
}

object Reports {
    const val ACTION_WEEKLY = "app.nindo.WEEKLY_REPORT"
    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun build(): WeekReport {
        val db = GuardApp.db
        val week = db.lastDays(14)
        val cur = week.takeLast(7); val prev = week.take(7)
        val from = cur.first().first; val to = cur.last().first
        val fromTs = dayFmt.parse(from)!!.time
        val toTs = dayFmt.parse(to)!!.time + 86_400_000L
        val (_, opens, scroll) = db.rangeUsage(from, to)
        return WeekReport(
            from, to,
            cur.sumOf { it.second }, prev.sumOf { it.second },
            opens, scroll,
            db.countEvents(listOf(Kind.BLOCK), fromTs, toTs) + db.dnsBlocked(from, to),
            db.countEvents(listOf(Kind.NUDGE), fromTs, toTs),
            db.focusMs(fromTs, toTs),
            db.countEvents(listOf(Kind.TAMPER), fromTs, toTs),
            db.rangeApps(from, to, 5), cur, Streaks.load(),
            db.reelsRange(from, to),
        )
    }

    fun text(ctx: Context, r: WeekReport): String = buildString {
        val days = r.daily.count { it.second > 0 }.coerceAtLeast(1)
        appendLine("Weekly report")
        append("Screen time: ${fmtDuration(r.screenMs)} (${fmtDuration(r.screenMs / days)}/day)")
        r.changePct?.let { append(if (it <= 0) " · ${-it}% less than last week" else " · $it% more than last week") }
        appendLine()
        appendLine("Focus: ${fmtDuration(r.focusMs)} · Scrolling: ${fmtDuration(r.scrollMs)}")
        if (r.reels > 0) appendLine("Reels & Shorts: ${r.reels} (${r.reels / days}/day)")
        appendLine("App opens: ${r.opens} · Blocks: ${r.blocks} · Scroll nudges: ${r.nudges}")
        appendLine("Streak: ${r.streak.current} day(s) within goal (best ${r.streak.best})")
        runCatching { HabitWeek.load() }.getOrNull()?.takeIf { it.due > 0 }?.let { h ->
            appendLine("Habits: ${h.done} of ${h.due} done (${h.done * 100 / h.due}%) · +${h.xp} XP · best streak ${h.bestStreak} days")
        }
        if (r.topApps.isNotEmpty()) appendLine("Top apps: " + r.topApps.take(3).joinToString(", ") { "${Apps.label(ctx, it.pkg)} ${fmtDuration(it.ms)}" })
        if (r.tamper > 0) appendLine("Protection changes this week: ${r.tamper}")
    }.trim()

    /** Next Sunday at 20:00. */
    fun nextRun(now: Calendar = Calendar.getInstance()): Long {
        val c = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            while (get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || !after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun schedule(ctx: Context) {
        val pi = PendingIntent.getBroadcast(ctx, 30, Intent(ctx, FeatureReceiver::class.java).setAction(ACTION_WEEKLY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextRun(), pi)
    }

    fun deliver(ctx: Context) {
        val s = GuardApp.store
        val r = build()
        val text = text(ctx, r)
        if (s.reportsOn) Notify.post(ctx, Notify.ID_REPORT, Notify.builder(ctx, Notify.CH_REPORT)
            .setContentTitle("Your week: ${fmtDuration(r.screenMs)} screen time")
            .setContentText(r.changePct?.let { if (it <= 0) "${-it}% less than last week. Well done." else "$it% more than last week" } ?: "Tap to see your report")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(Notify.openApp(ctx, report = true)).setAutoCancel(true))
        if (s.hasPartner && s.partnerWeekly) Partner.send(ctx, text)
    }
}

// =================================================================== receivers

/** Alarms, notification buttons, widget taps and boot. */
class FeatureReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Focus.ACTION_END -> Focus.check(ctx)
            Focus.ACTION_STOP -> { Focus.stop(ctx); Focus.rearm(ctx) }
            Focus.ACTION_START -> if (!GuardApp.store.focusActive) Focus.start(ctx, GuardApp.store.focusDefaultMin)
            Reports.ACTION_WEEKLY -> { Reports.deliver(ctx); Reports.schedule(ctx) }
            Reminders.ACTION_FIRE, Reminders.ACTION_DONE, Reminders.ACTION_SNOOZE, Reminders.ACTION_SKIP -> {
                val done = goAsync()
                Thread { try { runCatching { Reminders.onReceive(ctx, intent) } } finally { done.finish() } }.start()
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Reports.schedule(ctx); Focus.rearm(ctx)
                Thread { runCatching { Reminders.scheduleAll(ctx) } }.start()
                if (GuardApp.store.vpnWanted && VpnService.prepare(ctx) == null) runCatching { DnsVpnService.start(ctx) }
            }
        }
    }
}
