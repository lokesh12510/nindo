package app.nindo

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.Locale

/**
 * Habit reminders. One daily alarm per habit at its reminder time; when it fires and the habit
 * is due and not done yet, it reminds in the chosen way: a notification, a spoken reminder
 * (plus the notification), or a popup over the screen with Done / Snooze buttons.
 */
object Reminders {
    const val CH_HABITS = "habits"
    const val ACTION_FIRE = "app.nindo.HABIT_REMIND"
    const val ACTION_DONE = "app.nindo.HABIT_DONE"
    const val ACTION_SNOOZE = "app.nindo.HABIT_SNOOZE"
    const val ACTION_SKIP = "app.nindo.HABIT_SKIP"
    const val EXTRA_ID = "habit"
    private const val SNOOZE_MIN = 10

    /** Set by the watcher while it runs, so popups can be drawn over any app. */
    @Volatile var overlayHost: ((Habit, String) -> Boolean)? = null

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH_HABITS, "Habit reminders", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun code(id: String) = 5000 + (id.hashCode() and 0x7ffff)

    private fun pending(ctx: Context, action: String, id: String, extra: Int = 0): PendingIntent =
        PendingIntent.getBroadcast(ctx, code(id) + extra, Intent(ctx, FeatureReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun nextAt(minuteOfDay: Int, now: Calendar = Calendar.getInstance()): Long {
        val c = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60); set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun scheduleAll(ctx: Context) {
        createChannel(ctx)
        GuardApp.habits.habits(true).forEach { h ->
            when {
                h.archived -> cancel(ctx, h.id)
                h.isCycle -> nextCycleAt(h)?.let { schedule(ctx, h.id, it) } ?: cancel(ctx, h.id)
                h.cfg.remind == Remind.NONE -> cancel(ctx, h.id)
                else -> schedule(ctx, h.id, nextAt(h.cfg.remindMin))
            }
        }
    }

    private fun minuteNow(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** The next cycle reminder of a repeating timer: later today, or the first one on the next due day. */
    fun nextCycleAt(h: Habit): Long? {
        val times = h.cycleTimes
        if (times.isEmpty()) return null
        val now = minuteNow()
        for (i in 0..14) {
            val day = Days.shift(today(), i)
            if (!h.scheduledOn(day)) continue
            val m = if (i == 0) times.firstOrNull { it > now } else times.first()
            if (m != null) return Days.startMs(day) + m * 60_000L
        }
        return null
    }

    private fun schedule(ctx: Context, id: String, at: Long) {
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, ACTION_FIRE, id))
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, ACTION_FIRE, id))
        Notify.cancel(ctx, code(id))
    }

    fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val h = GuardApp.habits.habit(id) ?: return
        when (intent.action) {
            ACTION_FIRE -> if (h.isCycle) cycleFired(ctx, h) else {
                if (h.cfg.remind != Remind.NONE) schedule(ctx, id, nextAt(h.cfg.remindMin))
                if (dueAndOpen(h)) remind(ctx, h)
            }
            ACTION_DONE -> { GuardApp.habits.complete(h); Notify.cancel(ctx, code(id)) }
            ACTION_SKIP -> { GuardApp.habits.answerCycle(id, false); Notify.cancel(ctx, code(id)) }
            ACTION_SNOOZE -> { snooze(ctx, h) }
        }
    }

    /** A repeating timer's cycle: the last one left unanswered counts as missed, then this one is shown. */
    private fun cycleFired(ctx: Context, h: Habit) {
        val db = GuardApp.habits
        db.pendingCycle(h.id)?.let { (day, _) -> db.setPendingCycle(h.id, null); db.addMiss(day, h.id) }
        val now = minuteNow()
        val due = h.scheduledOn(today()) && h.cycleTimes.any { it in (now - 15)..(now + 2) }
        if (due) {
            db.setPendingCycle(h.id, today(), now)
            val times = h.cycleTimes
            val n = times.indexOfFirst { it >= now - 2 }.let { if (it < 0) times.size else it + 1 }
            val done = db.value(today(), h.id)
            val missed = db.value(today(), "${h.id}#miss")
            val msg = "Reminder $n of ${times.size} · $done done, $missed missed." + if (h.cfg.description.isNotBlank()) " ${h.cfg.description}" else ""
            when (h.cfg.remind) {
                Remind.POPUP -> if (overlayHost?.invoke(h, msg) != true) notify(ctx, h, msg)
                Remind.VOICE -> { notify(ctx, h, msg); Voice.say(ctx, "Time for ${h.name}.") }
                else -> notify(ctx, h, msg)
            }
        }
        nextCycleAt(h)?.let { schedule(ctx, h.id, it) }
    }

    fun snooze(ctx: Context, h: Habit) {
        Notify.cancel(ctx, code(h.id))
        // A one-off alarm on a different request code, so the daily one stays.
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + SNOOZE_MIN * 60_000L, pending(ctx, ACTION_FIRE, h.id, extra = 1))
    }

    /** Scheduled today and not finished (avoid habits never need a reminder to "do" them, but can ask for one). */
    private fun dueAndOpen(h: Habit): Boolean {
        val d = HabitEngine.dayHabits(today()).firstOrNull { it.habit.id == h.id } ?: return false
        return !d.done && !d.failed
    }

    fun message(h: Habit): String {
        val streak = HabitEngine.lastOrLoad()?.streaks?.get(h.id)?.current ?: 0
        val base = h.cfg.description.ifBlank {
            when (h.kind) {
                HabitKind.COUNT -> "${h.target} ${h.unit.ifBlank { "times" }} today."
                HabitKind.TIMER -> "${h.target} minutes today."
                HabitKind.CHECKLIST -> "${h.goal} items to tick."
                HabitKind.AVOID -> "Stay strong today."
                else -> "A small step keeps the streak alive."
            }
        }
        return if (streak > 0) "$base You're on a $streak-day streak." else base
    }

    fun remind(ctx: Context, h: Habit) {
        val msg = message(h)
        when (h.cfg.remind) {
            Remind.POPUP -> if (overlayHost?.invoke(h, msg) != true) notify(ctx, h, msg)
            Remind.VOICE -> { notify(ctx, h, msg); Voice.say(ctx, "Hey. It's time for ${h.name}. $msg") }
            else -> notify(ctx, h, msg)
        }
    }

    private fun notify(ctx: Context, h: Habit, msg: String) {
        createChannel(ctx)
        val b = Notify.builder(ctx, CH_HABITS)
            .setContentTitle(if (h.isCycle) h.name else "Time for ${h.name}")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(Notify.openApp(ctx)).setAutoCancel(true)
        if (h.isCycle) {
            b.addAction(0, "Skip", pending(ctx, ACTION_SKIP, h.id, extra = 4))
            b.addAction(0, "Done", pending(ctx, ACTION_DONE, h.id, extra = 3))
        } else {
            b.addAction(0, "Snooze $SNOOZE_MIN min", pending(ctx, ACTION_SNOOZE, h.id, extra = 2))
            if (h.kind != HabitKind.AVOID) b.addAction(0, if (h.kind == HabitKind.CHECK) "Done" else "Mark complete", pending(ctx, ACTION_DONE, h.id, extra = 3))
        }
        Notify.post(ctx, code(h.id), b)
    }
}

