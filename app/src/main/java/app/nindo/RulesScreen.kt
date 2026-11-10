@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.nindo

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
fun RulesScreen() {
    val store = GuardApp.store
    val db = GuardApp.db
    val version by store.version.collectAsState()
    val rules = remember(version) { store.rules() }
    val usage by rememberPolled(emptyMap<String, Pair<Long, Int>>(), version) {
        rules.associate { r ->
            r.id to if (r.target == Target.APP) db.appUsage(r.value) else store.ruleMs(r.id) to store.ruleVisits(r.id)
        }
    }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var creating by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                expanded = expandedFab,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add rule") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AuroraHeader("Rules", "${rules.count { it.enabled }} active · apps, websites and keywords",
                    colors = listOf(Color(0xFF0F766E), Brand.Indigo, Brand.Violet))
                Spacer(Modifier.height(6.dp))
                StrictBanner("you can add rules, not loosen them")
            }
            if (rules.isEmpty()) item {
                EmptyHint(Icons.Rounded.Shield, "No rules yet.\nTry: Instagram · 30 min a day · 10 opens\nor youtube.com · blocked 22:00–07:00.")
            }
            items(rules, key = { it.id }) { r ->
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                    if (v == SwipeToDismissBoxValue.EndToStart && store.strict) {
                        scope.launch { snackbar.showSnackbar("Strict mode: rules are locked until ${fmtUntil(store.strictUntil)}") }
                        false
                    } else if (v == SwipeToDismissBoxValue.EndToStart) {
                        store.deleteRule(r.id)
                        scope.launch {
                            val res = snackbar.showSnackbar("Rule deleted", "Undo", duration = SnackbarDuration.Short)
                            if (res == SnackbarResult.ActionPerformed) store.upsertRule(r)
                        }
                        true
                    } else false
                })
                SwipeToDismissBox(
                    state = dismiss,
                    enableDismissFromStartToEnd = false,
                    modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                    backgroundContent = {
                        val s by animateFloatAsState(if (dismiss.targetValue == SwipeToDismissBoxValue.EndToStart) 1.2f else 0.8f, label = "trash")
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(Brand.Rose).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                            Icon(Icons.Rounded.Delete, null, tint = Color.White, modifier = Modifier.scale(s))
                        }
                    },
                ) {
                    RuleCard(r, usage[r.id], store.place(r.placeId)?.name, onClick = {
                        if (store.strict) scope.launch { snackbar.showSnackbar("Strict mode: you can add rules but not change them") } else editing = r
                    }) { on ->
                        if (!on && store.strict) scope.launch { snackbar.showSnackbar("Strict mode: rules can't be switched off") }
                        else store.upsertRule(r.copy(enabled = on))
                    }
                }
            }
            if (rules.isNotEmpty()) item {
                Text("Tap a rule to edit · swipe left to delete", Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }

    if (creating || editing != null) {
        RuleEditor(editing, onDismiss = { creating = false; editing = null }) { r ->
            store.upsertRule(r); creating = false; editing = null
        }
    }
}

