package app.nindo

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Tell Sensei" from the home-screen widget: say one line ("remind me at 7 to call mom") and it
 * becomes a reminder call or a task. Speech recognition runs on the phone (the same Moonshine
 * model as calls, ready in about a second); no AI needed, so it's quick. Typing works too.
 */
class QuickNoteActivity : ComponentActivity() {
    private var ears: SenseiEars? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GuardTheme { QuickNote(onStartEars = ::startEars, onStopEars = ::stopEars, onDone = { finish() }) } }
    }

    /** Loads speech recognition and calls [onText] with the first thing said. */
    private fun startEars(onText: (String) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val e = ears ?: SenseiEars(VoiceModels.path(this, VoiceModels.VAD, "silero_vad.onnx"),
                    VoiceModels.path(this, VoiceModels.STT, "").trimEnd('/'), 0.9f).also { ears = it }
                e.start(onHeard = { text, _, _ -> e.mute(); runOnUiThread { onText(text) } },
                    onError = { msg -> runOnUiThread { onError(msg) } })
                e.unmute(0)
            } catch (t: Throwable) {
                runOnUiThread { onError("Couldn't start listening: ${t.message ?: t.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun stopEars() { ears?.stop() }

    override fun onDestroy() {
        ears?.release(); ears = null
        super.onDestroy()
    }
}

@Composable
private fun QuickNote(onStartEars: ((String) -> Unit, (String) -> Unit) -> Unit, onStopEars: () -> Unit, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val canListen = remember { VoiceModels.ready(ctx, VoiceModels.STT) && VoiceModels.ready(ctx, VoiceModels.VAD) }
    val phase by VoiceLab.phase.collectAsState()
    val onCall = phase != VoiceLab.Phase.IDLE
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    val now = remember(heard) { System.currentTimeMillis() }
    val result = remember(heard) { QuickParse.parse(heard, now) }
    var title by remember(result) { mutableStateOf(when (result) { is QuickParse.Result.Call -> result.title; is QuickParse.Result.Task -> result.title; null -> "" }) }

    fun listen() {
        error = null; heard = ""; listening = true
        onStartEars({ t -> listening = false; heard = t }, { e -> listening = false; error = e })
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else error = "Microphone permission is needed to listen. You can type instead."
    }
    LaunchedEffect(Unit) {
        if (canListen && !onCall) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
            else mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun save(r: QuickParse.Result, name: String) {
        val clean = name.trim().ifEmpty { return }
        Thread {
            val msg = when (r) {
                is QuickParse.Result.Call -> if (SenseiCalls.addReminder(ctx, clean, r.at)) "Sensei will call you ${CallTimes.whenLabel(System.currentTimeMillis(), r.at)}"
                    else "That time has already passed"
                is QuickParse.Result.Task -> {
                    val t = today()
                    GuardApp.sensei.upsertTask(SenseiTask(title = clean, due = when (r.due) { "today" -> t; "tomorrow" -> Days.shift(t, 1); else -> null },
                        source = SenseiSource.MANUAL))
                    "Task added"
                }
            }
            (ctx as? ComponentActivity)?.runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); onDone() }
        }.start()
    }

    // Dim background; tap outside to close.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
        .clickable(remember { MutableInteractionSource() }, null) { onStopEars(); onDone() },
        contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp).clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(remember { MutableInteractionSource() }, null) {}
            .padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tell Sensei", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("“Remind me at 7 to call mom” or “buy milk tomorrow”", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))

            when {
                onCall -> Text("You're on a call with Sensei. Just tell it there.", style = MaterialTheme.typography.bodyMedium)
                listening -> {
                    VoiceOrb(96.dp, speaking = true)
                    Spacer(Modifier.height(8.dp))
                    Text("Listening…", style = MaterialTheme.typography.bodyMedium)
                }
                result != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(if (result is QuickParse.Result.Call) Icons.Rounded.PhoneCallback else Icons.Rounded.TaskAlt, Tone.B, 40.dp, filled = true)
                        Spacer(Modifier.width(12.dp))
                        Text(QuickParse.describe(result, now), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("About") })
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canListen) OutlinedButton({ listen() }, Modifier.weight(1f), shape = RoundedCornerShape(50)) { Text("Say it again") }
                        Button({ save(result, title) }, Modifier.weight(1f), shape = RoundedCornerShape(50), enabled = title.isNotBlank()) { Text("Save") }
                    }
                }
                else -> {
                    if (heard.isNotEmpty()) Text("Heard “$heard” but couldn't find a task or a time in it.", style = MaterialTheme.typography.bodyMedium)
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Rose) }
                    if (!canListen) Text("Voice needs Sensei's speech model (in Sensei → Voice and models). Type it for now.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (canListen) {
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton({ listen() }, shape = RoundedCornerShape(50)) {
                            Icon(Icons.Rounded.Mic, null); Spacer(Modifier.width(8.dp)); Text("Speak")
                        }
                    }
                }
            }
            if (!onCall && !listening && result == null) {
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(typed, { typed = it.take(160) }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Or type it") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (typed.isNotBlank()) heard = typed }),
                    trailingIcon = { IconButton({ if (typed.isNotBlank()) heard = typed }) { Icon(Icons.Rounded.Send, "Go") } })
            }
        }
    }
}

/** Home-screen widget: "Tell Sensei" plus when Sensei calls next. */
class SenseiWidget : AppWidgetProvider() {
    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, SenseiWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_sensei)
            val next = runCatching { SenseiCalls.next(ctx) }.getOrNull()
            v.setTextViewText(R.id.s_next, next?.let { "Next call ${CallTimes.whenLabel(System.currentTimeMillis(), it.at)}" }
                ?: "A reminder or a task, by voice")
            val open = PendingIntent.getActivity(ctx, 70, Intent(ctx, QuickNoteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.s_root, open)
            v.setOnClickPendingIntent(R.id.s_mic, open)
            runCatching { mgr.updateAppWidget(ids, v) }
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = updateAll(ctx)
}
