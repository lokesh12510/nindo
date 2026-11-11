package app.nindo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a call with Sensei going when the screen turns off or you switch apps: a microphone
 * foreground service with an ongoing "On a call with Sensei" notification (tap to return, End
 * to hang up). Started when the call goes live, stopped when it ends.
 */
class SenseiCallService : Service() {
    companion object {
        const val ACTION_END = "app.nindo.SENSEI_CALL_END"
        private const val CH = "sensei_call_live"
        private const val ID = 42

        /** Where the call screen lives, so the notification takes you back to it. */
        @Volatile var fromIncoming = false

        fun start(ctx: Context, incoming: Boolean) {
            fromIncoming = incoming
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, SenseiCallService::class.java)) }
        }

        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, SenseiCallService::class.java)) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            VoiceLab.endCall(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Call in progress", NotificationManager.IMPORTANCE_LOW))
        val back = if (fromIncoming)
            Intent(this, IncomingCallActivity::class.java).setAction(IncomingCallActivity.ACTION_RESUME)
        else Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CALL, true)
        val open = PendingIntent.getActivity(this, 60, back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 61, Intent(this, SenseiCallService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notify.builder(this, CH)
            .setContentTitle("On a call with Sensei")
            .setContentText("Tap to go back to the call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(VoiceLab.callStartedAt.value.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setContentIntent(open)
            .addAction(0, "End call", end)
            .build()
        val ok = runCatching {
            ServiceCompat.startForeground(this, ID, n,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        }.isSuccess
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away mid-call: hang up properly so the notes are still written.
        VoiceLab.endCall(applicationContext)
        super.onTaskRemoved(rootIntent)
    }
}
