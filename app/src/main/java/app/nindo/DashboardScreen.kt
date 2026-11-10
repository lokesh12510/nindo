package app.nindo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private data class DashData(
    val totals: Totals = Totals(0, 0, 0, 0, 0, 0, 0, 0),
    val apps: List<AppStat> = emptyList(),
    val sites: List<SiteStat> = emptyList(),
    val blockedDns: List<DnsStat> = emptyList(),
    val hourly: LongArray = LongArray(24),
    val week: List<Pair<String, Long>> = emptyList(),
)

@Composable
fun DashboardScreen(goTo: (Tab) -> Unit) {
    val db = GuardApp.db
    val store = GuardApp.store
    val ctx = LocalContext.current
    val data by rememberPolled(DashData()) {
        DashData(db.totals(), db.topApps(limit = 6), db.topSites(limit = 5), db.topDns(limit = 5, blockedOnly = true), db.hourly(), db.lastDays(7))
    }
    val watcherOn by UrlWatcherService.running.collectAsState()
    val vpnOn by DnsVpnService.running.collectAsState()
    val goalMs = store.screenGoalMin * 60_000L
    val wantReport by UiNav.openReport.collectAsState()
    var showReport by remember { mutableStateOf(false) }
    LaunchedEffect(wantReport) { if (wantReport) { showReport = true; UiNav.openReport.value = false } }
    if (showReport) ReportSheet { showReport = false }
    val t = data.totals

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            AuroraHeader(title = greeting(), subtitle = "Nindō · keep moving forward") {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UsageRing(t.screenMs.toFloat() / goalMs.coerceAtLeast(1)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AnimatedDuration(t.screenMs, MaterialTheme.typography.headlineSmall, Color.White)
                            Text("of ${fmtMinutes(store.screenGoalMin)}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeaderPill(if (watcherOn) "Watcher on" else "Watcher off", watcherOn)
                        HeaderPill(if (vpnOn) "VPN filter on" else "VPN filter off", vpnOn)
                        val pct = (t.screenMs * 100 / goalMs.coerceAtLeast(1)).toInt()
                        Text(
                            if (pct >= 100) "Over your daily goal" else "$pct% of your goal",
                            color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp).enter(1), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(Icons.Rounded.TouchApp, "App opens", Brand.Sky, Modifier.weight(1f), { goTo(Tab.Activity) }) {
                        AnimatedNumber(t.opens, MaterialTheme.typography.headlineSmall)
                    }
                    StatTile(Icons.Rounded.Swipe, "Scrolling · ${t.swipes} swipes", Brand.Amber, Modifier.weight(1f), { goTo(Tab.Focus) }) {
                        AnimatedDuration(t.scrollMs, MaterialTheme.typography.headlineSmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(Icons.Rounded.Block, "Blocks today", Brand.Rose, Modifier.weight(1f), { goTo(Tab.Activity) }) {
                        AnimatedNumber(t.blocks + t.dnsBlocked, MaterialTheme.typography.headlineSmall)
                    }
                    StatTile(Icons.Rounded.Psychology, "Reels · ${Reels.stage(t.reels, store.reelCap).emoji} ${Reels.stage(t.reels, store.reelCap).label}",
                        Brand.Teal, Modifier.weight(1f), { goTo(Tab.Focus) }) {
                        AnimatedNumber(t.reels, MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }

        item { StreakCard(Modifier.padding(horizontal = 16.dp).enter(2)) { showReport = true } }

        if (store.focusActive) item {
            Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth().pressable { goTo(Tab.Focus) }, shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = Brand.Violet.copy(alpha = 0.15f))) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CenterFocusStrong, null, tint = Brand.Violet)
                    Spacer(Modifier.width(10.dp))
                    Text("Focus session until ${fmtClock(store.focusUntil)}", fontWeight = FontWeight.Medium)
                }
            }
        }

        item {
            SectionCard("By hour", Modifier.padding(horizontal = 16.dp).enter(2)) {
                val hourNow = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                BarChart(
                    data.hourly.map { it.toFloat() },
                    List(24) { if (it % 6 == 0) "${it}h" else "" },
                    highlight = hourNow,
                )
            }
        }

        item {
            SectionCard("Top apps", Modifier.padding(horizontal = 16.dp).enter(3)) {
                if (data.apps.isEmpty()) EmptyHint(Icons.Rounded.Apps, "Turn on the watcher in Protect to start tracking apps.")
                val max = data.apps.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
                data.apps.forEach { a ->
                    UsageRow(
                        leading = { AppIcon(a.pkg) },
                        title = Apps.label(ctx, a.pkg),
                        subtitle = "${a.opens} opens" + (if (a.scrollMs > 0) " · ${fmtDuration(a.scrollMs)} scrolling" else "") +
                            (if (a.blocks > 0) " · ${a.blocks} blocked" else ""),
                        value = fmtDuration(a.ms),
                        fraction = a.ms.toFloat() / max,
                    )
                }
            }
        }

        item {
            SectionCard("Top websites", Modifier.padding(horizontal = 16.dp).enter(4)) {
                if (data.sites.isEmpty()) EmptyHint(Icons.Rounded.Language, "Sites you open in any browser appear here.")
                val max = data.sites.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
                data.sites.forEach { s ->
                    UsageRow(
                        leading = { IconBadge(Icons.Rounded.Public, Brand.Sky) },
                        title = s.host,
                        subtitle = "${s.visits} visits" + if (s.blocks > 0) " · ${s.blocks} blocked" else "",
                        value = fmtDuration(s.ms),
                        fraction = s.ms.toFloat() / max,
                        color = Brand.Sky,
                    )
                }
            }
        }

        item {
            SectionCard("Network filter", Modifier.padding(horizontal = 16.dp).enter(5)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        AnimatedNumber(t.dnsQueries, MaterialTheme.typography.titleLarge)
                        Text("lookups checked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        AnimatedNumber(t.dnsBlocked, MaterialTheme.typography.titleLarge, Brand.Rose)
                        Text("blocked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (data.blockedDns.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    data.blockedDns.forEach { d ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(d.domain, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text("${d.blocked}×", color = Brand.Rose, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else if (!vpnOn) {
                    Spacer(Modifier.height(8.dp))
                    Text("Turn on the VPN filter in Protect to block sites in every app.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            SectionCard("Last 7 days", Modifier.padding(horizontal = 16.dp).enter(6)) {
                val fmtIn = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val fmtOut = SimpleDateFormat("EEE", Locale.getDefault())
                if (data.week.isNotEmpty()) {
                    BarChart(
                        data.week.map { it.second.toFloat() },
                        data.week.map { fmtOut.format(fmtIn.parse(it.first)!!) },
                        highlight = data.week.size - 1, height = 110.dp, color = Brand.Teal,
                    )
                    val avg = data.week.map { it.second }.average().toLong()
                    Spacer(Modifier.height(8.dp))
                    Text("Daily average ${fmtDuration(avg)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HeaderPill(text: String, on: Boolean) {
    Surface(color = Color.White.copy(alpha = 0.16f), shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(on)
            Spacer(Modifier.width(4.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night"
}
