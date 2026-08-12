package com.trueshine.threadsposter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        RubricEntity::class,
        ScheduleEntity::class,
        PostEntity::class,
        QueryEntity::class,
        LeadEntity::class,
        LogEntity::class,
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun rubricDao(): RubricDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun postDao(): PostDao
    abstract fun queryDao(): QueryDao
    abstract fun leadDao(): LeadDao
    abstract fun logDao(): LogDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "threadsposter.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
