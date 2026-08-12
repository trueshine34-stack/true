package com.trueshine.healthcalendar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trueshine.healthcalendar.data.SmokeFreeRepository
import com.trueshine.healthcalendar.data.SmokeFreeState
import com.trueshine.healthcalendar.domain.SmokeFreeStats
import com.trueshine.healthcalendar.domain.statsAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate

data class HealthUiState(
    val state: SmokeFreeState = SmokeFreeState(),
    val stats: SmokeFreeStats = SmokeFreeStats.EMPTY,
    val now: Instant = Instant.EPOCH,
)

class HealthViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SmokeFreeRepository(application)

    /** Тикает раз в секунду, чтобы счётчик на главном экране шёл вживую. */
    private val ticker = flow {
        while (true) {
            emit(Instant.now())
            delay(TICK_MILLIS)
        }
    }

    val uiState: StateFlow<HealthUiState> =
        combine(repository.state, ticker) { state, now ->
            HealthUiState(state = state, stats = state.statsAt(now), now = now)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = HealthUiState(
                state = repository.state.value,
                stats = repository.state.value.statsAt(Instant.now()),
                now = Instant.now(),
            ),
        )

    fun startTracking(quitAt: Instant) = repository.start(quitAt)

    fun setQuitAt(quitAt: Instant) = repository.setQuitAt(quitAt)

    fun setCigarettesPerDay(value: Int) = repository.setCigarettesPerDay(value)

    fun setPricePerPack(value: Double) = repository.setPricePerPack(value)

    fun setCigarettesPerPack(value: Int) = repository.setCigarettesPerPack(value)

    fun setCurrency(value: String) = repository.setCurrency(value)

    fun toggleRelapse(day: LocalDate) = repository.toggleRelapse(day)

    fun reset() = repository.reset()

    private companion object {
        const val TICK_MILLIS = 1000L
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
