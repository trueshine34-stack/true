package com.trueshine.tgposter

import android.app.Application
import com.trueshine.tgposter.work.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TgPosterApp : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        scope.launch {
            val settings = container.settings.current()
            container.workScheduler.ensureAutopilot(settings.wifiOnly)
        }
    }

    companion object {
        lateinit var instance: TgPosterApp
            private set
    }
}
