package com.polybot.btc5m.bot

import android.content.Context

/**
 * The coin the desk was left on.
 *
 * Kept apart from every rule's settings because it is not a rule's business:
 * the service starts without the WebView after a restart, and it has to open
 * the same market the screen was last looking at rather than falling back to
 * bitcoin and quietly trading something else.
 */
class CoinStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("polybot_coin", Context.MODE_PRIVATE)

    fun load(): Coins.Coin = Coins.of(prefs.getString("id", null))

    fun save(coin: Coins.Coin) {
        prefs.edit().putString("id", coin.id).apply()
    }
}
