package ae.dressrent.studio.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Money that counts toward the daily goal. Deposits are the client's money, not revenue. */
private const val REVENUE_KINDS = "('PREPAY','BALANCE','EXTRA')"
private const val HELD = "('CONFIRMED','OUT')"

private const val CARD_SELECT = """
    SELECT b.*, i.title AS itemTitle, i.photoUri AS itemPhotoUri,
           c.name AS clientName, c.phone AS clientPhone,
           IFNULL((SELECT SUM(p.amount) FROM payments p
                   WHERE p.bookingId = b.id AND p.kind IN $REVENUE_KINDS), 0) AS collected
    FROM bookings b
    JOIN items i ON i.id = b.itemId
    JOIN clients c ON c.id = b.clientId
"""

data class DayRevenue(val date: Long, val total: Minor)

data class ItemStat(
    val id: Long,
    val title: String,
    val photoUri: String?,
    val purchaseCost: Minor,
    val rentals: Int,
    val revenue: Minor
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY active DESC, title ASC")
    fun observeAll(): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE active = 1 ORDER BY title ASC")
    fun observeActive(): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE id = :id")
    fun observe(id: Long): Flow<Item?>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: Long): Item?

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int

    @Query(
        """
        SELECT i.id AS id, i.title AS title, i.photoUri AS photoUri, i.purchaseCost AS purchaseCost,
               (SELECT COUNT(*) FROM bookings b
                 WHERE b.itemId = i.id AND b.status IN ('OUT','RETURNED')) AS rentals,
               IFNULL((SELECT SUM(p.amount) FROM payments p
                        JOIN bookings b2 ON b2.id = p.bookingId
                       WHERE b2.itemId = i.id AND p.kind IN $REVENUE_KINDS), 0) AS revenue
        FROM items i
        ORDER BY revenue DESC, i.title ASC
        """
    )
    fun observeStats(): Flow<List<ItemStat>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Item): Long

    @Update
    suspend fun update(item: Item)

    @Delete
    suspend fun delete(item: Item)
}

@Dao
interface ClientDao {
    @Query("SELECT * FROM clients ORDER BY name ASC")
    fun observeAll(): Flow<List<Client>>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun byId(id: Long): Client?

    @Query("SELECT COUNT(*) FROM clients")
    suspend fun count(): Int

    /**
     * Past clients who have not rented since [sinceDay] — the cheapest source of the next booking,
     * because they already trust the studio.
     */
    @Query(
        """
        SELECT c.* FROM clients c
        JOIN bookings b ON b.clientId = c.id AND b.status = 'RETURNED'
        GROUP BY c.id
        HAVING MAX(b.endDate) < :sinceDay
        ORDER BY MAX(b.endDate) DESC
        LIMIT 10
        """
    )
    fun observeDormant(sinceDay: Long): Flow<List<Client>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(client: Client): Long

    @Update
    suspend fun update(client: Client)

    @Delete
    suspend fun delete(client: Client)
}

@Dao
interface BookingDao {
    @Query("$CARD_SELECT ORDER BY b.startDate DESC, b.id DESC")
    fun observeAll(): Flow<List<BookingCard>>

    @Query("$CARD_SELECT WHERE b.id = :id")
    fun observeCard(id: Long): Flow<BookingCard?>

    @Query("SELECT * FROM bookings WHERE id = :id")
    suspend fun byId(id: Long): Booking?

    /** Dresses to hand over on [day]. */
    @Query("$CARD_SELECT WHERE b.startDate = :day AND b.status = 'CONFIRMED' ORDER BY b.id")
    fun observePickups(day: Long): Flow<List<BookingCard>>

    /** Dresses due back on [day]. */
    @Query("$CARD_SELECT WHERE b.endDate = :day AND b.status = 'OUT' ORDER BY b.id")
    fun observeReturnsDue(day: Long): Flow<List<BookingCard>>

    /** Still out after the agreed return date — every extra day is an unbilled day. */
    @Query("$CARD_SELECT WHERE b.endDate < :day AND b.status = 'OUT' ORDER BY b.endDate ASC")
    fun observeOverdue(day: Long): Flow<List<BookingCard>>

    /** Requests that never turned into a paid booking. */
    @Query("$CARD_SELECT WHERE b.status = 'LEAD' AND b.endDate >= :day ORDER BY b.startDate ASC")
    fun observeOpenLeads(day: Long): Flow<List<BookingCard>>

    /** Finished rentals that were never asked for a review. */
    @Query(
        "$CARD_SELECT WHERE b.status = 'RETURNED' AND b.reviewRequested = 0 AND b.endDate >= :sinceDay " +
            "ORDER BY b.endDate DESC LIMIT 10"
    )
    fun observeReviewCandidates(sinceDay: Long): Flow<List<BookingCard>>

    @Query("$CARD_SELECT WHERE b.status IN $HELD ORDER BY b.startDate ASC")
    fun observeActive(): Flow<List<BookingCard>>

    /** Confirmed money already on the calendar for the days ahead. */
    @Query("SELECT IFNULL(SUM(price), 0) FROM bookings WHERE status IN $HELD AND startDate BETWEEN :from AND :to")
    fun observeBookedValue(from: Long, to: Long): Flow<Minor>

    @Query(
        """
        SELECT COUNT(*) FROM bookings
        WHERE itemId = :itemId AND id != :ignoreId AND status IN $HELD
          AND startDate <= :end AND endDate >= :start
        """
    )
    suspend fun conflicts(itemId: Long, start: Long, end: Long, ignoreId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(booking: Booking): Long

    @Update
    suspend fun update(booking: Booking)

    @Delete
    suspend fun delete(booking: Booking)

    @Query("UPDATE bookings SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: BookingStatus)

    @Query("UPDATE bookings SET reviewRequested = 1 WHERE id = :id")
    suspend fun markReviewRequested(id: Long)
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE bookingId = :bookingId ORDER BY date DESC, id DESC")
    fun observeForBooking(bookingId: Long): Flow<List<Payment>>

    @Query("SELECT IFNULL(SUM(amount), 0) FROM payments WHERE date = :day AND kind IN $REVENUE_KINDS")
    fun observeDayRevenue(day: Long): Flow<Minor>

    @Query("SELECT IFNULL(SUM(amount), 0) FROM payments WHERE date BETWEEN :from AND :to AND kind IN $REVENUE_KINDS")
    fun observeRangeRevenue(from: Long, to: Long): Flow<Minor>

    @Query(
        """
        SELECT date AS date, SUM(amount) AS total FROM payments
        WHERE date BETWEEN :from AND :to AND kind IN $REVENUE_KINDS
        GROUP BY date ORDER BY date ASC
        """
    )
    fun observeDailyRevenue(from: Long, to: Long): Flow<List<DayRevenue>>

    @Query("SELECT * FROM payments ORDER BY date DESC, id DESC")
    suspend fun all(): List<Payment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payment: Payment): Long

    @Delete
    suspend fun delete(payment: Payment)
}
