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
import androidx.compose.foundation.border
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

/** Pause screen settings: reminders (photo + note), roast level and bedtime. To-dos come from habits. */
@Composable
fun MindsetCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s = GuardApp.store
    val version by s.version.collectAsState()
    val anchors = remember(version) { s.anchors() }
    var addAnchor by remember { mutableStateOf(false) }

    SectionCard("Mindset", modifier) {
        // Pause screen on/off
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.SelfImprovement, Tone.B, 44.dp, filled = s.pauseOn)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Pause screen", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(if (s.strict) "Locked on during strict mode"
                    else "30 seconds you can't skip: when you turn off the filter, open adult content, or use your phone past bedtime.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(s.pauseOn, { on ->
                when {
                    on -> s.pauseOn = true
                    s.strict -> {}
                    else -> Urges.request(ctx, Urges.Request(Trigger.WEAKEN, "Pause screen", onContinue = { s.pauseOn = false }))
                }
            })
        }

        AnimatedVisibility(s.pauseOn) {
            Column {
                // ---- your reminders: photo cards
                Text("Your reminders", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
                Text("A photo and a line that would stop you. One fills the screen each time.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    anchors.forEach { a -> AnchorTile(a) { AnchorImages.delete(ctx, a.image); s.setAnchors(s.anchors().filter { it.id != a.id }) } }
                    Box(
                        Modifier.size(width = 128.dp, height = 176.dp).clip(RoundedCornerShape(20.dp))
                            .background(Tone.B.copy(alpha = 0.08f)).border(1.5.dp, Tone.B.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .clickable { addAnchor = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconTile(Icons.Rounded.AddPhotoAlternate, Tone.B, 44.dp)
                            Spacer(Modifier.height(8.dp))
                            Text("Add photo", style = MaterialTheme.typography.labelLarge, color = Tone.B)
                        }
                    }
                }

                // ---- roast level: three cards
                Text("Tone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("Gentle", Icons.Rounded.Spa, "Kind words"),
                        Triple("Honest", Icons.Rounded.RecordVoiceOver, "Straight talk"),
                        Triple("Savage", Icons.Rounded.LocalFireDepartment, "No mercy"),
                    ).forEachIndexed { i, (label, icon, hint) ->
                        val sel = s.roastLevel == i
                        val c = listOf(Brand.Green, Tone.B, Brand.Rose)[i]
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                            .border(if (sel) 2.dp else 1.dp, if (sel) c else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                            .background(if (sel) c.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable { s.roastLevel = i }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(icon, null, tint = if (sel) c else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ---- bedtime: its own tinted panel
                Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Tone.A.copy(alpha = 0.07f)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.Bedtime, Tone.A, 40.dp, filled = s.bedOn)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Bedtime", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Browsers and social apps show a sleep reminder", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(s.bedOn, { s.bedOn = it })
                    }
                    AnimatedVisibility(s.bedOn) {
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TimeButton("From", s.bedStartMin, Modifier.weight(1f)) { s.bedStartMin = it }
                            TimeButton("Until", s.bedEndMin, Modifier.weight(1f)) { s.bedEndMin = it }
                        }
                    }
                    LabeledSwitch("Follow Bedtime mode", "Also when Digital Wellbeing's Bedtime mode or Do Not Disturb is on at night", s.bedDnd) { s.bedDnd = it }
                }

                Text("The pause screen also suggests one of today's unfinished habits.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                Spacer(Modifier.height(10.dp))
                FilledTonalButton({ Urges.request(ctx, Urges.Request(Trigger.PREVIEW, "preview")) }, Modifier.fillMaxWidth().height(48.dp)) {
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
    Box(Modifier.size(width = 128.dp, height = 176.dp).clip(RoundedCornerShape(20.dp))
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
