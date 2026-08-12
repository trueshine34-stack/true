package com.trueshine.threadsposter.core

/**
 * Подготовка текста к публикации в Threads.
 *
 * Threads не поддерживает разметку вообще: любые теги и markdown-звёздочки уйдут
 * в ленту как есть. Поэтому текст очищается до обычного текста, а длинный —
 * разбивается на цепочку постов по 500 символов.
 */
object ThreadsText {

    const val MAX_LENGTH = 500

    private val htmlTag = Regex("<[^>]+>")
    private val mdBold = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)
    private val mdItalicStar = Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])", RegexOption.DOT_MATCHES_ALL)
    private val mdItalicUnderscore = Regex("(?<![_\\w])_(?!\\s)(.+?)(?<!\\s)_(?![_\\w])", RegexOption.DOT_MATCHES_ALL)
    private val mdHeading = Regex("^#{1,6}\\s*", RegexOption.MULTILINE)
    private val mdLink = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
    private val mdBullet = Regex("^\\s*[*+]\\s+", RegexOption.MULTILINE)
    private val codeFence = Regex("```[a-zA-Z]*\\n?")
    private val extraBlankLines = Regex("\n{3,}")
    private val trailingSpaces = Regex("[ \t]+\n")

    /** Приводит ответ модели к чистому тексту, пригодному для Threads. */
    fun prepare(raw: String): String {
        var text = raw.trim().replace("\r\n", "\n")
        text = codeFence.replace(text, "")
        // Ссылку из markdown оставляем как «текст (url)», иначе адрес потеряется.
        text = mdLink.replace(text) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            if (label.contains(url)) label else "$label — $url"
        }
        text = htmlTag.replace(text, "")
        text = mdBold.replace(text) { it.groupValues[1] }
        text = mdItalicStar.replace(text) { it.groupValues[1] }
        text = mdItalicUnderscore.replace(text) { it.groupValues[1] }
        text = mdHeading.replace(text, "")
        text = mdBullet.replace(text, "• ")
        text = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        text = trailingSpaces.replace(text, "\n")
        text = extraBlankLines.replace(text, "\n\n")
        return text.trim()
    }

    /**
     * Разбивает текст на части не длиннее [limit], стараясь резать по пустой
     * строке, затем по концу предложения, затем по пробелу.
     */
    fun split(text: String, limit: Int = MAX_LENGTH): List<String> {
        val clean = text.trim()
        if (clean.length <= limit) return listOf(clean)

        val parts = mutableListOf<String>()
        var rest = clean
        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            var cut = window.lastIndexOf("\n\n")
            if (cut < limit / 3) cut = lastSentenceEnd(window)
            if (cut < limit / 3) cut = window.lastIndexOf('\n')
            if (cut < limit / 3) cut = window.lastIndexOf(' ')
            if (cut <= 0) cut = limit
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotBlank()) parts += rest
        return parts
    }

    private fun lastSentenceEnd(window: String): Int {
        val candidates = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n", "…")
        return candidates.maxOf { marker ->
            val index = window.lastIndexOf(marker)
            if (index < 0) -1 else index + marker.length
        }
    }

    /**
     * Нумерует части цепочки — без этого читатель не понимает, что пост
     * продолжается ответом.
     */
    fun numberParts(parts: List<String>): List<String> {
        if (parts.size < 2) return parts
        return parts.mapIndexed { index, part ->
            val suffix = " ${index + 1}/${parts.size}"
            if (part.length + suffix.length <= MAX_LENGTH) part + suffix else part
        }
    }

    /** Аккуратно обрезает текст по границе слова. */
    fun trimTo(text: String, limit: Int = MAX_LENGTH): String {
        val clean = text.trim()
        if (clean.length <= limit) return clean
        val window = clean.substring(0, limit - 1)
        val cut = window.lastIndexOf(' ')
        return (if (cut > limit / 2) window.substring(0, cut) else window).trimEnd() + "…"
    }
}
