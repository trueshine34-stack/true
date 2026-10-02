package com.polybot.btc5m.bot

import android.content.Context

/**
 * The wallets this app trades from, and which of them the screen is on.
 *
 * One wallet was an assumption rather than a decision: the key, the order log,
 * the reserve and the sell rule were each a single thing because there was only
 * ever one account to have them. A second account is not a second copy of the
 * app — it is a second set of exactly those four, running at the same time as
 * the first, because a position bought on one wallet has to keep being sold
 * while the screen is showing the other.
 *
 * So everything that belongs to an account is addressed by slot, and everything
 * that belongs to the market — the candles, the oracle, the book, what each
 * window settled at — stays shared, because it is the same market either way.
 *
 * Slot zero keeps the storage keys the app has always used. An upgrade finds
 * its wallet, its reserve and its history exactly where it left them, and the
 * second slot is new ground rather than a migration.
 */
object Wallets {

    /** How many accounts the desk will hold. Two is what a thumb can swipe. */
    const val SLOTS = 2

    /**
     * The colour a slot is framed in.
     *
     * The one thing a second wallet must never do is let you act on it thinking
     * it was the first, and a label in a corner is not enough for that — it is
     * read when you look for it, which is not when the mistake happens. A frame
     * around the whole screen is seen without looking.
     */
    val DEFAULT_ACCENTS = listOf("#4c8dff", "#f4b740")

    data class Slot(
        val index: Int,
        /** What the user calls it. Empty means "the one it came with". */
        val label: String,
        val accent: String,
        /** The signing address, once there is one. Null while it is empty. */
        val address: String?,
    ) {
        val connected: Boolean get() = address != null
        val name: String get() = label.ifBlank { "Кошелёк ${index + 1}" }
    }

    private const val PREFS = "polybot_wallets"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The suffix a slot's own storage carries.
     *
     * Empty for slot zero on purpose: its preferences files, its vault entry and
     * its reserve are the ones that are already on the phone, and renaming them
     * would lose a running account's history to a feature it is not using.
     */
    fun suffix(slot: Int): String = if (slot <= 0) "" else ".w$slot"

    @Volatile
    private var currentSlot: Int = 0

    /** Which wallet the desk is on. Everything the screen asks for is this one's. */
    val current: Int get() = currentSlot

    fun select(context: Context, slot: Int): Boolean {
        val next = slot.coerceIn(0, SLOTS - 1)
        if (next == currentSlot) return false
        currentSlot = next
        prefs(context).edit().putInt("current", next).apply()
        return true
    }

    fun restore(context: Context) {
        currentSlot = prefs(context).getInt("current", 0).coerceIn(0, SLOTS - 1)
    }

    fun label(context: Context, slot: Int): String =
        prefs(context).getString("label.$slot", "") ?: ""

    fun setLabel(context: Context, slot: Int, label: String) {
        prefs(context).edit().putString("label.$slot", label.take(24)).apply()
    }

    fun accent(context: Context, slot: Int): String =
        prefs(context).getString("accent.$slot", null)
            ?: DEFAULT_ACCENTS.getOrElse(slot) { DEFAULT_ACCENTS.first() }

    fun setAccent(context: Context, slot: Int, accent: String) {
        if (!accent.matches(Regex("^#[0-9a-fA-F]{6}$"))) return
        prefs(context).edit().putString("accent.$slot", accent).apply()
    }

    /**
     * The address a slot signs with, remembered separately from the key itself.
     *
     * The key lives sealed in the keystore and is only opened to sign; this is
     * just enough to say "slot two is connected, and this is who it is" without
     * unsealing anything to find out.
     */
    fun address(context: Context, slot: Int): String? =
        prefs(context).getString("address.$slot", null)

    fun setAddress(context: Context, slot: Int, address: String?) {
        val edit = prefs(context).edit()
        if (address.isNullOrBlank()) edit.remove("address.$slot")
        else edit.putString("address.$slot", address)
        edit.apply()
    }

    fun slot(context: Context, index: Int): Slot = Slot(
        index = index,
        label = label(context, index),
        accent = accent(context, index),
        address = address(context, index),
    )

    fun all(context: Context): List<Slot> = (0 until SLOTS).map { slot(context, it) }

    /** The slots with a key behind them, which are the ones a rule may run for. */
    fun connected(context: Context): List<Int> =
        (0 until SLOTS).filter { address(context, it) != null }
}
