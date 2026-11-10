package app.nindo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/** Holds the process-wide store and tracker database. */
class GuardApp : Application() {
    companion object {
        lateinit var app: GuardApp; private set
        lateinit var store: Store; private set
        lateinit var db: Db; private set
        lateinit var habits: HabitDb; private set
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        store = Store(this)
        db = Db(this)
        habits = HabitDb(this)
        Thread { runCatching { habits.seedIfNeeded() }; runCatching { Reminders.scheduleAll(this) } }.start()
        Thread { Blocklists.init(filesDir) }.start()
        Notify.createChannels(this)
        Reports.schedule(this)
        Focus.rearm(this)
        LocationTracker.start(this)
    }
}

/** Installed-app helpers. */
object Apps {
    /** Default apps watched by the doom-scroll guard. */
    val SOCIAL = setOf(
        "com.instagram.android", "com.google.android.youtube", "com.facebook.katana", "com.facebook.lite",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.twitter.android", "com.reddit.frontpage",
        "com.snapchat.android", "in.mohalla.sharechat", "in.mohalla.video", "com.eterno.shortvideos",
        "com.linkedin.android", "com.pinterest", "com.threads.android", "com.instagram.barcelona",
    )

    /** Apps that are never blocked, so the phone always stays usable. */
    private val SAFE = setOf(
        "com.android.settings", "com.android.systemui", "android",
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.android.phone",
        "com.android.emergency", "com.google.android.apps.safetyhub", "com.android.server.telecom",
    )

    data class AppInfo(val pkg: String, val label: String)

    private val labels = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, Bitmap>()

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    fun icon(ctx: Context, pkg: String): Bitmap? = icons[pkg] ?: runCatching {
        ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).also { icons[pkg] = it }
    }.getOrNull()

    fun launchable(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName && it !in neverBlock(ctx) }
            .map { AppInfo(it, label(ctx, it)) }
            .sortedBy { it.label.lowercase() }
    }

    fun homePackages(ctx: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
    }

    fun imePackages(ctx: Context): Set<String> =
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .inputMethodList.map { it.packageName }.toSet()

    fun neverBlock(ctx: Context): Set<String> = SAFE + homePackages(ctx) + ctx.packageName

    private val games = ConcurrentHashMap<String, Boolean>()

    fun isGame(ctx: Context, pkg: String): Boolean = games.getOrPut(pkg) {
        runCatching {
            ctx.packageManager.getApplicationInfo(pkg, 0).category == android.content.pm.ApplicationInfo.CATEGORY_GAME
        }.getOrDefault(false)
    }
}

/** Keeps the latest location for place-based rules. */
object LocationTracker {
    val current = MutableStateFlow<Location?>(null)
    private var started = false

    private val listener = LocationListener { loc -> accept(loc) }

    private fun accept(loc: Location) {
        val cur = current.value
        // Prefer a newer fix unless it's much less accurate and the old one is fresh.
        if (cur == null || loc.time - cur.time > 60_000 || loc.accuracy <= cur.accuracy) current.value = loc
    }

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (started || !hasPermission(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        providers.forEach { p ->
            lm.getLastKnownLocation(p)?.let(::accept)
            runCatching { lm.requestLocationUpdates(p, 120_000L, 50f, listener, Looper.getMainLooper()) }
        }
        runCatching { lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000L, 25f, listener, Looper.getMainLooper()) }
        started = true
    }

    /** Asks for one fresh fix (used when saving a place). */
    @SuppressLint("MissingPermission")
    fun refresh(ctx: Context, done: (Location?) -> Unit) {
        if (!hasPermission(ctx)) { done(null); return }
        start(ctx)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> { done(current.value); return }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc -> loc?.let(::accept); done(loc ?: current.value) }
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, { loc -> accept(loc); done(loc) }, Looper.getMainLooper())
        }
    }

    /** null when location is unknown. */
    fun isInside(place: Place): Boolean? {
        val loc = current.value ?: return null
        val d = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, place.lat, place.lng, d)
        return d[0] <= place.radiusM + loc.accuracy.coerceAtMost(100f)
    }
}

