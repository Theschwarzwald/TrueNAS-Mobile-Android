package com.example.truenasapp.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.truenasapp.Graph
import com.example.truenasapp.data.NasApp
import com.example.truenasapp.data.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** What you can do to an app, alone or several at once. */
private enum class AppAction(val label: String, val doneVerb: String, val icon: ImageVector) {
    Start("Start", "Started", Icons.Filled.PlayArrow),
    Stop("Stop", "Stopped", Icons.Filled.Stop),
    Restart("Restart", "Restarted", Icons.Filled.RestartAlt),
    Redeploy("Redeploy", "Redeployed", Icons.Filled.CloudSync),
    Remove("Remove", "Removed", Icons.Filled.Delete),
}

private val NasApp.running get() = state == "RUNNING"

/** True if [action] makes sense for this app right now (bulk actions skip the rest). */
private fun NasApp.allows(action: AppAction) = when (action) {
    AppAction.Start -> !running
    AppAction.Stop -> running || state == "DEPLOYING"
    AppAction.Restart -> running
    AppAction.Redeploy, AppAction.Remove -> true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun InstalledApps(externalReload: Int) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<NasApp>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmRemove by remember { mutableStateOf<List<NasApp>?>(null) }

    LaunchedEffect(reload, externalReload) {
        refreshing = true
        try {
            apps = Graph.api.apps()
            error = null
            selected = selected intersect apps.map { it.name }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
        refreshing = false
    }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    BackHandler(enabled = selecting) { exitSelection() }

    /**
     * Runs [action] on every app in [targets] at the same time. Work runs in the app-wide
     * scope so it finishes even if you leave this screen. One summary message at the end.
     */
    fun run(targets: List<NasApp>, action: AppAction, deleteData: Boolean = false) {
        val todo = targets.filter { it.allows(action) && it.name !in busy }
        if (todo.isEmpty()) {
            toast(context, "Nothing to ${action.label.lowercase()}")
            return
        }
        busy = busy + todo.map { it.name }
        Graph.appScope.launch {
            val failures = todo.map { app ->
                async {
                    try {
                        when (action) {
                            AppAction.Start -> Graph.api.startApp(app.name)
                            AppAction.Stop -> Graph.api.stopApp(app.name)
                            AppAction.Restart -> Graph.api.restartApp(app.name)
                            AppAction.Redeploy -> Graph.api.redeployApp(app.name)
                            AppAction.Remove -> Graph.api.deleteApp(app.name, deleteData)
                        }
                        null
                    } catch (e: Exception) {
                        "${app.name}: ${friendlyError(e)}"
                    } finally {
                        busy = busy - app.name
                    }
                }
            }.awaitAll().filterNotNull()
            val ok = todo.size - failures.size
            val msg = buildString {
                if (ok > 0) append("${action.doneVerb} ${if (todo.size == 1) todo[0].name else "$ok app${if (ok == 1) "" else "s"}"}")
                if (failures.isNotEmpty()) {
                    if (ok > 0) append(". ")
                    append(failures.joinToString("\n"))
                }
            }
            toast(context, msg)
            reload++
        }
    }

    fun request(targets: List<NasApp>, action: AppAction) {
        if (action == AppAction.Remove) confirmRemove = targets.filter { it.name !in busy } else run(targets, action)
    }

    val updatable = apps.filter { it.upgradeAvailable }
    val chosen = apps.filter { it.name in selected }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = chosen.size,
                total = apps.size,
                chosen = chosen,
                onClose = ::exitSelection,
                onAll = { selected = if (selected.size == apps.size) emptySet() else apps.map { it.name }.toSet() },
                onAction = { action ->
                    request(chosen, action)
                    if (action != AppAction.Remove) exitSelection()
                },
            )
        }

        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = adaptiveGridCells(360.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(gridGap()),
                modifier = Modifier.fillMaxSize(),
            ) {
                val full: (LazyGridItemSpanScope) -> GridItemSpan = { GridItemSpan(it.maxLineSpan) }
                val err = error
                if (err != null) item(span = full) { ErrorCard(err) { reload++ } }
                if (apps.isEmpty() && !refreshing && err == null) {
                    item(span = full) { Text("No apps installed yet. Check the Discover tab.") }
                }
                if (!selecting && apps.isNotEmpty()) {
                    item(span = full) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Long-press an app to select several",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { selecting = true }) {
                                Icon(Icons.Filled.Checklist, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Select")
                            }
                        }
                    }
                }
                if (!selecting && updatable.size > 1) {
                    item(span = full) {
                        Button(
                            onClick = {
                                busy = busy + updatable.map { it.name }
                                updatable.forEach { a ->
                                    Graph.appScope.launch {
                                        try {
                                            Graph.api.upgradeApp(a.name)
                                            toast(context, "Updated ${a.name}")
                                        } catch (e: Exception) {
                                            toast(context, "${a.name}: ${friendlyError(e)}")
                                        }
                                        busy = busy - a.name
                                        reload++
                                    }
                                }
                            },
                            enabled = updatable.none { it.name in busy },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Update all (${updatable.size})")
                        }
                    }
                }
                items(apps, key = { it.name }) { app ->
                    InstalledAppCard(
                        app = app,
                        busy = app.name in busy,
                        selecting = selecting,
                        selected = app.name in selected,
                        onToggle = {
                            selected = if (app.name in selected) selected - app.name else selected + app.name
                        },
                        onLongPress = {
                            selecting = true
                            selected = selected + app.name
                        },
                        onOpen = { app.webUrl?.let { url -> AppWebController.open(app.name, url) } },
                        onAction = { request(listOf(app), it) },
                        onUpdate = {
                            busy = busy + app.name
                            Graph.appScope.launch {
                                try {
                                    Graph.api.upgradeApp(app.name)
                                    toast(context, "Updated ${app.name}")
                                } catch (e: Exception) {
                                    toast(context, "${app.name}: ${friendlyError(e)}")
                                }
                                busy = busy - app.name
                                reload++
                            }
                        },
                    )
                }
            }
        }
    }

    confirmRemove?.let { targets ->
        RemoveDialog(
            targets = targets,
            onDismiss = { confirmRemove = null },
            onConfirm = { deleteData ->
                confirmRemove = null
                exitSelection()
                run(targets, AppAction.Remove, deleteData)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    chosen: List<NasApp>,
    onClose: () -> Unit,
    onAll: () -> Unit,
    onAction: (AppAction) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Done selecting") }
                Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onAll) { Text(if (count == total && total > 0) "Select none" else "Select all") }
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppAction.entries.forEach { action ->
                    val n = chosen.count { it.allows(action) }
                    FilledTonalButton(onClick = { onAction(action) }, enabled = n > 0) {
                        Icon(action.icon, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (n > 0 && n != count) "${action.label} ($n)" else action.label)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledAppCard(
    app: NasApp,
    busy: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onOpen: () -> Unit,
    onAction: (AppAction) -> Unit,
    onUpdate: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = onLongPress),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(4.dp))
            }
            AppIcon(app.iconUrl, app.name, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(app.state.lowercase().replaceFirstChar { it.uppercase() }, app.version)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (app.running) TrueNasGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (app.upgradeAvailable && !busy && !selecting) {
                    AssistChip(
                        onClick = onUpdate,
                        label = { Text(if (app.latestVersion.isNotBlank()) "Update to ${app.latestVersion}" else "Update available") },
                        leadingIcon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
            }
            when {
                busy -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                selecting -> Unit
                else -> {
                    if (app.webUrl != null && app.running) {
                        IconButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open web UI") }
                    }
                    IconButton(onClick = { onAction(if (app.running) AppAction.Stop else AppAction.Start) }) {
                        if (app.running) Icon(Icons.Filled.Stop, contentDescription = "Stop")
                        else Icon(Icons.Filled.PlayArrow, contentDescription = "Start")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            AppAction.entries.filter { app.allows(it) }.forEach { action ->
                                if (action == AppAction.Remove) HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(if (action == AppAction.Remove) "Remove…" else action.label)
                                            if (action == AppAction.Redeploy) {
                                                Text(
                                                    "Pull images again and restart",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                                    onClick = { menu = false; onAction(action) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoveDialog(targets: List<NasApp>, onDismiss: () -> Unit, onConfirm: (deleteData: Boolean) -> Unit) {
    var deleteData by remember { mutableStateOf(false) }
    val one = targets.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(if (one != null) "Remove ${one.name}?" else "Remove ${targets.size} apps?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(if (one != null) "The app is stopped and removed from your NAS." else "These apps are stopped and removed from your NAS:")
                if (one == null) {
                    Spacer(Modifier.size(8.dp))
                    targets.forEach { Text("• ${it.name}", fontWeight = FontWeight.SemiBold) }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { deleteData = !deleteData }.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = deleteData, onCheckedChange = { deleteData = it })
                    Column {
                        Text("Also delete ${if (one != null) "its" else "their"} data")
                        Text(
                            "Storage TrueNAS created for ${if (one != null) "it" else "them"} (ix volumes). " +
                                "Folders you chose (host paths) are never touched. This can't be undone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteData) }) {
                Text(if (one != null) "Remove" else "Remove ${targets.size}", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun toast(context: Context, text: String) =
    Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
