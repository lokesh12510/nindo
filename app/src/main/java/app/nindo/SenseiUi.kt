@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package app.nindo

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

private var lastSensei = SenseiState()

/** Sensei's data, reloaded when Sensei or habit data changes (and every 30 s for the date and recap). */
@Composable
fun rememberSensei(): State<SenseiState> {
    val rev by SenseiBus.rev.collectAsState()
    val habitRev by HabitBus.rev.collectAsState()
    return produceState(lastSensei, rev, habitRev) {
        while (true) {
            val v = withContext(Dispatchers.IO) { runCatching { Sensei.load() }.getOrNull() }
            if (v != null) { lastSensei = v; value = v }
            delay(30_000)
        }
    }
}

// =============================================================== Sensei tab

/** Sensei's home: the call, today's word, what's pending, today's picks and what Sensei knows. */
@Composable
fun SenseiScreen() {
    val hs by rememberHabits()
    val ss by rememberSensei()
    val openHabit = habitSheets(hs)
    val pendingHabits = hs.today.filter { !it.done && !it.failed && !it.pending }

    var addTask by remember { mutableStateOf(false) }
    var addInterest by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SenseiTask?>(null) }
    var forgetting by remember { mutableStateOf<Interest?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Sensei", "Your voice coach")

        CallCard(Modifier.padding(horizontal = 16.dp).enter(0))
        if (ss.word.isNotEmpty()) WordCard(ss.word, Modifier.padding(horizontal = 16.dp).enter(1))

        SectionHeader("Pending", Modifier.padding(top = 6.dp), action = "Add task") { addTask = true }
        Panel(Modifier.padding(horizontal = 16.dp).enter(2), padding = 12.dp) {
            if (pendingHabits.isEmpty() && ss.tasks.isEmpty()) Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.TaskAlt, Brand.Green)
                Spacer(Modifier.width(14.dp))
                Text("Nothing pending. Well done.", style = MaterialTheme.typography.bodyMedium)
            }
            var first = true
            pendingHabits.forEach { d ->
                if (!first) RowDivider(); first = false
                HabitRow(d, hs.streaks[d.habit.id]) { openHabit(d.habit.id) }
            }
            ss.tasks.forEach { t ->
                if (!first) RowDivider(); first = false
                TaskRow(t, onToggle = { senseiWrite { setTaskDone(t.id, !t.done) } }, onLong = { deleting = t })
            }
        }

        SectionHeader("For you", Modifier.padding(top = 6.dp))
        Panel(Modifier.padding(horizontal = 16.dp).enter(3), padding = 14.dp) { ForYou(ss) }

        SectionHeader("What Sensei knows", Modifier.padding(top = 6.dp), action = "Add") { addInterest = true }
        Panel(Modifier.padding(horizontal = 16.dp).enter(4), padding = 14.dp) {
            Knows(ss.interests, onForget = { forgetting = it }, onAdd = { addInterest = true })
        }
    }

    if (addTask) AddTaskSheet { addTask = false }
    if (addInterest) AddInterestSheet(ss.interests) { addInterest = false }

    deleting?.let { t ->
        AlertDialog(onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Delete task?") },
            text = { Text(t.title) },
            confirmButton = { TextButton({ senseiWrite { deleteTask(t.id) }; deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } })
    }
    forgetting?.let { i ->
        AlertDialog(onDismissRequest = { forgetting = null },
            icon = { Icon(Icons.Rounded.Psychology, null) },
            title = { Text("Forget “${i.label}”?") },
            text = { Text("Sensei stops picking updates and videos for it.") },
            confirmButton = { TextButton({ senseiWrite { deleteInterest(i.id) }; forgetting = null }) { Text("Forget") } },
            dismissButton = { TextButton({ forgetting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun RowDivider() =
    HorizontalDivider(Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

// ------------------------------------------------------------------ cards

@Composable
private fun CallCard(modifier: Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .background(Brush.verticalGradient(listOf(Tone.B.copy(alpha = 0.12f), Color.Transparent)))
        .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        VoiceOrb(128.dp)
        Spacer(Modifier.height(6.dp))
        Text("Talk to Sensei every day", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("A short voice call at your time to look back on your day and plan tomorrow.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button({}, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(50)) {
            Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(8.dp)); Text("Call Sensei")
        }
        Spacer(Modifier.height(6.dp))
        Text("Voice calls are coming in the next update", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WordCard(word: String, modifier: Modifier) {
    Panel(modifier, padding = 16.dp) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(Icons.Rounded.FormatQuote, Tone.B, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("TODAY'S WORD", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text(word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ------------------------------------------------------------------ tasks

private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

private fun dueLabel(t: SenseiTask, today: String): String = when {
    t.done -> "Done"
    t.due == null -> "Task, any time"
    t.due < today -> "Overdue since ${dayFmt.format(Days.cal(t.due).time)}"
    t.due == today -> "Today"
    t.due == Days.shift(today, 1) -> "Tomorrow"
    else -> dayFmt.format(Days.cal(t.due).time)
}

@Composable
private fun TaskRow(t: SenseiTask, onToggle: () -> Unit, onLong: () -> Unit) {
    val today = today()
    val overdue = !t.done && t.due != null && t.due < today
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onToggle, onLongClick = onLong)
        .padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(if (t.done) Icons.Rounded.Check else Icons.Rounded.RadioButtonUnchecked, Brand.Sky, 44.dp, filled = t.done)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textDecoration = if (t.done) TextDecoration.LineThrough else null,
                color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(dueLabel(t, today), style = MaterialTheme.typography.bodySmall,
                color = if (overdue) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddTaskSheet(onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var due by remember { mutableIntStateOf(0) } // 0 today, 1 tomorrow, 2 any time
    val focus = remember { FocusRequester() }
    fun save() {
        if (title.isBlank()) return
        val t = today()
        val day = when (due) { 0 -> t; 1 -> Days.shift(t, 1); else -> null }
        val task = SenseiTask(title = title.trim(), due = day)
        senseiWrite { upsertTask(task) }
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("New task", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text("What needs doing?") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }))
            Spacer(Modifier.height(14.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Today", "Tomorrow", "Any time").forEachIndexed { i, l ->
                    SegmentedButton(selected = due == i, onClick = { due = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(::save, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(50)) {
                Text("Add task")
            }
        }
    }
    LaunchedEffect(Unit) { delay(250); runCatching { focus.requestFocus() } }
}

// ---------------------------------------------------------------- for you

@Composable
private fun ForYou(ss: SenseiState) {
    val ctx = LocalContext.current
    when {
        ss.interests.isEmpty() -> InfoRow(Icons.Rounded.Explore, "Tell Sensei what you're learning",
            "Add an interest below. Each morning Sensei picks one update and up to two videos for it, never an endless feed.")
        ss.picks.isEmpty() -> InfoRow(Icons.Rounded.WbTwilight, "Your picks arrive in the morning",
            "Sensei will choose one update and up to two videos for ${ss.interests.take(3).joinToString(", ") { it.label }}.")
        else -> {
            ss.picks.forEachIndexed { i, p ->
                if (i > 0) RowDivider()
                PickRow(p) {
                    senseiWrite { markOpened(p.id) }
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("That's all for today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PickRow(p: Pick, onClick: () -> Unit) {
    val video = p.kind == PickKind.VIDEO
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconTile(if (video) Icons.Rounded.SmartDisplay else Icons.Rounded.Newspaper, if (video) Brand.Rose else Brand.Teal, 44.dp, filled = p.opened)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, Tone.B, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------- what Sensei knows

@Composable
private fun Knows(interests: List<Interest>, onForget: (Interest) -> Unit, onAdd: () -> Unit) {
    Text(if (interests.isEmpty()) "Sensei only knows what you add here or say on a call. It all stays on your phone."
        else "Tap one to make Sensei forget it. It all stays on your phone.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        interests.forEach { i ->
            InputChip(selected = false, onClick = { onForget(i) }, label = { Text(i.label) },
                leadingIcon = { Icon(if (i.source == SenseiSource.CALL) Icons.Rounded.RecordVoiceOver else Icons.Rounded.School, null,
                    Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Rounded.Close, "Forget", Modifier.size(16.dp)) })
        }
        AssistChip(onClick = onAdd, label = { Text("Add") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) })
    }
}

private val SUGGESTIONS = listOf("Python", "Fitness", "Guitar", "Investing", "Drawing", "Public speaking", "Cooking", "English speaking")

@Composable
private fun AddInterestSheet(existing: List<Interest>, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var words by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val taken = existing.map { it.label.lowercase() }.toSet()
    val dup = label.trim().lowercase() in taken
    fun save() {
        if (label.isBlank() || dup) return
        val i = Interest(label = label.trim().replaceFirstChar { it.uppercase() },
            keywords = words.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            channels = listOf(channel.trim()).filter { it.isNotEmpty() })
        senseiWrite { upsertInterest(i) }
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Something you're learning", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Sensei uses it for your daily update and video picks.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(label, { label = it.take(40) }, Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text("Topic") }, placeholder = { Text("e.g. Python, guitar, investing") }, singleLine = true,
                isError = dup, supportingText = if (dup) ({ Text("Sensei already knows this") }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SUGGESTIONS.filter { it.lowercase() !in taken }.forEach { s ->
                    SuggestionChip(onClick = { label = s }, label = { Text(s) })
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(words, { words = it.take(120) }, Modifier.fillMaxWidth(),
                label = { Text("Extra search words (optional)") }, placeholder = { Text("e.g. loops, pandas") }, singleLine = true,
                supportingText = { Text("Separate with commas") })
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(channel, { channel = it.take(200) }, Modifier.fillMaxWidth(),
                label = { Text("YouTube channel link (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }),
                supportingText = { Text("Videos come from channels you trust") })
            Spacer(Modifier.height(18.dp))
            Button(::save, enabled = label.isNotBlank() && !dup, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(50)) {
                Text("Add")
            }
        }
    }
    LaunchedEffect(Unit) { delay(250); runCatching { focus.requestFocus() } }
}
