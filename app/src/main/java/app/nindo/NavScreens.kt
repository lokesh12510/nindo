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

// Sensei tab: see SenseiUi.kt.
