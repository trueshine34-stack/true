package com.plovault.sync.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun money(v: Double, currency: String = "$"): String =
    (if (v < 0) "-" else "") + currency + String.format(Locale.US, "%.2f", kotlin.math.abs(v))

fun bb(v: Double): String = String.format(Locale.US, "%+.1f", v)

fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v)

fun rate(v: Double): String = String.format(Locale.US, "%+.2f", v)

fun dateTime(ts: Long): String =
    if (ts <= 0) "—" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))

fun dateOnly(ts: Long): String =
    if (ts <= 0) "—" else SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ts))

fun ago(ts: Long): String {
    if (ts <= 0) return "никогда"
    val diff = System.currentTimeMillis() - ts
    val min = diff / 60000
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 1440 -> "${min / 60} ч назад"
        else -> "${min / 1440} дн назад"
    }
}
