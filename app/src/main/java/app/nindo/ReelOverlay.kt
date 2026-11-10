package app.nindo

import android.accessibilityservice.AccessibilityService
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The floating brain bubble shown in reel apps, plus small milestone popups.
 * Drawn as accessibility overlays, so no "display over other apps" permission is needed.
 */
class ReelOverlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = svc.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val prefs = svc.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    private var bubble: LinearLayout? = null
    private var emojiView: TextView? = null
    private var countView: TextView? = null
    private var bg: GradientDrawable? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastStage: Reels.Stage? = null
    private var lastCount = 0
    private var lastCap = 0
    private val hideDetail = Runnable { countView?.text = lastCount.toString() }
    private val autoHide = Runnable { hide() }

    /** Pops the bubble in with the new count, then hides it after [ms]. */
    fun flash(count: Int, cap: Int, ms: Long = Reels.FLASH_MS) {
        val wasShowing = bubble != null
        show(count, cap, fast = true)
        if (wasShowing) update(count, cap, bump = true)
        bubble?.let { it.removeCallbacks(autoHide); it.postDelayed(autoHide, ms) }
    }

    val showing get() = bubble != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(count: Int, cap: Int, fast: Boolean = false) {
        if (bubble != null) { update(count, cap, bump = false); return }
        val shape = GradientDrawable().apply { cornerRadius = px(24).toFloat() }
        val emoji = TextView(svc).apply { textSize = 22f }
        val num = TextView(svc).apply {
            textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
            setPadding(px(6), 0, px(2), 0)
        }
        val v = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape
            elevation = px(8).toFloat()
            setPadding(px(10), px(6), px(12), px(6))
            addView(emoji); addView(num)
            contentDescription = "Reel counter"
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = px(10)
            y = prefs.getInt("bubble_y", px(140))
        }
        bubble = v; emojiView = emoji; countView = num; bg = shape; params = lp
        lastStage = null
        update(count, cap, bump = false)
        v.setOnTouchListener(DragListener())
        runCatching { wm.addView(v, lp) }.onFailure { bubble = null; return }
        // Slide in from the edge.
        v.translationX = px(120).toFloat(); v.alpha = 0f
        v.animate().translationX(0f).alpha(1f).setDuration(if (fast) 220 else 420).setInterpolator(OvershootInterpolator(1.4f)).start()
        if (fast) { v.scaleX = 1.3f; v.scaleY = 1.3f; v.animate().scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(300).start() }
    }

    fun hide() {
        val v = bubble ?: return
        bubble = null
        v.removeCallbacks(autoHide)
        v.animate().translationX(px(120).toFloat()).alpha(0f).setDuration(200).withEndAction {
            runCatching { wm.removeView(v) }
        }.start()
    }

    fun update(count: Int, cap: Int, bump: Boolean) {
        lastCount = count; lastCap = cap
        val stage = Reels.stage(count, cap)
        val v = bubble ?: return
        countView?.text = count.toString()
        val prev = lastStage
        if (prev?.emoji != stage.emoji) {
            emojiView?.text = stage.emoji
            if (prev != null) {
                // Stage change: wobble and blend to the new colour.
                ValueAnimator.ofObject(ArgbEvaluator(), prev.color.toInt(), stage.color.toInt()).apply {
                    duration = 600
                    addUpdateListener { bg?.setColor(it.animatedValue as Int) }
                }.start()
                emojiView?.let { e ->
                    e.rotation = -25f
                    e.animate().rotation(0f).setDuration(700).setInterpolator(OvershootInterpolator(6f)).start()
                }
            } else bg?.setColor(stage.color.toInt())
            lastStage = stage
        }
        if (bump) {
            v.scaleX = 1.3f; v.scaleY = 1.3f
            v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
        }
    }

    /** A small card that drops in from the top for ~3 s. Doesn't take touches. */
    fun toast(text: String) {
        val card = TextView(svc).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(px(18), px(12), px(18), px(12))
            background = GradientDrawable().apply { cornerRadius = px(20).toFloat(); setColor(0xEE1E1B4B.toInt()) }
            elevation = px(10).toFloat()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = px(56) }
        if (runCatching { wm.addView(card, lp) }.isFailure) return
        card.translationY = -px(120).toFloat(); card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(450).setInterpolator(OvershootInterpolator(1.6f)).start()
        card.postDelayed({
            card.animate().translationY(-px(120).toFloat()).alpha(0f).setDuration(300).withEndAction {
                runCatching { wm.removeView(card) }
            }.start()
        }, 3000)
    }

    fun destroy() {
        bubble?.let { runCatching { wm.removeViewImmediate(it) } }
        bubble = null
    }

    /** Drag the bubble up and down; a tap shows "count / cap today" for a moment. */
    private inner class DragListener : View.OnTouchListener {
        private var startY = 0; private var downRaw = 0f; private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = lp.y; downRaw = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downRaw).toInt()
                    if (abs(dy) > px(6)) moved = true
                    if (moved) { lp.y = (startY + dy).coerceAtLeast(0); runCatching { wm.updateViewLayout(v, lp) } }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("bubble_y", lp.y).apply()
                    else {
                        countView?.text = if (lastCap > 0) "$lastCount / $lastCap today" else "$lastCount today · ${Reels.stage(lastCount, lastCap).label}"
                        countView?.removeCallbacks(hideDetail)
                        countView?.postDelayed(hideDetail, 2500)
                        v.removeCallbacks(autoHide); v.postDelayed(autoHide, 2600)
                        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).withEndAction {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                        }.start()
                    }
                }
            }
            return true
        }
    }
}
