@file:OptIn(ExperimentalMaterial3Api::class)

package app.nindo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/**
 * The tracker: streaks, a calendar coloured by how each day went, the chosen day's numbers,
 * the week and two-week trends. The detailed timeline and the weekly report open as drawers.
 */
@Composable
fun ActivityScreen() {
    val db = GuardApp.db
    val store = GuardApp.store
    val todayDate = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(todayDate) }
    var showTimeline by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val goalMs = store.screenGoalMin * 60_000L

    val monthStats by rememberPolled(emptyMap<String, DayStat>(), month) {
        db.dayStats(month.atDay(1).toString(), month.atEndOfMonth().toString())
    }
    val day by rememberPolled(DayDetail(), selected) { loadDay(selected) }
    val trend by rememberPolled(Trend()) { loadTrend() }
    val streak by rememberPolled(Streaks.Result(0, 0, true, 0, goalMs)) { Streaks.load() }
    var habitMode by remember { mutableStateOf(false) }
    val habitMonth by rememberPolled(emptyMap<String, Pair<Int, Int>>(), month) {
        HabitEngine.history(month.atDay(1).toString(), month.atEndOfMonth().toString())
    }
    val dayHabits by rememberPolled(emptyList<HabitDay>(), selected) { HabitEngine.dayHabits(selected.toString()) }
    val rates by rememberPolled(emptyList<Triple<Habit, Int, Int>>()) { HabitEngine.rates(30) }
    val hs by rememberHabits()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("Insights", "Your patterns") {
            FilledTonalIconButton({ showReport = true }) { Icon(Icons.Rounded.Assessment, "Weekly report") }
        }
        run {
            val onGoal = monthStats.values.count { it.ms in 1..goalMs && it.day < todayDate.toString() }
            val tracked = monthStats.values.count { it.ms > 0 && it.day < todayDate.toString() }
            val version by store.version.collectAsState()
            val clean = remember(version) { store.cleanDays }
            Row(Modifier.padding(horizontal = 16.dp).enter(0), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryTile(Icons.Rounded.LocalFireDepartment, Brand.Amber, "${streak.current}", "goal streak", Modifier.weight(1f))
                SummaryTile(Icons.Rounded.Shield, Brand.Teal, "$clean", if (clean == 1) "clean day" else "clean days", Modifier.weight(1f))
                SummaryTile(Icons.Rounded.EventAvailable, Brand.Green, "$onGoal/$tracked", "days on goal", Modifier.weight(1f))
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).fillMaxWidth().enter(1)) {
            SegmentedButton(!habitMode, { habitMode = false }, SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(18.dp)) }) { Text("Screen time") }
            SegmentedButton(habitMode, { habitMode = true }, SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Rounded.TaskAlt, null, Modifier.size(18.dp)) }) { Text("Habits") }
        }

        CalendarCard(month, selected, todayDate, monthStats, goalMs, Modifier.padding(horizontal = 16.dp).enter(1),
            habits = if (habitMode) habitMonth else null, onMonth = { month = it }, onSelect = { selected = it })

        if (habitMode) {
            HabitsDayCard(selected, todayDate, dayHabits, Modifier.padding(horizontal = 16.dp).enter(2))
            HabitRatesCard(rates, hs, Modifier.padding(horizontal = 16.dp).enter(3))
        } else {
            DayCard(selected, todayDate, day, goalMs, Modifier.padding(horizontal = 16.dp).enter(2)) { showTimeline = true }
        }

        if (!habitMode) {
            WeekCard(trend, goalMs, Modifier.padding(horizontal = 16.dp).enter(3)) { showReport = true }
            TrendsCard(trend, Modifier.padding(horizontal = 16.dp).enter(4))
        }
    }

    if (showTimeline) TimelineSheet(selected) { showTimeline = false }
    if (showReport) ReportSheet { showReport = false }
}

