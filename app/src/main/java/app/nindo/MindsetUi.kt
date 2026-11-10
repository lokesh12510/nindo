@file:OptIn(ExperimentalMaterial3Api::class)

package app.nindo

import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pause screen settings: reminders (photo + note), roast level, today's tasks and bedtime. */
@Composable
fun MindsetCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s = GuardApp.store
    val version by s.version.collectAsState()
    val anchors = remember(version) { s.anchors() }
    val tasks = remember(version) { s.tasks() }
    var addAnchor by remember { mutableStateOf(false) }

    SectionCard("Mindset & pause screen", modifier) {
        Text("A 15-second pause you can't skip when you turn off the filter, open adult content, or pick up your phone past bedtime.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledSwitch("Pause screen", if (s.strict) "Locked on during strict mode" else null, s.pauseOn) { on ->
            when {
                on -> s.pauseOn = true
                s.strict -> {}
                else -> Urges.request(ctx, Urges.Request(Trigger.WEAKEN, "Pause screen", onContinue = { s.pauseOn = false }))
            }
        }

        AnimatedVisibility(s.pauseOn) {
            Column {
                Text("Roast", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Gentle", "Honest", "Savage").forEachIndexed { i, l ->
                        SegmentedButton(s.roastLevel == i, { s.roastLevel = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }

                // ---- reminders
                Text("Your reminders", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
                Text("A photo and a line that would stop you: family, a goal, how you want to feel. One is shown each time.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    anchors.forEach { a -> AnchorTile(a) { AnchorImages.delete(ctx, a.image); s.setAnchors(s.anchors().filter { it.id != a.id }) } }
                    Box(
                        Modifier.size(width = 120.dp, height = 150.dp).clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).clickable { addAnchor = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Add", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // ---- tasks
                Text("Today's tasks", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
                Text("Unfinished ones are listed on the pause screen.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                tasks.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(t.done, { d -> s.setTasks(s.tasks().map { if (it.id == t.id) it.copy(done = d) else it }) })
                        Text(t.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (t.done) TextDecoration.LineThrough else null,
                            color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        IconButton({ s.setTasks(s.tasks().filter { it.id != t.id }) }) { Icon(Icons.Rounded.Close, "Delete", Modifier.size(18.dp)) }
                    }
                }
                var newTask by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newTask, { newTask = it }, Modifier.weight(1f), placeholder = { Text("Add a task") },
                        singleLine = true, shape = RoundedCornerShape(14.dp))
                    IconButton({
                        if (newTask.isNotBlank()) { s.setTasks(s.tasks() + TaskItem(text = newTask.trim())); newTask = "" }
                    }) { Icon(Icons.Rounded.AddCircle, "Add task", tint = MaterialTheme.colorScheme.primary) }
                }
                if (tasks.any { it.done }) TextButton({ s.setTasks(s.tasks().filter { !it.done }) }) { Text("Clear done") }

                // ---- bedtime
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                LabeledSwitch("Bedtime", "Opening browsers or social apps then shows a sleep reminder", s.bedOn) { s.bedOn = it }
                AnimatedVisibility(s.bedOn) {
                    Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeButton("From", s.bedStartMin, Modifier.weight(1f)) { s.bedStartMin = it }
                        TimeButton("Until", s.bedEndMin, Modifier.weight(1f)) { s.bedEndMin = it }
                    }
                }
                LabeledSwitch("Follow Bedtime mode", "Also at night when Digital Wellbeing's Bedtime mode or Do Not Disturb is on", s.bedDnd) { s.bedDnd = it }

                Spacer(Modifier.height(8.dp))
                OutlinedButton({ Urges.request(ctx, Urges.Request(Trigger.PREVIEW, "preview")) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.width(8.dp)); Text("Preview the pause screen")
                }
            }
        }
    }

    if (addAnchor) AddAnchorDialog { addAnchor = false }
}

@Composable
private fun AnchorTile(a: Anchor, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, a.image) {
        value = a.image?.let { withContext(Dispatchers.IO) { AnchorImages.load(ctx, it, 400) } }
    }
    Box(Modifier.size(width = 120.dp, height = 150.dp).clip(RoundedCornerShape(18.dp))
        .background(Brush.verticalGradient(listOf(Brand.Indigo, Brand.Violet)))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (a.note.isNotBlank()) {
            Text(a.note, Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))).padding(8.dp),
                color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun AddAnchorDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    val preview by produceState<Bitmap?>(null, uri) {
        value = uri?.let { u -> withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) } }.getOrNull()
        } }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri = it ?: uri }

    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Rounded.Favorite, null) },
        title = { Text("Add a reminder") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center) {
                    val p = preview
                    if (p != null) Image(p.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null); Text("Pick a photo (optional)", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Your note") },
                    placeholder = { Text("Why I'm doing this…") }, minLines = 2, shape = RoundedCornerShape(14.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && (note.isNotBlank() || uri != null), onClick = {
                saving = true
                scope.launch {
                    val img = uri?.let { u -> withContext(Dispatchers.IO) { AnchorImages.import(ctx, u) } }
                    val s = GuardApp.store
                    s.setAnchors(s.anchors() + Anchor(note = note.trim(), image = img))
                    onClose()
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
private fun TimeButton(label: String, minutes: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    OutlinedButton({
        TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
    }, modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(fmtTime(minutes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
