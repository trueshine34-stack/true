package ae.dressrent.studio.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ae.dressrent.studio.util.DEFAULT_AED_PER_USD
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val businessName: String = "DressRentDubai",
    val currency: String = "AED",
    /** 184 AED ≈ $50 at the pegged rate — the goal the dashboard is built around. */
    val dailyGoal: Minor = 18_400,
    val aedPerUsd: Double = DEFAULT_AED_PER_USD,
    val countryCode: String = "971",
    val reminderHour: Int = 9,
    /** A request with no payment after this many days gets a follow-up task. */
    val leadFollowUpDays: Int = 2,
    /** A past client silent for this many days gets a win-back task. */
    val dormantDays: Int = 45,
    val promoCode: String = "COMEBACK10",
    val showroomAddress: String = "Downtown Dubai",
    val seeded: Boolean = false
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val business = stringPreferencesKey("business_name")
        val currency = stringPreferencesKey("currency")
        val goal = longPreferencesKey("daily_goal")
        val rate = doublePreferencesKey("aed_per_usd")
        val country = stringPreferencesKey("country_code")
        val hour = intPreferencesKey("reminder_hour")
        val leadDays = intPreferencesKey("lead_follow_up_days")
        val dormantDays = intPreferencesKey("dormant_days")
        val promo = stringPreferencesKey("promo_code")
        val address = stringPreferencesKey("showroom_address")
        val seeded = booleanPreferencesKey("seeded")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            businessName = p[Keys.business] ?: d.businessName,
            currency = p[Keys.currency] ?: d.currency,
            dailyGoal = p[Keys.goal] ?: d.dailyGoal,
            aedPerUsd = p[Keys.rate] ?: d.aedPerUsd,
            countryCode = p[Keys.country] ?: d.countryCode,
            reminderHour = p[Keys.hour] ?: d.reminderHour,
            leadFollowUpDays = p[Keys.leadDays] ?: d.leadFollowUpDays,
            dormantDays = p[Keys.dormantDays] ?: d.dormantDays,
            promoCode = p[Keys.promo] ?: d.promoCode,
            showroomAddress = p[Keys.address] ?: d.showroomAddress,
            seeded = p[Keys.seeded] ?: d.seeded
        )
    }

    suspend fun save(settings: Settings) {
        context.dataStore.edit { p ->
            p[Keys.business] = settings.businessName
            p[Keys.currency] = settings.currency
            p[Keys.goal] = settings.dailyGoal
            p[Keys.rate] = settings.aedPerUsd
            p[Keys.country] = settings.countryCode
            p[Keys.hour] = settings.reminderHour
            p[Keys.leadDays] = settings.leadFollowUpDays
            p[Keys.dormantDays] = settings.dormantDays
            p[Keys.promo] = settings.promoCode
            p[Keys.address] = settings.showroomAddress
        }
    }

    suspend fun markSeeded() {
        context.dataStore.edit { it[Keys.seeded] = true }
    }
}
