@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.nindo

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** Ticks once a second while shown; returns the current time. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    return now
}

@Composable
fun StrictBanner(text: String) {
    val s = GuardApp.store
    AnimatedVisibility(s.strict) {
        Surface(color = Brand.Violet.copy(alpha = 0.14f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = Brand.Violet, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Strict mode until ${fmtUntil(s.strictUntil)} · $text", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ================================================================= focus card

@Composable
fun FocusSessionCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s = GuardApp.store
    val version by s.version.collectAsState()
    val now = rememberNow()
    LaunchedEffect(now / 5000) { Focus.check(ctx) }
    var minutes by remember { mutableIntStateOf(s.focusDefaultMin) }
    var lock by remember { mutableStateOf(false) }
    var pickApps by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Focus.rearm(ctx) }

    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF312E81), Color(0xFF6D28D9), Color(0xFF0F766E)))).padding(20.dp)) {
            AnimatedContent(s.focusActive, label = "focus", transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() }) { active ->
                if (active) {
                    val total = (s.focusUntil - s.focusStart).coerceAtLeast(1)
                    val left = (s.focusUntil - now).coerceAtLeast(0)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Focus session", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(12.dp))
                        CountdownRing(left.toFloat() / total) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(String.format(Locale.US, "%d:%02d", left / 60_000, (left / 1000) % 60), color = Color.White,
                                    fontSize = 40.sp, fontWeight = FontWeight.Bold)
                                Text("until ${fmtClock(s.focusUntil)}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Blocking ${s.focusApps.size} apps + ${s.focusCategories.joinToString { Blocklists.category(it)?.name ?: it }}",
                            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        if (Focus.canStop(s)) OutlinedButton({ Focus.stop(ctx) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Stop early") }
                        else Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (s.strict) "Locked by strict mode" else "Locked session", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CenterFocusStrong, null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Focus session", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        Text("Block distractions for a while, in one tap.", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 25, 45, 60, 90).forEach { m ->
                                val sel = m == minutes
                                val bg by animateColorAsState(if (sel) Color.White else Color.White.copy(alpha = 0.16f), label = "chip")
                                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg).pressable { minutes = m }.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text("$m", color = if (sel) Color(0xFF312E81) else Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text("minutes", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(10.dp))
                        key(version) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip({ pickApps = true }, { Text("${s.focusApps.size} apps", color = Color.White) },
                                    leadingIcon = { Icon(Icons.Rounded.Apps, null, tint = Color.White, modifier = Modifier.size(16.dp)) },
                                    border = AssistChipDefaults.assistChipBorder(true, borderColor = Color.White.copy(alpha = 0.4f)))
                                Blocklists.CATEGORIES.forEach { c ->
                                    val on = c.id in s.focusCategories
                                    FilterChip(on, { s.focusCategories = if (on) s.focusCategories - c.id else s.focusCategories + c.id },
                                        { Text(c.name) },
                                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = Color.White,
                                            selectedLabelColor = Color(0xFF312E81)),
                                        border = FilterChipDefaults.filterChipBorder(true, on, borderColor = Color.White.copy(alpha = 0.4f)))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Lock it (no stopping early)", color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(lock, { lock = it })
                        }
                        Spacer(Modifier.height(8.dp))
                        val pulse = rememberInfiniteTransition(label = "start")
                        val sc by pulse.animateFloat(1f, 1.03f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "sc")
                        Button({
                            if (!Notify.allowed(ctx) && android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                            s.focusDefaultMin = minutes
                            Focus.start(ctx, minutes, lock)
                        }, Modifier.fillMaxWidth().height(54.dp).graphicsLayer { scaleX = sc; scaleY = sc }, shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF312E81))) {
                            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start $minutes min focus", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    if (pickApps) AppPicker(single = false, selected = s.focusApps, onDismiss = { pickApps = false }) { s.focusApps = it; pickApps = false }
    if (refused) AlertDialog({ refused = false }, confirmButton = { TextButton({ refused = false }) { Text("OK") } }, text = { Text("This session is locked.") })
}

@Composable
private fun CountdownRing(fraction: Float, content: @Composable () -> Unit) {
    val f by animateFloatAsState(fraction, tween(900), label = "cd")
    val t = rememberInfiniteTransition(label = "orbit")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "a")
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 14.dp.toPx(); val inset = sw / 2
            val sz = Size(size.width - sw, size.height - sw)
            drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(sw))
            drawArc(Brush.sweepGradient(listOf(Color(0xFF5EEAD4), Color.White, Color(0xFFC4B5FD), Color(0xFF5EEAD4))),
                -90f, 360f * f, false, Offset(inset, inset), sz, style = Stroke(sw, cap = StrokeCap.Round))
            val r = size.minDimension / 2 - sw * 1.8f
            val rad = Math.toRadians(angle.toDouble())
            drawCircle(Color.White.copy(alpha = 0.7f), 4.dp.toPx(), Offset(center.x + r * Math.cos(rad).toFloat(), center.y + r * Math.sin(rad).toFloat()))
        }
        content()
    }
}

