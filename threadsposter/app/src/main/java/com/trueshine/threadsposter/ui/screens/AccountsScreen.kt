package com.trueshine.threadsposter.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trueshine.threadsposter.core.Time
import com.trueshine.threadsposter.data.db.AccountEntity
import com.trueshine.threadsposter.ui.components.BusyIndicator
import com.trueshine.threadsposter.ui.components.Field
import com.trueshine.threadsposter.ui.components.SectionCard
import com.trueshine.threadsposter.ui.components.SwitchRow
import com.trueshine.threadsposter.ui.vm.AccountsVm
import com.trueshine.threadsposter.ui.vm.rememberVm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: AccountsVm = rememberVm("accounts") { AccountsVm(it) }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val authorizeUrl by vm.authorizeUrl.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var showAdd by remember { mutableStateOf(false) }
    var tokenTarget by remember { mutableStateOf<AccountEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<AccountEntity?>(null) }
    var showLogin by remember { mutableStateOf(false) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Аккаунты Threads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Добавить аккаунт")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BusyIndicator(busy) }

            item {
                SectionCard("Как подключить аккаунт") {
                    Text(
                        "Threads пускает сторонние приложения только через приложение Meta. " +
                            "Порядок такой:\n\n" +
                            "1. На developers.facebook.com создайте приложение с продуктом Threads API.\n" +
                            "2. Разрешите права threads_basic, threads_content_publish, " +
                            "threads_read_replies, threads_manage_replies, threads_keyword_search.\n" +
                            "3. Получите долгоживущий токен своего аккаунта (60 дней) и вставьте его сюда.\n\n" +
                            "Дальше приложение продлевает токен само — заново авторизовываться не придётся.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (authorizeUrl != null) {
                        OutlinedButton(onClick = { showLogin = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Войти через Threads")
                        }
                    } else {
                        Text(
                            "Вход в одно нажатие станет доступен, когда вы впишете App ID, " +
                                "App Secret и Redirect URI в настройках.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onOpenSettings) { Text("Открыть настройки") }
                    }
                }
            }

            items(accounts, key = { it.id }) { account ->
                SectionCard(modifier = Modifier.clickable { onOpenAccount(account.id) }) {
                    Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        account.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "аккаунт не проверен",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val status = when (account.lastCheckOk) {
                        true -> "Проверен ${account.lastCheckAt?.let { Time.format(it) } ?: ""}"
                        false -> "Ошибка: ${account.lastError ?: "неизвестно"}"
                        else -> "Ещё не проверялся"
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (account.lastCheckOk == false) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    account.tokenExpiresAt?.let { expires ->
                        val left = expires - System.currentTimeMillis()
                        Text(
                            if (left > 0) "Токен действует ещё ${left / 86_400_000} дн."
                            else "Токен истёк — продлите или вставьте новый",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (left > 7 * 86_400_000L) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!vm.hasToken(account.id)) {
                        Text(
                            "Токен не сохранён",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    SwitchRow(
                        title = "Активен",
                        checked = account.enabled,
                        onCheckedChange = { vm.setEnabled(account, it) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.verify(account) }, modifier = Modifier.weight(1f)) {
                            Text("Проверить")
                        }
                        OutlinedButton(onClick = { vm.refreshToken(account) }, modifier = Modifier.weight(1f)) {
                            Text("Продлить")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenAccount(account.id) }, modifier = Modifier.weight(1f)) {
                            Text("Настроить")
                        }
                        OutlinedButton(onClick = { tokenTarget = account }, modifier = Modifier.weight(1f)) {
                            Text("Токен")
                        }
                    }
                    TextButton(onClick = { deleteTarget = account }) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (accounts.isEmpty()) {
                item {
                    Text(
                        "Пока нет ни одного аккаунта. Нажмите «+», чтобы добавить токен.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            item { Column(Modifier.padding(bottom = 32.dp)) {} }
        }
    }

    if (showAdd) {
        TokenDialog(
            title = "Новый аккаунт",
            withName = true,
            onDismiss = { showAdd = false },
            onConfirm = { name, token -> vm.addWithToken(name, token); showAdd = false },
        )
    }

    tokenTarget?.let { account ->
        TokenDialog(
            title = "Токен для «${account.displayName}»",
            withName = false,
            onDismiss = { tokenTarget = null },
            onConfirm = { _, token -> vm.updateToken(account, token); tokenTarget = null },
        )
    }

    deleteTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить аккаунт?") },
            text = { Text("Вместе с «${account.displayName}» удалятся его расписания, рубрики, посты и находки.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(account); deleteTarget = null }) {
                    Text("Удалить", color = Color(0xFFD64545))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } },
        )
    }

    if (showLogin) {
        authorizeUrl?.let { url ->
            OAuthDialog(
                url = url,
                redirectPrefix = settings.metaRedirectUri,
                onDismiss = { showLogin = false },
                onCode = { code ->
                    showLogin = false
                    vm.completeOAuth(code) {}
                },
            )
        }
    }
}

@Composable
private fun TokenDialog(
    title: String,
    withName: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, token: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (withName) {
                    Field(name, { name = it }, "Название (для себя)", hint = "Например: основной аккаунт")
                }
                Field(
                    token, { token = it }, "Долгоживущий токен Threads",
                    hint = "60-дневный токен из приложения Meta. Приложение продлит его само.",
                    singleLine = false, minLines = 3,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, token) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Вход через встроенный браузер.
 *
 * Threads требует https-адрес возврата, поэтому перехватываем переход на него
 * прямо в WebView и достаём параметр code — сам адрес при этом никуда не грузится
 * и своего сервера не требует.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthDialog(
    url: String,
    redirectPrefix: String,
    onDismiss: () -> Unit,
    onCode: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вход в Threads") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "После разрешения доступа окно закроется само.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(420.dp).padding(top = 8.dp),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                                    val code = extractCode(pageUrl, redirectPrefix)
                                    if (code != null) {
                                        view?.stopLoading()
                                        onCode(code)
                                    }
                                    super.onPageStarted(view, pageUrl, favicon)
                                }

                                @Deprecated("Нужен для API ниже 24, оставлен для совместимости")
                                override fun shouldOverrideUrlLoading(view: WebView?, pageUrl: String?): Boolean {
                                    val code = extractCode(pageUrl, redirectPrefix)
                                    if (code != null) {
                                        onCode(code)
                                        return true
                                    }
                                    return false
                                }
                            }
                            loadUrl(url)
                        }
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

private fun extractCode(pageUrl: String?, redirectPrefix: String): String? {
    if (pageUrl.isNullOrBlank() || redirectPrefix.isBlank()) return null
    if (!pageUrl.startsWith(redirectPrefix)) return null
    // Threads добавляет к коду хвост #_, который в запрос отправлять нельзя.
    return runCatching { Uri.parse(pageUrl).getQueryParameter("code") }
        .getOrNull()
        ?.substringBefore("#")
        ?.takeIf { it.isNotBlank() }
}
