package com.polybot.btc5m.bot

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the desk trades through.
 *
 * It holds the signing session, the price feed, the top of book and the
 * positions, and it sends the orders the screen asks for. Nothing in it
 * decides to trade: the automatic strategy that used to live here is gone, and
 * the only thing that acts on its own now is the standing sell rule, which is
 * a rule about exits rather than a bot with an opinion.
 */
class BotEngine(
    val journal: Journal,
    private val onStateChanged: () -> Unit,
    private val onLog: (LogEntry) -> Unit,
) {
    val feed = ChainlinkFeed()

    /** Told when this engine buys, so a sell rule can start watching it. */
    @Volatile
    var onBought: ((String) -> Unit)? = null

    @Volatile
    private var account: Account? = null

    @Volatile
    private var creds: Credentials? = null

    @Volatile
    private var keyPair: Secp256k1.KeyPair? = null

    val logs = CopyOnWriteArrayList<LogEntry>()

    /** Top of book for both outcomes, refreshed whether or not the bot trades. */
    @Volatile
    var quotes: Quotes? = null
        private set

    @Volatile
    var positions: List<Position> = emptyList()
        private set

    private val logId = AtomicLong(0)

    private val ambientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ambientJob: Job? = null
    private var cachedMarket: Market? = null

    private companion object {
        const val MAX_LOGS = 400
    }

    fun log(level: String, message: String) {
        journal.log(level, message)
        val entry = LogEntry(logId.incrementAndGet(), System.currentTimeMillis(), level, message)
        logs.add(0, entry)
        while (logs.size > MAX_LOGS) logs.removeAt(logs.size - 1)
        onLog(entry)
    }

    fun configure(account: Account, creds: Credentials) {
        this.account = account
        this.creds = creds
        this.keyPair = Secp256k1.keyPairFromPrivateKey(account.privateKey)
    }

    fun isConfigured(): Boolean = account != null && creds != null && keyPair != null

    /** Everything needed to sign and send, or nothing. */
    data class Session(
        val account: Account,
        val creds: Credentials,
        val keys: Secp256k1.KeyPair,
    )

    /** The signing session, or nothing — never the key itself. */
    fun session(): Session? {
        val a = account ?: return null
        val c = creds ?: return null
        val k = keyPair ?: return null
        return Session(a, c, k)
    }

    fun startFeed() {
        feed.start()
        // The Binance book is screen data too, and it is the only thing here
        // that says what the next few dollars of price would cost.
        BinanceBook.start()
        BinanceCandles.all.forEach { it.start() }
        BinanceTrades.start()
        startAmbient()
    }

    /**
     * Quotes and positions are screen data, not trading data, so they keep
     * refreshing while the bot is stopped — just more slowly, to spare the
     * battery when nothing is happening.
     */
    private fun startAmbient() {
        if (ambientJob != null) return
        ambientJob = ambientScope.launch {
            var round = 0L
            while (isActive) {
                try {
                    refreshQuotes()
                } catch (e: Exception) {
                    // A missed quote is cosmetic; the next round retries.
                }
                if (round % 4 == 0L && isConfigured()) {
                    try {
                        refreshPositions()
                    } catch (e: Exception) {
                        // Same: the position list is a view, not a decision input.
                    }
                }
                round += 1
                onStateChanged()
                delay(3_000)
            }
        }
    }

    private fun refreshQuotes() {
        val market = currentMarket() ?: return
        quotes = Quotes(
            up = ClobApi.quote(market.up.tokenId),
            down = ClobApi.quote(market.down.tokenId),
            atMs = System.currentTimeMillis(),
        )
    }

    private fun refreshPositions() {
        val acct = account ?: return
        positions = DataApi.positions(acct.funderAddress)
    }

    fun currentMarket(): Market? {
        val windowStart = GammaApi.windowStartFor()
        cachedMarket?.let { if (it.windowStart == windowStart) return it }
        return try {
            GammaApi.marketForWindow(windowStart)?.also { cachedMarket = it }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The market for any window, not just the current one.
     *
     * Gamma publishes the next window shortly before it opens, so this returns
     * null until then rather than inventing something.
     */
    fun marketForWindow(windowStart: Long): Market? {
        currentMarket()?.let { if (it.windowStart == windowStart) return it }
        return try {
            GammaApi.marketForWindow(windowStart)
        } catch (e: Exception) {
            null
        }
    }

    fun openOrders(market: String? = null): List<ClobApi.OpenOrder> {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        return ClobApi.openOrders(creds, acct.signerAddress, market)
    }

    fun cancelOrder(orderId: String): Boolean {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        val ok = ClobApi.cancelOrder(creds, acct.signerAddress, orderId)
        log("info", if (ok) "Ордер отменён" else "Ордер уже неактивен")
        onStateChanged()
        return ok
    }

    fun cancelMarketOrders(conditionId: String): Int {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        val n = ClobApi.cancelMarketOrders(creds, acct.signerAddress, conditionId)
        log("info", "Отменено ордеров: $n")
        onStateChanged()
        return n
    }

    /**
     * Place an order by hand. Limit orders size in shares; a market buy sizes
     * in USDC, matching how the venue reads the amount.
     */
    fun placeManualOrder(
        tokenId: String,
        conditionId: String,
        side: String,
        price: Double,
        size: Double,
        orderType: String,
        /** Placed by a rule rather than by a tap; only the log cares. */
        auto: Boolean = false,
    ): ClobApi.OrderResult {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        val keys = keyPair ?: error("ключ не загружен")

        val meta = ClobApi.marketMeta(conditionId)

        // The early ceiling, on the one path every order takes. A tap, a
        // ladder rung and a rebuy are all buys, and the rule is about what is
        // paid rather than about who asked.
        if (side == "BUY") {
            val elapsed = BuyCap.elapsedFor(meta.windowStart)
            if (BuyCap.blocked(price, elapsed)) error(BuyCap.reason(elapsed))
        }

        val cfg = Orders.roundConfigFor(meta.tickSize)
        val amounts = if (orderType == "FOK" || orderType == "FAK") {
            Orders.marketOrderAmounts(side, size, price, cfg)
        } else {
            Orders.limitOrderAmounts(side, size, price, cfg)
        }

        val order = Orders.buildAndSign(
            keyPair = keys,
            signerAddress = acct.signerAddress,
            funder = acct.funderAddress,
            signatureType = acct.signatureType,
            tokenId = tokenId,
            side = side,
            amounts = amounts,
            negRisk = meta.negRisk,
        )
        val result = ClobApi.postOrder(order, creds, acct.signerAddress, orderType)

        // Which of the venue's two amounts is shares depends on the side.
        val fill = Orders.filled(side, result.makingAmount, result.takingAmount)

        if (result.success) {
            OrderLog.record(
                orderId = result.orderId,
                asset = tokenId,
                conditionId = conditionId,
                outcome = meta.outcomes[tokenId] ?: outcomeFor(tokenId),
                action = side,
                price = price,
                size = size,
                matched = fill.shares,
                // What it actually went at. A marketable limit at 81c that
                // sweeps offers at 78 and 79 costs neither 81 nor either of
                // them, and the exit is priced off what the position cost.
                fillPrice = if (fill.shares > 1e-9 && fill.usd > 0.0) {
                    fill.usd / fill.shares
                } else {
                    null
                },
                auto = auto,
                windowStart = meta.windowStart,
            )
        }

        // Remember what actually filled. The data API needs a moment to index a
        // trade, and until it does it reports the position with no cost basis.
        val filledShares = fill.shares
        if (result.success && filledShares > 1e-9) {
            if (side == "BUY") {
                LocalFills.bought(
                    tokenId,
                    filledShares,
                    fill.usd.takeIf { it > 0.0 } ?: (filledShares * price),
                )
            } else {
                LocalFills.sold(tokenId, filledShares)
            }
        }

        // Every accepted buy arms the sell rule, filled or not. Arming only on
        // an immediate fill meant a limit buy that rested for a few seconds —
        // the ordinary way of buying here — was never watched, and no sell was
        // ever placed against it. The venue also reports no taking amount at
        // all for some fills, which broke the same way.
        if (result.success && side == "BUY") onBought?.invoke(tokenId)

        log(
            if (result.success) "trade" else "error",
            if (result.success) {
                "Ручной ордер $side " + String.format("%.2f", size) + " по " +
                    String.format(
                        "%.0f",
                        (if (fill.shares > 1e-9 && fill.usd > 0.0) fill.usd / fill.shares
                        else price) * 100,
                    ) + "¢ (${result.status ?: "ok"})"
            } else {
                "Ручной ордер отклонён: ${result.error ?: "отказ CLOB"}"
            },
        )
        onStateChanged()
        return result
    }

    /** Which side of the current market a token is, for the order log. */
    private fun outcomeFor(tokenId: String): String {
        val market = currentMarket() ?: return ""
        return when (tokenId) {
            market.up.tokenId -> "Up"
            market.down.tokenId -> "Down"
            else -> ""
        }
    }

    /** Reads the funder's USDC balance using the credentials held here. */
    fun usdcBalance(): Double {
        val acct = account ?: error("кошелёк не подключён")
        val creds = this.creds ?: error("нет ключей CLOB")
        return ClobApi.usdcBalance(creds, acct.signerAddress, acct.signatureType)
    }

}