// ================================================================ streak card

@Composable
fun StreakCard(modifier: Modifier, onReport: () -> Unit) {
    val streak by rememberPolled(Streaks.Result(0, 0, true, 0, 1)) { Streaks.load() }
    val t = rememberInfiniteTransition(label = "flame")
    val flick by t.animateFloat(0.9f, 1.12f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "f")
    val tilt by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "t")
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.weight(1.2f).pressable(onReport), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔥", fontSize = 34.sp, modifier = Modifier.graphicsLayer {
                    val sc = if (streak.current > 0) flick else 1f; scaleX = sc; scaleY = sc; rotationZ = if (streak.current > 0) tilt else 0f
                    alpha = if (streak.current > 0) 1f else 0.4f
                })
                Spacer(Modifier.width(10.dp))
                Column {
                    AnimatedNumber(streak.current, MaterialTheme.typography.headlineSmall, suffix = if (streak.current == 1) " day" else " days")
                    Text("streak · best ${streak.best}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (streak.todayOk) "Today on track" else "Over goal today",
                        style = MaterialTheme.typography.labelSmall, color = if (streak.todayOk) Brand.Green else Brand.Rose)
                }
            }
        }
        Card(Modifier.weight(1f).pressable(onReport), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Icon(Icons.Rounded.Assessment, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(8.dp))
                Text("Weekly report", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Last 7 days →", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
        }
    }
}

// ============================================================== weekly report

