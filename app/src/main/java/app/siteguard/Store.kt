package app.siteguard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** Rules, places, settings and per-rule usage counters. Process-wide singleton. */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("siteguard", Context.MODE_PRIVATE)

    /** Bumped on every change so screens can refresh. */
    val version = MutableStateFlow(0)
    private fun changed() { version.value++ }

    // ---------- rules ----------
    @Volatile private var rulesCache: List<Rule>? = null

    fun rules(): List<Rule> = rulesCache ?: loadRules().also { rulesCache = it }

    private fun loadRules(): List<Rule> {
        prefs.getString("rules_v2", null)?.let { s ->
            val a = JSONArray(s)
            return (0 until a.length()).mapNotNull { runCatching { Rule.fromJson(a.getJSONObject(it)) }.getOrNull() }
        }
        // First run of v2: migrate v1 rules.
        val old = JSONArray(prefs.getString("rules", "[]"))
        val migrated = (0 until old.length()).mapNotNull { runCatching { Rule.fromV1(old.getJSONObject(it)) }.getOrNull() }
        writeRules(migrated)
        return migrated
    }

    private fun writeRules(list: List<Rule>) {
        prefs.edit().putString("rules_v2", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
        rulesCache = list
    }

    fun upsertRule(r: Rule) {
        val list = rules().toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list.add(r)
        writeRules(list); changed()
    }

    fun deleteRule(id: String) { writeRules(rules().filter { it.id != id }); changed() }

    // ---------- places ----------
    @Volatile private var placesCache: List<Place>? = null

    fun places(): List<Place> = placesCache ?: run {
        val a = JSONArray(prefs.getString("places", "[]"))
        (0 until a.length()).map { Place.fromJson(a.getJSONObject(it)) }.also { placesCache = it }
    }

    private fun writePlaces(list: List<Place>) {
        prefs.edit().putString("places", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
        placesCache = list; changed()
    }

    fun upsertPlace(p: Place) = writePlaces(places().filter { it.id != p.id } + p)
    fun deletePlace(id: String) {
        writePlaces(places().filter { it.id != id })
        // Rules tied to a deleted place would never match again; drop the condition.
        writeRules(rules().map { if (it.placeId == id) it.copy(placeId = null) else it })
    }
    fun place(id: String?) = id?.let { pid -> places().firstOrNull { it.id == pid } }

    // ---------- per-rule usage (websites) ----------
    fun ruleMs(id: String): Long = prefs.getLong("ms_${today()}_$id", 0)
    fun ruleVisits(id: String): Int = prefs.getInt("vis_${today()}_$id", 0)

    fun addRuleMs(ids: Collection<String>, ms: Long) {
        if (ms <= 0 || ids.isEmpty()) return
        val d = today(); val e = prefs.edit()
        ids.forEach { e.putLong("ms_${d}_$it", prefs.getLong("ms_${d}_$it", 0) + ms) }
        e.apply()
    }

    fun addRuleVisit(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val d = today(); val e = prefs.edit()
        ids.forEach { e.putInt("vis_${d}_$it", prefs.getInt("vis_${d}_$it", 0) + 1) }
        e.apply()
    }

    fun pruneCounters() {
        val d = today(); val e = prefs.edit()
        prefs.all.keys.filter { (it.startsWith("ms_") || it.startsWith("vis_")) && !it.contains("_${d}_") }
            .forEach { e.remove(it) }
        e.apply()
    }

    // ---------- break cooldowns from the doom-scroll guard ----------
    fun cooldownUntil(pkg: String): Long = prefs.getLong("cool_$pkg", 0)
    fun setCooldown(pkg: String, until: Long) = prefs.edit().putLong("cool_$pkg", until).apply()

    // ---------- settings ----------
    var pin: String?
        get() = prefs.getString("pin", null)
        set(v) { prefs.edit().putString("pin", v).apply(); changed() }

    var doomEnabled: Boolean
        get() = prefs.getBoolean("doom_on", true)
        set(v) { prefs.edit().putBoolean("doom_on", v).apply(); changed() }

    /** Minutes of continuous scrolling before the nudge. */
    var doomThresholdMin: Int
        get() = prefs.getInt("doom_min", 10)
        set(v) { prefs.edit().putInt("doom_min", v).apply(); changed() }

    /** Break length after "I'm done"; 0 = no enforced break. */
    var doomCooldownMin: Int
        get() = prefs.getInt("doom_cool", 15)
        set(v) { prefs.edit().putInt("doom_cool", v).apply(); changed() }

    var doomMaxSnoozes: Int
        get() = prefs.getInt("doom_snooze", 2)
        set(v) { prefs.edit().putInt("doom_snooze", v).apply(); changed() }

    var doomAllApps: Boolean
        get() = prefs.getBoolean("doom_all", false)
        set(v) { prefs.edit().putBoolean("doom_all", v).apply(); changed() }

    var doomApps: Set<String>
        get() = prefs.getStringSet("doom_apps", null) ?: Apps.SOCIAL
        set(v) { prefs.edit().putStringSet("doom_apps", v).apply(); changed() }

    var screenGoalMin: Int
        get() = prefs.getInt("goal", 180)
        set(v) { prefs.edit().putInt("goal", v).apply(); changed() }

    /** 0 = standard, 1 = block malware, 2 = block malware + adult. */
    var dnsLevel: Int
        get() = prefs.getInt("dns_level", 0)
        set(v) { prefs.edit().putInt("dns_level", v).apply(); changed() }

    var vpnWanted: Boolean
        get() = prefs.getBoolean("vpn_on", false)
        set(v) { prefs.edit().putBoolean("vpn_on", v).apply(); changed() }

    // ---------- focus sessions ----------
    var focusUntil: Long
        get() = prefs.getLong("focus_until", 0)
        set(v) { prefs.edit().putLong("focus_until", v).apply(); changed() }
    var focusStart: Long
        get() = prefs.getLong("focus_start", 0)
        set(v) { prefs.edit().putLong("focus_start", v).apply() }
    /** Locked sessions can't be stopped early. */
    var focusLocked: Boolean
        get() = prefs.getBoolean("focus_locked", false)
        set(v) { prefs.edit().putBoolean("focus_locked", v).apply(); changed() }
    var focusApps: Set<String>
        get() = prefs.getStringSet("focus_apps", null) ?: Apps.SOCIAL
        set(v) { prefs.edit().putStringSet("focus_apps", v).apply(); changed() }
    var focusCategories: Set<String>
        get() = prefs.getStringSet("focus_cats", null) ?: setOf("social", "video")
        set(v) { prefs.edit().putStringSet("focus_cats", v).apply(); changed() }
    var focusDefaultMin: Int
        get() = prefs.getInt("focus_default", 25)
        set(v) { prefs.edit().putInt("focus_default", v).apply(); changed() }
    val focusActive get() = focusUntil > System.currentTimeMillis()

    // ---------- strict mode ----------
    var strictUntil: Long
        get() = prefs.getLong("strict_until", 0)
        set(v) { prefs.edit().putLong("strict_until", v).apply(); changed() }
    var strictPartnerCanEnd: Boolean
        get() = prefs.getBoolean("strict_partner", false)
        set(v) { prefs.edit().putBoolean("strict_partner", v).apply(); changed() }
    val strict get() = strictUntil > System.currentTimeMillis()

    // ---------- accountability partner ----------
    var partnerName: String
        get() = prefs.getString("partner_name", "") ?: ""
        set(v) { prefs.edit().putString("partner_name", v).apply(); changed() }
    var partnerPhone: String
        get() = prefs.getString("partner_phone", "") ?: ""
        set(v) { prefs.edit().putString("partner_phone", v).apply(); changed() }
    /** true = send by SMS automatically; false = notification asking you to share. */
    var partnerAutoSms: Boolean
        get() = prefs.getBoolean("partner_sms", false)
        set(v) { prefs.edit().putBoolean("partner_sms", v).apply(); changed() }
    var partnerWeekly: Boolean
        get() = prefs.getBoolean("partner_weekly", true)
        set(v) { prefs.edit().putBoolean("partner_weekly", v).apply(); changed() }
    var partnerAlerts: Boolean
        get() = prefs.getBoolean("partner_alerts", true)
        set(v) { prefs.edit().putBoolean("partner_alerts", v).apply(); changed() }
    /** SHA-256 of the PIN the partner typed in; only they know it. */
    var partnerPinHash: String?
        get() = prefs.getString("partner_pin", null)
        set(v) { prefs.edit().putString("partner_pin", v).apply(); changed() }
    val hasPartner get() = partnerName.isNotBlank()
    /** Your name, shown in messages to the partner. */
    var ownerName: String
        get() = prefs.getString("owner_name", "") ?: ""
        set(v) { prefs.edit().putString("owner_name", v).apply(); changed() }

    fun lastAlertAt(key: String) = prefs.getLong("alert_$key", 0)
    fun markAlert(key: String) = prefs.edit().putLong("alert_$key", System.currentTimeMillis()).apply()

    // ---------- reels & shorts counter ----------
    var reelsOn: Boolean
        get() = prefs.getBoolean("reels_on", true)
        set(v) { prefs.edit().putBoolean("reels_on", v).apply(); changed() }
    var reelBubble: Boolean
        get() = prefs.getBoolean("reel_bubble", true)
        set(v) { prefs.edit().putBoolean("reel_bubble", v).apply(); changed() }
    var reelMilestones: Boolean
        get() = prefs.getBoolean("reel_milestones", true)
        set(v) { prefs.edit().putBoolean("reel_milestones", v).apply(); changed() }
    /** Daily reel cap across all reel apps; 0 = no cap. */
    var reelCap: Int
        get() = prefs.getInt("reel_cap", 0)
        set(v) { prefs.edit().putInt("reel_cap", v).apply(); changed() }
    /** At the cap: true = block the whole app, false = close only the reels/shorts screen. */
    var reelCapWholeApp: Boolean
        get() = prefs.getBoolean("reel_whole", false)
        set(v) { prefs.edit().putBoolean("reel_whole", v).apply(); changed() }
    var reelApps: Set<String>
        get() = prefs.getStringSet("reel_apps", null) ?: Reels.DEFAULT_APPS
        set(v) { prefs.edit().putStringSet("reel_apps", v).apply(); changed() }

    var reportsOn: Boolean
        get() = prefs.getBoolean("reports_on", true)
        set(v) { prefs.edit().putBoolean("reports_on", v).apply(); changed() }
}
