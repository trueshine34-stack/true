package ae.dressrent.studio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun categoryToString(value: Category): String = value.name
    @TypeConverter fun stringToCategory(value: String): Category =
        runCatching { Category.valueOf(value) }.getOrDefault(Category.EVENING)

    @TypeConverter fun sourceToString(value: LeadSource): String = value.name
    @TypeConverter fun stringToSource(value: String): LeadSource =
        runCatching { LeadSource.valueOf(value) }.getOrDefault(LeadSource.OTHER)

    @TypeConverter fun statusToString(value: BookingStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): BookingStatus =
        runCatching { BookingStatus.valueOf(value) }.getOrDefault(BookingStatus.LEAD)

    @TypeConverter fun kindToString(value: PaymentKind): String = value.name
    @TypeConverter fun stringToKind(value: String): PaymentKind =
        runCatching { PaymentKind.valueOf(value) }.getOrDefault(PaymentKind.PREPAY)

    @TypeConverter fun methodToString(value: PaymentMethod): String = value.name
    @TypeConverter fun stringToMethod(value: String): PaymentMethod =
        runCatching { PaymentMethod.valueOf(value) }.getOrDefault(PaymentMethod.CASH)
}

@Database(
    entities = [Item::class, Client::class, Booking::class, Payment::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun clients(): ClientDao
    abstract fun bookings(): BookingDao
    abstract fun payments(): PaymentDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "dressrent.db"
            ).build().also { instance = it }
        }
    }
}
