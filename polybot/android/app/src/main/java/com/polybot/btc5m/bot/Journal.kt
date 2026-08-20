package com.polybot.btc5m.bot

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Durable trading journal.
 *
 * The in-memory log is capped at a few hundred lines, which is nothing next to
 * a day of five-minute windows. This writes everything to disk so a session can
 * be exported and analysed afterwards — the only way to tell a strategy that
 * works from one that got lucky.
 */
class Journal(context: Context) {

    private val dir = File(context.filesDir, "journal").apply { mkdirs() }

    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    companion object {
        private const val KEEP_DAYS = 14
        private const val MAX_BYTES_PER_DAY = 4L * 1024 * 1024
    }

    private fun fileFor(at: Date): File = File(dir, "polybot-${day.format(at)}.log")

    @Synchronized
    fun append(line: String) {
        val now = Date()
        try {
            val file = fileFor(now)
            // A runaway loop must not fill the phone; drop the tail instead.
            if (file.length() > MAX_BYTES_PER_DAY) return
            file.appendText("${stamp.format(now)} $line\n")
        } catch (e: Exception) {
            // Journalling must never take the engine down with it.
        }
    }

    fun log(level: String, message: String) = append("[${level.uppercase()}] $message")

    /** One machine-readable line per settled window, for later analysis. */
    fun record(json: String) = append("[WINDOW] $json")

    @Synchronized
    fun prune() {
        try {
            val keep = dir.listFiles()
                ?.sortedByDescending { it.name }
                ?.drop(KEEP_DAYS)
                ?: return
            keep.forEach { it.delete() }
        } catch (e: Exception) {
            // Nothing to do; stale files are harmless.
        }
    }

    /** Everything on disk, newest day last, prefixed with the given header. */
    @Synchronized
    fun exportTo(target: File, header: String): File {
        target.parentFile?.mkdirs()
        target.writeText(header)
        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList<File>()
        for (file in files) {
            target.appendText("\n\n===== ${file.name} =====\n")
            try {
                file.forEachLine { target.appendText(it + "\n") }
            } catch (e: Exception) {
                target.appendText("<не удалось прочитать: ${e.message}>\n")
            }
        }
        return target
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}

/** Escapes a value for the compact JSON the window records use. */
internal fun jsonString(value: String?): String {
    if (value == null) return "null"
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

/** Formats a nullable number without locale decimal commas. */
internal fun jsonNumber(value: Double?, decimals: Int = 6): String =
    if (value == null || !value.isFinite()) "null"
    else String.format(Locale.US, "%.${decimals}f", value)

internal fun isoUtc(millis: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(millis))
}
