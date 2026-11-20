package com.example.truenasapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.truenasapp.Graph
import com.example.truenasapp.data.ConnState

@Composable
fun AppRoot() {
    val loggedIn by Graph.session.loggedIn.collectAsStateWithLifecycle()
    val locked by Graph.lock.locked.collectAsStateWithLifecycle()

    // A page shared from the browser: open it once you're signed in.
    val context = androidx.compose.ui.platform.LocalContext.current
    val shared by SharedLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(loggedIn, shared) {
        val url = shared
        if (loggedIn && url != null) {
            SharedLinks.pending.value = null
            SharedLinks.route(context, url)
        } else if (!loggedIn && url != null) {
            android.widget.Toast.makeText(context.applicationContext, "Sign in first; the page opens after", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(loggedIn) {
        if (!loggedIn) {
            ExpertController.close()
            InstallFormController.close()
            AppWebController.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (loggedIn) {
            MainScaffold()
            ExpertModeHost() // full web UI opens on top of the app
            InstallFormHost() // "Customize" install form
            AppWebHost() // an app's web page, opened in-app (also from Expert mode)
        } else {
            LoginScreen()
        }
        // The lock screen sits on top, so whatever you were doing is still there after unlocking.
        if (loggedIn && locked && Graph.lock.isEnabled) LockScreen()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Files("Files", Icons.Filled.Folder),
    Apps("Apps", Icons.Filled.Apps),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
private fun MainScaffold() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val conn by Graph.client.state.collectAsStateWithLifecycle()
    val layout = LocalLayoutInfo.current

    // Phone: bottom bar. Tablet / unfolded: side rail. Desktop mode: full side menu with labels.
    val useDrawer = layout.isDesktop
    val useRail = !useDrawer && layout.width != WidthClass.COMPACT

    Scaffold(
        bottomBar = {
            if (!useDrawer && !useRail) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (useDrawer) {
                PermanentDrawerSheet(Modifier.width(220.dp), windowInsets = WindowInsets(0)) {
                    Text(
                        "TrueNAS",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    )
                    Tab.entries.forEach { t ->
                        NavigationDrawerItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            } else if (useRail) {
                NavigationRail(windowInsets = WindowInsets(0)) {
                    Spacer(Modifier.height(12.dp))
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedVisibility(visible = conn != ConnState.ONLINE) {
                    Text(
                        if (conn == ConnState.CONNECTING) "Connecting to your NAS…"
                        else "Offline — reconnecting automatically",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                when (tab) {
                    Tab.Home -> HomeScreen()
                    Tab.Files -> FilesScreen()
                    Tab.Apps -> AppsScreen()
                    Tab.Settings -> SettingsScreen()
                }
            }
        }
    }
}
