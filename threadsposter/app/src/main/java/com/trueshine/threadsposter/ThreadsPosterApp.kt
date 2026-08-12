package com.trueshine.threadsposter

import android.app.Application
import com.trueshine.threadsposter.work.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ThreadsPosterApp : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        scope.launch {
            val settings = container.settings.current()
            container.workScheduler.ensureAutopilot(settings.wifiOnly)
        }
    }
}
