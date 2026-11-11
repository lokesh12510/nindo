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
    var voiceLab by remember { mutableStateOf(false) }
    var calling by remember { mutableStateOf(false) }
    var openCall by remember { mutableStateOf<CallLog?>(null) }
    val ctx = LocalContext.current
    // Load Sensei in the background so a call starts without the long wait.
    LaunchedEffect(Unit) { VoiceLab.prewarm(ctx) }
    val wantCall by UiNav.openCall.collectAsState()
    LaunchedEffect(wantCall) { if (wantCall) { UiNav.openCall.value = false; if (VoiceLab.phase.value != VoiceLab.Phase.IDLE) calling = true } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Sensei", "Your voice coach")

        CallCard(Modifier.padding(horizontal = 16.dp).enter(0), onCall = { calling = true }, onSettings = { voiceLab = true })
        SenseiCallsPanel(Modifier.padding(horizontal = 16.dp).enter(1))
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
        Panel(Modifier.padding(horizontal = 16.dp).enter(3), padding = 14.dp) {
            ForYou(ss)
            NoVideosHint(ss.noVideos)
            if (ss.interests.isNotEmpty()) CheckLine(ss.check)
        }

        SectionHeader("What Sensei knows", Modifier.padding(top = 6.dp), action = "Add") { addInterest = true }
        Panel(Modifier.padding(horizontal = 16.dp).enter(4), padding = 14.dp) {
            Knows(ss.interests, onForget = { forgetting = it }, onAdd = { addInterest = true })
            MemoryList(ss.facts, ss.lastCall, onForget = { f -> senseiWrite { deleteFact(f.id) } }, onOpenCall = { openCall = it })
        }
    }

    if (addTask) AddTaskSheet { addTask = false }
    if (voiceLab) VoiceLabDialog { voiceLab = false }
    if (calling) CallScreen(onSetup = { calling = false; voiceLab = true }) { calling = false }
    openCall?.let { c -> CallDetailSheet(c) { openCall = null } }
    if (addInterest) AddInterestSheet(ss.interests) { addInterest = false }

    deleting?.let { t ->
        AlertDialog(onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Delete task?") },
            text = { Text(t.title) },
            confirmButton = { TextButton({ senseiWrite { deleteTask(t.id) }; deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } })
    }
    forgetting?.let { i -> InterestDialog(i) { forgetting = null } }
}

