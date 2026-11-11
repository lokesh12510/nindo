@file:OptIn(ExperimentalMaterial3Api::class)

package app.nindo

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ============================================================ loading & writes

/** Bumped on every habit write, so habit screens reload at once instead of on the next poll. */
object HabitBus { val rev = MutableStateFlow(0) }

private val habitIo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs a habit database write off the main thread. */
fun habitWrite(block: HabitDb.() -> Unit) { habitIo.launch { runCatching { GuardApp.habits.block() } } }

@Volatile private var lastSnapshot = HabitSnapshot()

/** The habit snapshot: reloads after every habit write, and every 15 s for screen-time-based habits. */
@Composable
fun rememberHabits(): State<HabitSnapshot> {
    val rev by HabitBus.rev.collectAsState()
    return produceState(lastSnapshot, rev) {
        while (true) {
            val v = withContext(Dispatchers.IO) { runCatching { HabitEngine.load() }.getOrNull() }
            if (v != null) { lastSnapshot = v; value = v }
            delay(15_000)
        }
    }
}

// =========================================================== small pieces

@Composable
fun StreakBadge(n: Int, tint: Color = Brand.Amber) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.LocalFireDepartment, null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(2.dp))
        Text("$n", style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Bold)
    }
}

/** Rank chip for page titles. */
@Composable
fun RankChip(xp: Int, onClick: () -> Unit) {
    val r = Ranks.of(xp)
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(r.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Lv ${Ranks.level(xp)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

private fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

private fun subtitle(d: HabitDay, liveMs: Long): String {
    val h = d.habit
    return when {
        h.auto == AutoSource.SCREEN_GOAL -> "${fmtDuration(d.used)} of ${fmtDuration(d.limit)}" + if (d.failed) " · over" else ""
        h.auto == AutoSource.REEL_CAP -> "${d.used} of ${d.limit} reels" + if (d.failed) " · over" else ""
        h.kind == HabitKind.AVOID -> if (d.failed) "Slipped today" else "Holding strong"
        h.kind == HabitKind.CHECK -> if (d.done) "Done today" else h.cfg.description.ifBlank { "Not done yet" }
        h.kind == HabitKind.COUNT -> "${d.value} of ${h.target} ${h.unit.ifBlank { "times" }}"
        h.kind == HabitKind.CHECKLIST -> "${d.value} of ${h.goal} items"
        h.isCycle -> "${d.value} done" + (if (d.missed > 0) " · ${d.missed} missed" else "") +
            if (d.nextMin >= 0) " · next ${fmtTime(d.nextMin)}" else " · none left today"
        d.timerStart > 0 -> "Timer running · ${mmss(liveMs)}"
        else -> "${d.value} of ${h.target} min"
    }
}

// ============================================================== habit rows

/** The row's status at the end, kept as small as possible: a check when done, or a short count. Not a button. */
@Composable
private fun RowStatus(d: HabitDay, liveMs: Long) {
    val h = d.habit
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    when {
        d.done -> Icon(Icons.Rounded.Check, "Done", tint = h.color, modifier = Modifier.size(20.dp))
        h.isAuto -> Text("${(d.progress * 100).roundToInt()}%", style = style, color = if (d.failed) Brand.Rose else muted)
        h.kind == HabitKind.CHECK || h.kind == HabitKind.AVOID -> {}
        h.kind == HabitKind.TIMER && !h.isCycle ->
            Text(if (d.timerStart > 0) mmss(liveMs) else "${d.value}/${h.target}m", style = style, color = if (d.timerStart > 0) h.color else muted)
        else -> Text("${d.value}/${h.goal}", style = style, color = muted)
    }
}

/** Live milliseconds for a running timer, updating each second. */
@Composable
private fun rememberLiveMs(timerStart: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timerStart) { while (timerStart > 0) { now = System.currentTimeMillis(); delay(1000) } }
    return if (timerStart > 0) now - timerStart else 0L
}

@Composable
fun HabitRow(d: HabitDay, streak: HabitStreak?, onClick: () -> Unit) {
    val h = d.habit
    val liveMs = rememberLiveMs(d.timerStart)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconTile(h.category.icon, h.color, 44.dp, filled = d.done)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if ((streak?.current ?: 0) > 0) { Spacer(Modifier.width(8.dp)); StreakBadge(streak!!.current) }
            }
            Text(subtitle(d, liveMs), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (d.failed) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        RowStatus(d, liveMs)
    }
}

/**
 * Hosts the habit drawer and the editor for a screen. Returns a function that opens the drawer
 * for a habit id; the drawer's Edit opens the editor.
 */
@Composable
fun habitSheets(s: HabitSnapshot): (String) -> Unit {
    var openId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Habit?>(null) }
    openId?.let { id -> HabitDetailSheet(id, s, onDismiss = { openId = null }, onEdit = { h -> openId = null; editing = h }) }
    editing?.let { h -> HabitEditorSheet(h) { editing = null } }
    return { openId = it }
}

