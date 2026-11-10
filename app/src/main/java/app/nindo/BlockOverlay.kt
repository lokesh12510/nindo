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

    fun show(title: String, reason: String, @Suppress("UNUSED_PARAMETER") emoji: String = "", showMs: Long = 9000) {
        // Same block again while it's on screen (the app was reopened): keep it, restart its timer.
        root?.let { r ->
            if (title == shownTitle) { r.removeCallbacks(autoHide); r.postDelayed(autoHide, showMs); return }
            removeNow(r)
        }
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        fun text(t: String, size: Float, color: Int, bold: Boolean = false, center: Boolean = false) = TextView(svc).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = if (center) Gravity.CENTER else Gravity.START
            setLineSpacing(0f, 1.15f)
            setShadowLayer(8f, 0f, 2f, 0x66000000)
        }
        fun lp(top: Int) = LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(top) }

        val store = GuardApp.store
        val dim = FrameLayout(svc).apply { setBackgroundColor(0xFF0B0A1F.toInt()); setOnClickListener { hide() } }

        // The person's own photo, full screen, like the pause screen; otherwise the app's gradient.
        val anchor = store.anchors().randomOrNull()
        val photo = anchor?.image?.let { AnchorImages.load(svc, it, maxPx = 1600) }
        val image = photo?.let { bmp -> android.widget.ImageView(svc).apply { setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } }
        if (image != null) dim.addView(image, FrameLayout.LayoutParams(match, match))
        else dim.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1E1B4B.toInt(), 0xFF4338CA.toInt(), 0xFF7C3AED.toInt()))
        dim.addView(View(svc).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x40000000, 0x00000000, 0xA6000000.toInt(), 0xF5000000.toInt()))
        }, FrameLayout.LayoutParams(match, match))

        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val blocked = !(reason.contains("limit", true) || reason.contains("opens", true))
        col.addView(text(if (blocked) "BLOCKED" else "TIME'S UP", 12f, 0xFFFFFFFF.toInt(), bold = true).apply {
            letterSpacing = 0.12f
            setPadding(px(14), px(7), px(14), px(7))
            background = GradientDrawable().apply { cornerRadius = px(50).toFloat(); setColor(if (blocked) 0xCCE11D48.toInt() else 0xCC7C3AED.toInt()) }
        }, LinearLayout.LayoutParams(wrap, wrap))
        col.addView(text(title, 30f, 0xFFFFFFFF.toInt(), bold = true), lp(12))
        col.addView(text(reason, 15f, 0xE6FFFFFF.toInt()), lp(6))
        val note = anchor?.note?.takeIf { it.isNotBlank() } ?: QUOTES.random()
        col.addView(text("“$note”", 20f, 0xFFFFFFFF.toInt(), bold = true), lp(20))

        // Streaks, for motivation.
        val goal = runCatching { Streaks.load().current }.getOrDefault(0)
        val clean = store.cleanDays
        val habitBest = HabitEngine.peek()?.streaks?.values?.maxOfOrNull { it.current } ?: 0
        val stats = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(value: String, label: String, first: Boolean) {
            val box = LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), px(10), px(12), px(10))
                background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(0x26FFFFFF) }
                addView(text(value, 20f, 0xFFFFFFFF.toInt(), bold = true))
                addView(text(label, 11f, 0xB3FFFFFF.toInt()))
            }
            stats.addView(box, LinearLayout.LayoutParams(0, wrap, 1f).apply { if (!first) leftMargin = px(8) })
        }
        stat("$goal", "day goal streak", true)
        stat("$clean", "clean days", false)
        stat("$habitBest", "best habit streak", false)
        col.addView(stats, lp(18))
        HabitEngine.pendingPick()?.let { d ->
            col.addView(text("Do this instead: ${d.habit.name}", 15f, 0xFFFCD34D.toInt(), bold = true), lp(14))
        }

        // Countdown bar, then the close button.
        val track = FrameLayout(svc).apply { background = GradientDrawable().apply { cornerRadius = px(3).toFloat(); setColor(0x33FFFFFF) } }
        val bar = View(svc).apply { background = GradientDrawable().apply { cornerRadius = px(3).toFloat(); setColor(0xFFFFFFFF.toInt()) } }
        track.addView(bar, FrameLayout.LayoutParams(match, px(4)))
        col.addView(track, LinearLayout.LayoutParams(match, px(4)).apply { topMargin = px(22) })
        col.addView(text("Close", 16f, 0xFF1E1B4B.toInt(), bold = true, center = true).apply {
            setShadowLayer(0f, 0f, 0f, 0)
            setPadding(px(16), px(15), px(16), px(15))
            background = GradientDrawable().apply { cornerRadius = px(50).toFloat(); setColor(0xFFFFFFFF.toInt()) }
            setOnClickListener { hide() }
        }, lp(16))

        dim.addView(col, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))
        dim.setOnApplyWindowInsetsListener { v, insets ->
            val bars = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v).getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            col.setPadding(px(24) + bars.left, 0, px(24) + bars.right, px(28) + bars.bottom)
            insets
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        fullBleed(lp)
        if (runCatching { wm.addView(dim, lp) }.isFailure) return
        root = dim; this.card = col; shownTitle = title
        dim.requestApplyInsets()
        dim.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Enter: fade in, text rises, photo slowly zooms, countdown runs.
        dim.alpha = 0f
        dim.animate().alpha(1f).setDuration(260).start()
        col.translationY = px(40).toFloat(); col.alpha = 0f
        col.animate().translationY(0f).alpha(1f).setDuration(500).setInterpolator(DecelerateInterpolator()).start()
        image?.animate()?.scaleX(1.1f)?.scaleY(1.1f)?.setDuration(showMs)?.setInterpolator(LinearInterpolator())?.start()
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
        private val QUOTES = listOf(
            "Your nindō: never give up on yourself.",
            "Stay committed. Keep moving forward.",
            "Every block kept is a promise kept.",
            "You set this rule for a reason.",
            "Go do something you'll remember.",
        )
    }
}
