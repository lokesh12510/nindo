package app.nindo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Shown every time the app opens until the watcher and Usage Access are both on.
 * "Not now" hides it until the next launch. Also re-syncs usage history on every resume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupGate() {
    val ctx = LocalContext.current
    val watcherOn by UrlWatcherService.running.collectAsState()
    var a11yOn by remember { mutableStateOf(Watcher.enabledInSettings(ctx)) }
    var usageOk by remember { mutableStateOf(UsageSync.hasAccess(ctx)) }
    var dismissed by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        a11yOn = Watcher.enabledInSettings(ctx)
        usageOk = UsageSync.hasAccess(ctx)
        UsageSync.syncAsync(ctx)
    }

    val watcherReady = watcherOn || a11yOn
    if (dismissed || (watcherReady && usageOk)) return

    ModalBottomSheet(
        onDismissRequest = { dismissed = true },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Set up Nindō", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Two switches let Nindō see your activity. Everything stays on this phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

            SetupStep(1, Icons.Rounded.Visibility, Brand.Indigo, "Turn on the watcher",
                "Accessibility → Nindō. Tracks apps, sites and scrolling live, and does the blocking.",
                done = watcherReady, action = "Turn on") { Watcher.openSettings(ctx) }
            AnimatedVisibility(!watcherReady) {
                Column(Modifier.padding(start = 52.dp, top = 4.dp)) {
                    Text("Switch greyed out (\"Restricted setting\")? Open App info → ⋮ → Allow restricted settings, then try again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ Watcher.openAppInfo(ctx) }, contentPadding = PaddingValues(0.dp)) { Text("Open App info") }
                }
            }
            Spacer(Modifier.height(12.dp))

            SetupStep(2, Icons.Rounded.History, Brand.Teal, "Allow usage access",
                "Brings in the last 7 days of app usage, so Today and Activity aren't empty.",
                done = usageOk, action = "Allow") { UsageSync.openSettings(ctx) }

            Spacer(Modifier.height(20.dp))
            TextButton({ dismissed = true }, Modifier.align(Alignment.End)) { Text("Not now") }
        }
    }
}

@Composable
private fun SetupStep(
    n: Int, icon: ImageVector, color: androidx.compose.ui.graphics.Color,
    title: String, body: String, done: Boolean, action: String, onClick: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$n. $title", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            AnimatedContent(done, transitionSpec = { (fadeIn() + scaleIn()) togetherWith fadeOut() }, label = "step$n") { ok ->
                if (ok) Icon(Icons.Rounded.CheckCircle, "Done", tint = Brand.Green, modifier = Modifier.size(32.dp))
                else FilledTonalButton(onClick) { Text(action) }
            }
        }
    }
}
