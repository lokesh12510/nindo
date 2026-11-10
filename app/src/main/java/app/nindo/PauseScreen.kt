package app.nindo

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** What the person tapped on the pause screen. */
enum class PauseAction { HOME, CONTINUE, EXTRA, PARTNER, CALL }

/**
 * Builds the pause screen as plain views, so it works both as an accessibility overlay
 * (over any app) and inside [PauseActivity]. Buttons appear only after the countdown.
 */
object PauseView {

    fun build(ctx: Context, r: Urges.Request, onAction: (PauseAction) -> Unit): View {
        val store = GuardApp.store
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        var acted = false
        fun act(a: PauseAction) { if (!acted) { acted = true; onAction(a) } }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        fun text(t: String, size: Float, color: Int, bold: Boolean = false, center: Boolean = false) = TextView(ctx).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = if (center) Gravity.CENTER_HORIZONTAL else Gravity.START
            setLineSpacing(0f, 1.15f)
            setShadowLayer(8f, 0f, 2f, 0x66000000)
        }
        fun lp(top: Int = 0, w: Int = match) = LinearLayout.LayoutParams(w, wrap).apply { topMargin = px(top) }

        val root = FrameLayout(ctx).apply { setBackgroundColor(0xFF0B0A1F.toInt()); isClickable = true }

