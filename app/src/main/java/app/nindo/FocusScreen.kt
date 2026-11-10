@file:OptIn(ExperimentalMaterial3Api::class)

package app.nindo

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun FocusScreen() {
    val store = GuardApp.store
    val db = GuardApp.db
    val ctx = LocalContext.current
    val version by store.version.collectAsState()
    val scrollers by rememberPolled(emptyList<AppStat>()) { db.topApps(limit = 5, by = "scroll_ms").filter { it.scrollMs > 0 } }
    val totals by rememberPolled(Totals(0, 0, 0, 0, 0, 0, 0, 0)) { db.totals() }
    var pickApps by remember { mutableStateOf(false) }
    var addPlace by remember { mutableStateOf(false) }

    // Local copies keep sliders smooth; they're saved when the drag ends.
    var threshold by remember { mutableFloatStateOf(store.doomThresholdMin.toFloat()) }
    var cooldown by remember { mutableFloatStateOf(store.doomCooldownMin.toFloat()) }
    var snoozes by remember { mutableFloatStateOf(store.doomMaxSnoozes.toFloat()) }
    var goal by remember { mutableFloatStateOf(store.screenGoalMin.toFloat()) }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            AuroraHeader("Focus", "Stop doom scrolling and set where rules apply",
                colors = listOf(Color(0xFF0F766E), Brand.Teal, Color(0xFF65A30D))) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column {
                        AnimatedDuration(totals.scrollMs, MaterialTheme.typography.headlineSmall, Color.White)
                        Text("scrolled today", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        AnimatedNumber(totals.swipes, MaterialTheme.typography.headlineSmall, Color.White)
                        Text("swipes", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        AnimatedNumber(totals.nudges, MaterialTheme.typography.headlineSmall, Color.White)
                        Text("nudges", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item { FocusSessionCard(Modifier.padding(horizontal = 16.dp).enter(1)) }
        item { ReelCounterCard(Modifier.padding(horizontal = 16.dp).enter(2)) }
        item { StrictBanner("guard settings are locked") }

        item {
            SectionCard("Doom-scroll guard", Modifier.padding(horizontal = 16.dp).enter(2)) {
                LabeledSwitch("Watch for endless scrolling", "Shows a breathing break after continuous scrolling", store.doomEnabled) {
                    if (!store.strict || it) store.doomEnabled = it
                }
                AnimatedVisibility(store.doomEnabled && !store.strict) {
                    Column {
                        SliderRow("Nudge after", "${threshold.toInt()} min", threshold, 3f..30f, 26) { threshold = it }
                        LaunchedEffect(threshold) { store.doomThresholdMin = threshold.toInt() }
                        SliderRow("Break after \"I'm done\"", if (cooldown.toInt() == 0) "None" else "${cooldown.toInt()} min", cooldown, 0f..60f, 11) { cooldown = it }
                        LaunchedEffect(cooldown) { store.doomCooldownMin = cooldown.toInt() }
                        SliderRow("\"5 more minutes\" allowed", "${snoozes.toInt()}×", snoozes, 0f..3f, 2) { snoozes = it }
                        LaunchedEffect(snoozes) { store.doomMaxSnoozes = snoozes.toInt() }
                        Spacer(Modifier.height(6.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(!store.doomAllApps, { store.doomAllApps = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Chosen apps") }
                            SegmentedButton(store.doomAllApps, { store.doomAllApps = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("All apps") }
                        }
                        AnimatedVisibility(!store.doomAllApps) {
                            OutlinedButton({ pickApps = true }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Icon(Icons.Rounded.Apps, null); Spacer(Modifier.width(8.dp))
                                Text("Watched apps (${store.doomApps.size})")
                            }
                        }
                    }
                }
            }
        }

        if (scrollers.isNotEmpty()) item {
            SectionCard("Most scrolled today", Modifier.padding(horizontal = 16.dp).enter(2)) {
                val max = scrollers.maxOf { it.scrollMs }.coerceAtLeast(1)
                scrollers.forEach { a ->
                    UsageRow({ AppIcon(a.pkg) }, Apps.label(ctx, a.pkg), "${a.swipes} swipes", fmtDuration(a.scrollMs),
                        a.scrollMs.toFloat() / max, Brand.Amber)
                }
            }
        }

        item {
            val places = remember(version) { store.places() }
            val loc by LocationTracker.current.collectAsState()
            SectionCard("Places", Modifier.padding(horizontal = 16.dp).enter(3), action = {
                FilledTonalButton({ addPlace = true }) { Icon(Icons.Rounded.AddLocationAlt, null); Spacer(Modifier.width(6.dp)); Text("Add") }
            }) {
                Text("Use places in rules, e.g. block games while at College, or allow Instagram only at Home.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (places.isEmpty()) EmptyHint(Icons.Rounded.Place, "No places saved yet.")
                places.forEach { p ->
                    val inside = LocationTracker.isInside(p)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.LocationOn, if (inside == true) Brand.Green else Brand.Indigo)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("${p.radiusM} m radius · " + when (inside) { true -> "you're here now"; false -> "you're away"; null -> "location unknown" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ store.deletePlace(p.id) }, enabled = !store.strict) { Icon(Icons.Rounded.Delete, null) }
                    }
                }
                if (loc == null && places.isNotEmpty()) Text("Allow location in Protect so place rules can work.",
                    style = MaterialTheme.typography.bodySmall, color = Brand.Amber)
            }
        }

        item {
            SectionCard("Daily screen-time goal", Modifier.padding(horizontal = 16.dp).enter(4)) {
                SliderRow("Goal", fmtMinutes(goal.toInt()), goal, 30f..600f, 37) { goal = it }
                LaunchedEffect(goal) { store.screenGoalMin = goal.toInt() }
                Text("Shown as the ring on the Today screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (pickApps) AppPicker(single = false, selected = store.doomApps, onDismiss = { pickApps = false }) {
        store.doomApps = it; pickApps = false
    }
    if (addPlace) AddPlaceDialog { addPlace = false }
}

@Composable
private fun AddPlaceDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableFloatStateOf(150f) }
    var loc by remember { mutableStateOf(LocationTracker.current.value) }
    var locating by remember { mutableStateOf(false) }
    var hasPerm by remember { mutableStateOf(LocationTracker.hasPermission(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPerm = LocationTracker.hasPermission(ctx)
    }
    LaunchedEffect(hasPerm) {
        if (hasPerm) { locating = true; LocationTracker.refresh(ctx) { loc = it; locating = false } }
    }

    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Rounded.MyLocation, null) },
        title = { Text("Save this place") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("Home, Office, College…") },
                    singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                SliderRow("Radius", "${radius.toInt()} m", radius, 50f..1000f, 18) { radius = it }
                when {
                    !hasPerm -> Button({ perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        Modifier.fillMaxWidth()) { Text("Allow location") }
                    locating -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Finding you…")
                    }
                    loc != null -> Text(String.format(Locale.US, "Current position: %.5f, %.5f (±%d m)", loc!!.latitude, loc!!.longitude, loc!!.accuracy.toInt()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text("Couldn't get your location. Turn on Location and try again.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && loc != null, onClick = {
                val l = loc!!
                GuardApp.store.upsertPlace(Place(name = name.trim(), lat = l.latitude, lng = l.longitude, radiusM = radius.toInt()))
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
