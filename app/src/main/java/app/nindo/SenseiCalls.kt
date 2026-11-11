package app.nindo

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import java.util.UUID

/**
 * Phase 2D: Sensei calls you. One exact alarm at a time, set for the next call: the daily check-in
 * (a time you choose) or a reminder you asked for in a call. When it fires, the phone rings with
 * a call-style notification that opens a full-screen incoming-call screen, even on the lock
 * screen. Not answered in 45 s = a missed call with "Call back".
 */
object SenseiCalls {
    const val ACTION_RING = "app.nindo.SENSEI_RING"
    const val ACTION_DECLINE = "app.nindo.SENSEI_DECLINE"
    const val ACTION_MISSED = "app.nindo.SENSEI_MISSED"
    const val EXTRA_ID = "call_id"
    const val EXTRA_KIND = "call_kind"
    const val EXTRA_TITLE = "call_title"
    const val CH_CALL = "sensei_call_ring"
    const val CH_MISSED = "sensei_call_missed"
    const val ID_RING = 40
    const val ID_MISSED = 41
    private const val RING_MS = 45_000L
    private const val DAILY_ID = "daily"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sensei_calls", Context.MODE_PRIVATE)

    // ---------------------------------------------------------- settings

    fun dailyOn(ctx: Context) = prefs(ctx).getBoolean("daily_on", false)
    fun dailyMinutes(ctx: Context) = prefs(ctx).getInt("daily_min", 21 * 60 + 30)
    fun setDaily(ctx: Context, on: Boolean, minutes: Int = dailyMinutes(ctx)) {
        prefs(ctx).edit().putBoolean("daily_on", on).putInt("daily_min", minutes).apply()
        reschedule(ctx)
    }

    /** Exact alarms are allowed (Android 12+ asks the user once). Without it calls can be a few minutes late. */
    fun canRingOnTime(ctx: Context) = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Full-screen incoming calls are allowed (Android 14+ asks the user). Without it, a heads-up notification rings instead. */
    fun canShowFullScreen(ctx: Context) = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    // ----------------------------------------------------------- planning

    data class Next(val id: String, val kind: ScheduledCall.Kind, val title: String, val at: Long)

    /** The next call: the earliest reminder still to come, or the daily check-in. */
    fun next(ctx: Context, now: Long = System.currentTimeMillis()): Next? {
        val reminder = runCatching { GuardApp.sensei.scheduled() }.getOrDefault(emptyList())
            .firstOrNull { it.state == ScheduledCall.State.PENDING && it.at > now - 60_000 }
            ?.let { Next(it.id, it.kind, it.title, it.at) }
        val daily = if (dailyOn(ctx)) CallTimes.at(now, dailyMinutes(ctx), null)
            ?.takeIf { it > lastRungDaily(ctx) + 60_000 }
            ?.let { Next(DAILY_ID, ScheduledCall.Kind.DAILY, "Daily check-in", it) } else null
        return listOfNotNull(reminder, daily).minByOrNull { it.at }
    }

    private fun lastRungDaily(ctx: Context) = prefs(ctx).getLong("daily_last", 0L)

