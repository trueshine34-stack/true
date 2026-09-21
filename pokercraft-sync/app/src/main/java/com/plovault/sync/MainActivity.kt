package com.plovault.sync

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.plovault.sync.data.Prefs
import com.plovault.sync.ui.AppState
import com.plovault.sync.ui.HandsScreen
import com.plovault.sync.ui.HomeScreen
import com.plovault.sync.ui.PloVaultTheme
import com.plovault.sync.ui.PokerCraftScreen
import com.plovault.sync.ui.SettingsScreen
import com.plovault.sync.ui.StatsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** Ссылка PokerCraft пришла извне — открываем портал сразу. */
    private val openPortal = mutableStateOf(false)

    /** Файл выгрузки, которым поделились в приложение. */
    private val pendingImport = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        setContent { PloVaultTheme { Root(openPortal, pendingImport) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val prefs = Prefs(this)
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                val scheme = data.scheme.orEmpty()
                if (scheme.startsWith("http")) {
                    val url = data.toString()
                    if (url.contains("pokercraft", ignoreCase = true)) {
                        prefs.portalUrl = url
                        openPortal.value = true
                    }
                } else {
                    pendingImport.value = data
                }
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    pendingImport.value = uri
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    val link = Regex("""https?://\S*pokercraft\S*""").find(text)?.value
                    if (link != null) {
                        prefs.portalUrl = link
                        openPortal.value = true
                    }
                }
            }
        }
    }
}

private enum class Tab(val label: String) { HOME("Синхро"), HANDS("Руки"), STATS("Статистика"), SETTINGS("Настройки") }

@Composable
private fun Root(openPortal: MutableState<Boolean>, pendingImport: MutableState<Uri?>) {
    val context = LocalContext.current
    val state = remember { AppState(context) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var webOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Ссылка пришла из клиента GGPoker — сразу открываем портал, пока токен свежий.
    LaunchedEffect(openPortal.value) {
        if (openPortal.value) {
            openPortal.value = false
            webOpen = true
        }
    }

    // Файлом выгрузки поделились в приложение — импортируем.
    LaunchedEffect(pendingImport.value) {
        val uri = pendingImport.value ?: return@LaunchedEffect
        pendingImport.value = null
        state.busy = true
        val msg = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "shared.bin"
                state.pipeline.importBytes(bytes, name, "share").message
            }.getOrElse { "Ошибка импорта: ${it.message}" }
        }
        state.busy = false
        state.refresh()
        snackbar.showSnackbar(msg)
    }

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