        // Full-screen photo (the person's own reminder), slowly zooming; or the app's gradient.
        val anchor = store.anchors().randomOrNull()
        val photo = anchor?.image?.let { AnchorImages.load(ctx, it, maxPx = 1600) }
        val image = photo?.let { bmp -> ImageView(ctx).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP } }
        if (image != null) root.addView(image, FrameLayout.LayoutParams(match, match))
        else root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1E1B4B.toInt(), 0xFF4338CA.toInt(), 0xFF7C3AED.toInt()))
        // Scrim: a little dark at the top for the bar, clear in the middle, dark at the bottom for the text.
        root.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x73000000, 0x00000000, 0x99000000.toInt(), 0xF2000000.toInt()))
        }, FrameLayout.LayoutParams(match, match))

        // Top: what this is, and the countdown.
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("PAUSE  ·  ${r.trigger.title.uppercase()}", 12f, 0xE6FFFFFF.toInt(), bold = true).apply {
            letterSpacing = 0.08f
            setPadding(px(14), px(8), px(14), px(8))
            background = GradientDrawable().apply { cornerRadius = px(50).toFloat(); setColor(0x33FFFFFF) }
        }, LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = px(12) })
        val ring = BreathRing(ctx)
        top.addView(ring, LinearLayout.LayoutParams(px(72), px(72)))

        // Bottom: the note, the roast, the facts, then the buttons.
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
        val note = anchor?.note?.takeIf { it.isNotBlank() } ?: "Remember why you started."
        col.addView(text("“$note”", 28f, Color.WHITE, bold = true), lp(0))
        if (anchor == null) col.addView(text("Add your own photo and note in Guard → Focus → Mindset.", 12f, 0x99FFFFFF.toInt()), lp(6))
        col.addView(text(Urges.roast(r), 16f, 0xFFFCD34D.toInt()), lp(14))
        Urges.facts(ctx, r).forEach { f ->
            col.addView(text(f, 14f, 0xF2FFFFFF.toInt()).apply {
                setPadding(px(14), px(10), px(14), px(10))
                background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(0x26FFFFFF) }
            }, lp(8))
        }
        val breath = text("Breathe in…", 15f, 0xCCFFFFFF.toInt(), center = true)
        col.addView(breath, lp(18))

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; alpha = 0f; visibility = View.GONE }
        fun button(label: String, filled: Boolean, a: PauseAction) = text(label, 16f, if (filled) 0xFF1E1B4B.toInt() else Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(0f, 0f, 0f, 0)
            setPadding(px(16), px(16), px(16), px(16))
            background = GradientDrawable().apply {
                cornerRadius = px(50).toFloat()
                if (filled) setColor(Color.WHITE) else { setColor(0x1FFFFFFF); setStroke(px(1), 0x66FFFFFF) }
            }
            setOnClickListener { act(a) }
        }
        if (r.trigger == Trigger.PREVIEW) {
            buttons.addView(button("Close preview", true, PauseAction.EXTRA), lp(0))
        } else {
            buttons.addView(button("Close and go home", true, PauseAction.HOME), lp(0))
            r.extraLabel?.let { buttons.addView(button(it, false, PauseAction.EXTRA), lp(10)) }
            if (store.hasPartner) buttons.addView(button("Message ${store.partnerName}", false, PauseAction.PARTNER), lp(10))
            if (!store.strict) buttons.addView(text("Type “${Urges.PHRASE}” to continue", 13f, 0x8CFFFFFF.toInt(), center = true).apply {
                paint.isUnderlineText = true
                setPadding(px(8), px(14), px(8), px(4))
                setOnClickListener { act(PauseAction.CONTINUE) }
            }, lp(6))
        }
        col.addView(buttons, lp(18))

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            addView(col, FrameLayout.LayoutParams(match, match))
        }
        root.addView(scroll, FrameLayout.LayoutParams(match, match))
        root.addView(top, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))

        // Edge to edge: keep content clear of the status bar, cutout and navigation bar.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v).getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            top.setPadding(px(20) + bars.left, px(14) + bars.top, px(20) + bars.right, 0)
            col.setPadding(px(24) + bars.left, px(140) + bars.top, px(24) + bars.right, px(28) + bars.bottom)
            insets
        }

        // Countdown, breathing text and the incoming-call escape.
        val handler = Handler(Looper.getMainLooper())
        val audio = ctx.getSystemService(AudioManager::class.java)
        val callCheck = object : Runnable {
            override fun run() {
                val m = audio?.mode ?: AudioManager.MODE_NORMAL
                if (m == AudioManager.MODE_RINGTONE || m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION) {
                    act(PauseAction.CALL); return
                }
                if (!acted) handler.postDelayed(this, 500)
            }
        }
        val total = Urges.SECONDS * 1000L
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total; interpolator = LinearInterpolator()
            addUpdateListener {
                val ms = (it.animatedFraction * total).toLong()
                ring.elapsedMs = ms
                breath.text = if (ms % 10_000 < 4_000) "Breathe in…" else "Breathe out…"
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (acted) return
                    breath.text = "You made it through. Now choose."
                    buttons.visibility = View.VISIBLE
                    buttons.translationY = px(20).toFloat()
                    buttons.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(DecelerateInterpolator()).start()
                    scroll.post { scroll.smoothScrollTo(0, col.height) }
                    root.tag = "done"
                }
            })
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                anim.start(); handler.post(callCheck)
                image?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.setDuration(total)?.setInterpolator(LinearInterpolator())?.start()
            }
            override fun onViewDetachedFromWindow(v: View) { anim.cancel(); handler.removeCallbacksAndMessages(null); image?.animate()?.cancel() }
        })

        // Entry: fade in, text rises.
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(350).start()
        col.translationY = px(40).toFloat()
        col.animate().translationY(0f).setDuration(600).setInterpolator(DecelerateInterpolator()).start()
        return root
    }

    /** True once the countdown has finished (back is allowed then). */
    fun countdownDone(root: View) = root.tag == "done"

    /** What every host does for an action (except removing itself). */
    fun handle(ctx: Context, r: Urges.Request, a: PauseAction, goHome: () -> Unit) {
        when (a) {
            PauseAction.HOME -> { Urges.resolve(ctx, r, Urges.Result.CLOSED); goHome() }
            PauseAction.CONTINUE -> ctx.startActivity(Intent(ctx, PauseConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            PauseAction.EXTRA -> { r.onExtra?.invoke(); Urges.resolve(ctx, r, Urges.Result.CLOSED) }
            PauseAction.PARTNER -> {
                Partner.send(ctx, "I'm having a hard moment right now. Could you check on me?")
                Urges.resolve(ctx, r, Urges.Result.CLOSED); goHome()
            }
            PauseAction.CALL -> Urges.resolve(ctx, r, Urges.Result.DISMISSED)
        }
    }
}

/** Countdown ring with a breathing circle: in for 4 s, out for 6 s. */
private class BreathRing(ctx: Context) : View(ctx) {
    var elapsedMs = 0L
        set(v) { field = v; invalidate() }
    private val dp = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * dp; color = 0x40FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * dp; strokeCap = Paint.Cap.ROUND; color = 0xFFFFFFFF.toInt()
    }
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22 * dp; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        num.textSize = min(w, h) * 0.3f
        track.strokeWidth = min(w, h) * 0.07f; arc.strokeWidth = track.strokeWidth
    }
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f - track.strokeWidth
        val cx = width / 2f; val cy = height / 2f
        box.set(cx - r, cy - r, cx + r, cy + r)
        c.drawCircle(cx, cy, r, track)
        val total = Urges.SECONDS * 1000f
        c.drawArc(box, -90f, 360f * (1f - elapsedMs / total), false, arc)
        // Breathing: grow over 4 s, shrink over 6 s.
        val t = elapsedMs % 10_000
        val k = if (t < 4_000) t / 4_000f else 1f - (t - 4_000) / 6_000f
        val eased = (1 - kotlin.math.cos(k * Math.PI).toFloat()) / 2f
        c.drawCircle(cx, cy, r * (0.45f + 0.4f * eased), blob)
        val left = ((total - elapsedMs) / 1000f).toInt().coerceAtLeast(0) + if (elapsedMs < total) 1 else 0
        val label = if (elapsedMs >= total) "0" else "${left.coerceAtMost(Urges.SECONDS)}"
        c.drawText(label, cx, cy - (num.descent() + num.ascent()) / 2, num)
    }
}

