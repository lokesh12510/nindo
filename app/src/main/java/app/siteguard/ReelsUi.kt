@file:OptIn(ExperimentalMaterial3Api::class)

package app.siteguard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale

private data class ReelData(val today: Int = 0, val byApp: List<Pair<String, Int>> = emptyList(), val week: List<Pair<String, Int>> = emptyList())

/** Live reel/short count with the brain emoji, plus the counter's settings. */
@Composable
fun ReelCounterCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s = GuardApp.store
    val db = GuardApp.db
    val version by s.version.collectAsState()
    val data by rememberPolled(ReelData(), intervalMs = 2000) { ReelData(db.reelsToday(), db.reelsByApp(), db.reelDays(7)) }
    var cap by remember { mutableFloatStateOf(s.reelCap.toFloat()) }
    var pickApps by remember { mutableStateOf(false) }
    val locked = s.strict
    val stage = Reels.stage(data.today, s.reelCap)
    val color by animateColorAsState(Color(stage.color), tween(700), label = "stage")

    // Bounce the brain each time the count goes up; wobble when the stage changes.
    val bounce = remember { Animatable(1f) }
    val wobble = remember { Animatable(0f) }
    var lastCount by remember { mutableIntStateOf(data.today) }
    var lastEmoji by remember { mutableStateOf(stage.emoji) }
    LaunchedEffect(data.today) {
        if (data.today > lastCount) { bounce.snapTo(1.25f); bounce.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 300f)) }
        lastCount = data.today
    }
    LaunchedEffect(stage.emoji) {
        if (stage.emoji != lastEmoji) { wobble.snapTo(-20f); wobble.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 200f)) }
        lastEmoji = stage.emoji
    }
    val idle = rememberInfiniteTransition(label = "idle")
    val float by idle.animateFloat(-4f, 4f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")

    SectionCard("Reels & Shorts counter", modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                AnimatedContent(stage.emoji, label = "emoji", transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }) { e ->
                    Text(e, fontSize = 52.sp, modifier = Modifier.graphicsLayer {
                        scaleX = bounce.value; scaleY = bounce.value; rotationZ = wobble.value; translationY = float
                    })
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                AnimatedNumber(data.today, MaterialTheme.typography.displaySmall, color)
                Text(if (s.reelCap > 0) "of ${s.reelCap} reels today" else "reels & shorts today",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stage.label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
            }
        }
        if (s.reelCap > 0) {
            Spacer(Modifier.height(10.dp))
            val f by animateFloatAsState((data.today.toFloat() / s.reelCap).coerceIn(0f, 1f), tween(800), label = "cap")
            LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {})
        }
        if (data.byApp.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val max = data.byApp.maxOf { it.second }.coerceAtLeast(1)
            data.byApp.forEach { (pkg, n) ->
                UsageRow({ AppIcon(pkg, 34.dp) }, Apps.label(ctx, pkg), "≈ ${fmtDuration(n * 20_000L)} watching", "$n", n.toFloat() / max, color)
            }
        }
        if (data.week.any { it.second > 0 }) {
            Spacer(Modifier.height(10.dp))
            val inF = SimpleDateFormat("yyyy-MM-dd", Locale.US); val outF = SimpleDateFormat("EEE", Locale.getDefault())
            BarChart(data.week.map { it.second.toFloat() }, data.week.map { outF.format(inF.parse(it.first)!!) },
                highlight = data.week.size - 1, height = 80.dp, color = Brand.Amber)
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        key(version) {
            LabeledSwitch("Count reels & shorts", "Instagram, YouTube, Facebook, Snapchat, TikTok, Moj, Josh…", s.reelsOn) {
                if (!locked || it) s.reelsOn = it
            }
            AnimatedVisibility(s.reelsOn) {
                Column {
                    LabeledSwitch("Floating brain bubble", "Shows the live count while you watch. Drag to move, tap for details.", s.reelBubble) { s.reelBubble = it }
                    LabeledSwitch("Milestone popups", "Every 25 reels, 10 before the limit, and at the limit", s.reelMilestones) { s.reelMilestones = it }
                    SliderRow("Daily reel limit", if (cap.toInt() == 0) "No limit" else "${cap.toInt()} reels", cap, 0f..300f, 29) {
                        // In strict mode the limit can only get tighter.
                        if (!locked || (it.toInt() in 1..s.reelCap.coerceAtLeast(1) && s.reelCap > 0)) cap = it
                    }
                    LaunchedEffect(cap) { s.reelCap = cap.toInt() }
                    AnimatedVisibility(cap.toInt() > 0) {
                        Column {
                            Text("When the limit is reached", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(!s.reelCapWholeApp, { if (!locked) s.reelCapWholeApp = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Close reels only") }
                                SegmentedButton(s.reelCapWholeApp, { s.reelCapWholeApp = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Block whole app") }
                            }
                            Text(
                                if (s.reelCapWholeApp) "Instagram, YouTube and the rest are blocked until midnight."
                                else "Only the Reels/Shorts screen closes; chats, posts and normal videos keep working. Apps that are only short videos (TikTok, Moj, Josh) are blocked.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    OutlinedButton({ pickApps = true }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !locked) {
                        Icon(Icons.Rounded.Apps, null); Spacer(Modifier.width(8.dp)); Text("Counted apps (${s.reelApps.size})")
                    }
                }
            }
        }
    }
    if (pickApps) AppPicker(single = false, selected = s.reelApps, onDismiss = { pickApps = false }) { s.reelApps = it; pickApps = false }
}