/** Speaks one line with the phone's text-to-speech, then lets go of the engine. */
object Voice {
    private var tts: TextToSpeech? = null

    fun say(ctx: Context, text: String) {
        val app = ctx.applicationContext
        // The engine binds to a service and calls back on the main thread, so start it there.
        android.os.Handler(android.os.Looper.getMainLooper()).post { start(app, text) }
    }

    private fun start(app: Context, text: String) {
        tts?.shutdown()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(app) { status ->
            val e = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { e.shutdown(); return@TextToSpeech }
            e.language = Locale.getDefault()
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { e.shutdown(); if (tts === e) tts = null }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { e.shutdown(); if (tts === e) tts = null }
            })
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nindo-remind")
        }
        tts = engine
    }
}

/** The reminder popup over any app, drawn by the watcher. */
class ReminderOverlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = svc.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private var root: View? = null

    fun show(h: Habit, msg: String): Boolean {
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(svc).apply {
            text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }
        fun button(label: String, filled: Boolean, onClick: () -> Unit) = text(label, 15f, if (filled) 0xFF312E81.toInt() else 0xFFFFFFFF.toInt(), true).apply {
            setPadding(px(12), px(14), px(12), px(14))
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                if (filled) setColor(0xFFFFFFFF.toInt()) else { setColor(0x1AFFFFFF); setStroke(px(1), 0x55FFFFFF) }
            }
            setOnClickListener { onClick() }
        }
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF4338CA.toInt(), 0xFF7C3AED.toInt()))
                .apply { cornerRadius = px(28).toFloat() }
            elevation = px(16).toFloat()
        }
        card.addView(text(if (h.isCycle) "TIMER" else "HABIT REMINDER", 11f, 0xB3FFFFFF.toInt(), true).apply { letterSpacing = 0.15f })
        card.addView(text(h.name, 24f, 0xFFFFFFFF.toInt(), true), lp(8))
        card.addView(text(msg, 15f, 0xE6FFFFFF.toInt()), lp(8))
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(button(if (h.isCycle) "Skip" else "Snooze", false) {
            close()
            if (h.isCycle) Thread { runCatching { GuardApp.habits.answerCycle(h.id, false) } }.start() else Reminders.snooze(svc, h)
        }, half)
        if (h.kind != HabitKind.AVOID) row.addView(button(if (h.kind == HabitKind.CHECK) "Done" else "Complete", true) {
            close(); Thread { runCatching { GuardApp.habits.complete(h) } }.start()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(10) })
        card.addView(row, lp(20))
        card.addView(text("Dismiss", 13f, 0x99FFFFFF.toInt()).apply {
            setPadding(px(8), px(12), px(8), px(2)); setOnClickListener { close() }
        }, lp(4))

        val dim = FrameLayout(svc).apply {
            setBackgroundColor(0x990B0A1F.toInt())
            addView(card, FrameLayout.LayoutParams(px(320), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        fullBleed(lp)
        if (runCatching { wm.addView(dim, lp) }.isFailure) return false
        root = dim
        dim.alpha = 0f; dim.animate().alpha(1f).setDuration(200).start()
        card.scaleX = 0.8f; card.scaleY = 0.8f
        card.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(1.6f)).start()
        return true
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }

    private fun close() {
        val v = root ?: return
        root = null
        v.animate().alpha(0f).setDuration(220).withEndAction { v.post { runCatching { wm.removeViewImmediate(v) } } }.start()
    }

    fun destroy() { root?.let { runCatching { wm.removeViewImmediate(it) } }; root = null }
}
