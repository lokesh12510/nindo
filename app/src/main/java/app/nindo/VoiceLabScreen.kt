@file:OptIn(ExperimentalMaterial3Api::class)

package app.nindo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Phase 2C test screen: download the models, pick Sensei's voice, load, and have a call while
 * every step is timed. Opened from the Sensei call card. The real call screen comes after this.
 */
@Composable
fun VoiceLabDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val status by VoiceLab.status.collectAsState()
    val busy by VoiceLab.busy.collectAsState()
    val listening by VoiceLab.listening.collectAsState()
    val thinking by VoiceLab.thinking.collectAsState()
    val turns by VoiceLab.turns.collectAsState()
    val prefs = remember { ctx.getSharedPreferences("voice_lab", 0) }

    var gpu by remember { mutableStateOf(prefs.getBoolean("gpu", true)) }
    var boost by remember { mutableStateOf(prefs.getBoolean("boost", true)) }
    var mobileData by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Map<String, VoiceModels.Progress>>(emptyMap()) }
    var free by remember { mutableLongStateOf(0L) }
    var oldE4b by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf(SenseiVoiceChoice.get(ctx)) }
    var silence by remember { mutableFloatStateOf(SenseiVoiceChoice.silence(ctx)) }
    var showPhoneVoices by remember { mutableStateOf(voice.startsWith("system:")) }
    val brainPack = VoiceModels.GEMMA_E2B

    LaunchedEffect(Unit) {
        while (true) {
            progress = withContext(Dispatchers.IO) { VoiceModels.ALL.associate { it.id to VoiceModels.progress(ctx, it) } }
            free = withContext(Dispatchers.IO) { VoiceModels.freeBytes(ctx) }
            oldE4b = withContext(Dispatchers.IO) { VoiceModels.hasOldE4b(ctx) }
            delay(1000)
        }
    }
    DisposableEffect(Unit) { VoiceLab.labOpen = true; onDispose { VoiceLab.labOpen = false; VoiceLab.stopListening() } }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) VoiceLab.startCall(ctx) else VoiceLab.status.value = "Microphone permission is needed for the call"
    }
    fun ready(p: VoiceModels.Pack) = progress[p.id]?.state == VoiceModels.State.READY
    val speechReady = ready(VoiceModels.STT) && ready(VoiceModels.VAD)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val list = rememberLazyListState()
            LaunchedEffect(turns.size) { if (turns.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), state = list,
                contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("VOICE TEST", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Talk to Sensei", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                    }
                    Text("Everything runs on this phone. This screen times each step so we can make the real call fast.",
                        Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // ---------------------------------------------------------- models
                item {
                    Panel(Modifier.padding(horizontal = 16.dp), padding = 14.dp) {
                        Text("1. Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        PackRow(brainPack, "Brain: Gemma 4 E2B", progress[brainPack.id], mobileData)
                        PackRow(VoiceModels.STT, null, progress[VoiceModels.STT.id], mobileData)
                        PackRow(VoiceModels.VAD, null, progress[VoiceModels.VAD.id], mobileData)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Allow mobile data", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(mobileData, { mobileData = it })
                        }
                        Text("Free space: ${gb(free)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (oldE4b) TextButton({ VoiceModels.deleteOldE4b(ctx) }) { Text("Remove the old E4B model (3.7 GB)") }
                    }
                }

                // ----------------------------------------------------------- voice
                item {
                    Panel(Modifier.padding(horizontal = 16.dp), padding = 14.dp) {
                        Text("2. Sensei's voice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Tap a voice to hear it. Each is about 63 MB, plus a 6 MB pronunciation list once.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        VoiceModels.VOICES.forEach { v ->
                            val id = "piper:${v.id}"
                            val have = ready(v.pack) && ready(VoiceModels.LEXICON)
                            val pr = progress[v.pack.id]
                            VoiceRow(v.name, v.accent, selected = voice == id, ready = have, progress = pr,
                                onPick = { voice = id; VoiceLab.preview(ctx, id) },
                                onDownload = {
                                    if (!ready(VoiceModels.LEXICON) && progress[VoiceModels.LEXICON.id]?.state != VoiceModels.State.DOWNLOADING)
                                        VoiceModels.download(ctx, VoiceModels.LEXICON, mobileData)
                                    VoiceModels.download(ctx, v.pack, mobileData)
                                },
                                onDelete = { VoiceModels.delete(ctx, v.pack) })
                        }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        Row(Modifier.fillMaxWidth().clickable {
                            showPhoneVoices = !showPhoneVoices
                            if (showPhoneVoices) VoiceLab.loadSystemVoices(ctx)
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.Rounded.PhoneAndroid, Tone.B, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Phone voices", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("No download. Many phones have Indian English male voices here.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(if (showPhoneVoices) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                        }
                        if (showPhoneVoices) {
                            val voices by VoiceLab.systemVoices.collectAsState()
                            if (voices.isEmpty()) Text("Loading the phone's voices…", style = MaterialTheme.typography.bodySmall)
                            voices.forEach { (name, label) ->
                                val id = "system:$name"
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { voice = id; VoiceLab.preview(ctx, id) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(voice == id, { voice = id; VoiceLab.preview(ctx, id) })
                                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            var pitch by remember { mutableFloatStateOf(SenseiVoiceChoice.pitch(ctx)) }
                            Text("Pitch ${"%.2f".format(pitch)} (lower sounds deeper and softer)", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(pitch, { pitch = it }, valueRange = 0.7f..1.2f, steps = 9, onValueChangeFinished = {
                                SenseiVoiceChoice.setPitch(ctx, pitch); if (voice.startsWith("system:")) VoiceLab.preview(ctx, voice)
                            })
                        }
                    }
                }

                // ------------------------------------------------------------ load
                item {
                    Panel(Modifier.padding(horizontal = 16.dp), padding = 14.dp) {
                        Text("3. Load", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(gpu, { gpu = true; prefs.edit().putBoolean("gpu", true).apply() },
                                SegmentedButtonDefaults.itemShape(0, 2)) { Text("GPU (fast)") }
                            SegmentedButton(!gpu, { gpu = false; prefs.edit().putBoolean("gpu", false).apply() },
                                SegmentedButtonDefaults.itemShape(1, 2)) { Text("CPU") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Speed boost", style = MaterialTheme.typography.bodyMedium)
                                Text("Speculative decoding. Turn off if replies look wrong.", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(boost, { boost = it; prefs.edit().putBoolean("boost", it).apply() })
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton({ VoiceLab.loadBrain(ctx, brainPack, gpu, boost) }, Modifier.weight(1f),
                                enabled = ready(brainPack) && !busy) { Text(if (VoiceLab.brainLoaded) "Reload brain" else "Load brain") }
                            FilledTonalButton({ VoiceLab.loadEars(ctx) }, Modifier.weight(1f),
                                enabled = speechReady && !busy) { Text(if (VoiceLab.earsLoaded) "Reload speech" else "Load speech") }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                        if (status.isNotEmpty()) Text(status, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                            color = if (status.startsWith("Couldn't")) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (VoiceLab.brainLoaded || VoiceLab.earsLoaded)
                            TextButton({ VoiceLab.unload() }, enabled = !busy) { Text("Unload and free memory") }
                    }
                }

                // ------------------------------------------------------------ talk
                item {
                    Panel(Modifier.padding(horizontal = 16.dp), padding = 14.dp) {
                        Text("4. Call", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        val level by (VoiceLab.level() ?: remember { MutableStateFlow(0f) }).collectAsState()
                        val hearing by (VoiceLab.hearing() ?: remember { MutableStateFlow(false) }).collectAsState()
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { VoiceOrb(120.dp, speaking = thinking || hearing) }
                        Text(when {
                            thinking -> "Sensei is talking"
                            hearing -> "Hearing you"
                            listening -> "Your turn"
                            else -> "Not on a call"
                        }, Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        if (listening) LinearProgressIndicator({ (level * 4).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 6.dp))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({
                                if (listening) VoiceLab.stopListening()
                                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                    VoiceLab.startCall(ctx)
                                else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }, Modifier.weight(1f), enabled = VoiceLab.brainLoaded && VoiceLab.earsLoaded, shape = RoundedCornerShape(50),
                                colors = if (listening) ButtonDefaults.buttonColors(containerColor = Brand.Rose) else ButtonDefaults.buttonColors()) {
                                Icon(if (listening) Icons.Rounded.CallEnd else Icons.Rounded.Call, null)
                                Spacer(Modifier.width(6.dp)); Text(if (listening) "End call" else "Start call")
                            }
                            OutlinedButton({ VoiceLab.interrupt() }, enabled = thinking, shape = RoundedCornerShape(50)) { Text("Interrupt") }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Sensei replies after you pause for", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            listOf(0.4f to "0.4 s, quick", 0.6f to "0.6 s", 0.8f to "0.8 s, relaxed").forEachIndexed { i, (s, label) ->
                                SegmentedButton(silence == s, {
                                    silence = s; SenseiVoiceChoice.setSilence(ctx, s)
                                    if (VoiceLab.earsLoaded && !listening) VoiceLab.loadEars(ctx)
                                }, SegmentedButtonDefaults.itemShape(i, 3), enabled = !listening) { Text(label, maxLines = 1) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(typed, { typed = it.take(300) }, Modifier.weight(1f), singleLine = true,
                                placeholder = { Text("Or type to test the brain alone") }, enabled = VoiceLab.brainLoaded)
                            IconButton({ VoiceLab.send(typed); typed = "" }, enabled = VoiceLab.brainLoaded && typed.isNotBlank() && !thinking) {
                                Icon(Icons.Rounded.Send, "Send")
                            }
                        }
                        val aec by (VoiceLab.echoCancel() ?: remember { MutableStateFlow(false) }).collectAsState()
                        Text("Sensei says hi first. Speaker call: the mic is off while Sensei talks" +
                            if (listening) (if (aec) ", and the phone's echo cancelling is on." else ". This phone didn't offer echo cancelling.") else ".",
                            Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // ----------------------------------------------------------- turns
                if (turns.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("5. Results", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton({ VoiceLab.reset() }) { Text("New chat") }
                        TextButton({
                            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nindo voice test", VoiceLab.report()))
                            Toast.makeText(ctx, "Results copied", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy") }
                    }
                }
                itemsIndexed(turns) { _, t -> TurnCard(t, Modifier.padding(horizontal = 16.dp)) }
            }
        }
    }
}

@Composable
private fun PackRow(p: VoiceModels.Pack, title: String?, pr: VoiceModels.Progress?, mobileData: Boolean) {
    val ctx = LocalContext.current
    val state = pr?.state ?: VoiceModels.State.MISSING
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(when (p) {
                VoiceModels.STT -> Icons.Rounded.Hearing
                VoiceModels.VAD -> Icons.Rounded.GraphicEq
                else -> Icons.Rounded.Psychology
            }, if (state == VoiceModels.State.READY) Brand.Green else Tone.B, 40.dp, filled = state == VoiceModels.State.READY)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title ?: p.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(stateText(p, pr), style = MaterialTheme.typography.bodySmall,
                    color = if (state == VoiceModels.State.FAILED) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PackAction(p, state, mobileData, ctx)
        }
        if (state == VoiceModels.State.DOWNLOADING || state == VoiceModels.State.PAUSED)
            LinearProgressIndicator({ pr!!.fraction }, Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun PackAction(p: VoiceModels.Pack, state: VoiceModels.State, mobileData: Boolean, ctx: android.content.Context) {
    when (state) {
        VoiceModels.State.READY -> TextButton({ VoiceModels.delete(ctx, p) }) { Text("Delete") }
        VoiceModels.State.DOWNLOADING, VoiceModels.State.PAUSED -> TextButton({ VoiceModels.cancel(ctx, p) }) { Text("Cancel") }
        else -> TextButton({ VoiceModels.download(ctx, p, mobileData) }) { Text(if (state == VoiceModels.State.FAILED) "Retry" else "Download") }
    }
}

private fun stateText(p: VoiceModels.Pack, pr: VoiceModels.Progress?): String = when (pr?.state ?: VoiceModels.State.MISSING) {
    VoiceModels.State.READY -> "Downloaded, ${gb(p.bytes)}"
    VoiceModels.State.DOWNLOADING -> "${gb(pr!!.done)} of ${gb(pr.total)}"
    VoiceModels.State.PAUSED -> pr?.reason ?: "Paused"
    VoiceModels.State.FAILED -> pr?.reason ?: "Failed"
    VoiceModels.State.MISSING -> gb(p.bytes)
}

@Composable
private fun VoiceRow(name: String, accent: String, selected: Boolean, ready: Boolean, progress: VoiceModels.Progress?,
                     onPick: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit) {
    val state = progress?.state ?: VoiceModels.State.MISSING
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = ready, onClick = onPick)
            .padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onPick, enabled = ready)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                Text(when (state) {
                    VoiceModels.State.DOWNLOADING -> "$accent · ${gb(progress!!.done)} of ${gb(progress.total)}"
                    VoiceModels.State.FAILED -> "$accent · ${progress?.reason ?: "failed"}"
                    VoiceModels.State.PAUSED -> "$accent · ${progress?.reason ?: "paused"}"
                    else -> if (ready) "$accent · tap to hear" else accent
                }, style = MaterialTheme.typography.bodySmall,
                    color = if (state == VoiceModels.State.FAILED) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                state == VoiceModels.State.READY -> IconButton(onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
                state == VoiceModels.State.DOWNLOADING || state == VoiceModels.State.PAUSED ->
                    CircularProgressIndicator({ progress!!.fraction }, Modifier.size(22.dp), strokeWidth = 2.dp)
                else -> IconButton(onDownload) { Icon(Icons.Rounded.Download, "Download") }
            }
        }
    }
}

@Composable
private fun TurnCard(t: VoiceTurn, modifier: Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) {
        if (!t.greeting) {
            Text(if (t.typed) "You typed" else "You said", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t.you, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
        }
        Text(if (t.greeting) "Sensei picks up" else "Sensei", style = MaterialTheme.typography.labelMedium, color = Tone.B, fontWeight = FontWeight.SemiBold)
        Text(t.sensei.ifEmpty { if (t.done) "" else "…" }, style = MaterialTheme.typography.bodyLarge)
        t.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Rose) }
        if (t.done && t.error == null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(6.dp))
            Text("${if (t.greeting) "Hello took" else "Reply delay"} ${VoiceLab.fmt(t.replyDelayMs)}", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = when {
                    t.replyDelayMs <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                    t.replyDelayMs < 2000 -> Brand.Green
                    t.replyDelayMs < 3000 -> Brand.Amber
                    else -> Brand.Rose
                })
            Text(listOfNotNull(
                if (!t.typed) "end of speech ${VoiceLab.fmt(t.endpointMs)}" else null,
                if (!t.typed) "speech to text ${VoiceLab.fmt(t.sttMs)}" else null,
                "first word ${VoiceLab.fmt(t.firstTokenMs)}",
                "first spoken ${VoiceLab.fmt(t.firstSentenceMs)}",
                if (t.tokensPerSec > 0) "%.0f tok/s".format(t.tokensPerSec) else null,
            ).joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun gb(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.0f MB".format(b / 1e6)
    else -> "%.0f KB".format(b / 1e3)
}
