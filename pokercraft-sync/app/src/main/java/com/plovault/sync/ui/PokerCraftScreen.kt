package com.plovault.sync.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.plovault.sync.data.ExportRecipe
import com.plovault.sync.net.Downloader
import com.plovault.sync.web.CaptureScript
import com.plovault.sync.web.CaptureStore
import com.plovault.sync.web.JsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран PokerCraft: обычный браузер с двумя добавками —
 * перехват скачиваемых файлов и запись «рецепта» выгрузки в режиме обучения.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PokerCraftScreen(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var training by remember { mutableStateOf(state.prefs.recipeJson == null) }
    var info by remember { mutableStateOf("") }
    var captured by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        CaptureStore.clear()
        CaptureStore.listener = { captured = CaptureStore.requests.size + CaptureStore.files.size }
        CaptureStore.onFile = { file ->
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    state.pipeline.importBytes(file.bytes, file.name, "webview").message
                }
                info = "Файл ${file.name}: $msg"
                state.refresh()
            }
        }
        onDispose {
            CaptureStore.listener = null
            CaptureStore.onFile = null
        }
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onClose) { Text("Готово") }
                    Text("Обучение", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = training, onCheckedChange = { training = it })
                    Text(
                        "перехвачено: $captured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (training) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = {
                            val best = CaptureStore.bestExportRequest()
                            if (best == null) {
                                info = "Подходящий запрос не найден. Скачайте историю рук на сайте — " +
                                    "приложение поймает запрос автоматически."
                            } else {
                                val token = state.prefs.authToken
                                val (tpl, found) = ExportRecipe.templatize(best.url, best.body, token)
                                val recipe = ExportRecipe(
                                    url = tpl.first,
                                    method = best.method,
                                    headers = ExportRecipe.templatizeHeaders(best.headers, token),
                                    body = tpl.second,
                                    dateFormat = tpl.third,
                                    capturedAt = System.currentTimeMillis(),
                                    note = buildString {
                                        append(
                                            if (found) "даты распознаны (${tpl.third})"
                                            else "даты не найдены — запрос будет повторяться без подстановки"
                                        )
                                        if (tpl.first.contains(ExportRecipe.TOKEN) ||
                                            tpl.second.contains(ExportRecipe.TOKEN)
                                        ) append("; токен вынесен в плейсхолдер")
                                    }
                                )
                                state.prefs.recipeJson = recipe.toJson()
                                info = "Рецепт сохранён: ${recipe.method} ${recipe.url.take(90)}\n${recipe.note}"
                            }
                        }) { Text("Сохранить рецепт") }
                        OutlinedButton(onClick = { CaptureStore.clear(); info = "Перехват очищен" }) {
                            Text("Очистить")
                        }
                    }
                }
                if (info.isNotBlank()) {
                    Text(
                        info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = true
                    if (state.prefs.desktopUa) settings.userAgentString = com.plovault.sync.data.Prefs.DESKTOP_UA
                    state.prefs.userAgent = settings.userAgentString

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    addJavascriptInterface(JsBridge(), "PloVault")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript(CaptureScript.JS, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(CaptureScript.JS, null)
                            CookieManager.getInstance().flush()
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false
                    }

                    setDownloadListener { url, _, contentDisposition, _, _ ->
                        val name = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""")
                            .find(contentDisposition ?: "")?.groupValues?.get(1)
                            ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "export.bin" }
                        scope.launch {
                            info = "Скачиваю $name…"
                            val msg = withContext(Dispatchers.IO) {
                                if (url.startsWith("data:")) {
                                    val b64 = url.substringAfter(",", "")
                                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                    state.pipeline.importBytes(bytes, name, "download-data").message
                                } else {
                                    val res = Downloader(state.context).get(url)
                                    if (!res.ok) "Не удалось скачать: ${res.error}"
                                    else state.pipeline.importBytes(res.bytes, res.fileName, "download").message
                                }
                            }
                            info = msg
                            state.refresh()
                        }
                    }

                    loadUrl(state.prefs.portalUrl)
                }
            }
        )
    }
}