/** Shows the pause screen over any app. Lives in the watcher. */
class PauseOverlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show(r: Urges.Request): Boolean {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        val holder = arrayOfNulls<View>(1)
        val root = PauseView.build(svc, r) { a ->
            remove(holder[0])
            PauseView.handle(svc, r, a) { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
        }
        holder[0] = root
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        fullBleed(lp)
        if (runCatching { wm.addView(root, lp) }.isFailure) return false
        view = root
        root.requestApplyInsets()
        return true
    }

    private fun remove(v: View?) {
        v ?: return
        if (view === v) view = null
        v.animate().alpha(0f).setDuration(280).withEndAction { v.post { runCatching { wm.removeViewImmediate(v) } } }.start()
    }

    fun destroy() { view?.let { runCatching { wm.removeViewImmediate(it) } }; view = null }
}

/** Lets a full-screen overlay draw behind the status bar, navigation bar and camera cutout. */
fun fullBleed(lp: WindowManager.LayoutParams) {
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        lp.layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    if (android.os.Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
}

/** The pause screen as an activity, for when the watcher is off. */
class PauseActivity : ComponentActivity() {
    private var root: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = Urges.pending ?: run { finish(); return }
        val v = PauseView.build(this, r) { a ->
            PauseView.handle(this, r, a) {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        }
        root = v
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContentView(v)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back works only after the countdown, and counts as "close and go home".
                if (root?.let(PauseView::countdownDone) == true) {
                    PauseView.handle(this@PauseActivity, r, PauseAction.HOME) {
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    finish()
                }
            }
        })
    }
}

/** "Type the phrase to continue". Leaving without typing it counts as closing. */
class PauseConfirmActivity : ComponentActivity() {
    private var request: Urges.Request? = null
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        request = Urges.pending ?: run { finish(); return }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = goHome() })
        setContent { GuardTheme { ConfirmScreen(onContinue = ::doContinue, onHome = ::goHome) } }
    }

    private fun doContinue() {
        val r = request ?: return
        resolved = true
        Urges.resolve(this, r, Urges.Result.CONTINUED)
        finish()
    }

    private fun goHome() {
        val r = request
        if (!resolved && r != null) { resolved = true; Urges.resolve(this, r, Urges.Result.CLOSED) }
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onDestroy() {
        val r = request
        if (!resolved && r != null) { resolved = true; Urges.resolve(this, r, Urges.Result.CLOSED) }
        super.onDestroy()
    }
}

@Composable
private fun ConfirmScreen(onContinue: () -> Unit, onHome: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val ok = Urges.phraseOk(typed)
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            androidx.compose.ui.graphics.Color(0xFF0F0C29), androidx.compose.ui.graphics.Color(0xFF302B63))))
            .systemBarsPadding().imePadding().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Still want to continue?", color = androidx.compose.ui.graphics.Color.White, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("Type it exactly, then decide:", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("“${Urges.PHRASE}”", color = androidx.compose.ui.graphics.Color(0xFFFCD34D), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(typed, { typed = it }, singleLine = true, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = androidx.compose.ui.graphics.Color.White,
                    unfocusedTextColor = androidx.compose.ui.graphics.Color.White,
                    focusedBorderColor = androidx.compose.ui.graphics.Color.White,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f),
                    cursorColor = androidx.compose.ui.graphics.Color.White))
            Spacer(Modifier.height(26.dp))
            Button(onHome, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.White,
                    contentColor = androidx.compose.ui.graphics.Color(0xFF1E1B4B))) {
                Text("Close and go home", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onContinue, enabled = ok) {
                Text("Continue (resets your clean streak)", color = androidx.compose.ui.graphics.Color.White.copy(alpha = if (ok) 0.85f else 0.3f))
            }
        }
    }
}
