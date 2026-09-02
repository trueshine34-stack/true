package com.polybot.btc5m.bot

/**
 * Which coin the desk is trading.
 *
 * Polymarket runs the same five-minute Up/Down market on several coins, under
 * slugs that differ only in their first three letters, settling against the
 * same sixty-second Chainlink TWAP. So a coin is not a mode or a second desk:
 * it is a handful of strings — a slug prefix, an oracle symbol, a Binance
 * stream — and everything else on the screen is the same machinery pointed
 * somewhere else.
 *
 * One at a time. The feeds this app keeps open are the real cost of a coin —
 * an order book at ten frames a second, a trade stream, three candle series
 * and the oracle socket — and running three of everything to watch one of them
 * spends the battery on windows nobody is trading.
 *
 * Note that `asset` elsewhere in this app means a Polymarket token id, which
 * is why this is a coin.
 */
object Coins {

    data class Coin(
        /** The slug's first field, and the id everything else is keyed by. */
        val id: String,
        /** On the buttons. */
        val label: String,
        /** Polymarket's own price-history symbol. */
        val poly: String,
        /** How the oracle stream names it, on both the raw and TWAP topics. */
        val oracle: String,
        /** And the spot topic, which is Binance's name for it lower-cased. */
        val spot: String,
        /**
         * How many decimals of price are worth reading.
         *
         * Bitcoin moves in dollars and a decimal on it is noise; Solana at a
         * hundred dollars moves in cents, and rounding it to the dollar hides
         * every move a five-minute window is made of.
         */
        val digits: Int,
    ) {
        /** `btc-updown-5m-` — the window number is appended to it. */
        val slugPrefix: String get() = "$id-updown-5m-"

        /** Binance names the pair in upper case on REST and lower on the socket. */
        val stream: String get() = spot
        val pair: String get() = spot.uppercase()
    }

    val BTC = Coin("btc", "BTC", "BTC", "btc/usd", "btcusdt", 0)
    val ETH = Coin("eth", "ETH", "ETH", "eth/usd", "ethusdt", 1)
    val SOL = Coin("sol", "SOL", "SOL", "sol/usd", "solusdt", 2)

    val all = listOf(BTC, ETH, SOL)

    fun of(id: String?): Coin =
        all.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: BTC

    /**
     * The one being traded.
     *
     * Read from everywhere and written from one place — the switch — so it is
     * volatile rather than locked: every reader wants the newest answer and
     * none of them can do anything useful with a stale one.
     */
    @Volatile
    var current: Coin = BTC
        private set

    /** True when this actually changed anything, which is what a switch acts on. */
    fun select(id: String?): Boolean {
        val next = of(id)
        if (next.id == current.id) return false
        current = next
        return true
    }
}