@Composable
fun ReportSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s = GuardApp.store
    val report by produceState<WeekReport?>(null) { value = withContext(Dispatchers.IO) { runCatching { Reports.build() }.getOrNull() } }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val r = report
        if (r == null) { Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@ModalBottomSheet }
        val inF = SimpleDateFormat("yyyy-MM-dd", Locale.US); val outF = SimpleDateFormat("d MMM", Locale.getDefault())
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Your week", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${outF.format(inF.parse(r.from)!!)} – ${outF.format(inF.parse(r.to)!!)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedDuration(r.screenMs, MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(12.dp))
                r.changePct?.let { pct ->
                    val good = pct <= 0
                    Surface(color = (if (good) Brand.Green else Brand.Rose).copy(alpha = 0.15f), shape = CircleShape) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (good) Icons.Rounded.TrendingDown else Icons.Rounded.TrendingUp, null, tint = if (good) Brand.Green else Brand.Rose,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${kotlin.math.abs(pct)}% vs last week", color = if (good) Brand.Green else Brand.Rose, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Text("screen time", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            val dayF = SimpleDateFormat("EEE", Locale.getDefault())
            BarChart(r.daily.map { it.second.toFloat() }, r.daily.map { dayF.format(inF.parse(it.first)!!) }, highlight = r.daily.size - 1, height = 120.dp)
            Spacer(Modifier.height(16.dp))
            val tiles = listOf(
                Triple(Icons.Rounded.CenterFocusStrong, "Focus", fmtDuration(r.focusMs)),
                Triple(Icons.Rounded.Swipe, "Scrolling", fmtDuration(r.scrollMs)),
                Triple(Icons.Rounded.TouchApp, "Opens", r.opens.toString()),
                Triple(Icons.Rounded.Block, "Blocks", r.blocks.toString()),
                Triple(Icons.Rounded.Psychology, "Reels", r.reels.toString()),
                Triple(Icons.Rounded.LocalFireDepartment, "Streak", "${r.streak.current}d"),
            )
            tiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    row.forEachIndexed { i, (icon, label, value) ->
                        Card(Modifier.weight(1f).enter(i), shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Column(Modifier.padding(12.dp)) {
                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (r.topApps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Top apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val max = r.topApps.maxOf { it.ms }.coerceAtLeast(1)
                r.topApps.forEach { a -> UsageRow({ AppIcon(a.pkg) }, Apps.label(ctx, a.pkg), "${a.opens} opens", fmtDuration(a.ms), a.ms.toFloat() / max) }
            }
            if (r.tamper > 0) {
                Spacer(Modifier.height(8.dp))
                Text("⚠️ ${r.tamper} protection change(s) this week", color = Brand.Amber, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({
                    val text = Reports.text(ctx, r)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share report"))
                }, Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                if (s.hasPartner) Button({ Partner.send(ctx, Reports.text(ctx, r)) }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Send, null); Spacer(Modifier.width(6.dp)); Text("To ${s.partnerName}")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("A report also arrives every Sunday at 8 pm.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// =============================================================== strict mode

@Composable
fun StrictCard(watcherOn: Boolean) {
    val ctx = LocalContext.current
    val s = GuardApp.store
    val version by s.version.collectAsState()
    val now = rememberNow()
    var hours by remember { mutableIntStateOf(24) }
    var partnerEnd by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    var pinEntry by remember { mutableStateOf(false) }
    var isAdmin by remember { mutableStateOf(Strict.isAdmin(ctx)) }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isAdmin = Strict.isAdmin(ctx) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { isAdmin = Strict.isAdmin(ctx) }
    val active = s.strict && now < s.strictUntil

    val glow = rememberInfiniteTransition(label = "strict")
    val g by glow.animateFloat(0.3f, 0.8f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "g")
    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(2.dp, if (active) Brand.Violet.copy(alpha = g) else Color.Transparent)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.GppGood, if (active) Brand.Violet else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Strict mode", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusDot(active)
                Text(if (active) " On" else " Off", style = MaterialTheme.typography.labelLarge, color = if (active) Brand.Green else Brand.Rose)
            }
            Spacer(Modifier.height(6.dp))
            key(version) {
                if (active) {
                    val left = s.strictUntil - now
                    Text("Locked until ${fmtUntil(s.strictUntil)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Brand.Violet)
                    Text("${left / 3_600_000}h ${(left / 60_000) % 60}m ${(left / 1000) % 60}s left", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Rules, the VPN, the doom-scroll guard and Nindō's system settings are locked. You can still add new rules.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.strictPartnerCanEnd) TextButton({ pinEntry = true }) { Text("End early with ${s.partnerName.ifBlank { "partner" }}'s PIN") }
                } else {
                    Text("Locks your protections for a set time so you can't switch them off in a weak moment: rules can't be loosened, the VPN and guard stay on, and screens for uninstalling or disabling Nindō are closed.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1 to "1 hour", 4 to "4 hours", 12 to "12 hours", 24 to "1 day", 72 to "3 days", 168 to "1 week").forEach { (h, l) ->
                            FilterChip(hours == h, { hours = h }, { Text(l) })
                        }
                    }
                    val canPartner = s.partnerPinHash != null
                    LabeledSwitch("Partner PIN can end it early", if (canPartner) "${s.partnerName} can unlock you" else "Add a partner and their PIN first",
                        partnerEnd && canPartner) { if (canPartner) partnerEnd = it }
                    if (!watcherOn) Text("Turn on the watcher first — strict mode depends on it.", color = Brand.Amber, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Button({ confirm = true }, Modifier.fillMaxWidth().height(50.dp), enabled = watcherOn, shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Violet)) {
                        Icon(Icons.Rounded.Lock, null); Spacer(Modifier.width(8.dp)); Text("Start strict mode")
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Uninstall protection", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(if (isAdmin) "On — Nindō can't be uninstalled directly" else "Off — recommended with strict mode",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!isAdmin) FilledTonalButton({ adminLauncher.launch(Strict.adminIntent(ctx)) }) { Text("Turn on") }
                else Icon(Icons.Rounded.CheckCircle, null, tint = Brand.Green)
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text("Lock for ${if (hours < 24) "$hours h" else "${hours / 24} day(s)"}?") },
        text = {
            Text("Until ${fmtUntil(System.currentTimeMillis() + hours * 3_600_000L)} you won't be able to turn off rules, the VPN filter, " +
                "the doom-scroll guard or the PIN, or open Nindō's system settings." +
                if (partnerEnd && s.partnerPinHash != null) " Only ${s.partnerName}'s PIN can end it early." else " It can't be ended early.")
        },
        confirmButton = { Button({ Strict.start(ctx, hours * 3_600_000L, partnerEnd); confirm = false }) { Text("Lock it") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
    if (pinEntry) PartnerPinDialog("Partner PIN to end strict mode", verify = true, onClose = { pinEntry = false }) { pin ->
        Strict.endWithPin(ctx, pin)
    }
}

/** Asks for a PIN. With [verify] it checks; otherwise it asks twice and returns the new PIN. */
@Composable
fun PartnerPinDialog(title: String, verify: Boolean, onClose: () -> Unit, onPin: (String) -> Boolean) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Rounded.Handshake, null) },
        title = { Text(title) },
        text = {
            Column(Modifier.graphicsLayer { translationX = shake.value }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!verify) Text("Hand the phone to your partner. Only they should know this PIN.", style = MaterialTheme.typography.bodySmall)
                val fields = if (verify) listOf("PIN" to a) else listOf("Partner PIN (4–8 digits)" to a, "Repeat" to b)
                fields.forEachIndexed { i, (label, v) ->
                    OutlinedTextField(v, { x -> val d = x.filter(Char::isDigit).take(8); if (i == 0) a = d else b = d; wrong = false },
                        label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                }
                if (wrong) Text(if (verify) "Wrong PIN" else "PINs don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = a.length in 4..8 && (verify || b.isNotEmpty()), onClick = {
                val ok = (verify || a == b) && onPin(a)
                if (ok) onClose() else {
                    wrong = true
                    scope.launch { for (x in listOf(20f, -16f, 10f, -5f, 0f)) shake.animateTo(x, tween(50)) }
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

// ===================================================================== partner

@Composable
fun PartnerCard() {
    val ctx = LocalContext.current
    val s = GuardApp.store
    val version by s.version.collectAsState()
    var setup by remember { mutableStateOf(false) }
    var setPin by remember { mutableStateOf(false) }
    var verifyOld by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) s.partnerAutoSms = false }
    val locked = s.strict

    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Handshake, Brand.Teal)
                Spacer(Modifier.width(12.dp))
                Text("Accountability partner", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            key(version) {
                if (!s.hasPartner) {
                    Text("Someone you trust gets your weekly report and is told if a protection is turned off. Their PIN can also end strict mode early. Ask them first.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button({ setup = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Add partner") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Brand.Teal, Brand.Indigo))), contentAlignment = Alignment.Center) {
                            Text(s.partnerName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.partnerName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(if (s.partnerAutoSms) "SMS to ${s.partnerPhone}" else "You'll get a notification to send each message",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!locked) IconButton({ setup = true }) { Icon(Icons.Rounded.Edit, null) }
                    }
                    Spacer(Modifier.height(6.dp))
                    LabeledSwitch("Send weekly report", null, s.partnerWeekly) { if (!locked || it) s.partnerWeekly = it }
                    LabeledSwitch("Alert when protections change", "Watcher, VPN or uninstall protection turned off; strict-mode bypass attempts",
                        s.partnerAlerts) { if (!locked || it) s.partnerAlerts = it }
                    LabeledSwitch("Send automatically by SMS", if (s.partnerPhone.isBlank()) "Add a phone number to use SMS" else "Uses your SIM; normal SMS charges apply",
                        s.partnerAutoSms) { on ->
                        if (on && s.partnerPhone.isNotBlank()) { s.partnerAutoSms = true; if (!Partner.canSms(ctx)) smsPerm.launch(Manifest.permission.SEND_SMS) }
                        else if (!on && !locked) s.partnerAutoSms = false
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ if (s.partnerPinHash == null) setPin = true else verifyOld = true }, Modifier.weight(1f), enabled = !locked) {
                            Text(if (s.partnerPinHash == null) "Set partner PIN" else "Reset partner PIN")
                        }
                        OutlinedButton({ Partner.send(ctx, "Hi! I've added you as my Nindō accountability partner. You'll get my weekly screen-time report and alerts if I turn protections off."); sent = true },
                            Modifier.weight(1f)) { Text(if (sent) "Sent ✓" else "Send hello") }
                    }
                    if (s.partnerPinHash == null) Text("No partner PIN yet — needed for ending strict mode early.", style = MaterialTheme.typography.bodySmall, color = Brand.Amber)
                    if (!locked) TextButton({ s.partnerName = ""; s.partnerPhone = ""; s.partnerPinHash = null; s.partnerAutoSms = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove partner") }
                }
            }
        }
    }
    if (setup) PartnerSetupDialog { setup = false; if (s.hasPartner && s.partnerPinHash == null) setPin = true }
    if (verifyOld) PartnerPinDialog("Partner: enter your current PIN", verify = true, onClose = { verifyOld = false }) { pin ->
        Partner.checkPin(pin).also { if (it) setPin = true }
    }
    if (setPin) PartnerPinDialog("Partner: set your PIN", verify = false, onClose = { setPin = false }) { pin ->
        s.partnerPinHash = Partner.hash(pin); true
    }
}

@Composable
private fun PartnerSetupDialog(onClose: () -> Unit) {
    val s = GuardApp.store
    var me by remember { mutableStateOf(s.ownerName) }
    var name by remember { mutableStateOf(s.partnerName) }
    var phone by remember { mutableStateOf(s.partnerPhone) }
    var agreed by remember { mutableStateOf(s.hasPartner) }
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Rounded.Handshake, null) },
        title = { Text("Accountability partner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(me, { me = it }, label = { Text("Your name (shown in messages)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Partner's name") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' } }, label = { Text("Partner's mobile (for SMS, optional)") },
                    singleLine = true, shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(agreed, { agreed = it })
                    Text("They've agreed to be my partner", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && agreed, onClick = {
                s.ownerName = me.trim(); s.partnerName = name.trim(); s.partnerPhone = phone.trim(); onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

// ================================================================= blocklists

@Composable
fun BlocklistsCard() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Category, Brand.Violet)
                Spacer(Modifier.width(12.dp))
                Text("Ready-made blocklists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text("Use them in Rules → List, and in focus sessions. Some lists can download a much larger community list.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            key(tick) {
                Blocklists.CATEGORIES.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            val dl = Blocklists.downloadedCount(c.id)
                            Text("${"%,d".format(Blocklists.totalCount(c.id))} sites" + (if (c.apps.isNotEmpty() || c.playGames) " · apps too" else "") +
                                (if (dl > 0) " · full list" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (c.remoteUrl != null) {
                            AnimatedContent(busy == c.id, label = "dl") { loading ->
                                if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                else FilledTonalButton({
                                    busy = c.id; error = null
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { runCatching { Blocklists.download(c.id) } }
                                        if (r.isFailure) error = "Couldn't download ${c.name}: ${r.exceptionOrNull()?.message}"
                                        busy = null; tick++
                                    }
                                }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    Text(if (Blocklists.downloadedCount(c.id) > 0) "Update" else "Get full list")
                                }
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Full lists: StevenBlack/hosts (GitHub).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
