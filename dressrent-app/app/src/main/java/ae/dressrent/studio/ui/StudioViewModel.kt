package ae.dressrent.studio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ae.dressrent.studio.data.Booking
import ae.dressrent.studio.data.BookingCard
import ae.dressrent.studio.data.BookingStatus
import ae.dressrent.studio.data.Client
import ae.dressrent.studio.data.DashboardState
import ae.dressrent.studio.data.Item
import ae.dressrent.studio.data.ItemStat
import ae.dressrent.studio.data.Payment
import ae.dressrent.studio.data.Repository
import ae.dressrent.studio.data.Seed
import ae.dressrent.studio.data.Settings
import ae.dressrent.studio.work.DailyBriefWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class StudioViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository.get(app)

    /** Recomputed when the user pulls to refresh or the app comes back on a new day. */
    private val today = MutableStateFlow(LocalDate.now())

    val dashboard: StateFlow<DashboardState> = repo.dashboard(today.value)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    val settings: StateFlow<Settings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    val items: StateFlow<List<Item>> = repo.allItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val itemStats: StateFlow<List<ItemStat>> = repo.itemStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val clients: StateFlow<List<Client>> = repo.allClients()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookings: StateFlow<List<BookingCard>> = repo.allBookings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun booking(id: Long): Flow<BookingCard?> = repo.booking(id)

    fun payments(bookingId: Long): Flow<List<Payment>> = repo.paymentsFor(bookingId)

    fun item(id: Long): Item? = items.value.firstOrNull { it.id == id }

    fun client(id: Long): Client? = clients.value.firstOrNull { it.id == id }

    fun saveItem(item: Item) = viewModelScope.launch {
        if (item.id == 0L) repo.items.insert(item) else repo.items.update(item)
    }

    fun deleteItem(item: Item) = viewModelScope.launch { repo.items.delete(item) }

    fun saveClient(client: Client) = viewModelScope.launch {
        if (client.id == 0L) repo.clients.insert(client) else repo.clients.update(client)
    }

    fun deleteClient(client: Client) = viewModelScope.launch { repo.clients.delete(client) }

    suspend fun hasConflict(itemId: Long, start: LocalDate, end: LocalDate, ignoreId: Long): Boolean =
        repo.hasConflict(itemId, start.toEpochDay(), end.toEpochDay(), ignoreId)

    suspend fun saveBooking(booking: Booking): Long = repo.saveBooking(booking)

    fun deleteBooking(booking: Booking) = viewModelScope.launch { repo.bookings.delete(booking) }

    fun setStatus(bookingId: Long, status: BookingStatus) = viewModelScope.launch {
        repo.setStatus(bookingId, status)
    }

    fun addPayment(payment: Payment) = viewModelScope.launch { repo.addPayment(payment) }

    fun deletePayment(payment: Payment) = viewModelScope.launch { repo.payments.delete(payment) }

    fun markReviewRequested(bookingId: Long) = viewModelScope.launch { repo.markReviewRequested(bookingId) }

    fun touchClient(clientId: Long) = viewModelScope.launch { repo.touchClient(clientId) }

    fun saveSettings(settings: Settings) = viewModelScope.launch {
        repo.settingsStore.save(settings)
        DailyBriefWorker.schedule(getApplication(), settings.reminderHour)
    }

    fun seedDemo(onDone: () -> Unit = {}) = viewModelScope.launch {
        Seed.fill(repo)
        onDone()
    }

    fun clearAll(onDone: () -> Unit = {}) = viewModelScope.launch {
        Seed.clear(repo)
        onDone()
    }

    fun previewBrief(): Pair<String, String> = DailyBriefWorker.compose(dashboard.value)
}
