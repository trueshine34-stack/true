package com.plovault.sync

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.plovault.sync.ui.AppState
import com.plovault.sync.ui.HandsScreen
import com.plovault.sync.ui.HomeScreen
import com.plovault.sync.ui.PloVaultTheme
import com.plovault.sync.ui.PokerCraftScreen
import com.plovault.sync.ui.SettingsScreen
import com.plovault.sync.ui.StatsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { PloVaultTheme { Root() } }
    }
}

private enum class Tab(val label: String) { HOME("Синхро"), HANDS("Руки"), STATS("Статистика"), SETTINGS("Настройки") }

@Composable
private fun Root() {
    val context = LocalContext.current
    val state = remember { AppState(context) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var webOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    if (webOpen) {
        PokerCraftScreen(state = state, onClose = { webOpen = false; state.refresh() })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t; state.refresh() },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.HOME -> Icons.Default.CloudDownload
                                    Tab.HANDS -> Icons.AutoMirrored.Filled.ViewList
                                    Tab.STATS -> Icons.Default.BarChart
                                    Tab.SETTINGS -> Icons.Default.Settings
                                },
                                contentDescription = t.label
                            )
                        },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.HOME -> HomeScreen(state, m, snackbar) { webOpen = true }
            Tab.HANDS -> HandsScreen(state, m)
            Tab.STATS -> StatsScreen(state, m)
            Tab.SETTINGS -> SettingsScreen(state, m, snackbar)
        }
    }
}
