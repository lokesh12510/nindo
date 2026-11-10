package app.nindo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handle(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Notify.allowed(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { GuardTheme { Root() } }
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handle(intent) }

    override fun onResume() {
        super.onResume()
        // Fresh app times from Android's usage history each time Nindō is opened.
        UsageSync.syncAsync(this)
        UsageSync.refreshTodayAsync(this, minGapMs = 0)
    }

    private fun handle(i: android.content.Intent?) {
        if (i?.getBooleanExtra(EXTRA_REPORT, false) == true) UiNav.openReport.value = true
    }
}

/** Material pattern: outlined icon normally, filled icon when the tab is selected. */
enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Today("Today", Icons.Outlined.WbSunny, Icons.Rounded.WbSunny),
    Path("Path", Icons.Outlined.Flag, Icons.Rounded.Flag),
    Sensei("Sensei", Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome),
    Guard("Guard", Icons.Outlined.Shield, Icons.Rounded.Shield),
    Insights("Insights", Icons.Outlined.BarChart, Icons.Rounded.BarChart),
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
    var tabName by rememberSaveable { mutableStateOf(Tab.Today.name) }
    val tab = Tab.entries.firstOrNull { it.name == tabName } ?: Tab.Today
    fun go(t: Tab) { tabName = t.name }
    val wantReport by UiNav.openReport.collectAsState()
    LaunchedEffect(wantReport) { if (wantReport) go(Tab.Today) }
    val guardPage by UiNav.guardPage.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Keep today's app times current while Nindō is on screen.
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); UsageSync.refreshTodayAsync(ctx) } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    val selected = t == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { go(t) },
                        icon = { Icon(if (selected) t.selectedIcon else t.icon, contentDescription = t.label) },
                        label = { Text(t.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { pad ->
        // Edge to edge: headers sit behind the status bar. Once the page scrolls past them, a scrim
        // fades in behind the status bar and its icons turn dark (light theme), so they stay readable.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scrolled = remember { mutableFloatStateOf(0f) }
        val track = remember {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                    source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    scrolled.floatValue = (scrolled.floatValue - consumed.y).coerceAtLeast(0f)
                    return androidx.compose.ui.geometry.Offset.Zero
                }
            }
        }
        LaunchedEffect(tab, guardPage) { scrolled.floatValue = 0f }
        val threshold = with(density) { 96.dp.toPx() }
        val scrim by remember { derivedStateOf { scrolled.floatValue > threshold } }
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (view.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !dark
            }
        }

        Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()
            .nestedScroll(track)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(300)) + slideInHorizontally(tween(350, easing = FastOutSlowInEasing)) { it / 5 * dir }) togetherWith
                        (fadeOut(tween(200)) + slideOutHorizontally(tween(300)) { -it / 8 * dir })
                },
                label = "tabs",
            ) { t ->
                // Entry animations play the first time each screen opens, not on later visits or scrolls.
                val reveal = remember(t) { RevealScope.forScreen(t) }
                CompositionLocalProvider(LocalReveal provides reveal) {
                    when (t) {
                        Tab.Today -> DashboardScreen { go(it) }
                        Tab.Path -> PathScreen()
                        Tab.Sensei -> SenseiScreen()
                        Tab.Guard -> GuardScreen()
                        Tab.Insights -> ActivityScreen()
                    }
                }
            }
            AnimatedVisibility(scrim, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f)))
            }
            SetupGate()
        }
    }
}

// ------------------------------------------------------------------ PIN lock

@Composable
fun PinLock(pin: String, title: String = "Enter your PIN", onUnlock: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val habits = remember { HabitEngine.last }
    val len = pin.length.coerceAtLeast(4)

    fun press(d: String) {
        if (entry.length >= len) return
        wrong = false
        entry += d
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (entry.length == pin.length) {
            if (entry == pin) onUnlock()
            else scope.launch {
                wrong = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                for (x in listOf(26f, -22f, 16f, -10f, 4f, 0f)) shake.animateTo(x, tween(50))
                entry = ""
            }
        }
    }

    // Slow-moving glows behind everything.
    val t = rememberInfiniteTransition(label = "lockGlow")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0B0A1F)).drawBehind {
            val w = size.width; val h = size.height
            drawCircle(Brush.radialGradient(listOf(Tone.A.copy(alpha = 0.55f), Color.Transparent),
                androidx.compose.ui.geometry.Offset(w * (0.15f + 0.2f * drift), h * 0.18f), w * 0.8f), w * 0.8f,
                androidx.compose.ui.geometry.Offset(w * (0.15f + 0.2f * drift), h * 0.18f))
            drawCircle(Brush.radialGradient(listOf(Tone.B.copy(alpha = 0.45f), Color.Transparent),
                androidx.compose.ui.geometry.Offset(w * (0.9f - 0.2f * drift), h * 0.55f), w * 0.75f), w * 0.75f,
                androidx.compose.ui.geometry.Offset(w * (0.9f - 0.2f * drift), h * 0.55f))
        }.systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(0.6f))
            Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(if (wrong) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Nindō", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            AnimatedContent(wrong, label = "lockMsg") { w ->
                Text(if (w) "Wrong PIN. Try again." else title, color = if (w) Color(0xFFFDA4AF) else Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            }
            habits?.takeIf { it.countToday > 0 }?.let { s ->
                Spacer(Modifier.height(14.dp))
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TaskAlt, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${s.doneToday} of ${s.countToday} habits done today", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(30.dp))
            Row(Modifier.graphicsLayer { translationX = shake.value }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(len) { i ->
                    val filled = i < entry.length
                    val s by animateFloatAsState(if (filled) 1f else 0.6f, spring(dampingRatio = 0.4f, stiffness = 600f), label = "dot")
                    Box(Modifier.size(16.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape)
                        .background(when { wrong -> Color(0xFFFDA4AF); filled -> Color.White; else -> Color.White.copy(alpha = 0.22f) }))
                }
            }
            Spacer(Modifier.weight(1f))

            val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
                "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "" to "", "0" to "+", "<" to "")
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { (k, letters) ->
                        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                            when (k) {
                                "" -> {}
                                "<" -> Box(Modifier.size(76.dp).clip(CircleShape).pressable { wrong = false; entry = entry.dropLast(1) },
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = Color.White.copy(alpha = 0.85f))
                                }
                                else -> Column(Modifier.size(76.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.09f)).pressable { press(k) },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(k, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Normal)
                                    if (letters.isNotEmpty()) Text(letters, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                                        letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Never give up · Stay committed · Keep moving forward", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
        }
    }
}