/** Today's habits in one panel. Tap a habit for its drawer. */
@Composable
fun HabitList(s: HabitSnapshot, modifier: Modifier = Modifier, onAdd: () -> Unit) {
    val open = habitSheets(s)
    Panel(modifier, padding = 12.dp) {
        if (s.today.isEmpty()) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.AddTask, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text("No habits for today yet.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onAdd) { Text("Add") }
            }
        }
        s.today.forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            HabitRow(d, s.streaks[d.habit.id]) { open(d.habit.id) }
        }
    }
}

// ============================================================ habit drawer

/** Swipe the thumb to the end to complete. */
@Composable
fun SwipeToComplete(label: String, color: Color, onComplete: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var x by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))) {
        val thumb = 54.dp
        val maxPx = with(density) { (maxWidth - thumb - 8.dp).toPx() }.coerceAtLeast(1f)
        val frac = (x / maxPx).coerceIn(0f, 1f)
        Box(Modifier.fillMaxHeight().width(with(density) { x.toDp() } + thumb + 8.dp).clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.22f)))
        Text(label, Modifier.align(Alignment.Center).graphicsLayer { alpha = 1f - frac }, color = color, fontWeight = FontWeight.SemiBold)
        Box(Modifier.padding(4.dp).offset { IntOffset(x.roundToInt(), 0) }.size(thumb).clip(CircleShape).background(color)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> x = (x + delta).coerceIn(0f, maxPx) },
                onDragStopped = {
                    if (x > maxPx * 0.8f) {
                        animate(x, maxPx) { v, _ -> x = v }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onComplete()
                        delay(400); x = 0f
                    } else animate(x, 0f, animationSpec = spring(dampingRatio = 0.7f)) { v, _ -> x = v }
                },
            ), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White)
        }
    }
}