    /** Sets the alarm for the next call (or clears it). Call after any change, and on boot. */
    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val n = next(ctx)
        val pi = PendingIntent.getBroadcast(ctx, 50, Intent(ctx, FeatureReceiver::class.java).setAction(ACTION_RING).apply {
            if (n != null) { putExtra(EXTRA_ID, n.id); putExtra(EXTRA_KIND, n.kind.name); putExtra(EXTRA_TITLE, n.title) }
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { SenseiWidget.updateAll(ctx) }
        if (n == null) { am.cancel(pi); return }
        val at = maxOf(n.at, System.currentTimeMillis() + 1000)
        runCatching {
            if (canRingOnTime(ctx)) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(ctx)), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** A reminder call. Returns false if the time has already passed. */
    fun addReminder(ctx: Context, title: String, at: Long, kind: ScheduledCall.Kind = ScheduledCall.Kind.REMINDER): Boolean {
        if (at <= System.currentTimeMillis()) return false
        GuardApp.sensei.addScheduled(ScheduledCall(at = at, kind = kind, title = title.trim()))
        reschedule(ctx)
        return true
    }

    fun cancelReminder(ctx: Context, id: String) {
        GuardApp.sensei.setScheduledState(id, ScheduledCall.State.CANCELLED)
        reschedule(ctx)
    }

    // ------------------------------------------------------------- ringing

    /** The alarm fired: ring, start loading Sensei, and set the next alarm. */
    fun ring(ctx: Context, id: String, kind: ScheduledCall.Kind, title: String) {
        if (VoiceLab.phase.value != VoiceLab.Phase.IDLE) {
            // Already on a call with Sensei: move this one 10 minutes on.
            if (id != DAILY_ID) GuardApp.sensei.setScheduledState(id, ScheduledCall.State.CANCELLED)
            addReminder(ctx, title, System.currentTimeMillis() + 10 * 60_000L, ScheduledCall.Kind.SNOOZE)
            return
        }
        if (id == DAILY_ID) prefs(ctx).edit().putLong("daily_last", System.currentTimeMillis()).apply()
        else GuardApp.sensei.setScheduledState(id, ScheduledCall.State.RINGING)
        prefs(ctx).edit().putString("ringing", "$id|${kind.name}|$title").apply()
        createChannels(ctx)
        // Keeps ringing (like a phone) until answered, declined or missed.
        if (Notify.allowed(ctx)) runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(ID_RING, ringNotification(ctx, id, kind, title).build().apply { flags = flags or Notification.FLAG_INSISTENT })
        }
        // Missed after 45 s.
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RING_MS,
            PendingIntent.getBroadcast(ctx, 51, Intent(ctx, FeatureReceiver::class.java).setAction(ACTION_MISSED).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        runCatching { VoiceLab.prewarm(ctx) }
        reschedule(ctx)
    }

    /** The call that is ringing now, as (id, kind, title), or null. */
    fun ringing(ctx: Context): Triple<String, ScheduledCall.Kind, String>? =
        prefs(ctx).getString("ringing", null)?.split("|", limit = 3)?.takeIf { it.size == 3 }?.let {
            Triple(it[0], runCatching { ScheduledCall.Kind.valueOf(it[1]) }.getOrDefault(ScheduledCall.Kind.REMINDER), it[2])
        }

    private fun stopRinging(ctx: Context) {
        prefs(ctx).edit().remove("ringing").apply()
        Notify.cancel(ctx, ID_RING)
    }

    fun answered(ctx: Context, id: String) {
        stopRinging(ctx); Notify.cancel(ctx, ID_MISSED)
        if (id != DAILY_ID) runCatching { GuardApp.sensei.setScheduledState(id, ScheduledCall.State.ANSWERED) }
    }

    fun declined(ctx: Context, id: String) {
        stopRinging(ctx)
        if (id != DAILY_ID) runCatching { GuardApp.sensei.setScheduledState(id, ScheduledCall.State.DECLINED) }
        reschedule(ctx)
    }

    /** "In 15 min": this call becomes a new one 15 minutes from now. */
    fun snooze(ctx: Context, id: String, title: String, minutes: Int = 15) {
        stopRinging(ctx)
        if (id != DAILY_ID) runCatching { GuardApp.sensei.setScheduledState(id, ScheduledCall.State.DECLINED) }
        addReminder(ctx, title, System.currentTimeMillis() + minutes * 60_000L, ScheduledCall.Kind.SNOOZE)
    }

    /** Not answered in time: stop ringing and leave a missed call. */
    fun missed(ctx: Context, id: String) {
        val r = ringing(ctx) ?: return
        if (r.first != id) return
        stopRinging(ctx)
        if (id != DAILY_ID) runCatching { GuardApp.sensei.setScheduledState(id, ScheduledCall.State.MISSED) }
        Notify.post(ctx, ID_MISSED, Notify.builder(ctx, CH_MISSED)
            .setContentTitle("Missed call from Sensei")
            .setContentText(if (r.second == ScheduledCall.Kind.DAILY) "Your daily check-in" else r.third)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(callIntent(ctx, id, r.second, r.third, answer = true, code = 54))
            .addAction(0, "Call back", callIntent(ctx, id, r.second, r.third, answer = true, code = 55))
            .setAutoCancel(true))
    }

    // ------------------------------------------------------- notifications

    private fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_CALL) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_CALL, "Calls from Sensei", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when Sensei calls for your check-in or a reminder"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800, 600, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        if (nm.getNotificationChannel(CH_MISSED) == null)
            nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls from Sensei", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun ringNotification(ctx: Context, id: String, kind: ScheduledCall.Kind, title: String): NotificationCompat.Builder {
        val sensei = Person.Builder().setName("Sensei").setImportant(true)
            .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_stat_shield)).build()
        val answer = callIntent(ctx, id, kind, title, answer = true, code = 52)
        val full = callIntent(ctx, id, kind, title, answer = false, code = 53)
        val decline = PendingIntent.getBroadcast(ctx, 56, Intent(ctx, FeatureReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notify.builder(ctx, CH_CALL)
            .setContentTitle("Sensei")
            .setContentText(subtitle(kind, title))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setTimeoutAfter(RING_MS)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(sensei, decline, answer))
            .addPerson(sensei)
    }

    fun subtitle(kind: ScheduledCall.Kind, title: String) = when (kind) {
        ScheduledCall.Kind.DAILY -> "Daily check-in"
        ScheduledCall.Kind.TEST -> "Test call"
        else -> "Reminder: $title"
    }

    /** Opens the incoming-call screen; [answer] = straight into the call. */
    fun callIntent(ctx: Context, id: String, kind: ScheduledCall.Kind, title: String, answer: Boolean, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, code, Intent(ctx, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setAction(if (answer) IncomingCallActivity.ACTION_ANSWER else IncomingCallActivity.ACTION_SHOW)
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_KIND, kind.name).putExtra(EXTRA_TITLE, title),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 57, Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    /** "Test: ring in 10 seconds" on the Sensei screen. */
    fun testRing(ctx: Context) {
        addReminder(ctx, "Test call", System.currentTimeMillis() + 10_000, ScheduledCall.Kind.TEST)
    }

    fun newId() = UUID.randomUUID().toString()
}
