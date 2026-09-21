package com.plovault.sync.data

import android.content.Context

/** Настройки приложения. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("plovault", Context.MODE_PRIVATE)

    /**
     * Полная ссылка на PokerCraft — та, что открывается из клиента GGPoker,
     * вместе с персональным токеном. Хранится только на этом устройстве.
     */
    var portalUrl: String
        get() = sp.getString("portal_url", DEFAULT_PORTAL)!!
        set(v) {
            sp.edit().putString("portal_url", v).apply()
            extractToken(v)?.let { authToken = it }
        }

    /** Токен доступа из ссылки PokerCraft. Подставляется в запрос выгрузки при автосинхронизации. */
    var authToken: String?
        get() = sp.getString("auth_token", null)
        set(v) = sp.edit().putString("auth_token", v).putLong("token_saved_at", System.currentTimeMillis()).apply()

    val tokenSavedAt: Long get() = sp.getLong("token_saved_at", 0L)

    var heroName: String
        get() = sp.getString("hero", "Hero")!!
        set(v) = sp.edit().putString("hero", v).apply()

    /** JSON рецепта выгрузки, записанный в режиме обучения. */
    var recipeJson: String?
        get() = sp.getString("recipe", null)
        set(v) = sp.edit().putString("recipe", v).apply()

    var userAgent: String?
        get() = sp.getString("ua", null)
        set(v) = sp.edit().putString("ua", v).apply()

    var autoSync: Boolean
        get() = sp.getBoolean("auto_sync", false)
        set(v) = sp.edit().putBoolean("auto_sync", v).apply()

    /** Периодичность автосинхронизации в часах (минимум 1 — ограничение WorkManager). */
    var syncIntervalHours: Int
        get() = sp.getInt("sync_hours", 6)
        set(v) = sp.edit().putInt("sync_hours", v.coerceIn(1, 168)).apply()

    /** Сколько дней назад запрашивать при каждой синхронизации (перекрытие). */
    var syncWindowDays: Int
        get() = sp.getInt("window_days", 3)
        set(v) = sp.edit().putInt("window_days", v.coerceIn(1, 90)).apply()

    var lastSyncTs: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) = sp.edit().putLong("last_sync", v).apply()

    var lastSyncStatus: String
        get() = sp.getString("last_status", "Синхронизаций ещё не было")!!
        set(v) = sp.edit().putString("last_status", v).apply()

    /** Десктопный User-Agent — часть страниц PokerCraft заточена под ПК. */
    var desktopUa: Boolean
        get() = sp.getBoolean("desktop_ua", false)
        set(v) = sp.edit().putBoolean("desktop_ua", v).apply()

    var onlyPlo4Rush: Boolean
        get() = sp.getBoolean("only_plo4_rush", true)
        set(v) = sp.edit().putBoolean("only_plo4_rush", v).apply()

    companion object {
        const val DEFAULT_PORTAL = "https://my.pokercraft.com/?lang=ru"

        private val TOKEN_PARAMS = listOf("token", "access_token", "accessToken", "authToken", "sessionToken")

        /** Достаёт токен из ссылки PokerCraft. */
        fun extractToken(url: String): String? {
            for (name in TOKEN_PARAMS) {
                val m = Regex("""[?&]$name=([^&#\s]+)""", RegexOption.IGNORE_CASE).find(url)
                if (m != null) return m.groupValues[1]
            }
            return null
        }

        /** Короткий вид токена для интерфейса — целиком его показывать незачем. */
        fun maskToken(token: String?): String =
            if (token.isNullOrBlank()) "нет"
            else token.take(4) + "…" + token.takeLast(4) + " (${token.length} симв.)"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"

        val PORTAL_PRESETS = listOf(
            "https://my.pokercraft.com/?lang=ru",
            "https://my.pokercraft.com/",
            "https://pokercraft.gg/"
        )
    }
}
