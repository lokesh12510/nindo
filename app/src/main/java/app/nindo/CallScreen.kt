package app.nindo

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The call with Sensei, full screen like a phone call: Sensei's orb, the call timer, and mute,
 * end and speaker buttons. Voice only; the line under the timer is just a hint of what was said.
 * Ending the call closes the screen; Sensei then writes its notes in the background.
 */
@Composable
fun CallScreen(onSetup: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val phase by VoiceLab.phase.collectAsState()
    val thinking by VoiceLab.thinking.collectAsState()
    val muted by VoiceLab.userMuted.collectAsState()
    val speaker by VoiceLab.speakerOn.collectAsState()
    val started by VoiceLab.callStartedAt.collectAsState()
    val turns by VoiceLab.turns.collectAsState()
    val status by VoiceLab.status.collectAsState()
    val hearing by (VoiceLab.hearing() ?: remember { MutableStateFlow(false) }).collectAsState()
    val notes by VoiceLab.lastNotes.collectAsState()
    val ready = remember { VoiceLab.modelsReady(ctx) }
    var askedMic by remember { mutableStateOf(false) }
    var everLive by remember { mutableStateOf(false) }

    // The screen stays on during the call.
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) VoiceLab.beginCall(ctx) else onDismiss()
    }
    LaunchedEffect(ready) {
        if (!ready || askedMic) return@LaunchedEffect
        askedMic = true
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) VoiceLab.beginCall(ctx)
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // After the call: stay open while Sensei writes its notes, show what it saved, then close.
    LaunchedEffect(phase) {
        if (phase == VoiceLab.Phase.LIVE) everLive = true
        if (everLive && phase == VoiceLab.Phase.IDLE) { delay(if (notes != null) 1800 else 400); onDismiss() }
    }
    fun end() { if (phase == VoiceLab.Phase.LIVE || phase == VoiceLab.Phase.CONNECTING) VoiceLab.endCall(ctx) else onDismiss() }
    BackHandler { end() }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase) { while (phase == VoiceLab.Phase.LIVE) { now = System.currentTimeMillis(); delay(500) } }

    Dialog(onDismissRequest = { end() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1E1B4B), Color(0xFF0B0A1A), Color(0xFF050509))))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.55f))
                    Spacer(Modifier.width(6.dp))
                    Text("Private call, on this phone only", fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
                }
                Spacer(Modifier.weight(0.6f))
                VoiceOrb(210.dp, speaking = phase == VoiceLab.Phase.LIVE && (thinking || hearing))
                Spacer(Modifier.height(22.dp))
                Text("Sensei", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                val line = when {
                    !ready -> "Sensei needs a one-time download first"
                    phase == VoiceLab.Phase.CONNECTING -> if (VoiceLab.brainLoaded) "Connecting…" else "Waking Sensei up…"
                    phase == VoiceLab.Phase.LIVE -> clock(now - started)
                    phase == VoiceLab.Phase.SAVING || (everLive && phase == VoiceLab.Phase.IDLE) -> "Call ended"
                    else -> "Calling…"
                }
                Text(line, fontSize = 17.sp, color = Color.White.copy(alpha = 0.75f))
                Spacer(Modifier.height(4.dp))
                AnimatedContent(when {
                    phase != VoiceLab.Phase.LIVE -> if (phase == VoiceLab.Phase.CONNECTING && !VoiceLab.brainLoaded) "This takes about 20 seconds after opening the app" else ""
                    muted -> "You're muted"
                    thinking -> "Sensei is talking"
                    hearing -> "Listening"
                    else -> "Your turn"
                }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "sub") { sub ->
                    Text(sub, fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center)
                }
                if (phase == VoiceLab.Phase.LIVE) {
                    val last = turns.lastOrNull()?.sensei.orEmpty()
                    Spacer(Modifier.height(18.dp))
                    Text(last, Modifier.fillMaxWidth().heightIn(min = 44.dp), fontSize = 15.sp, color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (status.startsWith("Couldn't")) {
                    Spacer(Modifier.height(10.dp))
                    Text(status, fontSize = 13.sp, color = Brand.Rose, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.weight(1f))

                if (everLive && phase != VoiceLab.Phase.LIVE) {
                    // Saving the call.
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (phase == VoiceLab.Phase.SAVING) {
                            CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
                            Spacer(Modifier.height(14.dp))
                            Text("Sensei is writing notes from your call", fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
                            Text("Ticking off what you finished and saving what to remember", fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center)
                        } else {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(36.dp), tint = Brand.Green)
                            Spacer(Modifier.height(14.dp))
                            Text(notes ?: "Call saved", fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                        }
                    }
                } else if (!ready) {
                    Button(onSetup, Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) { Text("Set up Sensei's voice (about 2.8 GB)") }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onDismiss) { Text("Not now", color = Color.White.copy(alpha = 0.7f)) }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (muted) "Unmute" else "Mute", active = muted,
                            enabled = phase == VoiceLab.Phase.LIVE) { VoiceLab.toggleMute() }
                        RoundButton(Icons.Rounded.CallEnd, "End", size = 76.dp, color = Color(0xFFE5484D), iconTint = Color.White) { end() }
                        RoundButton(if (speaker) Icons.Rounded.VolumeUp else Icons.Rounded.PhoneInTalk, if (speaker) "Speaker" else "Earpiece",
                            active = speaker, enabled = phase == VoiceLab.Phase.LIVE) { VoiceLab.toggleSpeaker(ctx) }
                    }
                }
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, size: Dp = 64.dp,
                        color: Color? = null, iconTint: Color? = null, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape)
            .background(color ?: if (active) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(size * 0.42f), tint = iconTint ?: if (active) Color(0xFF1E1B4B) else Color.White.copy(alpha = if (enabled) 1f else 0.4f))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
