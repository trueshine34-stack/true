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
 * Foreground service that keeps the standing sell rule alive.
 *
 * The rule cannot live in the WebView: Chromium throttles timers and suspends
 * sockets once the activity stops being visible, which is exactly when a
 * position bought a moment ago still needs its exit placed. Running it here, on
 * OkHttp and coroutines under a wake lock, is what makes "every buy gets a
 * sell" true with the screen off.
 *
 * The signing key is handed in from the unlocked UI and lives only in this
 * process's memory — never in an Intent extra, never on disk. That is why the
 * service does not restart itself after the process dies: without the app being
 * opened again there is nothing to sign with.
 */
class BotService : Service() {

    companion object {
        const val ACTION_START = "com.polybot.btc5m.START"
        const val ACTION_STOP = "com.polybot.btc5m.STOP"
        const val ACTION_START_AUTOSELL = "com.polybot.btc5m.START_AUTOSELL"
        const val ACTION_STOP_AUTOSELL = "com.polybot.btc5m.STOP_AUTOSELL"

        /**
         * Stands the desk down without touching the sell rule.
         *
         * [ACTION_STOP] stops both, which is right for "close the desk" and
         * wrong for "I no longer want the clock in my ear": a rule that is
         * working a position must not be switched off by a cue setting.
         */
        const val ACTION_STOP_DESK = "com.polybot.btc5m.STOP_DESK"

        private const val CHANNEL_ID = "polybot_engine"
        private const val NOTIFICATION_ID = 4201

        /** True while the sell rule is still working. */
        fun anyRunning(): Boolean = EngineHolder.peekAutoSell()?.running == true

        fun start(context: Context) = send(context, ACTION_START, foreground = true)

        fun stop(context: Context) = send(context, ACTION_STOP, foreground = false)

        fun startAutoSell(context: Context) =
            send(context, ACTION_START_AUTOSELL, foreground = true)

        fun stopAutoSell(context: Context) =
            send(context, ACTION_STOP_AUTOSELL, foreground = false)

        fun stopDesk(context: Context) = send(context, ACTION_STOP_DESK, foreground = false)

        private fun send(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, BotService::class.java).setAction(action)
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var stateHook: (() -> Unit)? = null

    /**
     * Whether the desk itself asked to be shown.
     *
     * The notification is the account's only face while the app is closed, so
     * switching the sell rule off must not take it away — only closing the desk
     * does that.
     */
    private var deskWanted = false

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
                deskWanted = false
                EngineHolder.peekAutoSell()?.stop()
                releaseUnlessBusy()
                return START_NOT_STICKY
            }

            ACTION_STOP_AUTOSELL -> {
                EngineHolder.peekAutoSell()?.stop()
                releaseUnlessBusy()
                return START_NOT_STICKY
            }

            ACTION_STOP_DESK -> {
                deskWanted = false
                releaseUnlessBusy()
                return START_NOT_STICKY
            }

            ACTION_START_AUTOSELL -> {
                startForeground(NOTIFICATION_ID, buildNotification("Автопродажа", "запускается…", "запускается…"))
                acquireWakeLock()
                EngineHolder.autoSell(this).start()
                updateNotification()
            }

