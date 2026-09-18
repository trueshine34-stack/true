package ae.dressrent.studio.util

import ae.dressrent.studio.data.Minor
import kotlin.math.abs
import kotlin.math.roundToLong

/** AED is pegged to the dollar at this rate, so the USD read-out is stable. */
const val DEFAULT_AED_PER_USD = 3.6725

object Money {

    fun format(minor: Minor, currency: String = "AED"): String {
        val sign = if (minor < 0) "-" else ""
        val abs = abs(minor)
        val units = abs / 100
        val cents = abs % 100
        val grouped = group(units)
        return if (cents == 0L) "$sign$grouped $currency" else "$sign$grouped,${"%02d".format(cents)} $currency"
    }

    fun formatShort(minor: Minor, currency: String = "AED"): String =
        "${group(abs(minor) / 100)} $currency".let { if (minor < 0) "-$it" else it }

    fun usd(minor: Minor, rate: Double = DEFAULT_AED_PER_USD): String {
        if (rate <= 0.0) return ""
        val dollars = minor / 100.0 / rate
        return "$" + "%,.0f".format(dollars).replace(",", " ")
    }

    /** Accepts "350", "350.5", "350,50" and blank. Returns minor units. */
    fun parse(input: String): Minor {
        val cleaned = input.trim().replace(" ", "").replace(",", ".")
        if (cleaned.isEmpty()) return 0
        val value = cleaned.toDoubleOrNull() ?: return 0
        return (value * 100).roundToLong()
    }

    /** Text an amount field should show when editing an existing value. */
    fun toInput(minor: Minor): String =
        if (minor == 0L) "" else if (minor % 100 == 0L) (minor / 100).toString()
        else "%.2f".format(minor / 100.0)

    private fun group(units: Long): String {
        val s = units.toString()
        if (s.length <= 3) return s
        val sb = StringBuilder()
        var count = 0
        for (i in s.lastIndex downTo 0) {
            sb.append(s[i])
            count++
            if (count % 3 == 0 && i != 0) sb.append(' ')
        }
        return sb.reverse().toString()
    }
}
