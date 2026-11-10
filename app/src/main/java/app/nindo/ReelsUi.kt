@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.nindo

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
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

    SectionCard("Reels & Shorts counter", modifier) {
        ReelGauge(data.today, s.reelCap, color, stage.label, bounce.value)
        if (data.byApp.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val total = data.byApp.sumOf { it.second }.coerceAtLeast(1)
            data.byApp.forEach { (pkg, n) ->
                ShareRow({ AppIcon(pkg, 34.dp) }, Apps.label(ctx, pkg), "≈ ${fmtDuration(n * 20_000L)} watching", "$n", n.toFloat() / total, color)
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
                    LabeledSwitch("Floating brain bubble", "Pops up for a second every 10 reels with your count. Drag to move, tap for details.", s.reelBubble) { s.reelBubble = it }
                    LabeledSwitch("Milestone popups", "Every 25 reels, 10 before the limit, and at the limit", s.reelMilestones) { s.reelMilestones = it }
                    SliderRow("Daily reel limit", if (cap.toInt() == 0) "No limit" else "${cap.toInt()} reels", cap, 0f..300f, 29) {
                        // In strict mode the limit can only get tighter.
                        if (!locked || (it.toInt() in 1..s.reelCap.coerceAtLeast(1) && s.reelCap > 0)) cap = it
                    }
                    LaunchedEffect(cap) { if (s.reelCap != cap.toInt()) { kotlinx.coroutines.delay(350); s.reelCap = cap.toInt() } }
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
                    // Counted apps as tap-to-toggle chips: installed reel apps first, then anything else chosen.
                    Text("Counted apps", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    val installed by rememberPolled(emptyList<String>(), version) {
                        val pm = ctx.packageManager
                        (Reels.DEFAULT_APPS + s.reelApps).filter { p -> runCatching { pm.getApplicationInfo(p, 0) }.isSuccess }
                            .sortedBy { Apps.label(ctx, it).lowercase() }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        installed.forEach { pkg ->
                            val on = pkg in s.reelApps
                            FilterChip(on, {
                                // In strict mode apps can be added but not removed.
                                if (on && !locked) s.reelApps = s.reelApps - pkg else if (!on) s.reelApps = s.reelApps + pkg
                            }, { Text(Apps.label(ctx, pkg)) }, leadingIcon = { AppIcon(pkg, 20.dp) })
                        }
                        AssistChip({ pickApps = true }, { Text("More apps") }, leadingIcon = { Icon(Icons.Rounded.Apps, null, Modifier.size(18.dp)) })
                    }
                    if (locked) Text("Strict mode: you can add apps, not remove them.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (pickApps) AppPicker(single = false, selected = s.reelApps, onDismiss = { pickApps = false }) {
        s.reelApps = if (locked) s.reelApps + it else it
        pickApps = false
    }
}


/** Stage colours, mild to severe, matching Reels.stage. */
private val GAUGE = listOf(0xFF16A34A, 0xFF65A30D, 0xFFD97706, 0xFFEA580C, 0xFFDC2626, 0xFF7C3AED)

/**
 * A half-circle gauge in six stage segments that fill as reels add up, with a knob at today's
 * count. The number and the brain stage sit inside the arc.
 */
@Composable
private fun ReelGauge(count: Int, cap: Int, color: Color, label: String, bounce: Float) {
    val max = if (cap > 0) cap else 100
    val f by animateFloatAsState((count.toFloat() / max).coerceIn(0f, 1f), tween(800, easing = FastOutSlowInEasing), label = "gauge")
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val sw = 18.dp.toPx()
            val d = minOf(size.width - sw * 2, (size.height - sw) * 2)
            val tl = Offset((size.width - d) / 2, sw / 2 + 4.dp.toPx())
            val arc = Size(d, d)
            val seg = 180f / GAUGE.size
            val gap = 2.5f
            val filledDeg = 180f * f
            GAUGE.forEachIndexed { i, c ->
                val start = 180f + i * seg
                drawArc(Color(c).copy(alpha = 0.16f), start + gap / 2, seg - gap, false, tl, arc, style = Stroke(sw))
                val fill = (filledDeg - i * seg).coerceIn(0f, seg) - gap
                if (fill > 0f) drawArc(Color(c), start + gap / 2, fill, false, tl, arc, style = Stroke(sw))
            }
            val r = d / 2
            val c = tl + Offset(r, r)
            val a = Math.toRadians((180.0 + filledDeg))
            val knob = c + Offset((r * kotlin.math.cos(a)).toFloat(), (r * kotlin.math.sin(a)).toFloat())
            drawCircle(Color.White, sw * 0.75f, knob)
            drawCircle(color, sw * 0.48f, knob)
        }
        Column(Modifier.padding(bottom = 6.dp).graphicsLayer { scaleX = bounce; scaleY = bounce },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Psychology, null, tint = color, modifier = Modifier.size(26.dp))
            AnimatedNumber(count, MaterialTheme.typography.displaySmall, color)
            Text(if (cap > 0) "of $cap reels today" else "reels & shorts today", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}
