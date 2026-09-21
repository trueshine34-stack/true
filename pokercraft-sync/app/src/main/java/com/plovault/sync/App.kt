package com.plovault.sync

import android.app.Application
import com.plovault.sync.sync.Notifier
import com.plovault.sync.sync.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        SyncWorker.schedule(this)
    }
}
