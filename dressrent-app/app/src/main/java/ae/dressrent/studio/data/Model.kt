package ae.dressrent.studio.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * All money in this app is stored as minor units (fils for AED, cents for USD) so that
 * totals never drift the way floating point does.
 */
typealias Minor = Long

enum class Category(val label: String) {
    EVENING("Вечернее"),
    COCKTAIL("Коктейльное"),
    WEDDING_GUEST("На свадьбу"),
    ABAYA("Абайя / кафтан"),
    ACCESSORY("Аксессуар")
}

enum class LeadSource(val label: String) {
    INSTAGRAM("Instagram"),
    TIKTOK("TikTok"),
    WHATSAPP("WhatsApp"),
    WEBSITE("Сайт"),
    REFERRAL("Рекомендация"),
    WALK_IN("Пришла в шоурум"),
    OTHER("Другое")
}

enum class BookingStatus(val label: String) {
    /** Client asked about a date but has not paid yet — the pipeline that needs follow-up. */
    LEAD("Заявка"),
    CONFIRMED("Подтверждено"),
    OUT("Выдано"),
    RETURNED("Возвращено"),
    CANCELLED("Отменено");

    /** Statuses that actually hold a dress on the calendar. */
    val blocksCalendar: Boolean get() = this == CONFIRMED || this == OUT
}

enum class PaymentKind(val label: String, val isRevenue: Boolean) {
    PREPAY("Предоплата", true),
    BALANCE("Доплата", true),
    EXTRA("Доп. услуги", true),
    DEPOSIT("Залог", false),
    DEPOSIT_REFUND("Возврат залога", false)
}

enum class PaymentMethod(val label: String) {
    CASH("Наличные"),
    CARD("Карта"),
    TRANSFER("Перевод")
}

@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val brand: String = "",
    val category: Category = Category.EVENING,
    val size: String = "",
    val color: String = "",
    val pricePerDay: Minor = 0,
    val deposit: Minor = 0,
    /** What the piece cost the studio — drives the payback indicator. */
    val purchaseCost: Minor = 0,
    val photoUri: String? = null,
    val active: Boolean = true,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "clients", indices = [Index("phone")])
data class Client(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Digits only, international format, e.g. 971501234567 — wa.me needs it that way. */
    val phone: String = "",
    val source: LeadSource = LeadSource.INSTAGRAM,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastContactAt: Long? = null
)

@Entity(
    tableName = "bookings",
    indices = [Index("itemId"), Index("clientId"), Index("startDate")],
    foreignKeys = [
        ForeignKey(Item::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Client::class, ["id"], ["clientId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class Booking(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val clientId: Long,
    /** Epoch day of hand-over. */
    val startDate: Long,
    /** Epoch day of the return, inclusive. */
    val endDate: Long,
    val eventName: String = "",
    val price: Minor = 0,
    val deposit: Minor = 0,
    val status: BookingStatus = BookingStatus.LEAD,
    val notes: String = "",
    val reviewRequested: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val start: LocalDate get() = LocalDate.ofEpochDay(startDate)
    val end: LocalDate get() = LocalDate.ofEpochDay(endDate)
    val days: Int get() = (endDate - startDate + 1).toInt().coerceAtLeast(1)
}

@Entity(
    tableName = "payments",
    indices = [Index("bookingId"), Index("date")],
    foreignKeys = [ForeignKey(Booking::class, ["id"], ["bookingId"], onDelete = ForeignKey.CASCADE)]
)
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookingId: Long,
    val amount: Minor,
    /** Epoch day the money actually changed hands — this is what the daily goal counts. */
    val date: Long,
    val kind: PaymentKind = PaymentKind.PREPAY,
    val method: PaymentMethod = PaymentMethod.CASH,
    val note: String = ""
)

/** A booking joined with the names needed to render a row without extra queries. */
data class BookingCard(
    @Embedded val booking: Booking,
    val itemTitle: String,
    val itemPhotoUri: String?,
    val clientName: String,
    val clientPhone: String,
    val collected: Minor
) {
    val outstanding: Minor get() = (booking.price - collected).coerceAtLeast(0)
}
