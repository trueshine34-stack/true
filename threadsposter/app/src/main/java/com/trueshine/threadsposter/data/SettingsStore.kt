package com.trueshine.threadsposter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trueshine.threadsposter.data.remote.DeepSeekClient
import com.trueshine.threadsposter.data.remote.DeepSeekModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "threadsposter_settings")

data class AppSettings(
    /** Главный рубильник: выключен — ничего не публикуется и не ищется. */
    val autopilot: Boolean = true,
    /** Отдельный рубильник для поиска и ответов чужим. */
    val searchEnabled: Boolean = true,
    val deepSeekBaseUrl: String = DeepSeekClient.DEFAULT_BASE_URL,
    val deepSeekModel: String = DeepSeekModels.CHAT,
    val maxTokens: Int = 1200,
    val notifyOnError: Boolean = true,
    val notifyOnApproval: Boolean = true,
    val wifiOnly: Boolean = false,
    val keepPostsDays: Int = 60,
    val keepLeadsDays: Int = 30,
    val keepLogsDays: Int = 14,
    val hasDeepSeekKey: Boolean = false,
    // Данные приложения Meta для входа через встроенный браузер.
    val metaAppId: String = "",
    val metaRedirectUri: String = "",
    val hasMetaSecret: Boolean = false,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            autopilot = p[Keys.AUTOPILOT] ?: true,
            searchEnabled = p[Keys.SEARCH_ENABLED] ?: true,
            deepSeekBaseUrl = p[Keys.BASE_URL] ?: DeepSeekClient.DEFAULT_BASE_URL,
            deepSeekModel = p[Keys.MODEL] ?: DeepSeekModels.CHAT,
            maxTokens = p[Keys.MAX_TOKENS] ?: 1200,
            notifyOnError = p[Keys.NOTIFY_ERROR] ?: true,
            notifyOnApproval = p[Keys.NOTIFY_APPROVAL] ?: true,
            wifiOnly = p[Keys.WIFI_ONLY] ?: false,
            keepPostsDays = p[Keys.KEEP_POSTS] ?: 60,
            keepLeadsDays = p[Keys.KEEP_LEADS] ?: 30,
            keepLogsDays = p[Keys.KEEP_LOGS] ?: 14,
            hasDeepSeekKey = p[Keys.HAS_KEY] ?: false,
            metaAppId = p[Keys.META_APP_ID] ?: "",
            metaRedirectUri = p[Keys.META_REDIRECT] ?: "",
            hasMetaSecret = p[Keys.HAS_META_SECRET] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setAutopilot(value: Boolean) = put(Keys.AUTOPILOT, value)
    suspend fun setSearchEnabled(value: Boolean) = put(Keys.SEARCH_ENABLED, value)
    suspend fun setBaseUrl(value: String) =
        put(Keys.BASE_URL, value.trim().ifBlank { DeepSeekClient.DEFAULT_BASE_URL })
    suspend fun setModel(value: String) = put(Keys.MODEL, value)
    suspend fun setMaxTokens(value: Int) = put(Keys.MAX_TOKENS, value.coerceIn(256, 8192))
    suspend fun setNotifyOnError(value: Boolean) = put(Keys.NOTIFY_ERROR, value)
    suspend fun setNotifyOnApproval(value: Boolean) = put(Keys.NOTIFY_APPROVAL, value)
    suspend fun setWifiOnly(value: Boolean) = put(Keys.WIFI_ONLY, value)
    suspend fun setKeepPostsDays(value: Int) = put(Keys.KEEP_POSTS, value.coerceIn(1, 365))
    suspend fun setKeepLeadsDays(value: Int) = put(Keys.KEEP_LEADS, value.coerceIn(1, 365))
    suspend fun setKeepLogsDays(value: Int) = put(Keys.KEEP_LOGS, value.coerceIn(1, 90))
    suspend fun setHasDeepSeekKey(value: Boolean) = put(Keys.HAS_KEY, value)
    suspend fun setMetaAppId(value: String) = put(Keys.META_APP_ID, value.trim())
    suspend fun setMetaRedirectUri(value: String) = put(Keys.META_REDIRECT, value.trim())
    suspend fun setHasMetaSecret(value: Boolean) = put(Keys.HAS_META_SECRET, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private object Keys {
        val AUTOPILOT = booleanPreferencesKey("autopilot")
        val SEARCH_ENABLED = booleanPreferencesKey("search_enabled")
        val BASE_URL = stringPreferencesKey("deepseek_base_url")
        val MODEL = stringPreferencesKey("deepseek_model")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val NOTIFY_ERROR = booleanPreferencesKey("notify_error")
        val NOTIFY_APPROVAL = booleanPreferencesKey("notify_approval")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val KEEP_POSTS = intPreferencesKey("keep_posts_days")
        val KEEP_LEADS = intPreferencesKey("keep_leads_days")
        val KEEP_LOGS = intPreferencesKey("keep_logs_days")
        val HAS_KEY = booleanPreferencesKey("has_deepseek_key")
        val META_APP_ID = stringPreferencesKey("meta_app_id")
        val META_REDIRECT = stringPreferencesKey("meta_redirect_uri")
        val HAS_META_SECRET = booleanPreferencesKey("has_meta_secret")
    }
}
