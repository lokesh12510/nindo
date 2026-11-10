package app.nindo

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Time's up" card shown when an app or site is blocked. Drawn as an accessibility
 * overlay, so it always appears (unlike an activity started right after pressing Home,
 * which Android delays or drops). Auto-hides after a few seconds; tap to close.
 */
class BlockOverlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = svc.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private var root: FrameLayout? = null
    private var card: View? = null
    private var shownTitle: String? = null
    private val autoHide = Runnable { hide() }

    fun show(title: String, reason: String, emoji: String = emojiFor(reason), showMs: Long = 4000) {
        // Same block again while it's on screen (the app was reopened): keep the card, restart its timer.
        root?.let { r ->
            if (title == shownTitle) { r.removeCallbacks(autoHide); r.postDelayed(autoHide, showMs); return }
            removeNow(r)
        }

        val emojiView = TextView(svc).apply { text = emoji; textSize = 52f; gravity = Gravity.CENTER }
        val titleView = TextView(svc).apply {
            text = title; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, px(10), 0, px(6))
        }
        val reasonView = TextView(svc).apply {
            text = reason; textSize = 15f; setTextColor(0xE6FFFFFF.toInt()); gravity = Gravity.CENTER
        }
        val quoteView = TextView(svc).apply {
            text = "“${QUOTES.random()}”"; textSize = 13f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, px(14), 0, px(16))
        }
        // Countdown bar that shrinks until the card hides.
        val track = FrameLayout(svc).apply {
            background = GradientDrawable().apply { cornerRadius = px(3).toFloat(); setColor(0x33FFFFFF) }
        }
        val bar = View(svc).apply {
            background = GradientDrawable().apply { cornerRadius = px(3).toFloat(); setColor(0xFFFFFFFF.toInt()) }
        }
        track.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(5)))

        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(26), px(26), px(26), px(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF312E81.toInt(), 0xFF9F1239.toInt(), 0xFF5B21B6.toInt()),
            ).apply { cornerRadius = px(30).toFloat() }
            elevation = px(16).toFloat()
            addView(emojiView); addView(titleView); addView(reasonView); addView(quoteView)
            addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(5)))
            addView(TextView(svc).apply {
                text = "Tap anywhere to close"; textSize = 11f; setTextColor(0x80FFFFFF.toInt())
                gravity = Gravity.CENTER; setPadding(0, px(8), 0, 0)
            })
        }

        val dim = FrameLayout(svc).apply {
            setBackgroundColor(0xB30B0A1F.toInt())
            addView(card, FrameLayout.LayoutParams(px(320), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { hide() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        fullBleed(lp)
        if (runCatching { wm.addView(dim, lp) }.isFailure) return
        root = dim; this.card = card; shownTitle = title
        // Hardware layers keep the fades smooth instead of re-rendering every child each frame.
        dim.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Enter: fade the dim, pop the card, wobble the emoji, run the countdown.
        dim.alpha = 0f
        dim.animate().alpha(1f).setDuration(220).start()
        card.scaleX = 0.7f; card.scaleY = 0.7f; card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(OvershootInterpolator(1.8f)).start()
        emojiView.rotation = -30f
        emojiView.animate().rotation(0f).setStartDelay(150).setDuration(800).setInterpolator(OvershootInterpolator(5f)).start()
        bar.post {
            bar.pivotX = 0f
            bar.animate().scaleX(0f).setDuration(showMs).setInterpolator(LinearInterpolator()).start()
        }
        dim.postDelayed(autoHide, showMs)
    }

    /** Exit: the card sinks and shrinks a little while everything fades, then the window is removed. */
    fun hide() {
        val v = root ?: return
        val c = card
        root = null; card = null; shownTitle = null
        v.removeCallbacks(autoHide)
        v.isClickable = false
        c?.animate()?.cancel()
        c?.animate()?.scaleX(0.92f)?.scaleY(0.92f)?.translationY(px(28).toFloat())?.alpha(0f)
            ?.setDuration(320)?.setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))?.start()
        v.animate().alpha(0f).setStartDelay(60).setDuration(340).setInterpolator(DecelerateInterpolator()).withEndAction {
            // Remove on the next frame, after the last faded frame has been drawn.
            v.post { runCatching { wm.removeViewImmediate(v) } }
        }.start()
    }

    fun destroy() { root?.let { removeNow(it) }; root = null; card = null; shownTitle = null }

    private fun removeNow(v: View) {
        v.removeCallbacks(autoHide)
        runCatching { wm.removeViewImmediate(v) }
    }

    companion object {
        fun emojiFor(reason: String): String = when {
            reason.contains("Reel", true) -> "🧟"
            reason.contains("limit", true) -> "⏳"
            reason.contains("opens", true) -> "🔁"
            reason.contains("Focus", true) -> "🎯"
            reason.contains("Break", true) -> "🌿"
            else -> "🛑"
        }

        private val QUOTES = listOf(
            "Your nindō: never give up on yourself.",
            "Stay committed. Keep moving forward.",
            "Every block kept is a promise kept.",
            "You set this rule for a reason.",
            "Go do something you'll remember.",
        )
    }
}
