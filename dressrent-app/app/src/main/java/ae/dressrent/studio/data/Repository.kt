package ae.dressrent.studio.data

import android.content.Context
import ae.dressrent.studio.util.Dates
import ae.dressrent.studio.util.Messages
import ae.dressrent.studio.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class ActionKind(val label: String, val priority: Int) {
    OVERDUE("Просрочен возврат", 0),
    HAND_OVER("Выдача сегодня", 1),
    COLLECT("Забрать доплату", 2),
    RETURN_DUE("Возврат сегодня", 3),
    LEAD_FOLLOW_UP("Дожать заявку", 4),
    REVIEW("Попросить отзыв", 5),
    WIN_BACK("Вернуть клиентку", 6),
    PROMOTE("Продать свободный день", 7)
}

/**
 * One concrete thing to do today, with the text already written. The whole point of the
 * dashboard: turn "надо что-то делать" into a list you can finish in fifteen minutes.
 */
data class RevenueAction(
    val id: String,
    val kind: ActionKind,
    val title: String,
    val subtitle: String,
    val message: String,
    val phone: String?,
    val bookingId: Long? = null,
    val potential: Minor = 0
)

data class DashboardState(
    val date: LocalDate = LocalDate.now(),
    val settings: Settings = Settings(),
    val earnedToday: Minor = 0,
    val week: List<DayRevenue> = emptyList(),
    val weekTotal: Minor = 0,
    val monthTotal: Minor = 0,
    val bookedAhead: Minor = 0,
    val pickups: List<BookingCard> = emptyList(),
    val returnsDue: List<BookingCard> = emptyList(),
    val overdue: List<BookingCard> = emptyList(),
    val activeBookings: List<BookingCard> = emptyList(),
    val actions: List<RevenueAction> = emptyList()
) {
    val goal: Minor get() = settings.dailyGoal
    val gap: Minor get() = (goal - earnedToday).coerceAtLeast(0)
    val progress: Float get() = if (goal <= 0) 1f else (earnedToday.toFloat() / goal).coerceIn(0f, 1f)
    val goalReached: Boolean get() = earnedToday >= goal && goal > 0
    /** Money already promised by confirmed bookings but not yet collected. */
    val potentialToday: Minor get() = actions.sumOf { it.potential }
}

class Repository(context: Context) {

    private val db = AppDatabase.get(context)
    val items: ItemDao = db.items()
    val clients: ClientDao = db.clients()
    val bookings: BookingDao = db.bookings()
    val payments: PaymentDao = db.payments()
    val settingsStore = SettingsStore(context)

    val settings: Flow<Settings> = settingsStore.settings

    fun allItems(): Flow<List<Item>> = items.observeAll()
    fun activeItems(): Flow<List<Item>> = items.observeActive()
    fun itemStats(): Flow<List<ItemStat>> = items.observeStats()
    fun allClients(): Flow<List<Client>> = clients.observeAll()
    fun allBookings(): Flow<List<BookingCard>> = bookings.observeAll()
    fun booking(id: Long): Flow<BookingCard?> = bookings.observeCard(id)
    fun paymentsFor(id: Long): Flow<List<Payment>> = payments.observeForBooking(id)

    suspend fun isEmpty(): Boolean = items.count() == 0 && clients.count() == 0

    /**
     * Refuses to double-book a dress: [BookingStatus.blocksCalendar] statuses hold the calendar,
     * leads do not.
     */
    suspend fun hasConflict(itemId: Long, start: Long, end: Long, ignoreId: Long = 0): Boolean =
        bookings.conflicts(itemId, start, end, ignoreId) > 0

    suspend fun saveBooking(booking: Booking): Long =
        if (booking.id == 0L) bookings.insert(booking) else { bookings.update(booking); booking.id }

    suspend fun addPayment(payment: Payment): Long = payments.insert(payment)

    suspend fun setStatus(bookingId: Long, status: BookingStatus) = bookings.setStatus(bookingId, status)

    suspend fun markReviewRequested(bookingId: Long) = bookings.markReviewRequested(bookingId)

