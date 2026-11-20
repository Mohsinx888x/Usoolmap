package com.usool.map.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Tab(val label: String, val icon: ImageVector) {
    MAP("Map", Icons.Filled.Map),
    SATELLITES("Satellites", Icons.Filled.SatelliteAlt),
    OFFLINE("Offline", Icons.Filled.CloudDownload),
    SETTINGS("Settings", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme(primary = Color(0xFF8AB4FF), secondary = Color(0xFFF0A53A))
            else lightColorScheme(primary = Color(0xFF0B3D91), secondary = Color(0xFFB8660A))
            MaterialTheme(colorScheme = colors) {
                var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
                val ui by vm.ui.collectAsStateWithLifecycle()
                val nav by vm.navigation.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }

                val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    vm.container.gnss.start(navigating = nav.active)
                    vm.refreshModes()
                }
                LaunchedEffect(Unit) {
                    val wanted = buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (vm.container.gnss.hasPermission()) vm.container.gnss.start(navigating = nav.active) else permissions.launch(wanted.toTypedArray())
                }
                LaunchedEffect(ui.message, nav.message) {
                    val text = ui.message ?: nav.message ?: return@LaunchedEffect
                    snackbar.showSnackbar(text)
                    vm.clearMessage()
                    vm.container.navigator.clearMessage()
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        // Hidden while navigating so the map and the next turn get the whole screen.
                        if (!nav.active) {
                            NavigationBar {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) })
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize()) {
                        // The map stays composed under the other tabs so its state and tiles are kept.
                        MapScreen(vm, visible = tab == Tab.MAP || nav.active, contentPadding = padding)
                        val street = ui.streetImages
                        if (street != null && !nav.active) {
                            StreetViewScreen(street, ui.streetIndex, vm, Modifier.padding(padding))
                        } else if (!nav.active) {
                            when (tab) {
                                Tab.MAP -> Unit
                                Tab.SATELLITES -> SatelliteCenterScreen(vm, Modifier.padding(padding))
                                Tab.OFFLINE -> OfflineScreen(vm, Modifier.padding(padding))
                                Tab.SETTINGS -> SettingsScreen(vm, Modifier.padding(padding))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Opens `geo:` links shared from other apps. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "geo") vm.submitSearch(data.toString())
    }

    override fun onStart() {
        super.onStart()
        if (vm.container.gnss.hasPermission()) vm.container.gnss.start(navigating = vm.navigation.value.active)
    }

    override fun onStop() {
        super.onStop()
        // Positioning continues in the background only while a route is being navigated.
        if (!vm.navigation.value.active && !isChangingConfigurations) vm.container.gnss.stop()
    }
}
