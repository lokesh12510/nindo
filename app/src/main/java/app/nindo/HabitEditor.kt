@file:OptIn(ExperimentalMaterial3Api::class)

package app.nindo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class Template(val name: String, val category: HabitCategory, val kind: HabitKind, val target: Int = 1, val unit: String = "",
                            val items: List<String> = emptyList(), val every: Int = 0, val winStart: Int = 9 * 60, val winEnd: Int = 21 * 60)

private val TEMPLATES = listOf(
    Template("Drink water", HabitCategory.WATER, HabitKind.TIMER, every = 60),
    Template("Stand up & stretch", HabitCategory.FITNESS, HabitKind.TIMER, every = 45, winEnd = 18 * 60),
    Template("Eye break", HabitCategory.HEALTH, HabitKind.TIMER, every = 20, winEnd = 18 * 60),
    Template("Read", HabitCategory.LEARN, HabitKind.CHECK),
    Template("Workout", HabitCategory.FITNESS, HabitKind.CHECK),
    Template("Morning routine", HabitCategory.HEALTH, HabitKind.CHECKLIST, items = listOf("Make the bed", "Drink water", "Stretch 5 min")),
    Template("Meditate", HabitCategory.MIND, HabitKind.CHECK),
    Template("Learn Python", HabitCategory.LEARN, HabitKind.CHECK),
    Template("Glasses of water", HabitCategory.WATER, HabitKind.COUNT, 8, "glasses"),
    Template("Wake up early", HabitCategory.SLEEP, HabitKind.CHECK),
    Template("No junk food", HabitCategory.FOOD, HabitKind.CHECK),
)

private val HabitKind.icon: ImageVector get() = when (this) {
    HabitKind.CHECK -> Icons.Rounded.CheckCircle
    HabitKind.COUNT -> Icons.Rounded.DonutLarge
    HabitKind.TIMER -> Icons.Rounded.Timer
    HabitKind.CHECKLIST -> Icons.Rounded.Checklist
    HabitKind.AVOID -> Icons.Rounded.Shield
}

private fun kindHint(k: HabitKind) = when (k) {
    HabitKind.CHECK -> "Done once a day."
    HabitKind.COUNT -> "Add one each time until you reach the target."
    HabitKind.TIMER -> "Reminds you every few minutes during your hours, like drinking water or an eye break. Each reminder is done or missed."
    HabitKind.CHECKLIST -> "A short list of steps. Done when every item is ticked."
    HabitKind.AVOID -> ""
}

private val Remind.icon: ImageVector get() = when (this) {
    Remind.NONE -> Icons.Rounded.NotificationsOff
    Remind.NOTIFY -> Icons.Rounded.Notifications
    Remind.VOICE -> Icons.Rounded.RecordVoiceOver
    Remind.POPUP -> Icons.Rounded.Fullscreen
}

private enum class Ends(val label: String) { NEVER("Never"), ON_DATE("On a date"), AFTER("After X days") }

private fun toMillis(day: String) = LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun fromMillis(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
private fun pretty(day: String) = LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
}

