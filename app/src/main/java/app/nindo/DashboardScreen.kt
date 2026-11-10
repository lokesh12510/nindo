package app.nindo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class DashData(
    val totals: Totals = Totals(0, 0, 0, 0, 0, 0, 0, 0),
    val apps: List<AppStat> = emptyList(),
    val sites: List<SiteStat> = emptyList(),
)

/** Today: habits and missions first, then a compact look at screen time. Details live in Insights. */
@Composable
fun DashboardScreen(goTo: (Tab) -> Unit) {
    val db = GuardApp.db
    val store = GuardApp.store
    val ctx = LocalContext.current
    val data by rememberPolled(DashData()) { DashData(db.totals(), db.topApps(limit = 5), db.topSites(limit = 5)) }
    val watcherOn by UrlWatcherService.running.collectAsState()
    val vpnOn by DnsVpnService.running.collectAsState()
    val wantReport by UiNav.openReport.collectAsState()
    var showReport by remember { mutableStateOf(false) }
    LaunchedEffect(wantReport) { if (wantReport) { showReport = true; UiNav.openReport.value = false } }
    if (showReport) ReportSheet { showReport = false }
    val t = data.totals
    val hs by rememberHabits()
    fun guard(page: Int) { UiNav.guardPage.value = page; goTo(Tab.Guard) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle(greeting(), SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())) {
            RankChip(hs.xp) { goTo(Tab.Path) }
        }

        TodayHero(hs, Modifier.padding(horizontal = 16.dp).enter(0))

        SectionHeader("Today's habits", Modifier.padding(top = 6.dp), action = "All habits") { goTo(Tab.Path) }
        HabitList(hs, Modifier.padding(horizontal = 16.dp).enter(1)) { goTo(Tab.Path) }

        if (hs.missions.isNotEmpty()) {
            SectionHeader("Daily missions", Modifier.padding(top = 6.dp))
            MissionsRow(hs, Modifier.enter(2))
        }

        if (store.focusActive) Panel(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).clickable { guard(1) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.CenterFocusStrong, Tone.B)
                Spacer(Modifier.width(14.dp))
                Text("Focus session until ${fmtClock(store.focusUntil)}", fontWeight = FontWeight.Medium)
            }
        }

        SectionHeader("Screen time", Modifier.padding(top = 6.dp), action = "Insights") { goTo(Tab.Insights) }
        ScreenCard(t, store.screenGoalMin, watcherOn, vpnOn, Modifier.padding(horizontal = 16.dp).enter(3)) { guard(2) }

        if (data.apps.isNotEmpty()) {
            SectionHeader("Top apps", Modifier.padding(top = 6.dp))
            Panel(Modifier.padding(horizontal = 16.dp).enter(4), padding = 12.dp) {
                val total = data.apps.sumOf { it.ms }.coerceAtLeast(1)
                data.apps.forEach { a ->
                    ShareRow({ AppIcon(a.pkg, 36.dp) }, Apps.label(ctx, a.pkg),
                        "${a.opens} opens" + if (a.blocks > 0) " · ${a.blocks} blocked" else "",
                        fmtDuration(a.ms), a.ms.toFloat() / total)
                }
            }
        }

        if (data.sites.isNotEmpty()) {
            SectionHeader("Top websites", Modifier.padding(top = 6.dp))
            Panel(Modifier.padding(horizontal = 16.dp).enter(5), padding = 12.dp) {
                val total = data.sites.sumOf { it.ms }.coerceAtLeast(1)
                data.sites.forEach { s ->
                    ShareRow({ IconTile(Icons.Rounded.Public, Brand.Sky, 36.dp) }, s.host,
                        "${s.visits} visits" + if (s.blocks > 0) " · ${s.blocks} blocked" else "",
                        fmtDuration(s.ms), s.ms.toFloat() / total, Brand.Sky)
                }
            }
        }
    }
}

@Composable
private fun TodayHero(s: HabitSnapshot, modifier: Modifier) {
    val done = s.doneToday
    val count = s.countToday
    val headline = when {
        count == 0 -> "Start your path"
        done == count -> "All done today"
        done == 0 -> "Let's begin"
        else -> "Keep going"
    }
    HeroCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(if (count == 0) 0f else done.toFloat() / count, 104.dp, 9.dp, Color.White, Color.White.copy(alpha = 0.2f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$done/$count", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("habits", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(headline, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("+${s.xpToday} XP today", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                val next = Ranks.next(s.xp)
                Text(
                    when {
                        s.xpPendingToday > 0 -> "+${s.xpPendingToday} more at midnight if you hold on"
                        next != null -> "${next.minXp - s.xp} XP to ${next.name}"
                        else -> "Highest rank reached"
                    }, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }
        val done3 = s.missions.count { it.done }
        if (s.missions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.13f)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Flag, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Missions", Modifier.weight(1f), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                s.missions.forEach { m ->
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (m.done) Color.White else Color.White.copy(alpha = 0.25f)))
                }
                Spacer(Modifier.width(10.dp))
                Text("$done3 of ${s.missions.size}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ScreenCard(t: Totals, goalMin: Int, watcherOn: Boolean, vpnOn: Boolean, modifier: Modifier, onProtect: () -> Unit) {
    val goalMs = goalMin * 60_000L
    val f = t.screenMs.toFloat() / goalMs.coerceAtLeast(1)
    val c = when { f > 1f -> Brand.Rose; f > 0.8f -> Brand.Amber; else -> Brand.Green }
    val store = GuardApp.store
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(f, 96.dp, 8.dp, c, c.copy(alpha = 0.15f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedDuration(t.screenMs, MaterialTheme.typography.titleMedium)
                    Text("of ${fmtMinutes(goalMin)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                StatLine(Icons.Rounded.TouchApp, Brand.Sky, "App opens", "${t.opens}")
                StatLine(Icons.Rounded.Movie, Brand.Teal, "Reels", if (store.reelCap > 0) "${t.reels} / ${store.reelCap}" else "${t.reels}")
                StatLine(Icons.Rounded.Swipe, Brand.Amber, "Scrolling", fmtDuration(t.scrollMs))
                StatLine(Icons.Rounded.Block, Brand.Rose, "Blocks", "${t.blocks + t.dnsBlocked}")
            }
        }
        if (!watcherOn || !vpnOn) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Amber.copy(alpha = 0.12f)).clickable(onClick = onProtect)
                .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Brand.Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(listOfNotNull(if (!watcherOn) "Watcher is off" else null, if (!vpnOn) "VPN filter is off" else null).joinToString(" · "),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text("Fix", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night"
}
