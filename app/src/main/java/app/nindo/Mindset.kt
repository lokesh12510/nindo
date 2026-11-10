package app.nindo

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.Calendar
import java.util.UUID

/** A photo and/or note shown on the pause screen. [image] is a file name in [AnchorImages.dir]. */
data class Anchor(val id: String = UUID.randomUUID().toString(), val note: String, val image: String? = null) {
    fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("note", note); image?.let { put("image", it) } }
    companion object {
        fun fromJson(o: JSONObject) = Anchor(o.getString("id"), o.optString("note"), o.optString("image").takeIf { it.isNotBlank() })
    }
}

data class TaskItem(val id: String = UUID.randomUUID().toString(), val text: String, val done: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("text", text); put("done", done) }
    companion object {
        fun fromJson(o: JSONObject) = TaskItem(o.getString("id"), o.getString("text"), o.optBoolean("done"))
    }
}

/** What opened the pause screen. */
enum class Trigger(val title: String) {
    VPN_OFF("Turning off the filter"),
    ADULT("Adult content"),
    WEAKEN("Weakening protection"),
    NIGHT("Past your bedtime"),
    PREVIEW("Preview"),
}

/**
 * The pause screen ("urge interrupt"): 15 seconds that can't be skipped, your own photo and
 * note, an honest roast and your real numbers, then "Close and go home" or type a phrase.
 */
object Urges {
    const val SECONDS = 15
    const val PHRASE = "I am a loser"

