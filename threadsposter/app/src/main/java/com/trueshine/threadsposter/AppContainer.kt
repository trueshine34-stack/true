package com.trueshine.threadsposter

import android.content.Context
import com.trueshine.threadsposter.core.SecretStore
import com.trueshine.threadsposter.data.SettingsStore
import com.trueshine.threadsposter.data.backup.BackupRepository
import com.trueshine.threadsposter.data.db.AppDatabase
import com.trueshine.threadsposter.data.remote.DeepSeekClient
import com.trueshine.threadsposter.data.remote.ThreadsAuth
import com.trueshine.threadsposter.data.remote.ThreadsClient
import com.trueshine.threadsposter.data.repo.ThreadsRepository
import com.trueshine.threadsposter.domain.ContentGenerator
import com.trueshine.threadsposter.domain.InboxWatcher
import com.trueshine.threadsposter.domain.LeadFinder
import com.trueshine.threadsposter.domain.Publisher
import com.trueshine.threadsposter.domain.QueuePlanner
import com.trueshine.threadsposter.domain.TokenKeeper
import com.trueshine.threadsposter.work.WorkScheduler
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Ручной DI: граф маленький, обходимся ленивыми полями. */
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

    val threads: ThreadsClient by lazy { ThreadsClient(http, json) }

    val auth: ThreadsAuth by lazy { ThreadsAuth(http, json) }

    val deepSeek: DeepSeekClient by lazy { DeepSeekClient(http, json) }

    val repository: ThreadsRepository by lazy {
        ThreadsRepository(
            accounts = database.accountDao(),
            rubrics = database.rubricDao(),
            schedules = database.scheduleDao(),
            posts = database.postDao(),
            queries = database.queryDao(),
            leads = database.leadDao(),
            logs = database.logDao(),
            secrets = secrets,
        )
    }

    val generator: ContentGenerator by lazy {
        ContentGenerator(repository, deepSeek, secrets, settings, json)
    }

    val publisher: Publisher by lazy { Publisher(repository, threads) }

    val planner: QueuePlanner by lazy { QueuePlanner(repository) }

    val leadFinder: LeadFinder by lazy { LeadFinder(repository, threads, generator) }

    val inbox: InboxWatcher by lazy { InboxWatcher(repository, threads, generator, leadFinder) }

    val tokenKeeper: TokenKeeper by lazy { TokenKeeper(repository, auth, threads) }

    val backup: BackupRepository by lazy { BackupRepository(repository, settings, secrets, json) }

    val workScheduler: WorkScheduler by lazy { WorkScheduler(context) }
}
