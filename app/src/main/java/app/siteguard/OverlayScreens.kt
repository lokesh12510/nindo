package app.siteguard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private fun ComponentActivity.goHome() {
    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    finish()
}

private val QUOTES = listOf(
    "Your nindō: never give up on yourself.",
    "Stay committed. Keep moving forward.",
    "Every block kept is a promise kept.",
    "Your attention is worth protecting.",
    "Future you says thanks.",
    "Small breaks, big wins.",
    "You set this rule for a reason.",
    "Go do something you'll remember.",
)

/** Full-screen "blocked" page. Leaving it always goes to the home screen. */
class BlockActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_REASON = "reason"
        const val EXTRA_IS_APP = "is_app"
    }

    private var data by mutableStateOf(Triple("", "", true))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bind(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = goHome() })
        setContent { GuardTheme { BlockScreen(data.first, data.second, data.third) { goHome() } } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); bind(intent) }

    private fun bind(i: Intent) {
        data = Triple(i.getStringExtra(EXTRA_TITLE) ?: "This", i.getStringExtra(EXTRA_REASON) ?: "", i.getBooleanExtra(EXTRA_IS_APP, false))
    }
}

@Composable
private fun BlockScreen(title: String, reason: String, isApp: Boolean, onHome: () -> Unit) {
    val t = rememberInfiniteTransition(label = "block")
    val pulse by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "ring")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(8000), RepeatMode.Reverse), label = "drift")
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 200f)) }
    val quote = remember { QUOTES.random() }

    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF7F1D1D).copy(alpha = 0.8f + 0.2f * drift), Color(0xFF4C1D95)))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp).graphicsLayer { alpha = enter.value; translationY = (1 - enter.value) * 80f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(170.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brand.Rose.copy(alpha = (1 - ring) * 0.45f), radius = size.minDimension / 2 * (0.5f + ring * 0.5f))
                }
                Box(
                    Modifier.size(96.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                        .background(Brush.radialGradient(listOf(Color(0xFFFB7185), Color(0xFFE11D48))), RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Block, null, tint = Color.White, modifier = Modifier.size(52.dp)) }
            }
            Spacer(Modifier.height(20.dp))
            Text("$title is blocked", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(reason, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Text("“$quote”", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(36.dp))
            Button(onHome, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1E1B4B)),
                modifier = Modifier.height(54.dp).fillMaxWidth(0.8f), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.Home, null); Spacer(Modifier.width(8.dp)); Text("Go to home screen", fontWeight = FontWeight.SemiBold)
            }
            if (!isApp) {
                Spacer(Modifier.height(10.dp))
                Text("The browser was sent back from that page.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
        }
    }
}

/** Shown after long continuous scrolling: a short breathing pause, then a choice. */
class NudgeActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_SWIPES = "swipes"
        const val EXTRA_CAN_SNOOZE = "can_snooze"
    }

    private var pkg = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "this app"
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        val swipes = intent.getIntExtra(EXTRA_SWIPES, 0)
        val canSnooze = intent.getBooleanExtra(EXTRA_CAN_SNOOZE, true)
        val cooldown = GuardApp.store.doomCooldownMin
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = done() })
        setContent {
            GuardTheme {
                NudgeScreen(label, minutes, swipes, canSnooze, cooldown, onDone = ::done, onSnooze = {
                    FocusBus.listener?.invoke(pkg, true)
                    finish()
                })
            }
        }
    }

    private fun done() {
        FocusBus.listener?.invoke(pkg, false)
        goHome()
    }
}

@Composable
private fun NudgeScreen(label: String, minutes: Int, swipes: Int, canSnooze: Boolean, cooldown: Int, onDone: () -> Unit, onSnooze: () -> Unit) {
    val t = rememberInfiniteTransition(label = "breath")
    // 4 s in, 4 s out.
    val breath by t.animateFloat(0.7f, 1.15f, infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val hue by t.animateFloat(0f, 1f, infiniteRepeatable(tween(8000), RepeatMode.Reverse), label = "h")
    var wait by remember { mutableIntStateOf(8) }
    LaunchedEffect(Unit) { while (wait > 0) { delay(1000); wait-- } }
    val inhale = breath > 0.93f

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF042F2E), Color(0xFF0F766E).copy(alpha = 0.7f + 0.3f * hue), Color(0xFF1E3A8A)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Time for a pause", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("You've been scrolling $label for $minutes min · about $swipes swipes",
                color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(36.dp))
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    drawCircle(Color(0xFF5EEAD4).copy(alpha = 0.12f), radius = r * breath)
                    drawCircle(Color(0xFF5EEAD4).copy(alpha = 0.22f), radius = r * 0.8f * breath)
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFCCFBF1), Color(0xFF2DD4BF))), radius = r * 0.55f * breath)
                }
                Text(if (inhale) "Breathe in" else "Breathe out", color = Color(0xFF042F2E), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(36.dp))
            Button(onDone, Modifier.fillMaxWidth(0.85f).height(54.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF042F2E))) {
                Text(if (cooldown > 0) "I'm done · ${cooldown} min break" else "I'm done", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            if (canSnooze) {
                OutlinedButton(onSnooze, enabled = wait == 0, modifier = Modifier.fillMaxWidth(0.85f).height(50.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.4f))) {
                    Text(if (wait > 0) "5 more minutes (in ${wait}s)" else "5 more minutes")
                }
            } else {
                Text("No more extensions this session.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
    }
}
