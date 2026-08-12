package com.trueshine.tgposter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        ChannelEntity::class,
        RubricEntity::class,
        SourceEntity::class,
        ScheduleEntity::class,
        PostEntity::class,
        LogEntity::class,
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun channelDao(): ChannelDao
    abstract fun rubricDao(): RubricDao
    abstract fun sourceDao(): SourceDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun postDao(): PostDao
    abstract fun logDao(): LogDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tgposter.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
