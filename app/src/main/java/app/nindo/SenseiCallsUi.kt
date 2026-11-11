@file:OptIn(ExperimentalMaterial3Api::class)

package app.nindo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Under "What Sensei knows": what Sensei remembers from calls, and the last call. */
@Composable
fun MemoryList(facts: List<Fact>, lastCall: CallLog?, onForget: (Fact) -> Unit, onOpenCall: (CallLog) -> Unit) {
    if (facts.isEmpty() && lastCall == null) return
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    Spacer(Modifier.height(10.dp))
    if (facts.isNotEmpty()) {
        Text("From your calls", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        facts.take(12).forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.RecordVoiceOver, null, Modifier.size(16.dp), tint = Tone.B)
                Spacer(Modifier.width(10.dp))
                Text(f.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton({ onForget(f) }, Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Forget", Modifier.size(16.dp)) }
            }
        }
        if (facts.size > 12) Text("and ${facts.size - 12} more", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    lastCall?.let { c ->
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpenCall(c) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Call, Tone.B, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Last call, ${whenText(c.started)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(c.summary.ifBlank { "No notes from this call" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Insights: calls with Sensei, newest first. */
@Composable
fun CallsCard(modifier: Modifier) {
    val rev by SenseiBus.rev.collectAsState()
    val calls by produceState(emptyList<CallLog>(), rev) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { GuardApp.sensei.calls(10) }.getOrDefault(emptyList()) }
    }
    var open by remember { mutableStateOf<CallLog?>(null) }
    if (calls.isEmpty()) return
    Panel(modifier, padding = 14.dp) {
        Text("Calls with Sensei", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        calls.forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { open = c }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Call, Tone.B, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${whenText(c.started)} · ${length(c)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(c.summary.ifBlank { "No notes from this call" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    open?.let { c -> CallDetailSheet(c) { open = null } }
}

/** One call: its notes and what was said. */
@Composable
fun CallDetailSheet(c: CallLog, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Call with Sensei", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${whenText(c.started)} · ${length(c)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.summary.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Panel(padding = 14.dp) { Text(c.summary, style = MaterialTheme.typography.bodyLarge) }
            }
            Spacer(Modifier.height(16.dp))
            Text("What was said", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            c.transcript.lines().filter { it.isNotBlank() }.forEach { line ->
                val sensei = line.startsWith("Sensei: ")
                Text(if (sensei) "Sensei" else "You", style = MaterialTheme.typography.labelMedium,
                    color = if (sensei) Tone.B else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Text(line.substringAfter(": "), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton({ confirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = Brand.Rose)) {
                Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("Delete this call and what Sensei learned from it")
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Delete this call?") },
        text = { Text("Its notes and the things Sensei remembered from it are removed. Tasks it added stay.") },
        confirmButton = { TextButton({ senseiWrite { deleteCall(c.id) }; confirm = false; onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

private fun whenText(ms: Long): String {
    val d = Date(ms)
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(d)
    return when (day) {
        today() -> "today $time"
        Days.shift(today(), -1) -> "yesterday $time"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(d) + " $time"
    }
}

private fun length(c: CallLog): String {
    val s = ((c.ended - c.started) / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60} min"
}

/** Sensei screen: when Sensei calls you, the reminder calls to come, and the permissions they need. */
@Composable
fun SenseiCallsPanel(modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rev by SenseiBus.rev.collectAsState()
    var dailyOn by remember { mutableStateOf(SenseiCalls.dailyOn(ctx)) }
    var dailyMin by remember { mutableIntStateOf(SenseiCalls.dailyMinutes(ctx)) }
    var picking by remember { mutableStateOf(false) }
    val upcoming by produceState(emptyList<ScheduledCall>(), rev) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { GuardApp.sensei.scheduled().filter { it.state == ScheduledCall.State.PENDING && it.kind != ScheduledCall.Kind.TEST } }
                .getOrDefault(emptyList())
        }
    }
    // Permissions can change in Settings while the app is open.
    val perms by produceState(Triple(true, true, true)) {
        while (true) {
            value = Triple(SenseiCalls.canRingOnTime(ctx), SenseiCalls.canShowFullScreen(ctx), Notify.allowed(ctx))
            kotlinx.coroutines.delay(2000)
        }
    }
    val now = System.currentTimeMillis()

    Panel(modifier, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.PhoneCallback, Tone.B, 40.dp, filled = dailyOn)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Daily check-in call", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(if (dailyOn) "Sensei calls you every day at ${CallTimes.label(dailyMin)}" else "Sensei calls you at a time you choose",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(dailyOn, { on -> dailyOn = on; Thread { SenseiCalls.setDaily(ctx, on, dailyMin) }.start(); if (on) picking = true })
        }
        if (dailyOn) TextButton({ picking = true }, Modifier.padding(start = 44.dp)) { Text("Change time") }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Spacer(Modifier.height(8.dp))
        Text("Reminder calls", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (upcoming.isEmpty()) Text("On a call, say “remind me at 7 to call mom” and Sensei will call you then.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        upcoming.take(6).forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Alarm, null, Modifier.size(18.dp), tint = Tone.B)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(CallTimes.whenLabel(now, c.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ Thread { SenseiCalls.cancelReminder(ctx, c.id) }.start() }, Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, "Cancel", Modifier.size(16.dp))
                }
            }
        }

        val (onTime, fullScreen, notify) = perms
        if (!notify) PermissionRow("Allow notifications so Sensei's calls can ring") {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (!onTime && android.os.Build.VERSION.SDK_INT >= 31) PermissionRow("Allow alarms so Sensei calls on time, not minutes late") {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (!fullScreen && android.os.Build.VERSION.SDK_INT >= 34) PermissionRow("Allow full-screen calls so Sensei rings like a real call") {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        TextButton({ Thread { SenseiCalls.testRing(ctx) }.start() }) {
            Icon(Icons.Rounded.RingVolume, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Test: ring in 10 seconds")
        }
    }

    if (picking) {
        val state = rememberTimePickerState(dailyMin / 60, dailyMin % 60, is24Hour = false)
        AlertDialog(onDismissRequest = { picking = false },
            title = { Text("When should Sensei call?") },
            text = { TimePicker(state) },
            confirmButton = { TextButton({
                dailyMin = state.hour * 60 + state.minute; dailyOn = true; picking = false
                Thread { SenseiCalls.setDaily(ctx, true, dailyMin) }.start()
            }) { Text("Set") } },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } })
    }
}

@Composable
private fun PermissionRow(text: String, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp))
        .background(Brand.Amber.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = Brand.Amber)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onAllow) { Text("Allow") }
    }
}
