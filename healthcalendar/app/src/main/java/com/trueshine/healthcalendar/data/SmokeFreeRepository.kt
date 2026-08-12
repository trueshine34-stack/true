package com.trueshine.healthcalendar.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate

/**
 * Хранит состояние трекера в SharedPreferences и отдаёт его наружу как [StateFlow].
 *
 * Данных мало и пишутся они редко (по нажатию пользователя), поэтому базы данных
 * здесь не нужно. Когда в календарь добавятся другие трекеры, у каждого будет
 * свой репозиторий с таким же контрактом.
 */
class SmokeFreeRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SmokeFreeState> = _state.asStateFlow()

    fun start(quitAt: Instant) = update { it.copy(quitAt = quitAt) }

    fun setQuitAt(quitAt: Instant) = update { current ->
        // Срывы до новой даты отказа больше не имеют смысла.
        val quitDay = quitAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        current.copy(
            quitAt = quitAt,
            relapseDays = current.relapseDays.filterNot { it < quitDay }.toSet(),
        )
    }

    fun setCigarettesPerDay(value: Int) = update { it.copy(cigarettesPerDay = value.coerceIn(1, 200)) }

    fun setPricePerPack(value: Double) = update { it.copy(pricePerPack = value.coerceIn(0.0, 100_000.0)) }

    fun setCigarettesPerPack(value: Int) = update { it.copy(cigarettesPerPack = value.coerceIn(1, 100)) }

    fun setCurrency(value: String) = update { it.copy(currency = value.take(4).ifBlank { SmokeFreeState.DEFAULT_CURRENCY }) }

    fun toggleRelapse(day: LocalDate) = update { current ->
        val days = current.relapseDays.toMutableSet()
        if (!days.remove(day)) days.add(day)
        current.copy(relapseDays = days)
    }

    /** Полный сброс: трекер возвращается к экрану первого запуска. */
    fun reset() {
        prefs.edit().clear().apply()
        _state.value = SmokeFreeState()
    }

    private fun update(transform: (SmokeFreeState) -> SmokeFreeState) {
        val next = transform(_state.value)
        write(next)
        _state.value = next
    }

    private fun read(): SmokeFreeState {
        val defaults = SmokeFreeState()
        val quitMillis = prefs.getLong(KEY_QUIT_AT, NO_QUIT_DATE)
        return SmokeFreeState(
            quitAt = if (quitMillis == NO_QUIT_DATE) null else Instant.ofEpochMilli(quitMillis),
            cigarettesPerDay = prefs.getInt(KEY_CIGS_PER_DAY, defaults.cigarettesPerDay),
            pricePerPack = prefs.getFloat(KEY_PRICE_PER_PACK, defaults.pricePerPack.toFloat()).toDouble(),
            cigarettesPerPack = prefs.getInt(KEY_CIGS_PER_PACK, defaults.cigarettesPerPack),
            currency = prefs.getString(KEY_CURRENCY, defaults.currency) ?: defaults.currency,
            relapseDays = prefs.getStringSet(KEY_RELAPSE_DAYS, emptySet())
                .orEmpty()
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .toSet(),
        )
    }

    private fun write(state: SmokeFreeState) {
        prefs.edit()
            .putLong(KEY_QUIT_AT, state.quitAt?.toEpochMilli() ?: NO_QUIT_DATE)
            .putInt(KEY_CIGS_PER_DAY, state.cigarettesPerDay)
            .putFloat(KEY_PRICE_PER_PACK, state.pricePerPack.toFloat())
            .putInt(KEY_CIGS_PER_PACK, state.cigarettesPerPack)
            .putString(KEY_CURRENCY, state.currency)
            .putStringSet(KEY_RELAPSE_DAYS, state.relapseDays.map { it.toString() }.toSet())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "health_calendar"
        const val KEY_QUIT_AT = "quit_at"
        const val KEY_CIGS_PER_DAY = "cigarettes_per_day"
        const val KEY_PRICE_PER_PACK = "price_per_pack"
        const val KEY_CIGS_PER_PACK = "cigarettes_per_pack"
        const val KEY_CURRENCY = "currency"
        const val KEY_RELAPSE_DAYS = "relapse_days"
        const val NO_QUIT_DATE = -1L
    }
}
