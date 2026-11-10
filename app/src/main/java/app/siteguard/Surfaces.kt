package app.siteguard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** Home-screen widget: today's screen time vs goal, streak, and a one-tap focus button. */
class GuardWidget : AppWidgetProvider() {
    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, GuardWidget::class.java))
            if (ids.isEmpty()) return
            val views = runCatching { build(ctx) }.getOrNull() ?: return
            mgr.updateAppWidget(ids, views)
        }

        private fun build(ctx: Context): RemoteViews {
            val s = GuardApp.store
            val t = GuardApp.db.totals()
            val streak = Streaks.load()
            val goal = s.screenGoalMin * 60_000L
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_time, fmtDuration(t.screenMs))
            v.setTextViewText(R.id.w_goal, "of ${fmtMinutes(s.screenGoalMin)} · ${t.opens} opens")
            v.setProgressBar(R.id.w_progress, 100, ((t.screenMs * 100) / goal.coerceAtLeast(1)).toInt().coerceIn(0, 100), false)
            val reelPart = if (s.reelsOn) " · ${Reels.stage(t.reels, s.reelCap).emoji} ${t.reels} reels" else ""
            v.setTextViewText(R.id.w_streak, (if (streak.current > 0) "🔥 ${streak.current}-day streak" else "Start a streak") + reelPart)
            val now = System.currentTimeMillis()
            v.setTextViewText(R.id.w_focus,
                if (s.focusActive) "Focus · ${(s.focusUntil - now) / 60_000 + 1} min left" else "▶  Focus ${fmtMinutes(s.focusDefaultMin)}")
            v.setOnClickPendingIntent(R.id.w_root, Notify.openApp(ctx))
            v.setOnClickPendingIntent(R.id.w_focus, if (s.focusActive) Notify.openApp(ctx) else PendingIntent.getBroadcast(ctx, 40,
                Intent(ctx, FeatureReceiver::class.java).setAction(Focus.ACTION_START), PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = updateAll(ctx)
}

/** Quick Settings tile that starts or stops a focus session. */
class FocusTileService : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        val s = GuardApp.store
        if (s.focusActive) Focus.stop(this) else Focus.start(this, s.focusDefaultMin)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val s = GuardApp.store
        Focus.check(this)
        val active = s.focusActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Focus"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_shield)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = when {
            active && !Focus.canStop(s) -> "Locked · ${(s.focusUntil - System.currentTimeMillis()) / 60_000 + 1}m"
            active -> "${(s.focusUntil - System.currentTimeMillis()) / 60_000 + 1} min left"
            else -> "Start ${fmtMinutes(s.focusDefaultMin)}"
        }
        tile.updateTile()
    }
}
