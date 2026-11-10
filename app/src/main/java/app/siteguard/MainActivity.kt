package app.siteguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_REPORT = "open_report" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Notify.allowed(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { GuardTheme { Root() } }
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: android.content.Intent?) {
        if (i?.getBooleanExtra(EXTRA_REPORT, false) == true) UiNav.openReport.value = true
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("Today", Icons.Rounded.Insights),
    Rules("Rules", Icons.Rounded.Shield),
    Activity("Activity", Icons.Rounded.Timeline),
    Focus("Focus", Icons.Rounded.SelfImprovement),
    Protect("Protect", Icons.Rounded.VerifiedUser),
}

@Composable
private fun Root() {
    val store = GuardApp.store
    var unlocked by rememberSaveable { mutableStateOf(store.pin == null) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (store.pin != null) unlocked = false }

    AnimatedContent(
        targetState = unlocked,
        transitionSpec = { (fadeIn(tween(400)) + scaleIn(initialScale = 1.06f)) togetherWith fadeOut(tween(250)) },
        label = "lock",
    ) { open ->
        if (open) MainScaffold() else PinLock(store.pin ?: "") { unlocked = true }
    }
}

@Composable
private fun MainScaffold() {
    var tab by rememberSaveable { mutableStateOf(Tab.Dashboard) }
    val wantReport by UiNav.openReport.collectAsState()
    LaunchedEffect(wantReport) { if (wantReport) tab = Tab.Dashboard }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    val selected = t == tab
                    val scale by animateFloatAsState(if (selected) 1.15f else 1f, spring(dampingRatio = 0.45f), label = "navScale")
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null, Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(300)) + slideInHorizontally(tween(350, easing = FastOutSlowInEasing)) { it / 5 * dir }) togetherWith
                        (fadeOut(tween(200)) + slideOutHorizontally(tween(300)) { -it / 8 * dir })
                },
                label = "tabs",
            ) { t ->
                when (t) {
                    Tab.Dashboard -> DashboardScreen { tab = it }
                    Tab.Rules -> RulesScreen()
                    Tab.Activity -> ActivityScreen()
                    Tab.Focus -> FocusScreen()
                    Tab.Protect -> ProtectScreen()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ PIN lock

@Composable
fun PinLock(pin: String, title: String = "Enter your PIN", onUnlock: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    fun press(d: String) {
        if (entry.length >= 8) return
        entry += d
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (entry.length == pin.length) {
            if (entry == pin) onUnlock()
            else scope.launch {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                for (x in listOf(24f, -20f, 14f, -8f, 0f)) shake.animateTo(x, tween(55))
                entry = ""
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Brand.Indigo, Brand.Violet))).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Never give up · Stay committed · Keep moving forward", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(28.dp))
        Row(Modifier.graphicsLayer { translationX = shake.value }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(pin.length.coerceAtLeast(4)) { i ->
                val filled = i < entry.length
                val s by animateFloatAsState(if (filled) 1.25f else 1f, spring(dampingRatio = 0.4f), label = "dot")
                Box(Modifier.size(14.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape)
                    .background(if (filled) Color.White else Color.White.copy(alpha = 0.3f)))
            }
        }
        Spacer(Modifier.height(40.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.padding(vertical = 9.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(if (k.isEmpty()) Color.Transparent else Color.White.copy(alpha = 0.14f))
                            .then(if (k.isEmpty()) Modifier else Modifier.pressable {
                                if (k == "<") entry = entry.dropLast(1) else press(k)
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "<") Icon(Icons.AutoMirrored.Rounded.Backspace, null, tint = Color.White)
                        else Text(k, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
