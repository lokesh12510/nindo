package app.nindo

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The on-device watcher (keeps its v1 class name so the Accessibility toggle survives the update).
 *  - tracks which app is in front: screen time, opens, app blocking, time/open limits
 *  - reads browser address bars: site history, site time, website rules
 *  - watches scrolling: doom-scroll sessions and nudges
 */
class UrlWatcherService : AccessibilityService() {

    companion object {
        val running = MutableStateFlow(false)

        /** Browser package -> (name, address-bar view IDs). */
        val BROWSERS: Map<String, Pair<String, List<String>>> = mapOf(
            "com.android.chrome" to ("Chrome" to listOf("url_bar")),
            "com.chrome.beta" to ("Chrome Beta" to listOf("url_bar")),
            "com.chrome.dev" to ("Chrome Dev" to listOf("url_bar")),
            "com.brave.browser" to ("Brave" to listOf("url_bar")),
            "com.microsoft.emmx" to ("Edge" to listOf("url_bar")),
            "com.kiwibrowser.browser" to ("Kiwi" to listOf("url_bar")),
            "com.vivaldi.browser" to ("Vivaldi" to listOf("url_bar")),
            "org.mozilla.firefox" to ("Firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title")),
            "org.mozilla.firefox_beta" to ("Firefox Beta" to listOf("mozac_browser_toolbar_url_view")),
            "org.mozilla.focus" to ("Firefox Focus" to listOf("mozac_browser_toolbar_url_view", "display_url")),
            "com.sec.android.app.sbrowser" to ("Samsung Internet" to listOf("location_bar_edit_text", "url_bar_text")),
            "com.opera.browser" to ("Opera" to listOf("url_field")),
            "com.opera.mini.native" to ("Opera Mini" to listOf("url_field")),
            "com.duckduckgo.mobile.android" to ("DuckDuckGo" to listOf("omnibarTextInput")),
            "com.mi.globalbrowser" to ("Mi Browser" to listOf("url")),
        )

