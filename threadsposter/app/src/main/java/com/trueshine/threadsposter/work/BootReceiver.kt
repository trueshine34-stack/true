package com.trueshine.threadsposter.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trueshine.threadsposter.ThreadsPosterApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * После перезагрузки одноразовые задачи публикации теряются, поэтому здесь
 * перезапускаем периодический автопилот — на первом же проходе он восстановит
 * очередь и точные публикации.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        val container = (context.applicationContext as ThreadsPosterApp).container
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = container.settings.current()
                if (settings.autopilot) {
                    container.workScheduler.ensureAutopilot(settings.wifiOnly)
                    container.workScheduler.runAutopilotNow()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
