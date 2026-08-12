package com.trueshine.threadsposter.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class WorkScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun ensureAutopilot(wifiOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<AutopilotWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(AUTOPILOT_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelAutopilot() {
        workManager.cancelUniqueWork(AUTOPILOT_WORK)
    }

    fun runAutopilotNow() {
        val request = OneTimeWorkRequestBuilder<AutopilotWorker>()
            .setConstraints(constraints(false))
            .build()
        workManager.enqueueUniqueWork(AUTOPILOT_NOW_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** Разовый прогон поиска: по кнопке «искать сейчас». */
    fun runSearchNow(queryId: Long = 0) {
        val request = OneTimeWorkRequestBuilder<SearchWorker>()
            .setInputData(Data.Builder().putLong(KEY_QUERY_ID, queryId).build())
            .setConstraints(constraints(false))
            .build()
        workManager.enqueueUniqueWork(searchWorkName(queryId), ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueGenerate(postId: Long, brief: String? = null, wifiOnly: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<GenerateWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(KEY_POST_ID, postId)
                    .putString(KEY_BRIEF, brief)
                    .build()
            )
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(generateWorkName(postId), ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueuePublish(postId: Long, delayMillis: Long, wifiOnly: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<PublishWorker>()
            .setInputData(Data.Builder().putLong(KEY_POST_ID, postId).build())
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(publishWorkName(postId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelPost(postId: Long) {
        workManager.cancelUniqueWork(generateWorkName(postId))
        workManager.cancelUniqueWork(publishWorkName(postId))
    }

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    companion object {
        const val AUTOPILOT_WORK = "threadsposter_autopilot"
        const val AUTOPILOT_NOW_WORK = "threadsposter_autopilot_now"
        const val KEY_POST_ID = "post_id"
        const val KEY_QUERY_ID = "query_id"
        const val KEY_BRIEF = "brief"

        fun generateWorkName(postId: Long) = "gen_$postId"
        fun publishWorkName(postId: Long) = "pub_$postId"
        fun searchWorkName(queryId: Long) = "search_$queryId"
    }
}
