package com.trueshine.tgposter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trueshine.tgposter.data.remote.DeepSeekClient
import com.trueshine.tgposter.data.remote.DeepSeekModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tgposter_settings")

data class AppSettings(
    /** Главный рубильник: выключен — ничего не генерируется и не публикуется. */
    val autopilot: Boolean = true,
    val deepSeekBaseUrl: String = DeepSeekClient.DEFAULT_BASE_URL,
    val deepSeekModel: String = DeepSeekModels.CHAT,
    val maxTokens: Int = 1600,
    val notifyOnPublish: Boolean = false,
    val notifyOnError: Boolean = true,
    val notifyOnApproval: Boolean = true,
    val wifiOnly: Boolean = false,
    val keepPostsDays: Int = 60,
    val keepLogsDays: Int = 14,
    val onboardingDone: Boolean = false,
    val hasDeepSeekKey: Boolean = false,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            autopilot = p[Keys.AUTOPILOT] ?: true,
            deepSeekBaseUrl = p[Keys.BASE_URL] ?: DeepSeekClient.DEFAULT_BASE_URL,
            deepSeekModel = p[Keys.MODEL] ?: DeepSeekModels.CHAT,
            maxTokens = p[Keys.MAX_TOKENS] ?: 1600,
            notifyOnPublish = p[Keys.NOTIFY_PUBLISH] ?: false,
            notifyOnError = p[Keys.NOTIFY_ERROR] ?: true,
            notifyOnApproval = p[Keys.NOTIFY_APPROVAL] ?: true,
            wifiOnly = p[Keys.WIFI_ONLY] ?: false,
            keepPostsDays = p[Keys.KEEP_POSTS] ?: 60,
            keepLogsDays = p[Keys.KEEP_LOGS] ?: 14,
            onboardingDone = p[Keys.ONBOARDING] ?: false,
            hasDeepSeekKey = p[Keys.HAS_KEY] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setAutopilot(value: Boolean) = put(Keys.AUTOPILOT, value)
    suspend fun setBaseUrl(value: String) = put(Keys.BASE_URL, value.trim().ifBlank { DeepSeekClient.DEFAULT_BASE_URL })
    suspend fun setModel(value: String) = put(Keys.MODEL, value)
    suspend fun setMaxTokens(value: Int) = put(Keys.MAX_TOKENS, value.coerceIn(256, 8192))
    suspend fun setNotifyOnPublish(value: Boolean) = put(Keys.NOTIFY_PUBLISH, value)
    suspend fun setNotifyOnError(value: Boolean) = put(Keys.NOTIFY_ERROR, value)
    suspend fun setNotifyOnApproval(value: Boolean) = put(Keys.NOTIFY_APPROVAL, value)
    suspend fun setWifiOnly(value: Boolean) = put(Keys.WIFI_ONLY, value)
    suspend fun setKeepPostsDays(value: Int) = put(Keys.KEEP_POSTS, value.coerceIn(1, 365))
    suspend fun setKeepLogsDays(value: Int) = put(Keys.KEEP_LOGS, value.coerceIn(1, 90))
    suspend fun setOnboardingDone(value: Boolean) = put(Keys.ONBOARDING, value)
    suspend fun setHasDeepSeekKey(value: Boolean) = put(Keys.HAS_KEY, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private object Keys {
        val AUTOPILOT = booleanPreferencesKey("autopilot")
        val BASE_URL = stringPreferencesKey("deepseek_base_url")
        val MODEL = stringPreferencesKey("deepseek_model")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val NOTIFY_PUBLISH = booleanPreferencesKey("notify_publish")
        val NOTIFY_ERROR = booleanPreferencesKey("notify_error")
        val NOTIFY_APPROVAL = booleanPreferencesKey("notify_approval")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val KEEP_POSTS = intPreferencesKey("keep_posts_days")
        val KEEP_LOGS = intPreferencesKey("keep_logs_days")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val HAS_KEY = booleanPreferencesKey("has_deepseek_key")
    }
}
