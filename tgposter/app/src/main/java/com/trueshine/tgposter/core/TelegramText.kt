package com.trueshine.tgposter.core

/**
 * Подготовка текста от модели к отправке в Telegram.
 *
 * Модель иногда возвращает markdown или теги, которых Telegram не понимает, —
 * такой пост API отклоняет целиком. Поэтому текст приводится к безопасному
 * подмножеству HTML, а слишком длинный разбивается на части по абзацам.
 */
object TelegramText {

    private val allowedSimpleTags = setOf(
        "b", "strong", "i", "em", "u", "ins", "s", "strike", "del",
        "code", "pre", "blockquote", "tg-spoiler"
    )

    private val simpleTagRegex =
        Regex("&lt;(/?)(${allowedSimpleTags.joinToString("|")})&gt;", RegexOption.IGNORE_CASE)
    private val anchorOpenRegex =
        Regex("&lt;a\\s+href=([\"'])(https?://[^\"'\\s]+)\\1&gt;", RegexOption.IGNORE_CASE)
    private val anchorCloseRegex = Regex("&lt;/a&gt;", RegexOption.IGNORE_CASE)
    private val preLangRegex =
        Regex("&lt;pre\\s+language=([\"'])([a-zA-Z0-9_+-]+)\\1&gt;", RegexOption.IGNORE_CASE)
    private val codeClassRegex =
        Regex("&lt;code\\s+class=([\"'])(language-[a-zA-Z0-9_+-]+)\\1&gt;", RegexOption.IGNORE_CASE)

    /** Markdown-разметка, которую модель может подмешать вопреки инструкции. */
    private val mdBold = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)
    private val mdItalic = Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])", RegexOption.DOT_MATCHES_ALL)
    private val mdHeading = Regex("^#{1,6}\\s*", RegexOption.MULTILINE)
    private val mdLink = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")

    fun prepare(raw: String, parseMode: String): String {
        val trimmed = raw.trim().replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n")
        return when (parseMode.uppercase()) {
            "HTML" -> sanitizeHtml(trimmed)
            "MARKDOWNV2" -> escapeMarkdownV2(stripHtml(trimmed))
            else -> stripHtml(trimmed)
        }
    }

    fun sanitizeHtml(input: String): String {
        // Сначала markdown -> html, пока символы ещё не экранированы.
        var text = input
        text = mdLink.replace(text) { m -> "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>" }
        text = mdBold.replace(text) { m -> "<b>${m.groupValues[1]}</b>" }
        text = mdItalic.replace(text) { m -> "<i>${m.groupValues[1]}</i>" }
        text = mdHeading.replace(text, "")

        // Экранируем всё, затем возвращаем только разрешённые теги.
        text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        text = simpleTagRegex.replace(text) { m -> "<${m.groupValues[1]}${m.groupValues[2].lowercase()}>" }
        text = preLangRegex.replace(text) { m -> "<pre language=\"${m.groupValues[2]}\">" }
        text = codeClassRegex.replace(text) { m -> "<code class=\"${m.groupValues[2]}\">" }
        text = anchorOpenRegex.replace(text) { m -> "<a href=\"${m.groupValues[2]}\">" }
        text = anchorCloseRegex.replace(text, "</a>")
        return balance(text).trim()
    }

    /**
     * Telegram не примет сообщение с незакрытым тегом, поэтому непарные теги
     * закрываются в конце, а лишние закрывающие удаляются.
     */
    fun balance(text: String): String {
        val tagRegex = Regex("<(/?)([a-zA-Z-]+)(\\s[^>]*)?>")
        val open = ArrayDeque<String>()
        val result = StringBuilder()
        var last = 0
        for (m in tagRegex.findAll(text)) {
            result.append(text, last, m.range.first)
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            if (closing) {
                if (open.lastOrNull() == name) {
                    open.removeLast()
                    result.append(m.value)
                } else if (open.contains(name)) {
                    // Закрываем всё, что было открыто внутри.
                    while (open.isNotEmpty() && open.last() != name) {
                        result.append("</${open.removeLast()}>")
                    }
                    if (open.isNotEmpty()) {
                        open.removeLast()
                        result.append("</$name>")
                    }
                }
                // Непарное закрытие просто выбрасываем.
            } else {
                open.addLast(name)
                result.append(m.value)
            }
            last = m.range.last + 1
        }
        result.append(text, last, text.length)
        while (open.isNotEmpty()) result.append("</${open.removeLast()}>")
        return result.toString()
    }

    fun stripHtml(input: String): String =
        input.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    fun escapeMarkdownV2(input: String): String {
        val special = "_*[]()~`>#+-=|{}.!\\"
        val sb = StringBuilder(input.length + 16)
        for (ch in input) {
            if (special.indexOf(ch) >= 0) sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * Разбивает длинный текст на части, стараясь резать по пустой строке,
     * затем по переводу строки, и только в крайнем случае по символу.
     */
    fun split(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val parts = mutableListOf<String>()
        var rest = text
        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            var cut = window.lastIndexOf("\n\n")
            if (cut < limit / 2) cut = window.lastIndexOf('\n')
            if (cut < limit / 2) cut = window.lastIndexOf(' ')
            if (cut <= 0) cut = limit
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotBlank()) parts += rest
        return parts
    }

    /** Приблизительная длина видимого текста без учёта тегов — для UI-счётчика. */
    fun visibleLength(text: String): Int = stripHtml(text).length
}
