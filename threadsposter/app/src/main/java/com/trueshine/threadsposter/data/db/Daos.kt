package com.trueshine.threadsposter.data.db

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

    @Query("SELECT * FROM accounts WHERE enabled = 1")
    suspend fun getEnabled(): List<AccountEntity>

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
}

@Dao
interface RubricDao {
    @Query("SELECT * FROM rubrics WHERE accountId = :accountId ORDER BY id")
    fun observeByAccount(accountId: Long): Flow<List<RubricEntity>>

    @Query("SELECT * FROM rubrics WHERE accountId = :accountId AND enabled = 1")
    suspend fun enabledByAccount(accountId: Long): List<RubricEntity>

    @Upsert
    suspend fun upsert(rubric: RubricEntity): Long

    @Query("UPDATE rubrics SET lastUsedAt = :ts WHERE id = :id")
    suspend fun markUsed(id: Long, ts: Long)

    @Delete
    suspend fun delete(rubric: RubricEntity)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE accountId = :accountId ORDER BY id")
    fun observeByAccount(accountId: Long): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE accountId = :accountId AND enabled = 1")
    suspend fun enabledByAccount(accountId: Long): List<ScheduleEntity>

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

    @Query("SELECT * FROM posts WHERE status = :status AND scheduledAt <= :until ORDER BY scheduledAt")
    suspend fun dueByStatus(status: String, until: Long): List<PostEntity>

    @Query("SELECT * FROM posts WHERE status IN (:statuses) AND scheduledAt <= :until ORDER BY scheduledAt")
    suspend fun dueByStatuses(statuses: List<String>, until: Long): List<PostEntity>

    @Query(
        "SELECT topic FROM posts WHERE accountId = :accountId AND kind = 'POST' AND topic != '' " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun recentTopics(accountId: Long, limit: Int): List<String>

    @Query("SELECT * FROM posts WHERE status IN ('QUEUED','READY','NEEDS_APPROVAL') ORDER BY scheduledAt LIMIT 1")
    fun observeNextPost(): Flow<PostEntity?>

    @Query("SELECT COUNT(*) FROM posts WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM posts WHERE status = 'PUBLISHED' AND publishedAt >= :since")
    fun observePublishedSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM posts WHERE status = 'FAILED' AND updatedAt >= :since")
    fun observeFailedSince(since: Long): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM posts WHERE accountId = :accountId AND kind = :kind " +
            "AND status = 'PUBLISHED' AND publishedAt >= :since"
    )
    suspend fun publishedCountSince(accountId: Long, kind: String, since: Long): Int

    @Query(
        "SELECT MAX(publishedAt) FROM posts WHERE accountId = :accountId AND kind = 'REPLY' " +
            "AND status = 'PUBLISHED'"
    )
    suspend fun lastReplyAt(accountId: Long): Long?

    @Query("SELECT COUNT(*) FROM posts WHERE accountId = :accountId AND status = 'PUBLISHED'")
    suspend fun publishedCount(accountId: Long): Int

    @Query("DELETE FROM posts WHERE status IN ('PUBLISHED','SKIPPED','CANCELED','FAILED') AND updatedAt < :before")
    suspend fun purgeOld(before: Long): Int

    @Query("DELETE FROM posts WHERE accountId = :accountId AND status = 'QUEUED' AND manual = 0 AND kind = 'POST'")
    suspend fun clearQueuedForAccount(accountId: Long)
}

@Dao
interface QueryDao {
    @Query("SELECT * FROM queries ORDER BY id")
    fun observeAll(): Flow<List<QueryEntity>>

    @Query("SELECT * FROM queries WHERE accountId = :accountId ORDER BY id")
    fun observeByAccount(accountId: Long): Flow<List<QueryEntity>>

    @Query("SELECT * FROM queries WHERE enabled = 1")
    suspend fun enabled(): List<QueryEntity>

    @Query("SELECT * FROM queries WHERE id = :id")
    suspend fun byId(id: Long): QueryEntity?

    @Upsert
    suspend fun upsert(query: QueryEntity): Long

    @Delete
    suspend fun delete(query: QueryEntity)
}

@Dao
interface LeadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(lead: LeadEntity): Long

    @Update
    suspend fun update(lead: LeadEntity)

    @Delete
    suspend fun delete(lead: LeadEntity)

    @Query("SELECT * FROM leads WHERE id = :id")
    suspend fun byId(id: Long): LeadEntity?

    @Query("SELECT * FROM leads WHERE id = :id")
    fun observeById(id: Long): Flow<LeadEntity?>

    @Query("SELECT * FROM leads WHERE status IN (:statuses) ORDER BY score DESC, createdAt DESC LIMIT 200")
    fun observeByStatuses(statuses: List<String>): Flow<List<LeadEntity>>

    @Query("SELECT * FROM leads WHERE status = :status ORDER BY createdAt LIMIT :limit")
    suspend fun byStatus(status: String, limit: Int): List<LeadEntity>

    @Query("SELECT COUNT(*) FROM leads WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM leads WHERE accountId = :accountId AND authorUsername = :author AND repliedAt >= :since")
    suspend fun repliesToAuthorSince(accountId: Long, author: String, since: Long): Int

    @Query("DELETE FROM leads WHERE status IN ('SKIPPED','REPLIED','FAILED') AND updatedAt < :before")
    suspend fun purgeOld(before: Long): Int
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
