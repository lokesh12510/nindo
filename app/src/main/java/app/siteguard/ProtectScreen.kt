@file:OptIn(ExperimentalMaterial3Api::class)

package app.siteguard

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
fun ProtectScreen() {
    val ctx = LocalContext.current
    val store = GuardApp.store
    val version by store.version.collectAsState()
    val watcherOn by UrlWatcherService.running.collectAsState()
    val vpnOn by DnsVpnService.running.collectAsState()
    var locPerm by remember { mutableStateOf(LocationTracker.hasPermission(ctx)) }
    var bgPerm by remember { mutableStateOf(LocationTracker.hasBackground(ctx)) }
    var pinDialog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        locPerm = LocationTracker.hasPermission(ctx); bgPerm = LocationTracker.hasBackground(ctx)
        if (locPerm) LocationTracker.start(ctx)
    }

    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(ctx) == null) DnsVpnService.start(ctx)
    }
    val fgLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locPerm = LocationTracker.hasPermission(ctx); if (locPerm) LocationTracker.start(ctx)
    }
    val bgLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        bgPerm = LocationTracker.hasBackground(ctx)
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val score = listOf(watcherOn, vpnOn, store.strict, store.hasPartner, store.pin != null).count { it }
            AuroraHeader("Protection", "$score of 5 protections on",
                colors = listOf(Color(0xFF7C2D12), Color(0xFFDB2777), Brand.Violet)) {
                Spacer(Modifier.height(12.dp))
                val p by androidx.compose.animation.core.animateFloatAsState(score / 5f, label = "score")
                LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(8.dp).clipRound(),
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.25f), drawStopIndicator = {})
            }
        }

        item { key(version) { StrictCard(watcherOn) } }

        item {
            ProtectionCard(Icons.Rounded.Visibility, "Watcher", watcherOn, 1,
                "Tracks apps, browser sites and scrolling, and blocks apps and pages.") {
                Button({ ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.fillMaxWidth()) {
                    Text(if (watcherOn) "Open Accessibility settings" else "Turn on in Accessibility settings")
                }
                AnimatedVisibility(!watcherOn) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Tip("If the switch is greyed out (\"Restricted setting\"), open App info → ⋮ → Allow restricted settings, then try again.")
                        TextButton({
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        }) { Text("Open App info") }
                    }
                }
            }
        }

        item {
            ProtectionCard(Icons.Rounded.VpnKey, "VPN network filter", vpnOn, 2,
                "Blocks your website rules in every app and browser by filtering DNS on the phone. Other traffic isn't touched.") {
                LabeledSwitch(if (vpnOn) "Filter is running" else "Filter is off", null, vpnOn) { on ->
                    if (on) {
                        val consent = VpnService.prepare(ctx)
                        if (consent != null) vpnConsent.launch(consent) else DnsVpnService.start(ctx)
                    } else if (!store.strict) DnsVpnService.stop(ctx)
                }
                Spacer(Modifier.height(6.dp))
                Text("Extra protection", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                key(version) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Standard", "Malware", "+ Adult").forEachIndexed { i, l ->
                            SegmentedButton(store.dnsLevel == i, { if (!store.strict || i >= store.dnsLevel) store.dnsLevel = i },
                                SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                        }
                    }
                }
                Text(DnsVpnService.LEVEL_NAMES[store.dnsLevel] + when (store.dnsLevel) {
                    1 -> " · uses Cloudflare 1.1.1.2"; 2 -> " · uses Cloudflare 1.1.1.3"; else -> " · uses Cloudflare 1.1.1.1"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                Tip("Set Settings → Network → Private DNS to Off, or Android will skip the filter.")
                Tip("For automatic start after reboot, turn on Always-on VPN for Nindō.")
                TextButton({ ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) { Text("Open VPN settings") }
            }
        }

        item { BlocklistsCard() }
        item { PartnerCard() }

        item {
            ProtectionCard(Icons.Rounded.LocationOn, "Location for place rules", locPerm && bgPerm, 3,
                "Needed only for rules tied to places. Location stays on your phone.") {
                when {
                    !locPerm -> Button({ fgLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        Modifier.fillMaxWidth()) { Text("Allow location") }
                    !bgPerm && Build.VERSION.SDK_INT >= 29 -> {
                        Tip("Choose \"Allow all the time\" so place rules work while other apps are open.")
                        Button({ bgLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }, Modifier.fillMaxWidth()) { Text("Allow all the time") }
                    }
                    else -> Text("Location is allowed.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            ProtectionCard(Icons.Rounded.Password, "PIN lock", store.pin != null, 4,
                "Needs a PIN to open Nindō and change rules.") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({ pinDialog = true }, Modifier.weight(1f)) { Text(if (store.pin == null) "Set PIN" else "Change PIN") }
                    if (store.pin != null) OutlinedButton({ store.pin = null }, Modifier.weight(1f), enabled = !store.strict) { Text("Remove") }
                }
            }
        }

        item {
            SectionCard("Your data", Modifier.padding(horizontal = 16.dp).enter(5)) {
                Text("All tracking stays on this phone — daily totals for 180 days, the timeline for 30.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton({ confirmClear = true }, Modifier.fillMaxWidth(), enabled = !store.strict,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Clear all tracking data")
                }
            }
        }
    }

    if (pinDialog) PinSetupDialog { pinDialog = false }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all tracking data?") },
        text = { Text("Usage, history and the timeline will be deleted. Rules and places are kept.") },
        confirmButton = { TextButton({ GuardApp.db.clearAll(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

private fun Modifier.clipRound() = this.then(Modifier.clip(RoundedCornerShape(50)))

@Composable
private fun ProtectionCard(icon: ImageVector, title: String, on: Boolean, index: Int, description: String, content: @Composable ColumnScope.() -> Unit) {
    val border by animateColorAsState(if (on) Brand.Green.copy(alpha = 0.5f) else Color.Transparent, label = "border")
    Card(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().enter(index),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, border),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, if (on) Brand.Green else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusDot(on)
                Spacer(Modifier.width(4.dp))
                Text(if (on) "On" else "Off", style = MaterialTheme.typography.labelLarge, color = if (on) Brand.Green else Brand.Rose)
            }
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Tip(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Icon(Icons.Rounded.Lightbulb, null, Modifier.size(16.dp).padding(top = 2.dp), tint = Brand.Amber)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PinSetupDialog(onClose: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val valid = a.length in 4..8 && a == b
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text("Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("New PIN (4–8 digits)" to a, "Repeat PIN" to b).forEachIndexed { i, (label, v) ->
                    OutlinedTextField(v, { s -> val d = s.filter(Char::isDigit).take(8); if (i == 0) a = d else b = d },
                        label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                }
                if (b.isNotEmpty() && a != b) Text("PINs don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { GuardApp.store.pin = a; onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