    /** Typed phrase check: case and spacing don't matter, and "looser" counts too. */
    fun phraseOk(s: String): Boolean {
        val t = s.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '!')
        return t == "i am a loser" || t == "i am a looser" || t == "i'm a loser" || t == "i'm a looser"
    }

    enum class Result { CLOSED, CONTINUED, DISMISSED }

    class Request(
        val trigger: Trigger,
        /** App or site involved, for the log. */
        val subject: String,
        val pkg: String? = null,
        /** Runs only if the person types the phrase. */
        val onContinue: (() -> Unit)? = null,
        /** An extra button, e.g. "Turn the filter back on". */
        val extraLabel: String? = null,
        val onExtra: (() -> Unit)? = null,
    )

    /** Set by the watcher: shows the request as an overlay over any app. */
    @Volatile var overlayHost: ((Request) -> Boolean)? = null

    /** The request on screen, for the "type the phrase" screen. */
    @Volatile var pending: Request? = null
        private set
    @Volatile private var pendingAt = 0L

    private val lastShown = HashMap<String, Long>()
    @Volatile var adultAllowedUntil = 0L

    /**
     * Shows the pause screen. If it's switched off, [Request.onContinue] runs straight away.
     * Returns false if it wasn't shown (switched off, or the same pause was shown moments ago).
     */
    fun request(ctx: Context, r: Request): Boolean {
        val s = GuardApp.store
        if (!s.pauseOn && r.trigger != Trigger.PREVIEW) { r.onContinue?.invoke(); return false }
        val now = System.currentTimeMillis()
        // Already on screen (and not stale): don't restart its countdown.
        pending?.let { p -> if (now - pendingAt < 120_000 && p.trigger == r.trigger) return true }
        val key = "${r.trigger}|${r.subject}"
        val gap = if (r.trigger == Trigger.ADULT) 4_000 else 20_000
        if (r.trigger != Trigger.PREVIEW && now - (lastShown[key] ?: 0) < gap) return false
        lastShown[key] = now
        pending = r; pendingAt = now
        if (overlayHost?.invoke(r) == true) return true
        // No watcher: show it as a full-screen activity instead.
        runCatching {
            ctx.startActivity(Intent(ctx, PauseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onFailure { pending = null; r.onContinue?.invoke(); return false }
        return true
    }

    /** Called once by whichever screen handled [r]. */
    fun resolve(ctx: Context, r: Request, result: Result) {
        if (pending === r) pending = null
        if (r.trigger == Trigger.PREVIEW || result == Result.DISMISSED) return
        GuardApp.db.event(Kind.URGE, r.pkg, r.trigger.title, if (result == Result.CONTINUED) "continued" else "resisted")
        if (result == Result.CONTINUED) {
            if (r.trigger != Trigger.NIGHT) GuardApp.store.resetClean()
            if (r.trigger == Trigger.ADULT) adultAllowedUntil = System.currentTimeMillis() + 10 * 60_000L
            if (r.trigger == Trigger.VPN_OFF || r.trigger == Trigger.ADULT) {
                Partner.alert(ctx, "urge_continue", "Continued past a Nindō pause screen (${r.trigger.title.lowercase()}).", gapMs = 30 * 60_000L)
            }
            r.onContinue?.invoke()
        }
    }

    // ------------------------------------------------------------ detection

    /** Explicit words that only show up in adult searches (matched as whole words in search queries). */
    private val ADULT_WORDS = setOf(
        "porn", "porno", "pornhub", "xxx", "xvideos", "xnxx", "xhamster", "nsfw", "nude", "nudes", "naked", "hentai",
        "onlyfans", "milf", "camgirl", "camgirls", "sexvideo", "sexy", "boobs", "blowjob", "brazzers", "redtube", "youporn",
        "chaturbate", "stripchat", "escort", "escorts", "fansly", "rule34", "nhentai", "desi49", "mms", "bhabhi",
    )
    private val SEARCH_PARAMS = setOf("q", "p", "query", "search_query", "text", "wd", "search")

    fun isAdult(url: String, host: String): Boolean {
        if (System.currentTimeMillis() < adultAllowedUntil) return false
        if (Blocklists.containsHost("adult", host)) return true
        // Search queries: decode and look for explicit words.
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return false
        for (part in q.split('&')) {
            val k = part.substringBefore('=').lowercase()
            if (k !in SEARCH_PARAMS) continue
            val v = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault("").lowercase()
            val words = v.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            if (words.any { it in ADULT_WORDS }) return true
            if (v.contains("sex video") || v.contains("sex videos")) return true
        }
        return false
    }

    /** Inside the bedtime window, or Do Not Disturb at night (Bedtime mode). */
    fun inBedtime(ctx: Context): Boolean {
        val s = GuardApp.store
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        if (s.bedOn) {
            val a = s.bedStartMin; val b = s.bedEndMin
            val inside = if (a <= b) m in a until b else m >= a || m < b
            if (inside) return true
        }
        if (s.bedDnd) {
            val h = c.get(Calendar.HOUR_OF_DAY)
            val night = h >= 21 || h < 6
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (night && nm != null && nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return true
        }
        return false
    }

    // ------------------------------------------------------------ words

    /** The facts block: real numbers that make it personal. */
    fun facts(ctx: Context, r: Request): List<String> = buildList {
        val s = GuardApp.store
        val clean = s.cleanDays
        if (r.trigger != Trigger.NIGHT) {
            add(if (clean > 0) "🛡️ $clean clean day${if (clean == 1) "" else "s"}. Continuing resets it to zero."
                else "🛡️ Day 0. Today can be day 1.")
        }
        val c = Calendar.getInstance()
        val now = fmtTime(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
        if (inBedtime(ctx)) add("😴 It's $now. " + if (s.bedOn) "Bedtime was ${fmtTime(s.bedStartMin)}." else "Bedtime mode is on.")
        val open = s.tasks().filter { !it.done }
        if (open.isNotEmpty()) add("✅ Still to do: " + open.take(3).joinToString(", ") { it.text } + if (open.size > 3) " +${open.size - 3} more" else "")
    }

    fun roast(r: Request): String {
        val s = GuardApp.store
        val clean = s.cleanDays
        val lines = when (r.trigger) {
            Trigger.VPN_OFF, Trigger.WEAKEN -> when (s.roastLevel) {
                0 -> listOf("You set this up on a good day. Trust that version of you.",
                    "This switch is easy to flip. That's exactly why you made it hard.",
                    "Pause. What are you actually looking for right now?")
                2 -> listOf("Bold of you to disable a filter you built yourself. Ninja way? More like ninja delay.",
                    "You wrote the code to stop this exact moment. And now you're trying to outsmart… yourself?",
                    "Turning off your own guard at the gate. Very strategic. Truly a genius.")
                else -> listOf("You built this filter for moments exactly like this one.",
                    "Nobody turns off a porn filter to read the news. Be honest with yourself.",
                    "The urge is loud right now. In 15 minutes it'll be gone. The regret won't be.")
            }
            Trigger.ADULT -> when (s.roastLevel) {
                0 -> listOf("It's okay to feel the urge. It's not okay to obey it.",
                    "Close it, drink some water, step outside for two minutes.",
                    "You're stronger than one tap.")
                2 -> listOf("$clean days of discipline vs. 5 minutes of nonsense. Tough math, huh?",
                    "Your future self is watching this. He's not impressed.",
                    "The ninja way says never give up. Not 'never give up searching for this'.")
                else -> listOf("This won't fix the boredom, stress or loneliness behind it. It never has.",
                    "You know exactly how you'll feel in 10 minutes if you continue.",
                    if (clean > 0) "$clean days clean. Is this worth starting from zero?" else "Every streak starts with one closed tab.")
            }
            Trigger.NIGHT -> when (s.roastLevel) {
                0 -> listOf("Your body needs sleep more than your feed needs you.", "Put the phone down. Tomorrow-you will thank you.")
                2 -> listOf("It's late and your future self is already disappointed. He hasn't even woken up yet.",
                    "Nothing good is posted after midnight. Nothing.")
                else -> listOf("Late-night scrolling steals tomorrow's energy.",
                    "You set a bedtime for a reason. This is the reason.")
            }
            Trigger.PREVIEW -> listOf("This is how the pause screen looks. Add a photo and a note that would stop you.")
        }
        return lines.random()
    }
}

/** Anchor photos, downscaled and stored privately in the app. */
object AnchorImages {
    fun dir(ctx: Context) = File(ctx.filesDir, "anchors").apply { mkdirs() }

    /** Copies a picked image in, at most 1280 px on its long side. Returns the file name. */
    fun import(ctx: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val name = "${UUID.randomUUID()}.jpg"
        File(dir(ctx), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        name
    }.getOrNull()

    fun load(ctx: Context, name: String, maxPx: Int = 900): Bitmap? = runCatching {
        val f = File(dir(ctx), name)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    fun delete(ctx: Context, name: String?) { name?.let { File(dir(ctx), it).delete() } }
}
