package com.trueshine.tgposter.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY createdAt")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY createdAt")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observeById(id: Long): Flow<AccountEntity?>

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("SELECT COUNT(*) FROM accounts")
    fun observeCount(): Flow<Int>
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY title")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE accountId = :accountId ORDER BY title")
    fun observeByAccount(accountId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE enabled = 1")
    suspend fun getEnabled(): List<ChannelEntity>

    @Query("SELECT * FROM channels ORDER BY title")
    suspend fun getAll(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE id = :id")
    fun observeById(id: Long): Flow<ChannelEntity?>

    @Insert
    suspend fun insert(channel: ChannelEntity): Long

    @Update
    suspend fun update(channel: ChannelEntity)

    @Delete
    suspend fun delete(channel: ChannelEntity)
}

@Dao
interface RubricDao {
    @Query("SELECT * FROM rubrics WHERE channelId = :channelId ORDER BY id")
    fun observeByChannel(channelId: Long): Flow<List<RubricEntity>>

    @Query("SELECT * FROM rubrics WHERE channelId = :channelId AND enabled = 1")
    suspend fun enabledByChannel(channelId: Long): List<RubricEntity>

    @Upsert
    suspend fun upsert(rubric: RubricEntity): Long

    @Query("UPDATE rubrics SET lastUsedAt = :ts WHERE id = :id")
    suspend fun markUsed(id: Long, ts: Long)

    @Delete
    suspend fun delete(rubric: RubricEntity)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources WHERE channelId = :channelId ORDER BY id")
    fun observeByChannel(channelId: Long): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE channelId = :channelId AND enabled = 1")
    suspend fun enabledByChannel(channelId: Long): List<SourceEntity>

    @Upsert
    suspend fun upsert(source: SourceEntity): Long

    @Delete
    suspend fun delete(source: SourceEntity)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE channelId = :channelId ORDER BY id")
    fun observeByChannel(channelId: Long): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE channelId = :channelId AND enabled = 1")
    suspend fun enabledByChannel(channelId: Long): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun byId(id: Long): ScheduleEntity?

    @Upsert
    suspend fun upsert(schedule: ScheduleEntity): Long

    @Delete
    suspend fun delete(schedule: ScheduleEntity)
}

@Dao
interface PostDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(post: PostEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(post: PostEntity): Long

    @Update
    suspend fun update(post: PostEntity)

    @Delete
    suspend fun delete(post: PostEntity)

    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun byId(id: Long): PostEntity?

    @Query("SELECT * FROM posts WHERE id = :id")
    fun observeById(id: Long): Flow<PostEntity?>

    @Query("SELECT * FROM posts WHERE status IN (:statuses) ORDER BY scheduledAt")
    fun observeByStatuses(statuses: List<String>): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts ORDER BY scheduledAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE channelId = :channelId ORDER BY scheduledAt DESC LIMIT :limit")
    fun observeByChannel(channelId: Long, limit: Int): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE status = :status AND scheduledAt <= :until ORDER BY scheduledAt")
    suspend fun dueByStatus(status: String, until: Long): List<PostEntity>

    @Query("SELECT * FROM posts WHERE status IN (:statuses) AND scheduledAt <= :until ORDER BY scheduledAt")
    suspend fun dueByStatuses(statuses: List<String>, until: Long): List<PostEntity>

    @Query("SELECT topic FROM posts WHERE channelId = :channelId AND topic != '' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentTopics(channelId: Long, limit: Int): List<String>

    @Query(
        "SELECT * FROM posts WHERE status IN ('QUEUED','READY','NEEDS_APPROVAL') " +
            "ORDER BY scheduledAt LIMIT 1"
    )
    fun observeNextPost(): Flow<PostEntity?>

    @Query("SELECT COUNT(*) FROM posts WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM posts WHERE status = 'PUBLISHED' AND publishedAt >= :since")
    fun observePublishedSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM posts WHERE status = 'FAILED' AND updatedAt >= :since")
    fun observeFailedSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM posts WHERE channelId = :channelId AND status = 'PUBLISHED'")
    suspend fun publishedCount(channelId: Long): Int

    @Query("DELETE FROM posts WHERE status IN ('PUBLISHED','SKIPPED','CANCELED','FAILED') AND updatedAt < :before")
    suspend fun purgeOld(before: Long): Int

    @Query("DELETE FROM posts WHERE channelId = :channelId AND status IN ('QUEUED') AND manual = 0")
    suspend fun clearQueuedForChannel(channelId: Long)
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(log: LogEntity)

    @Query("SELECT * FROM logs ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LogEntity>>

    @Query("SELECT * FROM logs ORDER BY ts DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<LogEntity>

    @Query("DELETE FROM logs")
    suspend fun clear()

    @Query("DELETE FROM logs WHERE ts < :before")
    suspend fun purgeOld(before: Long)
}
