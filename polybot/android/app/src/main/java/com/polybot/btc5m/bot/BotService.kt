package com.polybot.btc5m.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.polybot.btc5m.MainActivity
import com.polybot.btc5m.R

/**
 * Foreground service that owns the trading loop.
 *
 * The loop cannot live in the WebView: Chromium throttles timers and suspends
 * sockets once the activity stops being visible, which is exactly when the user
 * expects the bot to keep trading. Running it here, on OkHttp and coroutines
 * under a wake lock, is what makes background operation real rather than
 * nominal.
 *
 * The signing key is handed in from the unlocked UI and lives only in this
 * process's memory — never in an Intent extra, never on disk. That is why the
 * service does not restart itself after the process dies: without the app being
 * opened and unlocked again there is nothing to sign with.
 */
class BotService : Service() {

    companion object {
        const val ACTION_START = "com.polybot.btc5m.START"
        const val ACTION_STOP = "com.polybot.btc5m.STOP"

        private const val CHANNEL_ID = "polybot_engine"
        private const val NOTIFICATION_ID = 4201

        fun isRunning(): Boolean = EngineHolder.peek()?.running == true

        fun start(context: Context) {
            val intent = Intent(context, BotService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BotService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var stateHook: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // The engine is process-wide and long-lived; the service only drives its
        // foreground lifecycle.
        EngineHolder.get(this)
        stateHook = { updateNotification() }
        EngineHolder.onServiceState = stateHook
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                return START_NOT_STICKY
            }

            else -> startEngine()
        }
        // Deliberately not sticky: the key is memory-only, so a restarted
        // service could not trade and would only show a misleading notification.
        return START_NOT_STICKY
    }

    private fun startEngine() {
        startForeground(NOTIFICATION_ID, buildNotification("Запуск…"))
        acquireWakeLock()

        val bot = EngineHolder.get(this)
        if (!bot.isConfigured()) {
            bot.log(
                "error",
                "Кошелёк не подключён — откройте приложение и введите PIN",
            )
            updateNotification()
            return
        }
        bot.start()
        updateNotification()
    }

    private fun stopEngine() {
        // Stops trading only. The engine keeps the day's statistics, the price
        // feed and the credentials so the user can still manage orders.
        EngineHolder.peek()?.shutdown()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (EngineHolder.onServiceState === stateHook) EngineHolder.onServiceState = null
        EngineHolder.peek()?.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Doze can park the process between price ticks even with a foreground
     * notification showing. A partial wake lock keeps the CPU available for the
     * loop; the screen is untouched.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "polybot:engine",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Торговый движок",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Показывает, что бот работает в фоне"
            setShowBadge(false)
        }
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("PolyBot · BTC 5м")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        val bot = EngineHolder.peek() ?: return
        val stats = bot.stats
        val mode = if (bot.settings.dryRun) "тест" else "реальные сделки"
        val state = when {
            !bot.running -> bot.haltReason?.let { "остановлен: $it" } ?: "остановлен"
            else -> {
                val pnl = String.format("%+.2f", stats.realisedPnlUsd)
                "$mode · сделок ${stats.trades} · $pnl $"
            }
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }
}