@Composable
private fun RowDivider() =
    HorizontalDivider(Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

// ------------------------------------------------------------------ cards

@Composable
private fun CallCard(modifier: Modifier, onCall: () -> Unit, onSettings: () -> Unit) {
    val phase by VoiceLab.phase.collectAsState()
    val busy by VoiceLab.busy.collectAsState()
    val notes by VoiceLab.lastNotes.collectAsState()
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
        Button(onCall, enabled = phase == VoiceLab.Phase.IDLE, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(50)) {
            Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(8.dp)); Text("Call Sensei")
        }
        val line = when {
            phase == VoiceLab.Phase.SAVING -> "Sensei is writing notes from your call…"
            notes != null -> notes
            busy && !VoiceLab.brainLoaded -> "Getting Sensei ready…"
            else -> null
        }
        if (line != null) Text(line, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium,
            color = if (line.startsWith("Couldn't")) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        TextButton(onSettings) {
            Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Voice and models")
        }
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
    val finding by Feeds.running.collectAsState()
    var playing by remember { mutableStateOf<Pick?>(null) }
    playing?.let { p -> VideoPassDialog(p) { playing = null } }
    HabitOffer(ss)
    when {
        ss.interests.isEmpty() -> InfoRow(Icons.Rounded.Explore, "Tell Sensei what you're learning",
            "Add an interest below. Each morning Sensei picks one update and up to two videos for it, never an endless feed.")
        ss.picks.isEmpty() && finding -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }
            Spacer(Modifier.width(14.dp))
            Text("Finding today's picks", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        ss.picks.isEmpty() -> {
            InfoRow(Icons.Rounded.WbTwilight, "Your picks arrive in the morning",
                "Sensei chooses one update and up to two videos for ${ss.interests.take(3).joinToString(", ") { it.label }}.")
            Spacer(Modifier.height(12.dp))
            FilledTonalButton({ Feeds.refreshAsync(ctx) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Find picks now")
            }
        }
        else -> {
            ss.picks.forEachIndexed { i, p ->
                if (i > 0) RowDivider()
                PickRow(p) {
                    if (p.kind == PickKind.VIDEO && VideoPass.safeId(p.videoId) != null) playing = p
                    else {
                        senseiWrite { markOpened(p.id) }
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
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

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

/** "Checked 3:12 pm: news 1 of 1, videos 2 of 3 channels", with Check again. Shows what the last fetch got. */
@Composable
private fun CheckLine(r: Feeds.Report?) {
    val ctx = LocalContext.current
    val finding by Feeds.running.collectAsState()
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val text = when {
                finding -> "Checking now"
                r == null -> "Not checked yet"
                else -> {
                    val day = if (Days.cal(today()).timeInMillis <= r.at) "" else "${dayFmt.format(java.util.Date(r.at))}, "
                    "Checked $day${timeFmt.format(java.util.Date(r.at))}: news ${r.newsOk} of ${r.newsTotal}, " +
                        "video channels ${r.channelsOk} of ${r.channelsTotal}"
                }
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!finding && r != null && r.newsOk == 0 && r.channelsOk == 0)
                Text("Couldn't reach the feeds. Check your internet and try again.", style = MaterialTheme.typography.labelMedium, color = Brand.Rose)
            else if (!finding && r != null && r.channelsTotal > 0 && r.channelsOk == 0)
                Text("YouTube didn't answer this time. Try again in a while.", style = MaterialTheme.typography.labelMedium, color = Brand.Rose)
            r?.unresolved?.takeIf { it.isNotEmpty() && !finding }?.let { bad ->
                Text("Couldn't find the channel ${bad.joinToString(", ") { "\u201c$it\u201d" }}. Paste a link to one of its videos or its /channel/ page instead.",
                    style = MaterialTheme.typography.labelMedium, color = Brand.Rose)
            }
        }
        if (finding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else TextButton({ Feeds.refreshAsync(ctx) }) { Text("Check again") }
    }
}

@Composable
private fun NoVideosHint(missing: List<Interest>) {
    if (missing.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Text("No video channel for ${missing.joinToString(", ") { it.label }} yet. Tap it below to add one you trust.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PickRow(p: Pick, onClick: () -> Unit) {
    val video = p.kind == PickKind.VIDEO
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconTile(if (p.watched) Icons.Rounded.Check else if (video) Icons.Rounded.SmartDisplay else Icons.Rounded.Newspaper,
            if (p.watched) Brand.Green else if (video) Brand.Rose else Brand.Teal, 44.dp, filled = p.opened)
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
    Text(if (interests.isEmpty()) "Sensei picks up what you're learning from your habits and tasks, or add it here. It all stays on your phone."
        else "Picked up from your habits and tasks, or added by you. Tap one to add a video channel or forget it.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        interests.forEach { i ->
            InputChip(selected = false, onClick = { onForget(i) }, label = { Text(i.label) },
                leadingIcon = { Icon(when (i.source) {
                    SenseiSource.CALL -> Icons.Rounded.RecordVoiceOver
                    SenseiSource.HABIT -> Icons.Rounded.Repeat
                    SenseiSource.TASK -> Icons.Rounded.TaskAlt
                    SenseiSource.MANUAL -> Icons.Rounded.School
                }, null, Modifier.size(18.dp)) },
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
        senseiWrite { upsertInterest(i); Feeds.afterInterestsChanged(GuardApp.app) }
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
                label = { Text("YouTube channel or video link (optional)") }, singleLine = true,
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

/** Tap on an interest chip: add or change its YouTube channel, or forget it. */
@Composable
private fun InterestDialog(i: Interest, onDismiss: () -> Unit) {
    val start = i.channels.firstOrNull() ?: ""
    var channel by remember { mutableStateOf(start) }
    var confirmForget by remember { mutableStateOf(false) }
    if (confirmForget) {
        AlertDialog(onDismissRequest = onDismiss,
            icon = { Icon(Icons.Rounded.Psychology, null) },
            title = { Text("Forget \u201c${i.label}\u201d?") },
            text = { Text("Sensei stops picking updates and videos for it.") },
            confirmButton = { TextButton({ senseiWrite { deleteInterest(i.id) }; onDismiss() }) { Text("Forget") } },
            dismissButton = { TextButton({ confirmForget = false }) { Text("Back") } })
        return
    }
    val starter = StarterChannels.forLabel(i.label).isNotEmpty()
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.School, null) },
        title = { Text(i.label) },
        text = {
            Column {
                Text(if (starter) "Sensei already uses a few trusted teaching channels for this. Add your own to get its new videos too."
                    else "Add a YouTube channel you trust so Sensei can pick videos for this.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(channel, { channel = it.take(200) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Channel link, @name or a video link") })
            }
        },
        confirmButton = {
            TextButton({
                val c = channel.trim()
                if (c != start) {
                    val updated = i.copy(channels = listOf(c).filter { it.isNotEmpty() } + i.channels.drop(1))
                    senseiWrite { upsertInterest(updated); Feeds.afterInterestsChanged(GuardApp.app) }
                }
                onDismiss()
            }) { Text(if (channel.trim() != start) "Save" else "Done") }
        },
        dismissButton = {
            TextButton({ confirmForget = true }, colors = ButtonDefaults.textButtonColors(contentColor = Brand.Rose)) { Text("Forget") }
        })
}

/** "Make it a habit": once you follow something, offer a habit that ticks itself when you watch the day's pick. */
@Composable
private fun HabitOffer(ss: SenseiState) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("sensei", 0) }
    var hidden by remember { mutableStateOf(prefs.getBoolean("habit_offer_done", false)) }
    val hs by rememberHabits()
    val has = (hs.today.map { it.habit } + hs.notToday).any { it.auto == AutoSource.SENSEI_PICK }
    if (hidden || has || ss.interests.isEmpty()) return
    val topic = ss.interests.first().label
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(Tone.B.copy(alpha = 0.10f))
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(Icons.Rounded.AddTask, Tone.B, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Make $topic a habit", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text("Watch your Sensei pick each day. It ticks itself, +${Xp.AUTO} XP.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton({
                habitWrite { upsert(Habit(name = "Watch my Sensei pick", category = HabitCategory.LEARN, kind = HabitKind.CHECK,
                    auto = AutoSource.SENSEI_PICK)) }
                prefs.edit().putBoolean("habit_offer_done", true).apply(); hidden = true
            }) { Text("Add") }
            TextButton({ prefs.edit().putBoolean("habit_offer_done", true).apply(); hidden = true }) {
                Text("Not now", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
