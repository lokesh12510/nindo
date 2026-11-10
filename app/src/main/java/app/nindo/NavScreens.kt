@file:OptIn(ExperimentalMaterial3Api::class)

package app.nindo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ================================================================ Guard tab

private val GUARD_PAGES = listOf("Rules", "Focus", "Protect")

/**
 * Rules, Focus and Protect as swipeable pages under one tab row. Neighbouring pages stay
 * composed, so switching is instant after the first open instead of rebuilding a whole screen.
 */
@Composable
fun GuardScreen() {
    val start = remember { UiNav.guardPage.value.coerceIn(0, 2) }
    val pager = rememberPagerState(initialPage = start) { GUARD_PAGES.size }
    val scope = rememberCoroutineScope()
    val wanted by UiNav.guardPage.collectAsState()
    // Shortcuts from other tabs set the page; swipes and taps report back once the page settles.
    LaunchedEffect(wanted) { if (!pager.isScrollInProgress && pager.settledPage != wanted) pager.scrollToPage(wanted.coerceIn(0, 2)) }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { UiNav.guardPage.value = it } }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Text("Guard", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            PrimaryTabRow(selectedTabIndex = pager.currentPage, containerColor = Color.Transparent, divider = {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }) {
                GUARD_PAGES.forEachIndexed { i, label ->
                    val sel = pager.currentPage == i
                    androidx.compose.material3.Tab(selected = sel, onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(label, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, fontSize = 15.sp) })
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1, key = { it }) { p ->
            val reveal = remember(p) { RevealScope.forScreen("guard$p") }
            CompositionLocalProvider(LocalReveal provides reveal, LocalHeaderFlat provides true) {
                when (p) {
                    0 -> RulesScreen()
                    1 -> FocusScreen()
                    else -> ProtectScreen()
                }
            }
        }
    }
}

// =============================================================== Sensei tab

/** Phase 2A: Sensei's home. The call and the daily picks arrive in the next phases. */
@Composable
fun SenseiScreen() {
    val hs by rememberHabits()
    val store = GuardApp.store
    val pendingHabits = hs.today.filter { !it.done && !it.failed && !it.pending }
    val open = habitSheets(hs)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Sensei", "Your voice coach")

        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .background(Brush.verticalGradient(listOf(Tone.B.copy(alpha = 0.12f), Color.Transparent)))
            .padding(horizontal = 20.dp, vertical = 24.dp).enter(0),
            horizontalAlignment = Alignment.CenterHorizontally) {
            VoiceOrb(184.dp)
            Spacer(Modifier.height(8.dp))
            Text("Talk to Sensei every day", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("A short call at your time to look back on your day, plan tomorrow and stay on your path.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button({}, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(50)) {
                Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(8.dp)); Text("Call Sensei")
            }
            Spacer(Modifier.height(8.dp))
            Text("Voice calls arrive in the next update", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionHeader("Pending today", Modifier.padding(top = 6.dp))
        Panel(Modifier.padding(horizontal = 16.dp).enter(1), padding = 14.dp) {
            if (pendingHabits.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.TaskAlt, Brand.Green)
                Spacer(Modifier.width(14.dp))
                Text("Nothing pending. Well done.", style = MaterialTheme.typography.bodyMedium)
            }
            pendingHabits.forEach { HabitRow(it, hs.streaks[it.habit.id]) { open(it.habit.id) } }
        }

        SectionHeader("Coming to Sensei", Modifier.padding(top = 6.dp))
        Panel(Modifier.padding(horizontal = 16.dp).enter(2), padding = 14.dp) {
            Upcoming(Icons.Rounded.Call, "Daily call", "Sensei calls you at your time, like a phone call")
            Upcoming(Icons.Rounded.Psychology, "Memory", "Remembers your goals from each call, on your phone only")
            Upcoming(Icons.Rounded.FormatQuote, "Morning word", "One line of motivation built from your own goals")
            Upcoming(Icons.Rounded.SmartDisplay, "One good video", "A pick for what you're learning, opened without the feed")
            Upcoming(Icons.Rounded.Alarm, "Voice reminders", "Say “remind me to call mom at 7”")
        }
    }
}

@Composable
private fun Upcoming(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, Tone.B, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