    suspend fun touchClient(clientId: Long) {
        clients.byId(clientId)?.let { clients.update(it.copy(lastContactAt = System.currentTimeMillis())) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun dashboard(today: LocalDate = LocalDate.now()): Flow<DashboardState> =
        settings.flatMapLatest { s -> buildDashboard(today, s) }

    private fun buildDashboard(today: LocalDate, s: Settings): Flow<DashboardState> {
        val day = today.toEpochDay()
        val monthStart = today.withDayOfMonth(1).toEpochDay()

        val money = combine(
            payments.observeDayRevenue(day),
            payments.observeDailyRevenue(day - 6, day),
            payments.observeRangeRevenue(monthStart, day),
            bookings.observeBookedValue(day, day + 13)
        ) { today1, week, month, ahead -> Money4(today1, week, month, ahead) }

        val work = combine(
            bookings.observePickups(day),
            bookings.observeReturnsDue(day),
            bookings.observeOverdue(day),
            bookings.observeOpenLeads(day),
            bookings.observeReviewCandidates(day - 21)
        ) { pickups, returns, overdue, leads, reviews -> Work5(pickups, returns, overdue, leads, reviews) }

        val people = combine(
            clients.observeDormant(day - s.dormantDays),
            bookings.observeActive()
        ) { dormant, active -> dormant to active }

        return combine(money, work, people) { m, w, p ->
            val weekDays = (0..6).map { offset ->
                val d = day - 6 + offset
                DayRevenue(d, m.week.firstOrNull { it.date == d }?.total ?: 0)
            }
            val state = DashboardState(
                date = today,
                settings = s,
                earnedToday = m.today,
                week = weekDays,
                weekTotal = weekDays.sumOf { it.total },
                monthTotal = m.month,
                bookedAhead = m.ahead,
                pickups = w.pickups,
                returnsDue = w.returns,
                overdue = w.overdue,
                activeBookings = p.second
            )
            state.copy(actions = buildActions(state, w, p.first, today, s))
        }
    }

    private fun buildActions(
        state: DashboardState,
        w: Work5,
        dormant: List<Client>,
        today: LocalDate,
        s: Settings
    ): List<RevenueAction> {
        val actions = mutableListOf<RevenueAction>()

        w.overdue.forEach { card ->
            val late = Dates.daysBetween(card.booking.end, today)
            actions += RevenueAction(
                id = "overdue-${card.booking.id}",
                kind = ActionKind.OVERDUE,
                title = "${card.clientName} — просрочка $late ${Dates.plural(late, "день", "дня", "дней")}",
                subtitle = "«${card.itemTitle}» ждём с ${Dates.human(card.booking.end)}. Платье не работает и не приносит денег.",
                message = Messages.overdue(card, s, late),
                phone = card.clientPhone,
                bookingId = card.booking.id,
                potential = card.outstanding
            )
        }

        w.pickups.forEach { card ->
            actions += RevenueAction(
                id = "pickup-${card.booking.id}",
                kind = if (card.outstanding > 0) ActionKind.COLLECT else ActionKind.HAND_OVER,
                title = "${card.clientName} — выдача «${card.itemTitle}»",
                subtitle = if (card.outstanding > 0)
                    "Сегодня забрать ${Money.format(card.outstanding, s.currency)} доплаты."
                else "Оплачено полностью. Согласуйте время выдачи.",
                message = Messages.pickupToday(card, s),
                phone = card.clientPhone,
                bookingId = card.booking.id,
                potential = card.outstanding
            )
        }

        w.returns.forEach { card ->
            actions += RevenueAction(
                id = "return-${card.booking.id}",
                kind = ActionKind.RETURN_DUE,
                title = "${card.clientName} — возврат сегодня",
                subtitle = "«${card.itemTitle}». После возврата платье снова можно сдать.",
                message = Messages.returnDue(card, s),
                phone = card.clientPhone,
                bookingId = card.booking.id
            )
        }

        w.leads
            .filter { Dates.daysBetween(epochToDate(it.booking.createdAt), today) >= s.leadFollowUpDays }
            .forEach { card ->
                actions += RevenueAction(
                    id = "lead-${card.booking.id}",
                    kind = ActionKind.LEAD_FOLLOW_UP,
                    title = "${card.clientName} — заявка без оплаты",
                    subtitle = "«${card.itemTitle}» на ${Dates.human(card.booking.start)}. " +
                        "Самый дешёвый способ закрыть день: ${Money.format(card.booking.price, s.currency)}.",
                    message = Messages.leadFollowUp(card, s),
                    phone = card.clientPhone,
                    bookingId = card.booking.id,
                    potential = card.booking.price
                )
            }

        w.reviews.take(3).forEach { card ->
            actions += RevenueAction(
                id = "review-${card.booking.id}",
                kind = ActionKind.REVIEW,
                title = "${card.clientName} — попросить отзыв",
                subtitle = "Вернула «${card.itemTitle}» ${Dates.human(card.booking.end)}. Отзывы приводят новые заявки.",
                message = Messages.reviewRequest(card, s),
                phone = card.clientPhone,
                bookingId = card.booking.id
            )
        }

        dormant.take(3).forEach { client ->
            val silent = client.lastContactAt?.let { Dates.daysBetween(epochToDate(it), today) } ?: s.dormantDays.toLong()
            actions += RevenueAction(
                id = "winback-${client.id}",
                kind = ActionKind.WIN_BACK,
                title = "${client.name} — давно не арендовала",
                subtitle = "Постоянные клиентки соглашаются чаще всего. Промокод ${s.promoCode}.",
                message = Messages.winBack(client, s, silent),
                phone = client.phone
            )
        }

        if (state.gap > 0 && w.pickups.isEmpty() && w.leads.isEmpty()) {
            actions += RevenueAction(
                id = "promote",
                kind = ActionKind.PROMOTE,
                title = "Сегодня нет ни выдач, ни заявок",
                subtitle = "Пустой день не закроется сам. Опубликуйте свободные даты в сторис и статусе WhatsApp.",
                message = Messages.freeDayPromo(s, state.gap),
                phone = null
            )
        }

        return actions.sortedWith(compareBy({ it.kind.priority }, { it.title }))
    }

    private fun epochToDate(millis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    suspend fun currentSettings(): Settings = settings.first()

    /** Wipes inventory, clients, bookings and payments. Settings are kept. */
    suspend fun clearAll() = withContext(Dispatchers.IO) { db.clearAllTables() }

    private data class Money4(val today: Minor, val week: List<DayRevenue>, val month: Minor, val ahead: Minor)

    private data class Work5(
        val pickups: List<BookingCard>,
        val returns: List<BookingCard>,
        val overdue: List<BookingCard>,
        val leads: List<BookingCard>,
        val reviews: List<BookingCard>
    )

    companion object {
        @Volatile private var instance: Repository? = null

        fun get(context: Context): Repository = instance ?: synchronized(this) {
            instance ?: Repository(context.applicationContext).also { instance = it }
        }
    }
}

/** Convenience for screens that only need the goal line. */
fun Repository.goalLine(): Flow<String> = settings.map { "План дня: ${Money.format(it.dailyGoal, it.currency)}" }
