package com.example.truenasapp.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.truenasapp.Graph
import com.example.truenasapp.data.FileEntry
import com.example.truenasapp.data.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val ROOT = "/mnt"

@Composable
fun FilesScreen() {
    val appContext = LocalContext.current.applicationContext
    var path by rememberSaveable { mutableStateOf(ROOT) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<FileEntry?>(null) }

    fun goUp() {
        path = path.substringBeforeLast('/').let { if (it.length < ROOT.length) ROOT else it }
    }

    BackHandler(enabled = path != ROOT) { goUp() }

    LaunchedEffect(path, reload) {
        loading = true
        try {
            entries = Graph.api.listDir(path)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (path != ROOT) {
                IconButton(onClick = { goUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
            }
            Column(Modifier.padding(horizontal = 8.dp)) {
                ScreenTitle(if (path == ROOT) "Files" else path.substringAfterLast('/'))
                Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider()

        Box(Modifier.fillMaxSize()) {
            val err = error
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                err != null -> Box(Modifier.padding(16.dp)) { ErrorCard(err) { reload++ } }
                entries.isEmpty() -> Text("This folder is empty", Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (entry.isDir) "Folder" else formatBytes(entry.size)) },
                            leadingContent = {
                                Icon(
                                    if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = if (entry.isDir) TrueNasBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (entry.isDir) path = entry.path else selected = entry },
                        )
                    }
                }
            }
        }
    }

    selected?.let { file ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(file.name) },
            text = { Text("${formatBytes(file.size)}\n\nDownload to this phone's Downloads folder?") },
            confirmButton = {
                TextButton(onClick = {
                    selected = null
                    // App-wide scope so the download keeps going if you switch tabs.
                    Graph.appScope.launch {
                        Toast.makeText(appContext, "Downloading ${file.name}…", Toast.LENGTH_SHORT).show()
                        try {
                            val name = Graph.api.downloadToDevice(appContext, file)
                            Toast.makeText(appContext, "Saved $name to Downloads", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(appContext, friendlyError(e), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
        )
    }
}
