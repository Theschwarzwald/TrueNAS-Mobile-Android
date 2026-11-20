package com.example.truenasapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.truenasapp.Graph
import com.example.truenasapp.data.CatalogApp
import com.example.truenasapp.data.NasApp
import com.example.truenasapp.data.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AppsScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var installedReload by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) { ScreenTitle("Apps") }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Discover") })
        }
        when (tab) {
            0 -> InstalledApps(installedReload)
            else -> DiscoverApps(onInstalled = { tab = 0; installedReload++ })
        }
    }
}

// ------------------------------------------------------------------ store

@Composable
private fun DiscoverApps(onInstalled: () -> Unit) {
    var catalog by remember { mutableStateOf<List<CatalogApp>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CatalogApp?>(null) }

    LaunchedEffect(reload) {
        try {
            catalog = Graph.api.catalog()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
    }

    val all = catalog
    val categories = remember(all) { all.orEmpty().flatMap { it.categories }.distinct().sorted() }
    val shown = remember(all, query, category) {
        all.orEmpty().filter { a ->
            (category == null || category in a.categories) &&
                (query.isBlank() || listOf(a.title, a.name, a.description).any { it.contains(query.trim(), ignoreCase = true) })
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).readableWidth(),
        )
        if (categories.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScrollCompat()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") })
                categories.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = if (category == c) null else c },
                        label = { Text(c.replace('-', ' ').replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }

        val err = error
        when {
            err != null -> Box(Modifier.padding(16.dp)) { ErrorCard(err) { reload++ } }
            all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyVerticalGrid(
                columns = adaptiveGridCells(360.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(gridGap()),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("No apps match.") }
                items(shown, key = { it.train + "/" + it.name }) { app ->
                    Card(Modifier.fillMaxWidth().clickable { selected = app }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.iconUrl, app.title, size = 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                                    if (app.installed) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("Installed", style = MaterialTheme.typography.labelSmall, color = TrueNasGreen)
                                    }
                                }
                                Text(
                                    app.description, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { app ->
        InstallDialog(app, onDismiss = { selected = null }, onInstalled = {
            selected = null
            onInstalled()
        })
    }
}

private val validName = Regex("^[a-z]([-a-z0-9]*[a-z0-9])?$")

@Composable
private fun InstallDialog(app: CatalogApp, onDismiss: () -> Unit, onInstalled: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(app.name.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-')) }
    var installing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameOk = name.length in 1..40 && validName.matches(name)

    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        icon = { AppIcon(app.iconUrl, app.title, size = 56.dp) },
        title = { Text(app.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    listOf(app.version.takeIf { it.isNotBlank() }?.let { "Version $it" }, "${app.train} train")
                        .filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.size(8.dp))
                Text(app.description)
                Spacer(Modifier.size(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase() },
                    label = { Text("App name") },
                    singleLine = true,
                    enabled = !installing,
                    isError = !nameOk,
                    supportingText = { if (!nameOk) Text("Lowercase letters, numbers and dashes") },
                )
                Text(
                    "Install uses TrueNAS's default settings. Customize lets you set ports, storage, " +
                        "environment variables and everything else, like the desktop installer.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (installing) {
                    Spacer(Modifier.size(12.dp))
                    Text("Installing… this can take a few minutes while it downloads.", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                error?.let {
                    Spacer(Modifier.size(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {
                        onDismiss()
                        InstallFormController.show(app, name, onInstalled)
                    }) { Text("Customize the settings") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameOk && !installing,
                onClick = {
                    onDismiss()
                    InstallFormController.show(app, name, onInstalled)
                },
            ) { Text("Customize") }
            TextButton(
                enabled = nameOk && !installing,
                onClick = {
                    installing = true
                    error = null
                    val appName = name
                    Graph.appScope.launch {
                        try {
                            Graph.api.installApp(appName, app)
                            toast(context, "${app.title} installed")
                            onInstalled()
                        } catch (e: Exception) {
                            error = friendlyError(e)
                        }
                        installing = false
                    }
                },
            ) { Text(if (app.installed) "Install another" else "Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !installing) { Text("Close") } },
    )
}

private fun toast(context: Context, text: String) =
    Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
