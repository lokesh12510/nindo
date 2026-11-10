package app.nindo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.composed
import androidx.compose.foundation.LocalIndication

// ---------------------------------------------------------------- theme

object Brand {
    val Indigo = Color(0xFF4F46E5)
    val Violet = Color(0xFF9333EA)
    val Teal = Color(0xFF14B8A6)
    val Amber = Color(0xFFF59E0B)
    val Rose = Color(0xFFF43F5E)
    val Sky = Color(0xFF0EA5E9)
    val Green = Color(0xFF22C55E)
}

@Composable
fun GuardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFA5B4FC), onPrimary = Color(0xFF1E1B4B),
        primaryContainer = Color(0xFF312E81), onPrimaryContainer = Color(0xFFE0E7FF),
        secondary = Color(0xFF5EEAD4), tertiary = Color(0xFFF0ABFC),
        background = Color(0xFF0E0F1A), surface = Color(0xFF0E0F1A),
        surfaceContainer = Color(0xFF181A2A), surfaceContainerHigh = Color(0xFF20233A),
        surfaceContainerLow = Color(0xFF14162A), surfaceVariant = Color(0xFF262A44),
        error = Color(0xFFFDA4AF),
    ) else lightColorScheme(
        primary = Brand.Indigo, onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
        secondary = Color(0xFF0D9488), tertiary = Color(0xFFA21CAF),
        background = Color(0xFFF6F6FB), surface = Color(0xFFF6F6FB),
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF0F0F8),
        surfaceContainerLow = Color(0xFFFAFAFF), surfaceVariant = Color(0xFFE9E9F3),
        error = Color(0xFFE11D48),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

// -------------------------------------------------------------- helpers

/** Loads data off the main thread and reloads it every [intervalMs] or when [key] changes. */
@Composable
fun <T> rememberPolled(initial: T, key: Any? = null, intervalMs: Long = 4000, load: () -> T): State<T> =
    produceState(initial, key) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching(load).getOrDefault(value) }
            delay(intervalMs)
        }
    }

/** Fades and slides content in once, staggered by [index]. */
fun Modifier.enter(index: Int): Modifier = composed {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(40L * index.coerceAtMost(10))
        anim.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = anim.value
        translationY = (1f - anim.value) * 40f
    }
}

/** Shrinks slightly while pressed for tactile feedback. */
fun Modifier.pressable(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "press")
    scale(s).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
}

// ------------------------------------------------------------ components

/** Gradient banner whose colours slowly drift. */
@Composable
fun AuroraHeader(
    title: String,
    subtitle: String? = null,
    colors: List<Color> = listOf(Brand.Indigo, Brand.Violet, Color(0xFFDB2777)),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val t = rememberInfiniteTransition(label = "aurora")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .background(
                Brush.linearGradient(
                    colors,
                    start = Offset(-400f + 800f * shift, 0f),
                    end = Offset(900f + 800f * shift, 900f),
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        content()
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    action?.invoke()
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun AnimatedNumber(value: Int, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified, suffix: String = "") {
    val v by animateIntAsState(value, tween(900, easing = FastOutSlowInEasing), label = "num")
    Text("$v$suffix", style = style, color = color, fontWeight = FontWeight.Bold)
}

@Composable
fun AnimatedDuration(ms: Long, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified) {
    val v by animateFloatAsState(ms.toFloat(), tween(900, easing = FastOutSlowInEasing), label = "dur")
    Text(fmtDuration(v.toLong()), style = style, color = color, fontWeight = FontWeight.Bold)
}

@Composable
fun StatTile(icon: ImageVector, label: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit = {}, value: @Composable () -> Unit) {
    Card(
        modifier = modifier.pressable(onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.height(10.dp))
            value()
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Circular progress with a gradient sweep that animates in. */
@Composable
fun UsageRing(progress: Float, size: Dp = 150.dp, stroke: Dp = 14.dp, center: @Composable () -> Unit) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(1400, easing = FastOutSlowInEasing), label = "ring")
    val over = progress > 1f
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "glow")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
            drawArc(
                Brush.sweepGradient(listOf(Color(0xFF5EEAD4), Color.White, if (over) Color(0xFFFDA4AF) else Color(0xFFC4B5FD), Color(0xFF5EEAD4))),
                -90f, 360f * p, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round)
            )
            if (over) drawCircle(Color(0xFFFDA4AF).copy(alpha = glow * 0.35f), radius = this.size.minDimension / 2 - sw * 1.6f)
        }
        center()
    }
}

/** Bars that grow in one after another. */
@Composable
fun BarChart(values: List<Float>, labels: List<String>, highlight: Int = -1, height: Dp = 120.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val anims = remember(values.size) { values.map { Animatable(0f) } }
    LaunchedEffect(values) {
        anims.forEachIndexed { i, a ->
            launch { delay(i * 25L); a.animateTo(values[i] / max, spring(dampingRatio = 0.7f, stiffness = 120f)) }
        }
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val bw = slot * 0.62f
            for (i in 0 until n) {
                val x = i * slot + (slot - bw) / 2
                drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), CornerRadius(bw / 2))
                val h = size.height * anims.getOrNull(i)?.value.orZero()
                if (h > 0f) drawRoundRect(
                    Brush.verticalGradient(
                        if (i == highlight) listOf(Brand.Violet, color) else listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f)),
                        startY = size.height - h, endY = size.height
                    ),
                    Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 2)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(it, Modifier.weight(1f), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
            }
        }
    }
}

private fun Float?.orZero() = this ?: 0f


@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, pkg) { value = withContext(Dispatchers.IO) { Apps.icon(ctx, pkg) } }
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize())
        else Icon(Icons.Rounded.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** A row with an icon, labels and an animated proportional bar. */
@Composable
fun UsageRow(leading: @Composable () -> Unit, title: String, subtitle: String, value: String, fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(1000, easing = FastOutSlowInEasing), label = "bar")
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(color, Brand.Violet))))
            }
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** A pulsing dot that shows whether a protection is on. */
@Composable
fun StatusDot(on: Boolean) {
    val c by animateColorAsState(if (on) Brand.Green else Brand.Rose, label = "dot")
    val t = rememberInfiniteTransition(label = "dot")
    val r by t.animateFloat(0.6f, 1.4f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "r")
    val a by t.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "a")
    Canvas(Modifier.size(18.dp)) {
        if (on) drawCircle(c.copy(alpha = a), radius = size.minDimension / 2 * r)
        drawCircle(c, radius = size.minDimension / 4)
    }
}

@Composable
fun EmptyHint(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val t = rememberInfiniteTransition(label = "float")
        val y by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y")
        Icon(icon, null, Modifier.size(48.dp).graphicsLayer { translationY = y }, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        Spacer(Modifier.height(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun LabeledSwitch(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SliderRow(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(value, onChange, valueRange = range, steps = steps)
    }
}
