package com.plovault.sync.data

import android.content.Context
import com.plovault.parser.GgHandParser
import com.plovault.parser.ParsedHand
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

data class ImportResult(
    val filesParsed: Int,
    val handsFound: Int,
    val handsNew: Int,
    /** Ссылки, найденные в JSON-ответе — их нужно скачать отдельно. */
    val followUpUrls: List<String>,
    val message: String
)

/**
 * Превращает скачанный файл (zip / txt / json) в раздачи в базе.
 * Сырые файлы сохраняются в files/hh_raw — их можно переразобрать позже
 * или отдать в PokerTracker / Hand2Note.
 */
class ImportPipeline(private val context: Context) {

    private val repo = HandRepository(context)
    private val prefs = Prefs(context)

    fun rawDir(): File = File(context.filesDir, "hh_raw").apply { mkdirs() }

    fun importBytes(bytes: ByteArray, fileName: String, source: String, keepRaw: Boolean = true): ImportResult {
        if (bytes.isEmpty()) return ImportResult(0, 0, 0, emptyList(), "Пустой файл")

        val texts = ArrayList<Pair<String, String>>() // имя -> текст
        val followUps = ArrayList<String>()

        when {
            isZip(bytes) -> readZip(bytes, fileName, texts, 0)
            else -> {
                val text = String(bytes, Charsets.UTF_8)
                when {
                    text.contains("Poker Hand #") -> texts.add(fileName to text)
                    looksLikeJson(text) -> followUps.addAll(extractUrls(text))
                    else -> return ImportResult(
                        0, 0, 0, emptyList(),
                        "Файл не похож на историю рук (${bytes.size} Б). " +
                            "Первые символы: ${text.take(60).replace('\n', ' ')}"
                    )
                }
            }
        }

        if (keepRaw && texts.isNotEmpty()) {
            runCatching { File(rawDir(), safeName(fileName)).writeBytes(bytes) }
        }

        var found = 0
        var new = 0
        for ((name, text) in texts) {
            val res = GgHandParser.parseFile(text, prefs.heroName.takeIf { it.isNotBlank() })
            val hands: List<ParsedHand> = res.hands
            found += hands.size
            new += repo.insertHands(hands, name)
        }
        if (texts.isNotEmpty()) {
            repo.recordFile(safeName(fileName), bytes.size.toLong(), found, new, source)
        }

        val msg = when {
            texts.isEmpty() && followUps.isNotEmpty() -> "Ответ содержит ${followUps.size} ссылок на выгрузку"
            texts.isEmpty() -> "Раздачи не найдены"
            else -> "Разобрано файлов: ${texts.size}, раздач: $found, новых: $new"
        }
        return ImportResult(texts.size, found, new, followUps, msg)
    }

    /** Повторно разбирает все сохранённые сырые файлы (после обновления парсера). */
    fun reimportRaw(): ImportResult {
        var files = 0
        var found = 0
        var new = 0
        rawDir().listFiles()?.forEach { f ->
            val r = importBytes(f.readBytes(), f.name, "reimport", keepRaw = false)
            files += r.filesParsed
            found += r.handsFound
            new += r.handsNew
        }
        return ImportResult(files, found, new, emptyList(), "Переразобрано файлов: $files, раздач: $found, новых: $new")
    }

    private fun readZip(bytes: ByteArray, parentName: String, out: MutableList<Pair<String, String>>, depth: Int) {
        if (depth > 2) return
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val data = zis.readBytes()
                val name = "$parentName/${entry.name}"
                when {
                    isZip(data) -> readZip(data, name, out, depth + 1)
                    else -> {
                        val text = String(data, Charsets.UTF_8)
                        if (text.contains("Poker Hand #")) out.add(name to text)
                    }
                }
            }
        }
    }

    private fun isZip(b: ByteArray) =
        b.size > 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
            (b[2] == 0x03.toByte() || b[2] == 0x05.toByte() || b[2] == 0x07.toByte())

    private fun looksLikeJson(t: String): Boolean {
        val s = t.trimStart()
        return s.startsWith("{") || s.startsWith("[")
    }

    private fun extractUrls(text: String): List<String> =
        Regex("""https?://[^"'\\\s]+""").findAll(text)
            .map { it.value }
            .filter { u ->
                val l = u.lowercase()
                l.endsWith(".zip") || l.endsWith(".txt") || l.contains("download") ||
                    l.contains("export") || l.contains("history")
            }
            .distinct()
            .take(50)
            .toList()

    private fun safeName(n: String): String =
        n.replace(Regex("""[^A-Za-z0-9._-]"""), "_").takeLast(120)
}
