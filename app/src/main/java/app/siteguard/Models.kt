package app.siteguard

import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** What a rule applies to. */
enum class Target { APP, DOMAIN, KEYWORD, CATEGORY }

/**
 * One blocking rule. Pieces combine:
 *  - conditions (schedule, location) decide WHEN the rule is in force;
 *  - limits (daily minutes, daily opens) decide how much is allowed while it is in force.
 * With no limits the target is fully blocked whenever the conditions match.
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val target: Target,
    /** Package name for APP, domain for DOMAIN, word for KEYWORD, category id for CATEGORY. */
    val value: String,
    /** Display name (app label, or the value itself). */
    val label: String = value,
    val scheduleOn: Boolean = false,
    val startMin: Int = 22 * 60,
    val endMin: Int = 7 * 60,
    /** Bit 0 = Monday … bit 6 = Sunday. */
    val days: Int = ALL_DAYS,
    val limitMinutes: Int = 0,
    val launchLimit: Int = 0,
    val placeId: String? = null,
    /** true = applies while at the place; false = applies while away from it. */
    val placeInside: Boolean = true,
    val enabled: Boolean = true,
) {
    val hasLimits get() = limitMinutes > 0 || launchLimit > 0

    /** Website/keyword match against a normalised URL and host. */
    fun matchesSite(url: String, host: String): Boolean {
        val p = value.trim().lowercase()
        if (p.isEmpty()) return false
        return when (target) {
            Target.DOMAIN -> p.removePrefix("www.").let { host == it || host.endsWith(".$it") }
            Target.KEYWORD -> url.lowercase().contains(p)
            Target.CATEGORY -> Blocklists.containsHost(p, host)
            Target.APP -> false
        }
    }

    /** @param dayIdx 0 = Monday … 6 = Sunday. @param inside null when location is unknown. */
    fun conditionsMet(minOfDay: Int, dayIdx: Int, inside: Boolean?): Boolean {
        if (scheduleOn) {
            val inWindow = if (startMin <= endMin) minOfDay in startMin until endMin
            else minOfDay >= startMin || minOfDay < endMin
            if (!inWindow) return false
            // An overnight window belongs to the day it started on.
            val startDay = if (startMin > endMin && minOfDay < endMin) (dayIdx + 6) % 7 else dayIdx
            if (days and (1 shl startDay) == 0) return false
        }
        if (placeId != null) {
            if (inside == null) return false
            if (inside != placeInside) return false
        }
        return true
    }

    /** True when usage has gone past the limits (or there are no limits). */
    fun limitHit(usedMs: Long, opens: Int): Boolean {
        if (!hasLimits) return true
        if (limitMinutes > 0 && usedMs >= limitMinutes * 60_000L) return true
        if (launchLimit > 0 && opens > launchLimit) return true
        return false
    }

    fun reason(usedMs: Long, opens: Int, placeName: String?): String = when {
        limitMinutes > 0 && usedMs >= limitMinutes * 60_000L ->
            "Daily limit of ${fmtMinutes(limitMinutes)} reached. Resets at midnight."
        launchLimit > 0 && opens > launchLimit ->
            "You've used all $launchLimit opens for today."
        scheduleOn -> "Blocked ${fmtTime(startMin)}–${fmtTime(endMin)}" +
            (placeName?.let { if (placeInside) " at $it" else " away from $it" } ?: "")
        placeName != null -> if (placeInside) "Blocked while you're at $placeName" else "Blocked while you're away from $placeName"
        else -> "Always blocked"
    }

    fun summaryParts(placeName: String?): List<String> = buildList {
        if (limitMinutes > 0) add("${fmtMinutes(limitMinutes)}/day")
        if (launchLimit > 0) add("$launchLimit opens/day")
        if (scheduleOn) add("${fmtTime(startMin)}–${fmtTime(endMin)}${daysLabel(days)}")
        if (placeName != null) add(if (placeInside) "at $placeName" else "away from $placeName")
        if (!hasLimits && !scheduleOn && placeName == null) add("Always blocked")
        else if (!hasLimits) add("Fully blocked")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("target", target.name); put("value", value); put("label", label)
        put("scheduleOn", scheduleOn); put("startMin", startMin); put("endMin", endMin); put("days", days)
        put("limitMinutes", limitMinutes); put("launchLimit", launchLimit)
        put("placeId", placeId ?: JSONObject.NULL); put("placeInside", placeInside); put("enabled", enabled)
    }

    companion object {
        const val ALL_DAYS = 0b1111111

        fun fromJson(o: JSONObject) = Rule(
            id = o.getString("id"),
            target = Target.valueOf(o.getString("target")),
            value = o.getString("value"),
            label = o.optString("label", o.getString("value")),
            scheduleOn = o.optBoolean("scheduleOn"),
            startMin = o.optInt("startMin", 22 * 60),
            endMin = o.optInt("endMin", 7 * 60),
            days = o.optInt("days", ALL_DAYS),
            limitMinutes = o.optInt("limitMinutes"),
            launchLimit = o.optInt("launchLimit"),
            placeId = if (o.isNull("placeId")) null else o.optString("placeId"),
            placeInside = o.optBoolean("placeInside", true),
            enabled = o.optBoolean("enabled", true),
        )

        /** Converts a v1 rule (pattern/matchType/mode) into the v2 model. */
        fun fromV1(o: JSONObject): Rule {
            val pattern = o.getString("pattern")
            val mode = o.optString("mode")
            return Rule(
                id = o.optString("id", UUID.randomUUID().toString()),
                target = if (o.optString("matchType") == "KEYWORD") Target.KEYWORD else Target.DOMAIN,
                value = pattern, label = pattern,
                scheduleOn = mode == "TIME_WINDOW",
                startMin = o.optInt("startMin", 22 * 60), endMin = o.optInt("endMin", 7 * 60),
                limitMinutes = if (mode == "DAILY_LIMIT") o.optInt("limitMinutes") else 0,
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

data class Place(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int = 150,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("lat", lat); put("lng", lng); put("radiusM", radiusM)
    }

    companion object {
        fun fromJson(o: JSONObject) = Place(
            o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lng"), o.optInt("radiusM", 150)
        )
    }
}

/** Splits address-bar text into a normalised URL and host. */
object UrlUtil {
    fun normalise(raw: String): Pair<String, String>? {
        var t = raw.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        t = t.removePrefix("https://").removePrefix("http://")
        val host = t.substringBefore('/').substringBefore('?').substringBefore(':')
            .lowercase().removePrefix("www.")
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
        return t to host
    }
}

fun fmtTime(min: Int) = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)

fun fmtMinutes(min: Int) = if (min >= 60) {
    val h = min / 60; val m = min % 60
    if (m == 0) "${h}h" else "${h}h ${m}m"
} else "${min}m"

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        s >= 60 -> "${s / 60}m"
        else -> "${s}s"
    }
}

private val DAY_LETTERS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun daysLabel(mask: Int): String = when (mask) {
    Rule.ALL_DAYS -> ""
    0b0011111 -> " Mon–Fri"
    0b1100000 -> " Sat–Sun"
    else -> " " + DAY_LETTERS.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(",")
}

val DAY_NAMES = DAY_LETTERS