        private const val TICK_MS = 10_000L
        private const val MAX_CHUNK_MS = 120_000L
        /** A pause longer than this ends a scrolling session. */
        private const val SCROLL_GAP_MS = 60_000L
        private const val SWIPE_DEBOUNCE_MS = 700L
        private const val SNOOZE_MS = 5 * 60_000L
    }

    private val store get() = GuardApp.store
    private val db get() = GuardApp.db
    private val handler = Handler(Looper.getMainLooper())

    /** Windows that sit over an app without replacing it (shade, keyboard, system dialogs). */
    private var transparent: Set<String> = emptySet()
    /** Windows that mean "no app in front" (launcher, this app). */
    private var neutral: Set<String> = emptySet()
    private var protected: Set<String> = emptySet()

    // foreground app
    private var currentPkg: String? = null
    private var pkgSince = 0L

    // browser page
    private var currentHost: String? = null
    private var currentUrl: String? = null
    private var hostSince = 0L
    private var hostRuleIds: List<String> = emptyList()

    // scrolling session
    private var scrollPkg: String? = null
    private var scrollStart = 0L
    private var lastScroll = 0L
    private var lastSwipe = 0L
    private var swipes = 0
    private var snoozes = 0
    private var snoozeUntil = 0L
    private var lastNudgeAt = 0L

    /** Category rules that cover the app in front (for combined limits). */
    private var appRuleIds: List<String> = emptyList()
    private var lastGuardCheck = 0L
    private var lastWidgetAt = 0L
    private var lastAliveAt = 0L

    // reels & shorts
    private var overlay: ReelOverlay? = null
    private var blockOverlay: BlockOverlay? = null
    private var pauseOverlay: PauseOverlay? = null
    private var lastNightAt = 0L
    private val reelSession = Reels.Session()
    private var reelPkg: String? = null
    private var reelsCount = 0
    private var reelsDay = ""
    private var lastReelAt = 0L
    private var lastCapActionAt = 0L
    private var lastCapToastAt = 0L
    private var lastIdCheck = 0L
    private var pagerKey: String? = null
    /** Reel pagers seen filling the screen during this visit to the app. */
    private val confirmedPagers = HashSet<String>()
    private var pagerAt = 0L

    private var lastBlockKey: String? = null
    private var lastBlockAt = 0L
    private var lastPruneDay = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { switchTo(null); endScrollSession() }
                Intent.ACTION_USER_PRESENT -> rootInActiveWindow?.packageName?.toString()?.let(::onWindow)
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, TICK_MS) }
    }

    override fun onServiceConnected() {
        refreshPackageSets()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        FocusBus.listener = ::onNudgeResult
        overlay = ReelOverlay(this)
        blockOverlay = BlockOverlay(this)
        pauseOverlay = PauseOverlay(this)
        Urges.overlayHost = { r -> pauseOverlay?.show(r) ?: false }
        refreshReelDay(force = true)
        LocationTracker.start(this)
        handler.post(ticker)
        val now = System.currentTimeMillis()
        store.watcherStartedAt = now; store.watcherAliveAt = now; lastAliveAt = now
        running.value = true
        UsageSync.syncAsync(this) // fill in what happened while the watcher was off
    }

    private fun refreshPackageSets() {
        transparent = setOf("com.android.systemui", "android") + Apps.imePackages(this)
        neutral = Apps.homePackages(this) + packageName
        protected = Apps.neverBlock(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown(); return super.onUnbind(intent)
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }

    private fun shutdown() {
        if (!running.value) return
        Partner.alert(this, "watcher_off", "The Nindō watcher was switched off (or the phone restarted).")
        switchTo(null); endScrollSession()
        handler.removeCallbacks(ticker)
        runCatching { unregisterReceiver(screenReceiver) }
        FocusBus.listener = null
        overlay?.destroy(); overlay = null
        blockOverlay?.destroy(); blockOverlay = null
        Urges.overlayHost = null
        pauseOverlay?.destroy(); pauseOverlay = null
        val now = System.currentTimeMillis()
        store.watcherAliveAt = now; store.usageSyncedUntil = now
        running.value = false
    }

    override fun onInterrupt() {}

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg in Strict.GUARDED && store.strict &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) strictGuard(pkg)
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onWindow(pkg)
                if (pkg in BROWSERS) readBrowser(pkg)
                checkReelEntry(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg in BROWSERS) readBrowser(pkg)
                checkReelEntry(pkg)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                onScroll(pkg)
                onReelScroll(event, pkg)
            }
        }
    }

    private fun onWindow(pkg: String) {
        // Nindō's own overlays (brain bubble, block card) fire window events, but the app underneath is still in front.
        if (pkg == packageName && rootInActiveWindow?.packageName?.toString().let { it != null && it != packageName }) return
        when (pkg) {
            in transparent -> return
            in neutral -> switchTo(null)
            else -> switchTo(pkg)
        }
    }

    // ------------------------------------------------------ foreground apps

    private fun switchTo(newPkg: String?) {
        if (newPkg == currentPkg) return
        val now = System.currentTimeMillis()
        flush(now)
        // Going home or seeing the nudge doesn't end a session; the 60 s gap or another app does.
        if (scrollPkg != null && newPkg != null && scrollPkg != newPkg) endScrollSession()
        currentHost = null; currentUrl = null; hostRuleIds = emptyList()
        if (newPkg != reelPkg) { reelSession.reset(); reelPkg = null; overlay?.hide() }
        currentPkg = newPkg
        pkgSince = now
        appRuleIds = newPkg?.let { p -> Engine.matchingAppCategoryRules(this, p).map { it.id } } ?: emptyList()
        if (newPkg != null) {
            store.addRuleVisit(appRuleIds)
            db.addOpen(newPkg)
            db.event(Kind.OPEN, newPkg, Apps.label(this, newPkg))
            checkApp(newPkg)
            checkBedtime(newPkg, now)
        }
    }

    /** Moves time since the last flush into the counters. */
    private fun flush(now: Long) {
        currentPkg?.let { pkg ->
            val d = (now - pkgSince).coerceIn(0, MAX_CHUNK_MS)
            db.addAppTime(pkg, d)
            store.addRuleMs(appRuleIds, d)
            pkgSince = now
        }
        currentHost?.let { host ->
            val d = (now - hostSince).coerceIn(0, MAX_CHUNK_MS)
            db.addSiteTime(host, d)
            store.addRuleMs(hostRuleIds, d)
            hostSince = now
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        flush(now)
        Focus.check(this)
        if (now - lastWidgetAt > 60_000) { lastWidgetAt = now; GuardWidget.updateAll(this) }
        if (now - lastAliveAt > 30_000) { lastAliveAt = now; store.watcherAliveAt = now }
        currentPkg?.let(::checkApp)
        val host = currentHost; val url = currentUrl
        if (host != null && url != null) Engine.checkSite(url, host)?.let { blockSite(host, it) }
        if (scrollPkg != null && now - lastScroll > SCROLL_GAP_MS) endScrollSession()
        refreshReelDay()
        val d = today()
        if (d != lastPruneDay) {
            lastPruneDay = d
            store.pruneCounters(); db.prune(); refreshPackageSets()
        }
    }

    private fun checkApp(pkg: String) {
        if (pkg in protected) return
        val v = Engine.checkApp(this, pkg) ?: return
        val label = Apps.label(this, pkg)
        if (block("app:$pkg", label, v.reason, goHome = true)) {
            db.addAppBlock(pkg)
            db.event(Kind.BLOCK, pkg, label, v.reason)
        }
    }

    /** Opening a browser or a social/reel app at bedtime: the pause screen with a sleep message, at most every 30 min. */
    private fun checkBedtime(pkg: String, now: Long) {
        if (!store.pauseOn || now - lastNightAt < 30 * 60_000L) return
        if (pkg !in BROWSERS && pkg !in Apps.SOCIAL && pkg !in store.reelApps) return
        if (!Urges.inBedtime(this)) return
        lastNightAt = now
        Urges.request(this, Urges.Request(Trigger.NIGHT, Apps.label(this, pkg), pkg))
    }

    // ------------------------------------------------------------- browsers

    private fun readBrowser(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val raw = findUrl(root, pkg, BROWSERS.getValue(pkg).second) ?: return
        val (url, host) = UrlUtil.normalise(raw) ?: return
        if (currentPkg != pkg) switchTo(pkg)
        val now = System.currentTimeMillis()
        if (host != currentHost) {
            flush(now)
            currentHost = host
            hostSince = now
            hostRuleIds = Engine.matchingSiteRules(url, host).map { it.id }
            store.addRuleVisit(hostRuleIds)
            db.addSiteVisit(host)
            db.event(Kind.VISIT, pkg, host, url)
        }
        currentUrl = url
        // Adult site or search: the pause screen instead of a plain block card. The page is sent back.
        if (store.pauseOn && Urges.isAdult(url, host) &&
            Urges.request(this, Urges.Request(Trigger.ADULT, host, pkg))
        ) { performGlobalAction(GLOBAL_ACTION_BACK); return }
        Engine.checkSite(url, host)?.let { blockSite(host, it) }
    }

    private fun blockSite(host: String, v: Engine.Verdict) {
        if (block("site:$host", host, v.reason, goHome = false)) {
            db.addSiteBlock(host)
            db.event(Kind.BLOCK, currentPkg, host, v.reason)
        }
    }

    private fun findUrl(root: AccessibilityNodeInfo, pkg: String, ids: List<String>): String? {
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { !it.text.isNullOrBlank() }
            if (node != null) return if (node.isFocused) null else node.text.toString()
        }
        return searchFallback(root, 0)
    }

    /** For browser updates that rename the address bar: look for a URL-like view by ID. */
    private fun searchFallback(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 12) return null
        val id = node.viewIdResourceName?.lowercase() ?: ""
        if ((id.contains("url") || id.contains("address") || id.contains("omnibox") || id.contains("location")) &&
            !node.text.isNullOrBlank() && !node.isFocused
        ) {
            val t = node.text.toString()
            if (UrlUtil.normalise(t) != null) return t
        }
        for (i in 0 until node.childCount) searchFallback(node.getChild(i), depth + 1)?.let { return it }
        return null
    }

    // -------------------------------------------------------------- blocking

    /** Returns false when the same thing was just blocked (debounce). */
    private fun block(key: String, title: String, reason: String, goHome: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (key == lastBlockKey && now - lastBlockAt < 1500) return false
        lastBlockKey = key; lastBlockAt = now
        flush(now)
        performGlobalAction(if (goHome) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        val headline = if (reason.contains("limit", true) || reason.contains("opens", true)) "Time's up for $title" else "$title is blocked"
        blockOverlay?.show(headline, reason)
        return true
    }

    // ---------------------------------------------------------- strict mode

    /** During strict mode, closes any system screen that shows Nindō (app info, uninstall, accessibility, VPN, admin). */
    private fun strictGuard(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastGuardCheck < 400) return
        lastGuardCheck = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val mentions = root.findAccessibilityNodeInfosByText(getString(R.string.app_name)).isNotEmpty()
        if (!mentions) return
        if (block("strict:$pkg", "Nindō settings", "Strict mode is on until ${fmtUntil(store.strictUntil)}. Protections can't be changed until then.", goHome = true)) {
            Partner.alert(this, "strict_attempt", "Tried to open Nindō's system settings during strict mode.", 30 * 60_000L)
        }
    }

    // -------------------------------------------------------- reels & shorts

    private fun refreshReelDay(force: Boolean = false) {
        val d = today()
        if (force || d != reelsDay) { reelsDay = d; reelsCount = db.reelsToday() }
    }

    private val capReached get() = store.reelCap > 0 && reelsCount >= store.reelCap

    /** Each swipe to a new reel/short adds one; the bubble and popups follow the count. */
    private fun onReelScroll(event: AccessibilityEvent, pkg: String) {
        if (!store.reelsOn || pkg !in store.reelApps) return
        val src = event.source ?: return
        if (!isReelPager(pkg, src, event)) return
        val now = System.currentTimeMillis()
        lastReelAt = now
        if (reelPkg != pkg) { reelSession.reset(); reelPkg = pkg; confirmedPagers.clear() }
        refreshReelDay()
        val cap = store.reelCap
        if (capReached) { capAction(pkg); return }
        val index = if (event.itemCount > 0 && event.fromIndex >= 0) event.fromIndex else -1
        // Android reports -1 when the app didn't set a delta: treat it as unknown, not as a scroll.
        val deltaY = if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaY.takeIf { it != -1 } ?: 0 else 0
        val pageH = Rect().also { src.getBoundsInScreen(it) }.height()
        if (!reelSession.onScroll(index, now, deltaY, pageH)) return

        reelsCount++
        db.addReel(pkg)
        if (store.reelBubble && Reels.shouldFlash(reelsCount)) overlay?.flash(reelsCount, cap)
        if (store.reelMilestones) Reels.milestone(reelsCount, cap)?.let { overlay?.toast(it) }
        if (cap > 0 && reelsCount >= cap) {
            db.event(Kind.REELS, pkg, "Reel limit reached", "$reelsCount reels today")
            handler.postDelayed({ capAction(pkg) }, 1600)
        }
    }

    /** At the cap: close the reels screen, or block the whole app, as chosen. */
    private fun capAction(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastCapActionAt < 1500) return
        lastCapActionAt = now
        if (store.reelCapWholeApp || pkg in Reels.SHORT_VIDEO_ONLY) { checkApp(pkg); return }
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (now - lastCapToastAt > 8000) {
            lastCapToastAt = now
            blockOverlay?.show("Reels closed for today", "Reel limit of ${store.reelCap} reached. Resets at midnight.", "🧟", 3000)
            db.addAppBlock(pkg)
            db.event(Kind.BLOCK, pkg, "Reels in ${Apps.label(this, pkg)}", "Reel limit of ${store.reelCap} reached")
        }
    }

    /** With "close reels only", leaving the cap means re-entering reels is closed straight away. */
    private fun checkReelEntry(pkg: String) {
        if (!store.reelsOn || !capReached || store.reelCapWholeApp || pkg !in store.reelApps) return
        val ids = Reels.KNOWN_IDS[pkg] ?: return
        val now = System.currentTimeMillis()
        if (now - lastIdCheck < 1200) return
        lastIdCheck = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        if (ids.any { root.findAccessibilityNodeInfosByViewId("$pkg:id/$it").isNotEmpty() }) capAction(pkg)
    }

    /**
     * A reel player is a known reel view, or a full-screen vertical pager whose pages
     * fill the screen (unlike a normal feed, whose posts are shorter than the screen).
     */
    private fun isReelPager(pkg: String, src: AccessibilityNodeInfo, event: AccessibilityEvent): Boolean {
        if (Build.VERSION.SDK_INT >= 28 && kotlin.math.abs(event.scrollDeltaX) > 1 &&
            kotlin.math.abs(event.scrollDeltaX) > kotlin.math.abs(event.scrollDeltaY)) return false // sideways: stories, carousels
        val r = Rect().also { src.getBoundsInScreen(it) }
        val id = src.viewIdResourceName
        val shortId = id?.substringAfter(":id/")
        if (shortId != null && Reels.KNOWN_IDS[pkg]?.contains(shortId) == true) return true

        val key = "$pkg|$id|${src.className}|${r.flattenToString()}"
        if (key in confirmedPagers) return true
        val now = System.currentTimeMillis()
        if (key == pagerKey && now - pagerAt < 300) return false

        // Full-screen pager whose page fills it. Mid-swipe a page is only partly visible, so a pager
        // is confirmed once (when a page fills it) and then trusted for the rest of the visit.
        val dm = resources.displayMetrics
        val full = r.height() >= dm.heightPixels * 0.7f && r.width() >= dm.widthPixels * 0.9f
        val ok = full && (0 until src.childCount).any { i ->
            val child = src.getChild(i) ?: return@any false
            val cr = Rect().also { child.getBoundsInScreen(it) }
            cr.height() >= r.height() * 0.8f && cr.width() >= r.width() * 0.9f
        }
        if (ok) confirmedPagers += key else { pagerKey = key; pagerAt = now }
        return ok
    }

    // ------------------------------------------------------- doom scrolling

    private fun watchesScroll(pkg: String): Boolean {
        if (!store.doomEnabled || pkg in transparent || pkg in neutral) return false
        return store.doomAllApps || pkg in store.doomApps
    }

    private fun onScroll(pkg: String) {
        if (!watchesScroll(pkg)) return
        val now = System.currentTimeMillis()
        if (scrollPkg != pkg || now - lastScroll > SCROLL_GAP_MS) {
            endScrollSession()
            scrollPkg = pkg; scrollStart = now; swipes = 0; snoozes = 0; snoozeUntil = 0
        }
        if (now - lastSwipe > SWIPE_DEBOUNCE_MS) { swipes++; lastSwipe = now }
        lastScroll = now

        val dur = now - scrollStart
        if (dur >= store.doomThresholdMin * 60_000L && now >= snoozeUntil && now - lastNudgeAt > 30_000) {
            lastNudgeAt = now
            val label = Apps.label(this, pkg)
            db.event(Kind.NUDGE, pkg, label, "${dur / 60_000} min · $swipes swipes")
            startActivity(
                Intent(this, NudgeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(NudgeActivity.EXTRA_PKG, pkg)
                    .putExtra(NudgeActivity.EXTRA_LABEL, label)
                    .putExtra(NudgeActivity.EXTRA_MINUTES, (dur / 60_000).toInt())
                    .putExtra(NudgeActivity.EXTRA_SWIPES, swipes)
                    .putExtra(NudgeActivity.EXTRA_CAN_SNOOZE, snoozes < store.doomMaxSnoozes)
            )
        }
    }

    private fun endScrollSession() {
        val pkg = scrollPkg ?: return
        val dur = lastScroll - scrollStart
        if (dur >= 10_000) db.addScroll(pkg, dur, swipes)
        if (dur >= 2 * 60_000) db.event(Kind.SCROLL, pkg, Apps.label(this, pkg), "$swipes swipes", dur)
        scrollPkg = null
    }

    private fun onNudgeResult(pkg: String, snooze: Boolean) {
        if (snooze) {
            snoozes++
            snoozeUntil = System.currentTimeMillis() + SNOOZE_MS
        } else {
            val cool = store.doomCooldownMin
            if (cool > 0) store.setCooldown(pkg, System.currentTimeMillis() + cool * 60_000L)
            endScrollSession()
        }
    }
}

/** Lets the nudge screen report the user's choice back to the watcher (same process). */
object FocusBus {
    @Volatile var listener: ((pkg: String, snooze: Boolean) -> Unit)? = null
}
