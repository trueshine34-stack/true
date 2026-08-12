package com.trueshine.healthcalendar.data

import java.time.Instant
import java.time.LocalDate

/**
 * Всё, что пользователь настроил и отметил по трекеру «без курения».
 *
 * [quitAt] == null означает, что трекер ещё не запущен: приложение показывает
 * экран первого запуска вместо счётчика.
 */
data class SmokeFreeState(
    val quitAt: Instant? = null,
    val cigarettesPerDay: Int = DEFAULT_CIGARETTES_PER_DAY,
    val pricePerPack: Double = DEFAULT_PRICE_PER_PACK,
    val cigarettesPerPack: Int = DEFAULT_CIGARETTES_PER_PACK,
    val currency: String = DEFAULT_CURRENCY,
    /** Дни, в которые пользователь отметил срыв. */
    val relapseDays: Set<LocalDate> = emptySet(),
) {
    val isConfigured: Boolean get() = quitAt != null

    /** Цена одной сигареты. */
    val pricePerCigarette: Double
        get() = if (cigarettesPerPack > 0) pricePerPack / cigarettesPerPack else 0.0

    companion object {
        const val DEFAULT_CIGARETTES_PER_DAY = 20
        const val DEFAULT_PRICE_PER_PACK = 200.0
        const val DEFAULT_CIGARETTES_PER_PACK = 20
        const val DEFAULT_CURRENCY = "₽"
    }
}
