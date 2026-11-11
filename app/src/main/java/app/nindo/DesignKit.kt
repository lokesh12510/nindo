package app.nindo

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// Phase 2 design kit: plain-background pages with a large title, one two-tone hero card per
// page, rings instead of progress bars, and Material icons only (no emoji).

/** The app's two tones, used for hero cards and Sensei. */
object Tone {
    val A = Color(0xFF4338CA) // indigo
    val B = Color(0xFF7C3AED) // violet
    val hero = listOf(A, B)
    /** Guard's single summary colour. */
    val Deep = Color(0xFF312E81)
}

/** Large page title on the plain background, under the status bar. [overline] sits above it, small and spaced. */
@Composable
fun PageTitle(title: String, overline: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (overline != null) Text(overline.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        trailing()
    }
}

/** Section label between cards, with an optional action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (action != null) TextButton(onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(action) }
    }
}

/** The page's one gradient card, with a soft circle in the corner. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, colors: List<Color> = Tone.hero, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).drawBehind {
            drawRect(Brush.linearGradient(colors, start = Offset.Zero, end = Offset(size.width, size.height)))
            drawCircle(Color.White.copy(alpha = 0.07f), size.height * 0.85f, Offset(size.width * 0.98f, -size.height * 0.05f))
            drawCircle(Color.White.copy(alpha = 0.05f), size.height * 0.5f, Offset(size.width * 0.1f, size.height * 1.15f))
        }.padding(20.dp),
        content = content,
    )
}

/** A plain rounded card on the surface colour. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(padding),
        content = content)
}

/** Circular progress arc, starting at the top. */
@Composable
fun Ring(progress: Float, diameter: Dp, stroke: Dp, color: Color, track: Color, modifier: Modifier = Modifier,
         animate: Boolean = true, content: @Composable BoxScope.() -> Unit = {}) {
    val p = if (animate) animateFloatAsState(progress.coerceIn(0f, 1f), tween(700, easing = FastOutSlowInEasing), label = "ring").value
        else progress.coerceIn(0f, 1f)
    Box(modifier.size(diameter).drawBehind {
        val sw = stroke.toPx()
        val tl = Offset(sw / 2, sw / 2)
        val arc = Size(this.size.width - sw, this.size.height - sw)
        drawArc(track, -90f, 360f, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
        if (p > 0.001f) drawArc(color, -90f, 360f * p, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
    }, contentAlignment = Alignment.Center, content = content)
}

/** A ring cut into [segments] pieces, [filled] of them coloured. Count habits use it: one piece per time. */
@Composable
fun SegmentRing(segments: Int, filled: Int, diameter: Dp, stroke: Dp, color: Color, track: Color, modifier: Modifier = Modifier,
                content: @Composable BoxScope.() -> Unit = {}) {
    if (segments > 24) return Ring(filled.toFloat() / segments, diameter, stroke, color, track, modifier, content = content)
    Box(modifier.size(diameter).drawBehind {
        val sw = stroke.toPx()
        val tl = Offset(sw / 2, sw / 2)
        val arc = Size(this.size.width - sw, this.size.height - sw)
        val n = segments.coerceAtLeast(1)
        val slot = 360f / n
        val gap = if (n == 1) 0f else (slot * 0.22f).coerceAtMost(14f)
        for (i in 0 until n) {
            drawArc(if (i < filled) color else track, -90f + i * slot + gap / 2, slot - gap, false, tl, arc,
                style = Stroke(sw, cap = if (n == 1) StrokeCap.Round else StrokeCap.Butt))
        }
    }, contentAlignment = Alignment.Center, content = content)
}

/** Rounded square with a tinted icon. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, size: Dp = 42.dp, filled: Boolean = false) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(if (filled) tint else tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (filled) Color.White else tint, modifier = Modifier.size(size * 0.52f))
    }
}

// ------------------------------------------------------------ habit visuals

val HabitCategory.icon: ImageVector get() = when (this) {
    HabitCategory.HEALTH -> Icons.Rounded.Favorite
    HabitCategory.FITNESS -> Icons.Rounded.FitnessCenter
    HabitCategory.MIND -> Icons.Rounded.SelfImprovement
    HabitCategory.LEARN -> Icons.Rounded.School
    HabitCategory.WORK -> Icons.Rounded.TaskAlt
    HabitCategory.SLEEP -> Icons.Rounded.Bedtime
    HabitCategory.FOOD -> Icons.Rounded.Restaurant
    HabitCategory.WATER -> Icons.Rounded.WaterDrop
    HabitCategory.DIGITAL -> Icons.Rounded.PhoneAndroid
    HabitCategory.MONEY -> Icons.Rounded.Savings
    HabitCategory.SOCIAL -> Icons.Rounded.Groups
    HabitCategory.QUIT -> Icons.Rounded.Block
}

/** Category colours, all from the theme palette. */
val HabitCategory.color: Color get() = when (this) {
    HabitCategory.HEALTH -> Brand.Rose
    HabitCategory.FITNESS -> Brand.Amber
    HabitCategory.MIND -> Brand.Violet
    HabitCategory.LEARN -> Brand.Indigo
    HabitCategory.WORK -> Brand.Sky
    HabitCategory.SLEEP -> Tone.A
    HabitCategory.FOOD -> Brand.Green
    HabitCategory.WATER -> Brand.Sky
    HabitCategory.DIGITAL -> Brand.Teal
    HabitCategory.MONEY -> Brand.Amber
    HabitCategory.SOCIAL -> Brand.Violet
    HabitCategory.QUIT -> Brand.Rose
}

val Habit.color: Color get() = category.color

val Ranks.Rank.icon: ImageVector get() = when (index) {
    0 -> Icons.Rounded.Spa
    1 -> Icons.Rounded.SportsMartialArts
    2 -> Icons.Rounded.MilitaryTech
    3 -> Icons.Rounded.Bolt
    else -> Icons.Rounded.WorkspacePremium
}

val MissionType.icon: ImageVector get() = when (this) {
    MissionType.HABITS -> Icons.Rounded.TaskAlt
    MissionType.PERFECT -> Icons.Rounded.AutoAwesome
    MissionType.SCREEN -> Icons.Rounded.PhoneAndroid
    MissionType.REELS -> Icons.Rounded.Movie
    MissionType.FOCUS -> Icons.Rounded.CenterFocusStrong
    MissionType.SENSEI -> Icons.Rounded.SmartDisplay
}

// --------------------------------------------------------------- Sensei orb

/**
 * Sensei's presence: a two-tone orb whose colours drift around each other, with a slow
 * breathing glow and a light sweeping ring. [speaking] makes it livelier (for calls later).
 */
@Composable
fun VoiceOrb(diameter: Dp, modifier: Modifier = Modifier, speaking: Boolean = false) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(if (speaking) 2600 else 7000, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(if (speaking) 700 else 2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    Canvas(modifier.size(diameter)) {
        val c = center
        val r = size.minDimension / 2f * 0.6f * breathe
        // Glow
        drawCircle(Brush.radialGradient(listOf(Tone.B.copy(alpha = 0.32f), Color.Transparent), c, r * 1.65f), r * 1.65f, c)
        // Core: two tones, with each tone's light drifting round the other
        drawCircle(Brush.linearGradient(listOf(Tone.A, Tone.B), c - Offset(r, r), c + Offset(r, r)), r, c)
        val o = r * 0.38f
        val pa = c + Offset(cos(spin) * o, sin(spin) * o)
        val pb = c + Offset(cos(spin + Math.PI.toFloat()) * o, sin(spin + Math.PI.toFloat()) * o)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF818CF8).copy(alpha = 0.85f), Color.Transparent), pa, r * 0.9f), r, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFC084FC).copy(alpha = 0.8f), Color.Transparent), pb, r * 0.8f), r, c)
        // Sheen
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), c + Offset(-r * 0.35f, -r * 0.45f), r * 0.7f), r, c)
        // Sweeping ring
        rotate(Math.toDegrees(spin.toDouble()).toFloat(), c) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Tone.B.copy(alpha = 0.9f), Tone.A, Color.Transparent), c),
                r * 1.22f, c, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** A list row with a small share ring at the end instead of a progress bar. */
@Composable
fun ShareRow(leading: @Composable () -> Unit, title: String, subtitle: String, value: String, fraction: Float,
             color: Color = MaterialTheme.colorScheme.primary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Ring(fraction, 24.dp, 3.dp, color, color.copy(alpha = 0.15f))
    }
}

/** Small "label · value" line with an icon, for stat lists inside cards. */
@Composable
fun StatLine(icon: ImageVector, tint: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
