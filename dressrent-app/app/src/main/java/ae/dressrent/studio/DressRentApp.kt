package ae.dressrent.studio

import android.app.Application
import ae.dressrent.studio.data.Repository
import ae.dressrent.studio.work.DailyBriefWorker
import ae.dressrent.studio.work.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DressRentApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        scope.launch {
            val settings = Repository.get(this@DressRentApp).settings.first()
            DailyBriefWorker.schedule(this@DressRentApp, settings.reminderHour)
        }
    }
}