            else -> {
                deskWanted = true
                startDesk()
            }
        }
        // Deliberately not sticky: the key is memory-only, so a restarted
        // service could not trade and would only show a misleading notification.
        return START_NOT_STICKY
    }

    private fun startDesk() {
        startForeground(NOTIFICATION_ID, buildNotification("Стол открыт", "жду данных…", "жду данных…"))
        acquireWakeLock()

        val bot = EngineHolder.get(this)
        if (!bot.isConfigured()) {
            bot.log("error", "Кошелёк не подключён — откройте приложение")
        }
        updateNotification()
    }

    /**
     * Stands down the rule only. The engine keeps the price feed and the
     * credentials, so the user can still manage their orders.
     */
    private fun releaseUnlessBusy() {
        if (deskWanted || anyRunning()) {
            updateNotification()
            return
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (EngineHolder.onServiceState === stateHook) EngineHolder.onServiceState = null
        EngineHolder.peekAutoSell()?.stop()
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

    private fun buildNotification(title: String, text: String, full: String): Notification {
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
            .setContentTitle(title)
            .setContentText(text)
            // Pulled down, the whole desk: every position with what it is worth
            // now, and every order still waiting.
            .setStyle(Notification.BigTextStyle().bigText(full))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun cents(price: Double) = "${Math.round(price * 100)}¢"

    private fun money(usd: Double) =
        (if (usd >= 0) "+" else "−") + "$" + String.format("%.2f", kotlin.math.abs(usd))

    private fun shares(size: Double) =
        String.format(if (size % 1.0 == 0.0) "%.0f" else "%.1f", size)

    /**
     * What is held, at what it cost and what it is worth now.
     *
     * The price is the bid, because the bid is what closing pays; the profit is
     * that bid less the taker fee, which is the money rather than the mark.
     * Positions the venue calls redeemable are settled rounds, not holdings.
     */
    private fun positionLines(engine: BotEngine, market: Market?): List<String> {
        val quotes = engine.quotes
        // Everything still held, not only this window's: shares from a window
        // that has closed but not settled are the ones most worth seeing.
        return engine.positions
            .filter { !it.redeemable && it.size > 1e-6 }
            .sortedByDescending { it.size }
            .map { position ->
                val bid = when (position.asset) {
                    market?.up?.tokenId -> quotes?.up?.bestBid
                    market?.down?.tokenId -> quotes?.down?.bestBid
                    else -> null
                }
                val paid = position.avgPrice
                val line = StringBuilder()
                line.append(position.outcome)
                line.append(" ").append(shares(position.size))
                if (paid > 0.0) line.append(" по ").append(cents(paid))
                if (bid != null && bid > 0.0) {
                    line.append(" → ").append(cents(bid))
                    if (paid > 0.0) {
                        val worth = SellPercent.netSell(bid) * position.size
                        line.append("  ").append(money(worth - paid * position.size))
                    }
                } else {
                    line.append(" → нет спроса")
                }
                line.toString()
            }
    }

    /** What is still on the book, and how much of it has already gone through. */
    private fun restingLines(engine: BotEngine, market: Market?): List<String> {
        return engine.resting
            .filter { it.remaining > 1e-6 }
            .sortedBy { it.price }
            .map { order ->
                val side = if (order.side == "BUY") "куп" else "прод"
                val outcome = when (order.assetId) {
                    market?.up?.tokenId -> "Up"
                    market?.down?.tokenId -> "Down"
                    else -> order.outcome ?: ""
                }
                val line = StringBuilder()
                line.append(side).append(" ").append(outcome).append(" ")
                line.append(shares(order.remaining)).append("×").append(cents(order.price))
                if (order.sizeMatched > 1e-6) {
                    line.append(" · налито ").append(shares(order.sizeMatched))
                        .append(" из ").append(shares(order.originalSize))
                }
                line.toString()
            }
    }

    /**
     * The desk, on the lock screen.
     *
     * This is the only view of the account there is while the app is closed, so
     * it carries the three things a held position raises: what is held, what it
     * is worth at the price right now, and what is still waiting on the book.
     * It is rebuilt on every ambient round — about every three seconds — which
     * is as often as the quotes behind it change.
     */
    private fun updateNotification() {
        val engine = EngineHolder.peek()
        val market = engine?.currentMarket()
        val positions = engine?.let { positionLines(it, market) } ?: emptyList()
        val resting = engine?.let { restingLines(it, market) } ?: emptyList()

        val left = market?.windowStart?.takeIf { it > 0 }?.let {
            val secs = (it + WINDOW_SECONDS - Clock.nowSec()).coerceAtLeast(0)
            String.format("%d:%02d", secs / 60, secs % 60)
        }

        val title = when {
            positions.isEmpty() -> "Позиций нет"
            else -> positions.first()
        }

        val text = when {
            resting.isNotEmpty() -> resting.joinToString(" · ")
            positions.size > 1 -> positions.drop(1).joinToString(" · ")
            EngineHolder.peekAutoSell()?.running == true -> "автопродажа следит"
            else -> "стол открыт"
        }

        val full = buildList {
            addAll(positions)
            if (resting.isNotEmpty()) {
                if (isNotEmpty()) add("")
                addAll(resting)
            }
            if (isEmpty()) add("Ни позиций, ни лимиток")
            // Which market this is about. From the notification there is
            // nothing else to tell three identical five-minute desks apart.
            left?.let { add("${Coins.current.label} · до конца окна $it") }
        }.joinToString("\n")

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, text, full))
    }
}