/** Pill-shaped choice used for the option rows. */
@Composable
private fun Choice(label: String, selected: Boolean, accent: Color, icon: ImageVector? = null, onClick: () -> Unit) {
    FilterChip(selected, onClick, { Text(label) },
        leadingIcon = if (icon != null) { { Icon(icon, null, Modifier.size(18.dp)) } } else null,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = 0.16f), selectedLabelColor = accent,
            selectedLeadingIconColor = accent))
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        FilledTonalIconButton({ onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Icon(Icons.Rounded.Remove, "Less") }
        Text("$value", Modifier.widthIn(min = 44.dp).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        FilledTonalIconButton({ onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Icon(Icons.Rounded.Add, "More") }
    }
}

/** A tappable row showing a value, e.g. a date or a time. */
@Composable
private fun PickerRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun HabitEditorSheet(existing: Habit?, onDismiss: () -> Unit) {
    val c0 = existing?.cfg ?: HabitCfg()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(c0.description) }
    var category by remember { mutableStateOf(existing?.category ?: HabitCategory.WORK) }
    var kind by remember { mutableStateOf(existing?.kind ?: HabitKind.CHECK) }
    var target by remember { mutableIntStateOf(existing?.target ?: 1) }
    var unit by remember { mutableStateOf(existing?.unit ?: "") }
    val items = remember { mutableStateListOf<String>().apply { addAll(c0.items) } }
    var newItem by remember { mutableStateOf("") }
    var freq by remember { mutableStateOf(c0.freq) }
    var days by remember { mutableIntStateOf(existing?.days ?: Habit.ALL_DAYS) }
    var perN by remember { mutableIntStateOf(c0.perN) }
    var period by remember { mutableStateOf(c0.period) }
    var interval by remember { mutableIntStateOf(c0.interval) }
    var start by remember { mutableStateOf(c0.start ?: existing?.created ?: today()) }
    var ends by remember { mutableStateOf(if (c0.end == null) Ends.NEVER else Ends.ON_DATE) }
    var end by remember { mutableStateOf(c0.end ?: Days.shift(today(), 29)) }
    var forDays by remember { mutableIntStateOf(30) }
    var remind by remember { mutableStateOf(c0.remind) }
    var remindMin by remember { mutableIntStateOf(c0.remindMin) }
    var every by remember { mutableIntStateOf(if (c0.every > 0) c0.every else 60) }
    var window by remember { mutableStateOf(c0.window) }
    var winStart by remember { mutableIntStateOf(c0.winStart) }
    var winEnd by remember { mutableIntStateOf(c0.winEnd) }
    var pickWin by remember { mutableIntStateOf(0) } // 1 = start, 2 = end
    var askDiscard by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val auto = existing?.isAuto == true
    val accent = category.color
    val valid = name.isNotBlank() && (kind != HabitKind.CHECKLIST || items.isNotEmpty())
    val cycles = if (kind == HabitKind.TIMER) {
        val st = if (window) winStart else 0; val en = if (window) winEnd else 1439
        ((en - st) / every.coerceAtLeast(1)).coerceAtLeast(0)
    } else 0
    // Anything changed? Then swiping the sheet away asks first instead of throwing the form away.
    val signature = listOf(name, description, category, kind, target, unit, items.toList(), freq, days, perN, period, interval, start, ends, end,
        forDays, remind, remindMin, every, window, winStart, winEnd).joinToString("|")
    val initial = remember { signature }
    val dirty by rememberUpdatedState(signature != initial)
    var discarding by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { v ->
        if (v == SheetValue.Hidden && dirty && !discarding) { askDiscard = true; false } else true
    })
    fun close() { if (dirty) askDiscard = true else onDismiss() }

    ModalBottomSheet(onDismissRequest = { close() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val side = Modifier.padding(horizontal = 20.dp)
            Text(if (existing == null) "New habit" else "Edit habit", side, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            if (existing == null) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TEMPLATES.forEach { t ->
                    AssistChip(onClick = {
                        name = t.name; category = t.category; kind = t.kind; target = t.target; unit = t.unit
                        items.clear(); items.addAll(t.items)
                        if (t.every > 0) {
                            every = t.every; window = true; winStart = t.winStart; winEnd = t.winEnd
                            target = ((winEnd - winStart) / every).coerceAtLeast(1)
                            if (remind == Remind.NONE) remind = Remind.NOTIFY
                        }
                    }, label = { Text(t.name) }, leadingIcon = { Icon(t.category.icon, null, Modifier.size(18.dp), tint = t.category.color) })
                }
            }

            Column(side, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(120) }, label = { Text("Description (optional)") },
                    placeholder = { Text("e.g. Before breakfast, at least 2 pages") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            }

            // ---- category: two rows, scrolling sideways
            Column(side) { Label("Category") }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HabitCategory.entries.chunked(2).forEach { pair ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        pair.forEach { c ->
                            val sel = c == category
                            Column(Modifier.width(78.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (sel) c.color.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable { category = c }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                IconTile(c.icon, c.color, 42.dp, filled = sel)
                                Spacer(Modifier.height(4.dp))
                                Text(c.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (sel) c.color else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }

            if (!auto) {
                // ---- type
                Column(side) { Label("How you track it") }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HabitKind.entries.filter { it != HabitKind.AVOID }.forEach { k ->
                        val sel = k == kind
                        Column(Modifier.width(92.dp).clip(RoundedCornerShape(18.dp))
                            .border(if (sel) 2.dp else 1.dp, if (sel) accent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                            .background(if (sel) accent.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable {
                                kind = k
                                target = when (k) {
                                    HabitKind.TIMER -> ((if (window) winEnd - winStart else 1439) / every).coerceAtLeast(1)
                                    HabitKind.COUNT -> if (target < 2 || target > 100) 3 else target
                                    else -> 1
                                }
                                if (k == HabitKind.TIMER && remind == Remind.NONE) remind = Remind.NOTIFY
                            }.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(k.icon, null, tint = if (sel) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Text(k.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
                Column(side, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(kindHint(kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (kind) {
                        HabitKind.COUNT -> {
                            Stepper("Times a day", target, 2..100) { target = it }
                            OutlinedTextField(unit, { unit = it.take(16) }, label = { Text("Unit, e.g. glasses") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                        }
                        HabitKind.TIMER -> {
                            Text("Remind every", style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 20, 30, 45, 60, 90, 120).forEach { m ->
                                    Choice(if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min", every == m, accent) {
                                        every = m; target = target.coerceAtMost(cycles.coerceAtLeast(1))
                                    }
                                }
                            }
                            Stepper("Minutes between reminders", every, 5..240, step = 5) { every = it }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Only during my hours", style = MaterialTheme.typography.bodyMedium)
                                    Text(if (window) "Like work hours. No reminders outside them." else "All day, around the clock.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(window, { window = it })
                            }
                            if (window) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.weight(1f)) { PickerRow(Icons.Rounded.WbSunny, "From", fmtTime(winStart)) { pickWin = 1 } }
                                Box(Modifier.weight(1f)) { PickerRow(Icons.Rounded.Bedtime, "Until", fmtTime(winEnd)) { pickWin = 2 } }
                            }
                            Text("$cycles reminders a day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (cycles > 0) Stepper("Day counts as done after", target.coerceIn(1, cycles), 1..cycles) { target = it }
                        }
                        HabitKind.CHECKLIST -> {
                            items.forEachIndexed { i, item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.CheckBoxOutlineBlank, null, tint = accent, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(item, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                    IconButton({ items.removeAt(i) }) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp)) }
                                }
                            }
                            if (items.size < 30) Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(newItem, { newItem = it.take(50) }, Modifier.weight(1f), placeholder = { Text("Add an item") }, singleLine = true)
                                IconButton({ if (newItem.isNotBlank()) { items.add(newItem.trim()); newItem = "" } }) {
                                    Icon(Icons.Rounded.AddCircle, "Add item", tint = accent)
                                }
                            }
                        }
                        else -> {}
                    }
                }

                // ---- frequency
                Column(side, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label("Repeat")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("Every day", freq == Freq.DAILY, accent) { freq = Freq.DAILY }
                        Choice("Specific days", freq == Freq.DAYS, accent) { freq = Freq.DAYS }
                        if (kind != HabitKind.AVOID) Choice("Days per period", freq == Freq.PER_PERIOD, accent) { freq = Freq.PER_PERIOD }
                        Choice("Alternate days", freq == Freq.INTERVAL && interval == 2, accent) { freq = Freq.INTERVAL; interval = 2 }
                        Choice("Every N days", freq == Freq.INTERVAL && interval != 2, accent) { freq = Freq.INTERVAL; if (interval == 2) interval = 3 }
                    }
                    when (freq) {
                        Freq.DAYS -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Days.LETTERS.forEachIndexed { i, l ->
                                val on = (days and (1 shl i)) != 0
                                Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) accent else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { val nd = days xor (1 shl i); if (nd != 0) days = nd }, contentAlignment = Alignment.Center) {
                                    Text(l, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Freq.PER_PERIOD -> {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                Period.entries.forEachIndexed { i, p ->
                                    SegmentedButton(period == p, { period = p; perN = perN.coerceAtMost(if (p == Period.WEEK) 7 else 31) },
                                        SegmentedButtonDefaults.itemShape(i, Period.entries.size)) { Text("Per ${p.label}") }
                                }
                            }
                            Stepper("Days per ${period.label}", perN, 1..(if (period == Period.WEEK) 7 else 31)) { perN = it }
                        }
                        Freq.INTERVAL -> if (interval != 2) Stepper("Every … days", interval, 3..60) { interval = it }
                        else -> {}
                    }
                }
            }

            // ---- dates
            Column(side, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Duration")
                PickerRow(Icons.Rounded.Event, "Starts", if (start == today()) "Today" else pretty(start)) { pickStart = true }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ends", Modifier.align(Alignment.CenterVertically).padding(end = 4.dp), style = MaterialTheme.typography.bodyMedium)
                    Ends.entries.forEach { e -> Choice(e.label, ends == e, accent) { ends = e } }
                }
                when (ends) {
                    Ends.ON_DATE -> PickerRow(Icons.Rounded.EventBusy, "Ends on", pretty(end)) { pickEnd = true }
                    Ends.AFTER -> {
                        Stepper("Repeat for (days)", forDays, 1..365) { forDays = it }
                        Text("Ends on ${pretty(Days.shift(start, forDays - 1))}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {}
                }
            }

            // ---- reminder
            Column(side, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Reminder")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Remind.entries.filter { kind != HabitKind.TIMER || it != Remind.NONE }
                        .forEach { r -> Choice(r.label, remind == r, accent, r.icon) { remind = r } }
                }
                if (kind == HabitKind.TIMER) Text("Shown at each reminder, with Done and Skip. Unanswered ones count as missed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (remind != Remind.NONE) {
                    PickerRow(Icons.Rounded.Schedule, "Remind at", fmtTime(remindMin)) { pickTime = true }
                    Text(when (remind) {
                        Remind.VOICE -> "Your phone says the reminder out loud, and a notification is shown too."
                        Remind.POPUP -> "A card pops up over whatever you're using, with Done and Snooze. Needs the watcher on; otherwise you get a notification."
                        else -> "A notification with Done and Snooze buttons."
                    } + " Only when the habit is due and not done yet.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(side, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = {
                    val k = if (auto) existing!!.kind else kind
                    val endDay = when (ends) {
                        Ends.NEVER -> null
                        Ends.ON_DATE -> maxOf(end, start)
                        Ends.AFTER -> Days.shift(start, forDays - 1)
                    }
                    val cfg = (existing?.cfg ?: HabitCfg()).copy(
                        description = description.trim(), freq = if (auto) Freq.DAILY else freq, perN = perN, period = period,
                        interval = interval, start = start, end = endDay, remind = remind, remindMin = remindMin,
                        items = if (k == HabitKind.CHECKLIST) items.toList() else emptyList(),
                        every = if (k == HabitKind.TIMER) every else 0, window = window, winStart = winStart, winEnd = winEnd,
                    )
                    val h = (existing ?: Habit(name = name.trim())).copy(
                        name = name.trim(), category = category, kind = k,
                        target = when (k) {
                            HabitKind.COUNT -> target
                            HabitKind.TIMER -> target.coerceIn(1, cycles.coerceAtLeast(1))
                            else -> 1
                        },
                        unit = if (k == HabitKind.COUNT) unit.trim() else "",
                        days = if (freq == Freq.DAYS) days else Habit.ALL_DAYS, cfg = cfg,
                    )
                    habitWrite { upsert(h) }
                    discarding = true
                    onDismiss()
                }, enabled = valid && (kind != HabitKind.TIMER || cycles > 0), modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                    Text(if (existing == null) "Add habit" else "Save", fontWeight = FontWeight.SemiBold)
                }
                if (existing != null) TextButton({ confirmDelete = true }, Modifier.fillMaxWidth()) {
                    Text("Delete habit", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (pickStart || pickEnd) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toMillis(if (pickStart) start else end))
        DatePickerDialog(
            onDismissRequest = { pickStart = false; pickEnd = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { ms -> if (pickStart) start = fromMillis(ms) else end = fromMillis(ms) }
                    pickStart = false; pickEnd = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickStart = false; pickEnd = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (pickTime) {
        val state = rememberTimePickerState(remindMin / 60, remindMin % 60, is24Hour = android.text.format.DateFormat.is24HourFormat(
            androidx.compose.ui.platform.LocalContext.current))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton({ remindMin = state.hour * 60 + state.minute; pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton({ pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }

    if (pickWin != 0) {
        val cur = if (pickWin == 1) winStart else winEnd
        val state = rememberTimePickerState(cur / 60, cur % 60, is24Hour = android.text.format.DateFormat.is24HourFormat(
            androidx.compose.ui.platform.LocalContext.current))
        AlertDialog(
            onDismissRequest = { pickWin = 0 },
            confirmButton = {
                TextButton({
                    val m = state.hour * 60 + state.minute
                    if (pickWin == 1) winStart = m.coerceAtMost(winEnd - 5) else winEnd = m.coerceAtLeast(winStart + 5)
                    target = target.coerceAtMost(cycles.coerceAtLeast(1))
                    pickWin = 0
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickWin = 0 }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }

    if (askDiscard) AlertDialog(
        onDismissRequest = { askDiscard = false },
        title = { Text("Discard changes?") },
        text = { Text("You've changed this habit. Leaving now loses those changes.") },
        confirmButton = { TextButton({ askDiscard = false; discarding = true; onDismiss() }) { Text("Discard") } },
        dismissButton = { TextButton({ askDiscard = false }) { Text("Keep editing") } },
    )

    if (confirmDelete && existing != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${existing.name}?") },
        text = { Text("Its history and streak are removed. XP you already earned stays.") },
        confirmButton = { TextButton({ habitWrite { delete(existing.id) }; confirmDelete = false; discarding = true; onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}
