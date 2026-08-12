package com.trueshine.tgposter

import android.content.Context
import com.trueshine.tgposter.core.SecretStore
import com.trueshine.tgposter.data.SettingsStore
import com.trueshine.tgposter.data.backup.BackupRepository
import com.trueshine.tgposter.data.db.AppDatabase
import com.trueshine.tgposter.data.remote.DeepSeekClient
import com.trueshine.tgposter.data.remote.RssFetcher
import com.trueshine.tgposter.data.remote.TelegramClient
import com.trueshine.tgposter.data.repo.PostingRepository
import com.trueshine.tgposter.domain.ContentGenerator
import com.trueshine.tgposter.domain.Publisher
import com.trueshine.tgposter.domain.QueuePlanner
import com.trueshine.tgposter.work.WorkScheduler
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Ручной DI: граф маленький и полностью синхронный, поэтому обходимся
 * ленивыми полями вместо фреймворка.
 */
class AppContainer(private val context: Context) {

    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            isLenient = true
        }
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS) // deepseek-reasoner отвечает долго
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val database: AppDatabase by lazy { AppDatabase.build(context) }

    val secrets: SecretStore by lazy { SecretStore(context) }

    val settings: SettingsStore by lazy { SettingsStore(context) }

    val telegram: TelegramClient by lazy { TelegramClient(http, json) }

    val deepSeek: DeepSeekClient by lazy { DeepSeekClient(http, json) }

    val rss: RssFetcher by lazy { RssFetcher(http) }

    val repository: PostingRepository by lazy {
        PostingRepository(
            accounts = database.accountDao(),
            channels = database.channelDao(),
            rubrics = database.rubricDao(),
            sources = database.sourceDao(),
            schedules = database.scheduleDao(),
            posts = database.postDao(),
            logs = database.logDao(),
            secrets = secrets,
        )
    }

    val generator: ContentGenerator by lazy {
        ContentGenerator(repository, deepSeek, rss, secrets, settings, json)
    }

    val publisher: Publisher by lazy { Publisher(repository, telegram) }

    val backup: BackupRepository by lazy { BackupRepository(repository, settings, secrets, json) }

    val planner: QueuePlanner by lazy { QueuePlanner(repository) }

    val workScheduler: WorkScheduler by lazy { WorkScheduler(context) }
}
