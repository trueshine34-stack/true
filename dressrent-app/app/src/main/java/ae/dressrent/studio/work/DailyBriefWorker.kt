package ae.dressrent.studio.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ae.dressrent.studio.data.DashboardState
import ae.dressrent.studio.data.Repository
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Once a morning: read today's calendar and post the plan. A rental studio loses money to
 * forgotten follow-ups far more often than to a lack of dresses.
 */
class DailyBriefWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = Repository.get(applicationContext)
        val state = repo.dashboard(LocalDate.now()).first()
        val (title, body) = compose(state)
        Notifications.showBrief(applicationContext, title, body)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_brief"

        fun compose(state: DashboardState): Pair<String, String> = BriefComposer.compose(state)

        fun schedule(context: Context, hour: Int) {
            val now = LocalDateTime.now()
            var next = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour.coerceIn(0, 23), 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)

            val request = PeriodicWorkRequestBuilder<DailyBriefWorker>(Duration.ofDays(1))
                .setInitialDelay(delay)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
