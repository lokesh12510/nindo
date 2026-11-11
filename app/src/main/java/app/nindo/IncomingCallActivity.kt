package app.nindo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sensei calling: a full-screen incoming call (also over the lock screen) with Decline, Answer
 * and "In 15 min". Answer turns this screen into the call itself. Sensei starts loading the moment
 * it rings, so it's usually ready by the time you pick up.
 */
class IncomingCallActivity : ComponentActivity() {
    companion object {
        const val ACTION_SHOW = "app.nindo.CALL_SHOW"
        const val ACTION_ANSWER = "app.nindo.CALL_ANSWER"
        const val ACTION_RESUME = "app.nindo.CALL_RESUME"
    }

    private var callId by mutableStateOf("")
    private var kind by mutableStateOf(ScheduledCall.Kind.DAILY)
    private var title by mutableStateOf("")
    private var inCall by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        bind(intent)
        VoiceLab.prewarm(this)
        setContent {
            // Declined from the notification, or missed: close this screen too.
            LaunchedEffect(inCall, callId) {
                while (!inCall) {
                    kotlinx.coroutines.delay(1500)
                    if (!inCall && SenseiCalls.ringing(this@IncomingCallActivity)?.first != callId) finish()
                }
            }
            GuardTheme {
                if (inCall) CallScreen(onSetup = { openApp(); finish() }) { finish() }
                else Ringing(kind, title,
                    onAnswer = { answer() },
                    onDecline = { SenseiCalls.declined(this, callId); finish() },
                    onLater = { SenseiCalls.snooze(this, callId, title); finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (!inCall) bind(intent) }

    private fun bind(i: Intent) {
        callId = i.getStringExtra(SenseiCalls.EXTRA_ID) ?: ""
        kind = runCatching { ScheduledCall.Kind.valueOf(i.getStringExtra(SenseiCalls.EXTRA_KIND) ?: "") }.getOrDefault(ScheduledCall.Kind.DAILY)
        title = i.getStringExtra(SenseiCalls.EXTRA_TITLE) ?: ""
        if (i.action == ACTION_ANSWER) answer()
        if (i.action == ACTION_RESUME && VoiceLab.phase.value != VoiceLab.Phase.IDLE) inCall = true
    }

    private fun answer() {
        if (inCall) return
        SenseiCalls.answered(this, callId)
        VoiceLab.nextCallReason = VoiceLab.CallReason(kind, title)
        inCall = true
    }

    private fun openApp() = startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun Ringing(kind: ScheduledCall.Kind, title: String, onAnswer: () -> Unit, onDecline: () -> Unit, onLater: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "ring")
    val s by pulse.animateFloat(1f, 1.08f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1E1B4B), Color(0xFF0B0A1A), Color(0xFF050509))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(56.dp))
            Text("Incoming call", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            Text("Sensei", fontSize = 38.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(SenseiCalls.subtitle(kind, title), fontSize = 17.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            Box(Modifier.scale(s)) { VoiceOrb(190.dp, speaking = true) }
            Spacer(Modifier.weight(1f))
            TextButton(onLater) {
                Icon(Icons.Rounded.Snooze, null, tint = Color.White.copy(alpha = 0.8f)); Spacer(Modifier.width(8.dp))
                Text("Call me in 15 min", color = Color.White.copy(alpha = 0.8f))
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Round(Icons.Rounded.CallEnd, "Decline", Color(0xFFE5484D), onDecline)
                Round(Icons.Rounded.Call, "Answer", Color(0xFF22C55E), onAnswer)
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun Round(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(34.dp), tint = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
    }
}