/** Avoid habits: hold for over a second to admit a slip, so it can't happen by accident. Tap to undo. */
@Composable
private fun HoldShield(d: HabitDay, size: Dp) {
    val h = d.habit
    val accent = h.color
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val hold = remember { Animatable(0f) }
    val slipped = d.failed
    LaunchedEffect(slipped) { if (!slipped) hold.snapTo(0f) }
    Ring(if (slipped) 1f else hold.value, size, 6.dp, Brand.Rose, (if (slipped) Brand.Rose else accent).copy(alpha = 0.16f),
        Modifier.clip(CircleShape).pointerInput(h.id, slipped) {
            detectTapGestures(onPress = {
                if (slipped) {
                    if (tryAwaitRelease()) habitWrite { setValue(today(), h.id, 0) }
                } else {
                    val job = scope.launch {
                        hold.animateTo(1f, tween(1200, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        habitWrite { setValue(today(), h.id, 1) }
                    }
                    tryAwaitRelease()
                    if (hold.value < 1f) { job.cancel(); scope.launch { hold.animateTo(0f, tween(250)) } }
                }
            })
        }, animate = false) {
        Icon(if (slipped) Icons.Rounded.Replay else Icons.Rounded.Shield, if (slipped) "Undo slip" else "Hold if you slipped",
            tint = if (slipped) Brand.Rose else accent, modifier = Modifier.size(size * 0.4f))
    }
}

private data class DrawerData(val marks: Map<String, Mark> = emptyMap(), val done30: Int = 0, val due30: Int = 0, val total: Int = 0,
                              val cycles30: Pair<Int, Int> = 0 to 0)

private fun freqText(h: Habit): String = when (h.cfg.freq) {
    Freq.DAILY -> "Every day"
    Freq.DAYS -> scheduleText(h.days)
    Freq.PER_PERIOD -> "${h.cfg.perN}× a ${h.cfg.period.label}"
    Freq.INTERVAL -> if (h.cfg.interval == 2) "Alternate days" else "Every ${h.cfg.interval} days"
}

@Composable
private fun DrawerStat(value: String, label: String, icon: ImageVector, tint: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A month of one habit: done days filled, missed days tinted red, today outlined, upcoming due days dotted. */
@Composable
private fun HabitCalendar(h: Habit, month: java.time.YearMonth, marks: Map<String, Mark>, onMonth: (java.time.YearMonth) -> Unit) {
    val accent = h.color
    val t = today()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " " + month.year,
            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        IconButton({ onMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
        IconButton({ onMonth(month.plusMonths(1)) }, enabled = month < java.time.YearMonth.now()) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month")
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Days.LETTERS.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    val lead = month.atDay(1).dayOfWeek.value - 1
    val cells = lead + month.lengthOfMonth()
    for (week in 0 until (cells + 6) / 7) {
        Row(Modifier.fillMaxWidth()) {
            for (col in 0 until 7) {
                val n = week * 7 + col - lead + 1
                Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                    if (n in 1..month.lengthOfMonth()) {
                        val day = month.atDay(n).toString()
                        val m = marks[day]
                        val isToday = day == t
                        val bg = when (m) { Mark.DONE -> accent; Mark.MISSED -> Brand.Rose.copy(alpha = 0.16f); else -> Color.Transparent }
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(bg)
                            .then(if (isToday && m != Mark.DONE) Modifier.border(2.dp, accent, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center) {
                            Text("$n", fontSize = 12.sp, fontWeight = if (isToday || m == Mark.DONE) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    m == Mark.DONE -> Color.White
                                    m == Mark.MISSED -> Brand.Rose
                                    day > t -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    m == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    else -> MaterialTheme.colorScheme.onSurface
                                })
                            if (day > t && h.scheduledOn(day)) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp)
                                .clip(CircleShape).background(accent.copy(alpha = 0.6f)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HabitDetailSheet(id: String, s: HabitSnapshot, onDismiss: () -> Unit, onEdit: (Habit) -> Unit) {
    val d = s.today.firstOrNull { it.habit.id == id }
    val h = d?.habit ?: s.notToday.firstOrNull { it.id == id }
    if (h == null) { LaunchedEffect(id) { onDismiss() }; return } // deleted
    val accent = h.color
    val streak = s.streaks[id] ?: HabitStreak()
    var month by remember { mutableStateOf(java.time.YearMonth.now()) }
    val rev by HabitBus.rev.collectAsState()
    val data by produceState(DrawerData(), month, rev) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val t = today()
                val m = HabitEngine.marks(h, month.atDay(1).toString(), month.atEndOfMonth().toString())
                val r = HabitEngine.rates(30).firstOrNull { it.first.id == id }
                val all = HabitEngine.marks(h, Days.shift(t, -365), t).values.count { it == Mark.DONE }
                DrawerData(m, r?.second ?: 0, r?.third ?: 0, all,
                    if (h.isCycle) GuardApp.habits.cycleTotals(h.id, Days.shift(t, -29), t) else 0 to 0)
            }.getOrDefault(value)
        }
    }
    var notes by remember(id) { mutableStateOf(h.cfg.notes) }
    val liveMs = rememberLiveMs(d?.timerStart ?: 0L)
    val haptic = LocalHapticFeedback.current
    fun saveNotes() { if (notes != h.cfg.notes) { val n = notes; habitWrite { upsert(h.copy(cfg = h.cfg.copy(notes = n))) } } }

    // Opens halfway (header and today's actions); drag up for stats, calendar and notes. Dragging down steps back the same way.
    ModalBottomSheet(onDismissRequest = { saveNotes(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(h.category.icon, accent, 54.dp, filled = d?.done == true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${h.category.label} · ${freqText(h)} · ${kindText(h)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton({ saveNotes(); onEdit(h) }) { Icon(Icons.Rounded.Edit, "Edit habit") }
            }
            if (h.cfg.description.isNotBlank()) Text(h.cfg.description, style = MaterialTheme.typography.bodyMedium)

            // Today
            if (d != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(accent.copy(alpha = 0.08f)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (h.kind == HabitKind.AVOID && !h.isAuto) HoldShield(d, 72.dp)
                        else {
                            val shown = if (h.kind == HabitKind.TIMER) d.value + (liveMs / 60_000).toInt() else d.value
                            val p = when { h.isAuto -> d.progress; h.kind == HabitKind.CHECK -> if (d.done) 1f else 0f; else -> shown.toFloat() / h.goal }
                            Ring(p, 72.dp, 7.dp, if (d.failed) Brand.Rose else accent, accent.copy(alpha = 0.16f), animate = d.timerStart == 0L) {
                                if (d.done) Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(30.dp))
                                else Text(when {
                                    h.isAuto -> "${(d.progress * 100).roundToInt()}%"
                                    h.kind == HabitKind.CHECK -> "0/1"
                                    else -> "$shown/${h.goal}"
                                }, fontWeight = FontWeight.Bold, color = accent, fontSize = 15.sp)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(subtitle(d, liveMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = if (d.failed) Brand.Rose else MaterialTheme.colorScheme.onSurface)
                            Text(when {
                                h.auto == AutoSource.SENSEI_PICK -> if (d.done) "Watched today. +${Xp.AUTO} XP" else "Ticks itself when you watch today's Sensei pick in Nindō."
                                h.isAuto -> "Nindō checks this one for you. It settles at midnight."
                                h.kind == HabitKind.AVOID -> if (d.failed) "Tap the shield to undo." else "Hold the shield if you slipped. It settles at midnight."
                                d.done -> "+${Xp.HABIT} XP earned"
                                else -> "+${Xp.HABIT} XP when done"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (h.kind == HabitKind.CHECKLIST) Column {
                        val mask = remember(rev) { GuardApp.habits.value(today(), h.id) }
                        h.cfg.items.forEachIndexed { i, item ->
                            val on = (mask and (1 shl i)) != 0
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { habitWrite { toggleItem(h.id, i) } }
                                .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(on, { habitWrite { toggleItem(h.id, i) } }, colors = CheckboxDefaults.colors(checkedColor = accent))
                                Text(item, style = MaterialTheme.typography.bodyLarge,
                                    color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }

                    if (h.isCycle) {
                        val now = rememberNow()
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
                        val nowSec = cal.get(java.util.Calendar.HOUR_OF_DAY) * 3600 + cal.get(java.util.Calendar.MINUTE) * 60 + cal.get(java.util.Calendar.SECOND)
                        val left = (d.cycles - d.value - d.missed).coerceAtLeast(0)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CycleChip("${d.value}", "done", Brand.Green, Modifier.weight(1f))
                            CycleChip("${d.missed}", "missed", Brand.Rose, Modifier.weight(1f))
                            CycleChip("$left", "left today", accent, Modifier.weight(1f))
                        }
                        Text(if (d.nextMin >= 0) "Next reminder in ${mmss((d.nextMin * 60L - nowSec) * 1000)} · at ${fmtTime(d.nextMin)}"
                            else "No more reminders today", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton({ habitWrite { answerCycle(h.id, false) } }, Modifier.weight(1f)) { Text("Skip") }
                            Button({ haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); habitWrite { answerCycle(h.id, true) } },
                                Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                                Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Done now")
                            }
                        }
                    } else if (!h.isAuto && h.kind != HabitKind.AVOID) {
                        when (h.kind) {
                            HabitKind.COUNT -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton({ habitWrite { add(today(), h.id, -1) } }, Modifier.weight(1f), enabled = d.value > 0) {
                                    Icon(Icons.Rounded.Remove, null); Spacer(Modifier.width(6.dp)); Text("1")
                                }
                                Button({ haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); habitWrite { add(today(), h.id, 1) } },
                                    Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("1 ${h.unit}".trim())
                                }
                            }
                            HabitKind.TIMER -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val running = d.timerStart > 0
                                Button({ habitWrite { if (timerStart(h.id) > 0) stopTimer(h.id) else startTimer(h.id) } }, Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                                    Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp))
                                    Text(if (running) "Stop · ${mmss(liveMs)}" else "Start timer")
                                }
                                OutlinedButton({ habitWrite { add(today(), h.id, 5) } }, Modifier.weight(1f)) { Text("+5 min") }
                            }
                            else -> {}
                        }
                        if (d.done) Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = accent)
                            Spacer(Modifier.width(8.dp))
                            Text("Completed today", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            TextButton({ habitWrite { setValue(today(), h.id, 0) } }) { Text("Undo") }
                        } else SwipeToComplete("Swipe to complete", accent) { habitWrite { complete(h) } }
                    }
                }
            } else {
                Text("Not due today.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerStat("${streak.current}", "current streak", Icons.Rounded.LocalFireDepartment, Brand.Amber, Modifier.weight(1f))
                DrawerStat("${streak.best}", "best streak", Icons.Rounded.EmojiEvents, Tone.B, Modifier.weight(1f))
                DrawerStat(if (data.due30 == 0) "–" else "${data.done30 * 100 / data.due30}%", "last 30 days", Icons.Rounded.DonutLarge,
                    Brand.Green, Modifier.weight(1f))
                DrawerStat("${data.total}", "times done", Icons.Rounded.TaskAlt, accent, Modifier.weight(1f))
            }
            if (h.isCycle) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawerStat("${data.cycles30.first}", "cycles done · 30 days", Icons.Rounded.CheckCircle, Brand.Green, Modifier.weight(1f))
                DrawerStat("${data.cycles30.second}", "cycles missed · 30 days", Icons.Rounded.Cancel, Brand.Rose, Modifier.weight(1f))
            }

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
                HabitCalendar(h, month, data.marks) { month = it }
            }

            OutlinedTextField(notes, { notes = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Notes") },
                placeholder = { Text("Why this habit matters, what helps, what to try next") }, minLines = 2)

            if (h.cfg.remind != Remind.NONE && !h.isCycle) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${h.cfg.remind.label} at ${fmtTime(h.cfg.remindMin)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CycleChip(value: String, label: String, tint: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ================================================================== missions

@Composable
fun MissionTile(m: Mission, modifier: Modifier = Modifier) {
    // "Stay under" missions show the budget left; the rest show progress.
    val budget = m.type == MissionType.SCREEN || m.type == MissionType.REELS
    val color = when { m.failed -> Brand.Rose; m.done -> Brand.Green; else -> MaterialTheme.colorScheme.primary }
    val shown = when { m.done -> 1f; m.failed -> 1f; budget -> 1f - m.progress; else -> m.progress }
    val status = when {
        m.done -> "+${Xp.MISSION} XP earned"
        m.failed -> "Missed today"
        m.pending -> "Holding · XP at midnight"
        else -> "${(m.progress * 100).roundToInt()}% · +${Xp.MISSION} XP"
    }
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) {
        Ring(shown, 46.dp, 4.dp, color, color.copy(alpha = 0.15f)) {
            Icon(if (m.done) Icons.Rounded.Check else m.type.icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(m.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2,
            overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (m.done || m.failed) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MissionsRow(s: HabitSnapshot, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        s.missions.forEach { MissionTile(it, Modifier.weight(1f)) }
    }
}

// ================================================================= Path tab

private fun scheduleText(days: Int): String = when (days) {
    Habit.ALL_DAYS -> "Every day"
    0b0011111 -> "Weekdays"
    0b1100000 -> "Weekends"
    else -> Days.LETTERS.indices.filter { (days and (1 shl it)) != 0 }.joinToString(" ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it] }
}

private fun kindText(h: Habit): String = when {
    h.isAuto -> "Tracked by Nindō"
    h.kind == HabitKind.CHECK -> "Once a day"
    h.kind == HabitKind.COUNT -> "${h.target} ${h.unit.ifBlank { "times" }} a day"
    h.isCycle -> "Every ${h.cfg.every} min" + if (h.cfg.window) " · ${fmtTime(h.cfg.winStart)}–${fmtTime(h.cfg.winEnd)}" else ""
    h.kind == HabitKind.TIMER -> "${h.target} min a day"
    h.kind == HabitKind.CHECKLIST -> "${h.goal} items"
    else -> "Avoid"
}

@Composable
private fun HeroStat(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.13f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
    }
}

/** The five ranks as a path, with the current one highlighted. */
@Composable
private fun RankLadder(xp: Int) {
    val cur = Ranks.of(xp).index
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Ranks.all.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.weight(1f).height(2.dp).background(Color.White.copy(alpha = if (i <= cur) 0.7f else 0.2f)))
            val reached = i <= cur
            Box(Modifier.size(if (i == cur) 34.dp else 26.dp).clip(CircleShape)
                .background(if (reached) Color.White else Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(r.icon, r.name, tint = if (reached) Tone.A else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(if (i == cur) 20.dp else 15.dp))
            }
        }
    }
}

@Composable
private fun RankHero(s: HabitSnapshot, modifier: Modifier) {
    val xp = s.xp
    val r = Ranks.of(xp)
    val next = Ranks.next(xp)
    HeroCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(Ranks.progress(xp), 80.dp, 6.dp, Color.White, Color.White.copy(alpha = 0.2f)) {
                Icon(r.icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("RANK", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                Text(r.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Level ${Ranks.level(xp)} · $xp XP", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        RankLadder(xp)
        Spacer(Modifier.height(8.dp))
        Text(if (next != null) "${next.minXp - xp} XP to ${next.name}" else "Highest rank reached",
            color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroStat("+${s.xpToday} XP", if (s.xpPendingToday > 0) "today · +${s.xpPendingToday} pending" else "today", Modifier.weight(1f))
            HeroStat("${s.streaks.values.maxOfOrNull { it.current } ?: 0} days", "best streak", Modifier.weight(1f))
            HeroStat("${(s.weekRate * 100).roundToInt()}%", "done this week", Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeekDots(week: List<Mark>, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        week.forEach { m ->
            val c = when (m) {
                Mark.DONE -> accent
                Mark.MISSED -> Brand.Rose.copy(alpha = 0.5f)
                Mark.PENDING -> accent.copy(alpha = 0.35f)
                Mark.OFF -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(50)).background(c))
        }
    }
}

@Composable
private fun HabitCard(h: Habit, st: HabitStreak, week: List<Mark>, modifier: Modifier, onEdit: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onEdit).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(h.category.icon, h.color, 38.dp)
            Spacer(Modifier.weight(1f))
            if (st.current > 0) StreakBadge(st.current)
        }
        Spacer(Modifier.height(12.dp))
        Text(h.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(kindText(h), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        WeekDots(week, h.color)
        Spacer(Modifier.height(6.dp))
        Text(freqText(h) + if (st.best > 0) " · best ${st.best}" else "", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun PathScreen() {
    val s by rememberHabits()
    var adding by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<String?>(null) } // null = all, "today", or a category name
    if (adding) HabitEditorSheet(null) { adding = false }
    val open = habitSheets(s)
    val all = s.today.map { it.habit } + s.notToday
    val todayIds = s.today.map { it.habit.id }.toSet()
    val shown = when (filter) {
        null -> all
        "today" -> all.filter { it.id in todayIds }
        else -> all.filter { it.category.name == filter }
    }
    val cats = all.map { it.category }.distinct()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Path", "The way of the ninja") {
            FilledTonalIconButton({ adding = true }) { Icon(Icons.Rounded.Add, "Add habit") }
        }
        RankHero(s, Modifier.padding(horizontal = 16.dp).enter(1))

        SectionHeader("Daily missions", Modifier.padding(top = 6.dp))
        MissionsRow(s, Modifier.enter(2))

        SectionHeader("Your habits", Modifier.padding(top = 6.dp), action = "Add") { adding = true }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { filter = null }, { Text("All") })
            FilterChip(filter == "today", { filter = "today" }, { Text("Today") })
            cats.forEach { c ->
                FilterChip(filter == c.name, { filter = if (filter == c.name) null else c.name }, { Text(c.label) },
                    leadingIcon = { Icon(c.icon, null, Modifier.size(16.dp), tint = c.color) })
            }
        }
        if (shown.isEmpty()) Panel(Modifier.padding(horizontal = 16.dp)) {
            EmptyHint(Icons.Rounded.AddTask, "Add your first habit. Small and daily beats big and rare.")
        }
        Column(Modifier.padding(horizontal = 16.dp).enter(3), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            shown.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { h -> HabitCard(h, s.streaks[h.id] ?: HabitStreak(), s.week[h.id].orEmpty(), Modifier.weight(1f)) { open(h.id) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        SectionHeader("XP this week", Modifier.padding(top = 6.dp))
        Panel(Modifier.padding(horizontal = 16.dp).enter(4)) {
            val t = today()
            val labels = Days.range(Days.shift(t, -6), t).map { Days.LETTERS[Days.weekday(it)] }
            if (s.xpWeek.size == 7) BarChart(s.xpWeek.map { it.toFloat() }, labels, highlight = 6, height = 100.dp, color = Tone.B)
        }
    }
}