@Composable
private fun SummaryTile(icon: ImageVector, tint: Color, value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) {
        IconTile(icon, tint, 34.dp)
        Spacer(Modifier.height(10.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

// ================================================================ calendar

/** How a day went against the screen-time goal. */
private fun dayColor(ms: Long, goalMs: Long): Color? = when {
    ms <= 0 -> null
    ms <= goalMs -> Brand.Green
    ms <= goalMs * 3 / 2 -> Brand.Amber
    else -> Brand.Rose
}

@Composable
private fun CalendarCard(
    month: YearMonth, selected: LocalDate, today: LocalDate, stats: Map<String, DayStat>, goalMs: Long,
    modifier: Modifier, habits: Map<String, Pair<Int, Int>>? = null, onMonth: (YearMonth) -> Unit, onSelect: (LocalDate) -> Unit,
) {
    SectionCard(null, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
            Text(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year,
                Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            IconButton({ onMonth(month.plusMonths(1)) }, enabled = month < YearMonth.now()) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month")
            }
        }
        Spacer(Modifier.height(6.dp))
        // Weekday initials, Monday first.
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.values().forEach { d ->
                Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        AnimatedContent(month, transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (slideInHorizontally(tween(280)) { it / 4 * dir } + fadeIn(tween(280))) togetherWith
                (slideOutHorizontally(tween(220)) { -it / 4 * dir } + fadeOut(tween(180)))
        }, label = "month") { m ->
            val lead = m.atDay(1).dayOfWeek.value - 1 // Monday = 0
            val cells = lead + m.lengthOfMonth()
            Column {
                for (week in 0 until (cells + 6) / 7) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val n = week * 7 + col - lead + 1
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                                if (n in 1..m.lengthOfMonth()) {
                                    val date = m.atDay(n)
                                    val st = stats[date.toString()]
                                    val tone = if (habits != null) habitColor(habits[date.toString()], date == today) else dayColor(st?.ms ?: 0L, goalMs)
                                    DayCell(n, tone, date == selected, date == today,
                                        future = date > today, shield = habits == null && (st?.urgesResisted ?: 0) > 0) { onSelect(date) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (habits != null) { Legend(Brand.Green, "All done"); Legend(Brand.Amber, "Half or more"); Legend(Brand.Rose, "Less") }
            else { Legend(Brand.Green, "On goal"); Legend(Brand.Amber, "A bit over"); Legend(Brand.Rose, "Way over") }
        }
        if (habits == null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = Brand.Teal, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text("Urge resisted that day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How a day went for habits: all done, half or more, or less. Today stays neutral until it's all done. */
private fun habitColor(v: Pair<Int, Int>?, isToday: Boolean): Color? {
    val (done, due) = v ?: return null
    if (due == 0) return null
    val f = done.toFloat() / due
    return when {
        f >= 1f -> Brand.Green
        isToday -> null
        f >= 0.5f -> Brand.Amber
        else -> Brand.Rose
    }
}

@Composable
private fun DayCell(n: Int, tone: Color?, selected: Boolean, today: Boolean, future: Boolean, shield: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary
            tone != null -> tone.copy(alpha = 0.22f)
            else -> Color.Transparent
        }, tween(250), label = "cell")
    val fg = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        future -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier.fillMaxSize().clip(CircleShape).background(bg)
            .then(if (today && !selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .then(if (future) Modifier else Modifier.clickable(onClick = onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = if (today || selected) FontWeight.Bold else FontWeight.Normal)
        // A small dot in the day's colour, so the result still shows when the day is selected.
        if (tone != null && selected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(5.dp).clip(CircleShape).background(tone))
        // Resisted an urge that day.
        if (shield) Icon(Icons.Rounded.Shield, null, tint = Brand.Teal, modifier = Modifier.align(Alignment.TopEnd).size(10.dp))
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c.copy(alpha = 0.6f)))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ================================================================ selected day

private data class DayDetail(
    val urges: Pair<Int, Int> = 0 to 0, // resisted, continued
    val totals: Totals = Totals(0, 0, 0, 0, 0, 0, 0, 0),
    val hourly: List<Float> = List(24) { 0f },
    val apps: List<AppStat> = emptyList(),
    val events: Int = 0,
)

private fun loadDay(date: LocalDate): DayDetail {
    val db = GuardApp.db
    val d = date.toString()
    val urges = db.eventsOn(d, listOf(Kind.URGE))
    return DayDetail(urges.count { it.detail == "resisted" } to urges.count { it.detail == "continued" },
        db.totals(d), db.hourly(d).map { it / 60_000f }, db.topApps(d, limit = 4), db.eventsOn(d, limit = 1).size)
}

@Composable
private fun DayCard(date: LocalDate, today: LocalDate, d: DayDetail, goalMs: Long, modifier: Modifier, onTimeline: () -> Unit) {
    val ctx = LocalContext.current
    val title = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))
    }
    val t = d.totals
    SectionCard(title, modifier, action = {
        val tone = dayColor(t.screenMs, goalMs)
        if (tone != null) {
            val over = t.screenMs - goalMs
            AssistChip({}, { Text(if (over <= 0) "On goal" else "${fmtDuration(over)} over") },
                colors = AssistChipDefaults.assistChipColors(containerColor = tone.copy(alpha = 0.15f), labelColor = tone),
                border = null)
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat(Icons.Rounded.Timer, Brand.Indigo, fmtDuration(t.screenMs), "screen time", Modifier.weight(1f))
            MiniStat(Icons.Rounded.TouchApp, Brand.Sky, "${t.opens}", "app opens", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat(Icons.Rounded.Psychology, Brand.Violet, "${t.reels}", "reels", Modifier.weight(1f))
            MiniStat(Icons.Rounded.Block, Brand.Rose, "${t.blocks}", "blocks", Modifier.weight(1f))
        }
        val (resisted, gaveIn) = d.urges
        if (resisted + gaveIn > 0) {
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = Brand.Teal.copy(alpha = 0.1f)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, tint = Brand.Teal, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("$resisted urge${if (resisted == 1) "" else "s"} resisted" + if (gaveIn > 0) " · $gaveIn continued" else "",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (t.screenMs > 0) {
            Spacer(Modifier.height(16.dp))
            Text("By hour (minutes)", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            val peak = d.hourly.indices.maxByOrNull { d.hourly[it] } ?: -1
            BarChart(d.hourly, List(24) { if (it % 6 == 0) "$it" else "" }, highlight = peak, height = 90.dp)
        }
        if (d.apps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val total = t.screenMs.coerceAtLeast(1)
            d.apps.forEach { a ->
                ShareRow({ AppIcon(a.pkg, 34.dp) }, Apps.label(ctx, a.pkg), "${a.opens} opens", fmtDuration(a.ms), a.ms.toFloat() / total)
            }
        }
        if (t.screenMs == 0L && d.events == 0) EmptyHint(Icons.Rounded.EventBusy, "Nothing tracked on this day.")
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onTimeline, Modifier.fillMaxWidth(), enabled = d.events > 0) {
            Icon(Icons.Rounded.Timeline, null); Spacer(Modifier.width(8.dp)); Text("Open timeline")
        }
    }
}

@Composable
private fun MiniStat(icon: ImageVector, tint: Color, value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = tint.copy(alpha = 0.08f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint, 34.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ================================================================ week and trends

private data class Trend(
    val days: List<DayStat> = emptyList(), // last 14 days, oldest first
)

private fun loadTrend(): Trend {
    val end = LocalDate.now()
    val start = end.minusDays(13)
    val stats = GuardApp.db.dayStats(start.toString(), end.toString())
    return Trend((0..13).map { i -> start.plusDays(i.toLong()).toString().let { stats[it] ?: DayStat(it) } })
}

@Composable
private fun WeekCard(trend: Trend, goalMs: Long, modifier: Modifier, onReport: () -> Unit) {
    if (trend.days.size < 14) return
    val cur = trend.days.takeLast(7)
    val prev = trend.days.take(7)
    val curMs = cur.sumOf { it.ms }
    val prevMs = prev.sumOf { it.ms }
    SectionCard("This week", modifier, action = {
        if (prevMs > 0) {
            val pct = ((curMs - prevMs) * 100 / prevMs).toInt()
            val good = pct <= 0
            AssistChip({}, { Text(if (good) "${-pct}% less" else "$pct% more") },
                leadingIcon = { Icon(if (good) Icons.AutoMirrored.Rounded.TrendingDown else Icons.AutoMirrored.Rounded.TrendingUp, null, Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = (if (good) Brand.Green else Brand.Rose).copy(alpha = 0.15f),
                    labelColor = if (good) Brand.Green else Brand.Rose,
                    leadingIconContentColor = if (good) Brand.Green else Brand.Rose),
                border = null)
        }
    }) {
        val days = cur.count { it.ms > 0 }.coerceAtLeast(1)
        Text("${fmtDuration(curMs)} total · ${fmtDuration(curMs / days)} a day", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val labels = cur.map { LocalDate.parse(it.day).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        GoalBars(cur.map { it.ms }, labels, goalMs)
        Spacer(Modifier.height(8.dp))
        TextButton(onReport) { Icon(Icons.Rounded.Assessment, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Full weekly report") }
    }
}

/** Seven bars coloured by the goal, with a dashed goal line. */
@Composable
private fun GoalBars(values: List<Long>, labels: List<String>, goalMs: Long) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val max = maxOf(values.maxOrNull() ?: 0L, goalMs).coerceAtLeast(1L).toFloat() * 1.1f
    Column {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val bw = slot * 0.55f
            values.forEachIndexed { i, v ->
                val x = i * slot + (slot - bw) / 2
                drawRoundRect(track, Offset(x, 0f), androidx.compose.ui.geometry.Size(bw, size.height),
                    androidx.compose.ui.geometry.CornerRadius(bw / 2))
                val h = size.height * (v / max)
                val c = dayColor(v, goalMs) ?: Color.Transparent
                if (h > 0) drawRoundRect(c, Offset(x, size.height - h), androidx.compose.ui.geometry.Size(bw, h),
                    androidx.compose.ui.geometry.CornerRadius(bw / 2))
            }
            val gy = size.height - size.height * (goalMs / max)
            drawLine(line, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 3f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(it, Modifier.weight(1f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TrendsCard(trend: Trend, modifier: Modifier) {
    if (trend.days.isEmpty()) return
    SectionCard("Last 14 days", modifier) {
        TrendRow("Screen time", Brand.Indigo, trend.days.map { it.ms / 60_000f }) { fmtDuration((it * 60_000).toLong()) }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        TrendRow("Reels", Brand.Violet, trend.days.map { it.reels.toFloat() }) { "${it.toInt()}" }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        TrendRow("App opens", Brand.Sky, trend.days.map { it.opens.toFloat() }) { "${it.toInt()}" }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        TrendRow("Blocks", Brand.Rose, trend.days.map { it.blocks.toFloat() }) { "${it.toInt()}" }
    }
}

/** Label with the daily average, and a smooth sparkline of the values. */
@Composable
private fun TrendRow(label: String, color: Color, values: List<Float>, fmt: (Float) -> String) {
    val tracked = values.filter { it > 0 }
    val avg = if (tracked.isEmpty()) 0f else tracked.sum() / tracked.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(110.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("${fmt(avg)} a day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.weight(1f).height(44.dp)) {
            if (values.size < 2) return@Canvas
            val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
            val step = size.width / (values.size - 1)
            fun y(v: Float) = size.height - 4f - (size.height - 8f) * (v / max)
            val path = Path().apply {
                moveTo(0f, y(values[0]))
                for (i in 1 until values.size) {
                    val x0 = (i - 1) * step; val x1 = i * step
                    val mid = (x0 + x1) / 2
                    cubicTo(mid, y(values[i - 1]), mid, y(values[i]), x1, y(values[i]))
                }
            }
            val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(fill, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
            drawPath(path, color, style = Stroke(5f, cap = StrokeCap.Round))
            drawCircle(color, 7f, Offset(size.width, y(values.last())))
        }
    }
}

// ================================================================ timeline drawer

private enum class Filter(val label: String, val kinds: List<String>?) {
    All("All", null),
    Apps("Apps", listOf(Kind.OPEN)),
    Sites("Websites", listOf(Kind.VISIT)),
    Blocks("Blocks", listOf(Kind.BLOCK, Kind.DNS_BLOCK)),
    Scroll("Scrolling", listOf(Kind.SCROLL, Kind.NUDGE, Kind.REELS)),
    Urge("Urges", listOf(Kind.URGE)),
}

private data class TimelineItem(val key: String, val event: Event, val time: String)

private fun buildItems(events: List<Event>): List<TimelineItem> {
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val seen = HashMap<String, Int>()
    return events.map { e ->
        val base = "${e.ts}_${e.kind}_${e.title}"
        val n = seen.merge(base, 1, Int::plus)!!
        TimelineItem(if (n == 1) base else "${base}_$n", e, timeFmt.format(Date(e.ts)))
    }
}

@Composable
private fun TimelineSheet(date: LocalDate, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf(Filter.All) }
    val items by produceState<List<TimelineItem>?>(null, filter) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            buildItems(GuardApp.db.eventsOn(date.toString(), filter.kinds))
        }
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Everything Nindō saw, newest first", Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f ->
                FilterChip(filter == f, { filter = f }, { Text(f.label) },
                    leadingIcon = if (filter == f) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null)
            }
        }
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyHint(Icons.Rounded.Timeline, "Nothing here for this day.")
            else -> LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.85f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.key }, contentType = { "event" }) { r -> TimelineRow(r.event, r.time) }
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
    Kind.URGE -> Icons.Rounded.Shield to Brand.Teal
    else -> Icons.Rounded.Circle to Color.Gray
}

@Composable
private fun TimelineRow(e: Event, time: String) {
    val (icon, color) = style(e.kind)
    val line = MaterialTheme.colorScheme.surfaceVariant
    // Timeline line drawn behind the row: no intrinsic measuring or extra Canvas per row.
    Row(Modifier.fillMaxWidth().drawBehind {
        val x = (16 + 46 + 17).dp.toPx()
        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
    }.padding(horizontal = 16.dp)) {
        Text(time, Modifier.width(46.dp).padding(top = 12.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.width(34.dp).padding(top = 6.dp), contentAlignment = Alignment.TopCenter) { IconBadge(icon, color, 30.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            val what = when (e.kind) {
                Kind.OPEN -> "Opened ${e.title}"
                Kind.VISIT -> e.title
                Kind.BLOCK -> "Blocked ${e.title}"
                Kind.DNS_BLOCK -> "Network block: ${e.title}"
                Kind.SCROLL -> "Scrolled ${e.title} for ${fmtDuration(e.durMs)}"
                Kind.NUDGE -> "Scroll nudge in ${e.title}"
                Kind.URGE -> if (e.detail == "resisted") "Resisted: ${e.title.lowercase()}" else "Continued: ${e.title.lowercase()}"
                else -> e.title
            }
            Text(what, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2)
            e.detail?.takeIf { it.isNotBlank() && it != e.title }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}


// ================================================================ habits

@Composable
private fun HabitsDayCard(date: LocalDate, today: LocalDate, list: List<HabitDay>, modifier: Modifier) {
    val title = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))
    }
    SectionCard(title, modifier, action = {
        if (list.isNotEmpty()) Text("${list.count { it.done }} of ${list.size} done", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }) {
        if (list.isEmpty()) EmptyHint(Icons.Rounded.EventBusy, "No habits were due on this day.")
        list.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(d.habit.category.icon, d.habit.color, 38.dp, filled = d.done)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.habit.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(when {
                        d.done -> "Done"
                        d.pending -> "On track"
                        d.failed -> if (d.habit.kind == HabitKind.AVOID || d.habit.isAuto) "Slipped" else "Missed"
                        date == today -> "Not done yet"
                        else -> "Missed"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    d.done -> Icon(Icons.Rounded.CheckCircle, "Done", tint = Brand.Green)
                    d.pending || date == today -> Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Icon(Icons.Rounded.Cancel, "Missed", tint = Brand.Rose)
                }
            }
        }
    }
}

@Composable
private fun HabitRatesCard(rates: List<Triple<Habit, Int, Int>>, hs: HabitSnapshot, modifier: Modifier) {
    if (rates.isEmpty()) return
    val done = rates.sumOf { it.second }
    val due = rates.sumOf { it.third }
    val overall = if (due == 0) 0f else done.toFloat() / due
    SectionCard("Last 30 days", modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(overall, 84.dp, 8.dp, Brand.Green, Brand.Green.copy(alpha = 0.15f)) {
                Text("${(overall * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("$done of $due habit days done", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("Rank ${Ranks.of(hs.xp).name} · ${hs.xp} XP", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hs.xpWeek.size == 7) Text("${hs.xpWeek.sum()} XP in the last 7 days", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        rates.sortedByDescending { if (it.third == 0) 0f else it.second.toFloat() / it.third }.forEach { (h, d, n) ->
            val f = if (n == 0) 0f else d.toFloat() / n
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(h.category.icon, h.color, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text("$d of $n days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val st = hs.streaks[h.id]?.current ?: 0
                if (st > 0) { StreakBadge(st); Spacer(Modifier.width(10.dp)) }
                Ring(f, 34.dp, 3.dp, h.color, h.color.copy(alpha = 0.15f)) {
                    Text("${(f * 100).toInt()}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = h.color)
                }
            }
        }
    }
}