/** Decides whether an app, page or domain is blocked right now. */
object Engine {
    data class Verdict(val rule: Rule?, val reason: String)

    private fun now(): Pair<Int, Int> {
        val c = Calendar.getInstance()
        val min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val dayIdx = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
        return min to dayIdx
    }

    private fun inForce(r: Rule, store: Store, min: Int, day: Int): Boolean {
        val place = store.place(r.placeId)
        val inside = place?.let { LocationTracker.isInside(it) }
        return r.copy(placeId = place?.id).conditionsMet(min, day, inside)
    }

    /** Category rules whose app list includes [pkg]. */
    fun matchingAppCategoryRules(ctx: Context, pkg: String, store: Store = GuardApp.store) =
        store.rules().filter { it.enabled && it.target == Target.CATEGORY && Blocklists.containsApp(it.value, pkg) { p -> Apps.isGame(ctx, p) } }

    fun checkApp(ctx: Context, pkg: String, store: Store = GuardApp.store, db: Db = GuardApp.db): Verdict? {
        val now = System.currentTimeMillis()
        val cool = store.cooldownUntil(pkg)
        if (cool > now) {
            return Verdict(null, "Break time. You chose to stop scrolling — back in ${(cool - now) / 60_000 + 1} min.")
        }
        if (store.focusActive) {
            val inFocus = pkg in store.focusApps ||
                store.focusCategories.any { Blocklists.containsApp(it, pkg) { p -> Apps.isGame(ctx, p) } }
            if (inFocus) return Verdict(null, "Focus session · ${(store.focusUntil - now) / 60_000 + 1} min left. You've got this.")
        }
        if (store.reelsOn && store.reelCap > 0 && pkg in store.reelApps &&
            (store.reelCapWholeApp || pkg in Reels.SHORT_VIDEO_ONLY) && db.reelsToday() >= store.reelCap
        ) return Verdict(null, "Reel limit reached: ${store.reelCap} reels today. Resets at midnight.")
        val (min, day) = now()
        val appRules = store.rules().filter { it.enabled && it.target == Target.APP && it.value == pkg }
        if (appRules.isNotEmpty()) {
            val (ms, opens) = db.appUsage(pkg)
            for (r in appRules) {
                if (inForce(r, store, min, day) && r.limitHit(ms, opens)) return Verdict(r, r.reason(ms, opens, store.place(r.placeId)?.name))
            }
        }
        for (r in matchingAppCategoryRules(ctx, pkg, store)) {
            val ms = store.ruleMs(r.id); val opens = store.ruleVisits(r.id)
            if (inForce(r, store, min, day) && r.limitHit(ms, opens)) {
                return Verdict(r, "${r.label}: " + r.reason(ms, opens, store.place(r.placeId)?.name))
            }
        }
        return null
    }

    fun matchingSiteRules(url: String, host: String, store: Store = GuardApp.store) =
        store.rules().filter { it.enabled && it.target != Target.APP && it.matchesSite(url, host) }

    fun checkSite(url: String, host: String, store: Store = GuardApp.store): Verdict? {
        if (store.focusActive && store.focusCategories.any { Blocklists.containsHost(it, host) }) {
            return Verdict(null, "Focus session · ${(store.focusUntil - System.currentTimeMillis()) / 60_000 + 1} min left.")
        }
        val rules = matchingSiteRules(url, host, store)
        if (rules.isEmpty()) return null
        val (min, day) = now()
        for (r in rules) {
            val ms = store.ruleMs(r.id); val visits = store.ruleVisits(r.id)
            if (inForce(r, store, min, day) && r.limitHit(ms, visits)) {
                val why = r.reason(ms, visits, store.place(r.placeId)?.name)
                return Verdict(r, if (r.target == Target.CATEGORY) "${r.label}: $why" else why)
            }
        }
        return null
    }

    /** DNS lookups only carry a domain name. */
    fun checkDomain(domain: String): Verdict? = checkSite(domain, domain.removePrefix("www."))
}
