@file:OptIn(ExperimentalFoundationApi::class)

package app.nindo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Filter(val label: String, val kinds: List<String>?) {
    All("All", null),
    Apps("Apps", listOf(Kind.OPEN)),
    Sites("Websites", listOf(Kind.VISIT)),
    Blocks("Blocks", listOf(Kind.BLOCK, Kind.DNS_BLOCK)),
    Scroll("Scrolling", listOf(Kind.SCROLL, Kind.NUDGE, Kind.REELS)),
    Network("Network", emptyList()),
}

@Composable
fun ActivityScreen() {
    val db = GuardApp.db
    var filter by remember { mutableStateOf(Filter.All) }
    val events by rememberPolled(emptyList<Event>(), filter) { if (filter == Filter.Network) emptyList() else db.events(filter.kinds) }
    val dns by rememberPolled(emptyList<DnsStat>(), filter) { if (filter == Filter.Network) db.topDns(limit = 100) else emptyList() }
    val dayFmt = remember { SimpleDateFormat("EEEE, d MMM", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val grouped = remember(events) { events.groupBy { dayFmt.format(Date(it.ts)) } }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            AuroraHeader("Activity", "Everything Nindō saw, newest first",
                colors = listOf(Color(0xFF1D4ED8), Color(0xFF0891B2), Brand.Teal))
        }
        stickyHeader {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Filter.entries.forEach { f ->
                        FilterChip(filter == f, { filter = f }, { Text(f.label) },
                            leadingIcon = if (filter == f) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null)
                    }
                }
            }
        }

        if (filter == Filter.Network) {
            item {
                Text("Domains looked up today by all apps (needs the VPN filter).", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (dns.isEmpty()) item { EmptyHint(Icons.Rounded.Dns, "No lookups yet today.") }
            items(dns, key = { it.domain }) { d ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).animateItem(), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(if (d.blocked > 0) Icons.Rounded.Block else Icons.Rounded.Dns, if (d.blocked > 0) Brand.Rose else Brand.Sky, 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(d.domain, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(if (d.blocked > 0) "${d.blocked} blocked" else "${d.queries}×", style = MaterialTheme.typography.labelMedium,
                        color = if (d.blocked > 0) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            if (events.isEmpty()) item { EmptyHint(Icons.Rounded.Timeline, "Nothing here yet. Turn on the watcher in Protect.") }
            grouped.forEach { (day, list) ->
                item(key = "h_$day") {
                    Text(day, Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                items(list, key = { "${it.ts}_${it.kind}_${it.title}" }) { e ->
                    TimelineRow(e, timeFmt.format(Date(e.ts)), Modifier.animateItem())
                }
            }
        }
    }
}

private fun style(kind: String): Pair<ImageVector, Color> = when (kind) {
    Kind.OPEN -> Icons.Rounded.Apps to Brand.Indigo
    Kind.VISIT -> Icons.Rounded.Language to Brand.Sky
    Kind.BLOCK -> Icons.Rounded.Block to Brand.Rose
    Kind.DNS_BLOCK -> Icons.Rounded.Dns to Brand.Rose
    Kind.SCROLL -> Icons.Rounded.Swipe to Brand.Amber
    Kind.NUDGE -> Icons.Rounded.Spa to Brand.Teal
    Kind.REELS -> Icons.Rounded.Psychology to Brand.Violet
    Kind.FOCUS -> Icons.Rounded.CenterFocusStrong to Brand.Indigo
    Kind.STRICT -> Icons.Rounded.Lock to Brand.Violet
    Kind.TAMPER -> Icons.Rounded.Warning to Brand.Amber
    else -> Icons.Rounded.Circle to Color.Gray
}

@Composable
private fun TimelineRow(e: Event, time: String, modifier: Modifier) {
    val (icon, color) = style(e.kind)
    val line = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp)) {
        Text(time, Modifier.width(46.dp).padding(top = 12.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.width(34.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 4f)
            }
            Box(Modifier.padding(top = 6.dp)) { IconBadge(icon, color, 30.dp) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            val what = when (e.kind) {
                Kind.OPEN -> "Opened ${e.title}"
                Kind.VISIT -> e.title
                Kind.BLOCK -> "Blocked ${e.title}"
                Kind.DNS_BLOCK -> "Network block: ${e.title}"
                Kind.SCROLL -> "Scrolled ${e.title} for ${fmtDuration(e.durMs)}"
                Kind.NUDGE -> "Scroll nudge in ${e.title}"
                else -> e.title
            }
            Text(what, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2)
            e.detail?.takeIf { it.isNotBlank() && it != e.title }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}