@Composable
private fun RuleCard(r: Rule, usage: Pair<Long, Int>?, placeName: String?, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val alpha by animateFloatAsState(if (r.enabled) 1f else 0.5f, label = "alpha")
    Card(
        Modifier.fillMaxWidth().pressable(onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                when (r.target) {
                    Target.APP -> AppIcon(r.value, 44.dp)
                    Target.DOMAIN -> IconBadge(Icons.Rounded.Language, Brand.Sky, 44.dp)
                    Target.KEYWORD -> IconBadge(Icons.Rounded.Key, Brand.Amber, 44.dp)
                    Target.CATEGORY -> IconBadge(Icons.Rounded.Category, Brand.Violet, 44.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                    Text(when (r.target) { Target.APP -> "App"; Target.DOMAIN -> "Website"; Target.KEYWORD -> "Keyword in address"; Target.CATEGORY -> "Ready-made list · sites and apps" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(r.enabled, onToggle)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.summaryParts(placeName).forEach { part ->
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha)) {
                        Text(part, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if (usage != null && r.hasLimits) {
                Spacer(Modifier.height(12.dp))
                if (r.limitMinutes > 0) LimitBar("Time today", "${fmtDuration(usage.first)} / ${fmtMinutes(r.limitMinutes)}",
                    usage.first.toFloat() / (r.limitMinutes * 60_000f))
                if (r.launchLimit > 0) LimitBar(if (r.target == Target.APP) "Opens today" else "Visits today",
                    "${usage.second} / ${r.launchLimit}", usage.second.toFloat() / r.launchLimit)
            }
        }
    }
}

@Composable
private fun LimitBar(label: String, value: String, fraction: Float) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(900), label = "limit")
    val color = when {
        fraction >= 1f -> Brand.Rose
        fraction >= 0.8f -> Brand.Amber
        else -> Brand.Teal
    }
    Column(Modifier.padding(vertical = 3.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {})
    }
}

// ------------------------------------------------------------------ editor

@Composable
private fun RuleEditor(existing: Rule?, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    val ctx = LocalContext.current
    val store = GuardApp.store
    val places = remember { store.places() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var target by remember { mutableStateOf(existing?.target ?: Target.APP) }
    var appPkg by remember { mutableStateOf(if (existing?.target == Target.APP) existing.value else null) }
    var text by remember { mutableStateOf(if (existing != null && existing.target in setOf(Target.DOMAIN, Target.KEYWORD)) existing.value else "") }
    var catId by remember { mutableStateOf(if (existing?.target == Target.CATEGORY) existing.value else null) }
    var timeOn by remember { mutableStateOf((existing?.limitMinutes ?: 0) > 0) }
    var minutes by remember { mutableFloatStateOf((existing?.limitMinutes?.takeIf { it > 0 } ?: 30).toFloat()) }
    var opensOn by remember { mutableStateOf((existing?.launchLimit ?: 0) > 0) }
    var opens by remember { mutableFloatStateOf((existing?.launchLimit?.takeIf { it > 0 } ?: 10).toFloat()) }
    var schedOn by remember { mutableStateOf(existing?.scheduleOn ?: false) }
    var start by remember { mutableIntStateOf(existing?.startMin ?: (22 * 60)) }
    var end by remember { mutableIntStateOf(existing?.endMin ?: (7 * 60)) }
    var days by remember { mutableIntStateOf(existing?.days ?: Rule.ALL_DAYS) }
    var placeId by remember { mutableStateOf(existing?.placeId) }
    var inside by remember { mutableStateOf(existing?.placeInside ?: true) }
    var picking by remember { mutableStateOf(false) }
    var timeDialog by remember { mutableStateOf<Int?>(null) } // 0 = start, 1 = end
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(if (existing == null) "New rule" else "Edit rule", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(Target.APP to "App", Target.DOMAIN to "Site", Target.KEYWORD to "Word", Target.CATEGORY to "List").forEachIndexed { i, (t, l) ->
                    SegmentedButton(selected = target == t, onClick = { target = t; error = null },
                        shape = SegmentedButtonDefaults.itemShape(i, 4), icon = {}) { Text(l, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(14.dp))

            AnimatedContent(target, label = "target") { t ->
                if (t == Target.CATEGORY) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Blocklists.CATEGORIES.forEach { c ->
                            val sel = catId == c.id
                            val border by androidx.compose.animation.animateColorAsState(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, label = "cat")
                            Card(Modifier.fillMaxWidth().pressable { catId = c.id; error = null }, shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                border = androidx.compose.foundation.BorderStroke(2.dp, border)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                        Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Text("Time and open limits are shared across the whole list.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (t == Target.APP) {
                    val pkg = appPkg
                    Card(Modifier.fillMaxWidth().pressable { picking = true }, shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (pkg != null) AppIcon(pkg) else IconBadge(Icons.Rounded.Apps, MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(pkg?.let { Apps.label(ctx, it) } ?: "Choose an app", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                } else {
                    OutlinedTextField(
                        text, { text = it; error = null }, Modifier.fillMaxWidth(),
                        label = { Text(if (t == Target.DOMAIN) "Website" else "Word in the address") },
                        placeholder = { Text(if (t == Target.DOMAIN) "instagram.com" else "casino") },
                        leadingIcon = { Icon(if (t == Target.DOMAIN) Icons.Rounded.Language else Icons.Rounded.Key, null) },
                        singleLine = true, shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = if (t == Target.DOMAIN) KeyboardType.Uri else KeyboardType.Text),
                        supportingText = { Text(if (t == Target.DOMAIN) "Also covers subdomains like m.instagram.com. Blocked in browsers and, with the VPN filter, in every app."
                            else "Matches anywhere in the browser address.") },
                    )
                }
            }

            EditorSection("Limits", Icons.Rounded.Timer)
            LabeledSwitch("Daily time limit", "Block once this much time is used today", timeOn) { timeOn = it }
            AnimatedVisibility(timeOn) {
                SliderRow("Allowed per day", fmtMinutes(minutes.toInt()), minutes, 5f..240f, 46) { minutes = it }
            }
            LabeledSwitch(if (target == Target.APP) "Max opens per day" else "Max visits per day", "Block after this many", opensOn) { opensOn = it }
            AnimatedVisibility(opensOn) {
                SliderRow("Allowed", "${opens.toInt()}×", opens, 1f..50f, 48) { opens = it }
            }
            AnimatedVisibility(!timeOn && !opensOn) {
                Text("No limit: fully blocked whenever the conditions below match.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            EditorSection("Schedule", Icons.Rounded.Schedule)
            LabeledSwitch("Only during certain hours", null, schedOn) { schedOn = it }
            AnimatedVisibility(schedOn) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeChip("From", start, Modifier.weight(1f)) { timeDialog = 0 }
                        TimeChip("To", end, Modifier.weight(1f)) { timeDialog = 1 }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DAY_NAMES.forEachIndexed { i, d ->
                            val on = days and (1 shl i) != 0
                            val bg by androidx.compose.animation.animateColorAsState(
                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, label = "day")
                            Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(bg).clickable { days = days xor (1 shl i) },
                                contentAlignment = Alignment.Center) {
                                Text(d.take(1), color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            EditorSection("Location", Icons.Rounded.Place)
            if (places.isEmpty()) {
                Text("Save places like Home, Office or College in Guard → Focus to make location-based rules.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(placeId == null, { placeId = null }, { Text("Anywhere") })
                    places.forEach { p ->
                        FilterChip(placeId == p.id, { placeId = p.id }, { Text(p.name) },
                            leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(16.dp)) })
                    }
                }
                AnimatedVisibility(placeId != null) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        SegmentedButton(inside, { inside = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("While there") }
                        SegmentedButton(!inside, { inside = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("While away") }
                    }
                }
            }

            AnimatedVisibility(error != null) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val value = when (target) {
                        Target.APP -> appPkg ?: run { error = "Choose an app"; return@Button }
                        Target.DOMAIN -> text.trim().lowercase().removePrefix("https://").removePrefix("http://")
                            .substringBefore('/').removePrefix("www.").takeIf { it.contains('.') } ?: run { error = "Enter a website like instagram.com"; return@Button }
                        Target.KEYWORD -> text.trim().lowercase().takeIf { it.length >= 2 } ?: run { error = "Enter at least 2 letters"; return@Button }
                        Target.CATEGORY -> catId ?: run { error = "Choose a list"; return@Button }
                    }
                    if (schedOn && days == 0) { error = "Pick at least one day"; return@Button }
                    onSave(Rule(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        target = target, value = value,
                        label = when (target) {
                            Target.APP -> Apps.label(ctx, value)
                            Target.CATEGORY -> Blocklists.category(value)?.name ?: value
                            else -> value
                        },
                        scheduleOn = schedOn, startMin = start, endMin = end, days = days,
                        limitMinutes = if (timeOn) minutes.toInt() else 0,
                        launchLimit = if (opensOn) opens.toInt() else 0,
                        placeId = placeId, placeInside = inside,
                        enabled = existing?.enabled ?: true,
                    ))
                },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
            ) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(8.dp)); Text("Save rule") }
        }
    }

    if (picking) AppPicker(single = true, selected = setOfNotNull(appPkg), onDismiss = { picking = false }) {
        appPkg = it.firstOrNull(); picking = false
    }

    timeDialog?.let { which ->
        val initial = if (which == 0) start else end
        val state = rememberTimePickerState(initial / 60, initial % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timeDialog = null },
            confirmButton = {
                TextButton({
                    val m = state.hour * 60 + state.minute
                    if (which == 0) start = m else end = m
                    timeDialog = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ timeDialog = null }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun EditorSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.padding(top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TimeChip(label: String, min: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.pressable(onClick), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtTime(min), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

// --------------------------------------------------------------- app picker

@Composable
fun AppPicker(single: Boolean, selected: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<Apps.AppInfo>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Apps.launchable(ctx) }
    }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(selected) }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.Rounded.Close, null) }
                    Text(if (single) "Choose an app" else "Choose apps", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (!single) TextButton({ onDone(chosen) }) { Text("Done (${chosen.size})") }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("Search apps") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true, shape = CircleShape)
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }, key = { it.pkg }) { a ->
                        val on = a.pkg in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (single) onDone(setOf(a.pkg)) else chosen = if (on) chosen - a.pkg else chosen + a.pkg
                            }.padding(horizontal = 16.dp, vertical = 10.dp).animateItem(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(a.pkg)
                            Spacer(Modifier.width(14.dp))
                            Text(a.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (!single) Checkbox(on, { chosen = if (on) chosen - a.pkg else chosen + a.pkg })
                        }
                    }
                }
            }
        }
    }
}
