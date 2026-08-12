package com.trueshine.tgposter.domain

import com.trueshine.tgposter.data.db.ChannelEntity
import com.trueshine.tgposter.data.db.RubricEntity
import com.trueshine.tgposter.data.remote.FeedItem

/** Собирает промпты для DeepSeek из настроек конкретного канала. */
object PromptBuilder {

    fun systemPrompt(channel: ChannelEntity): String = buildString {
        appendLine("Ты — редактор Telegram-канала «${channel.title}». Пишешь готовые к публикации посты.")
        appendLine()
        appendLine("ЯЗЫК: ${channel.language.ifBlank { "русский" }}.")
        if (channel.audience.isNotBlank()) appendLine("АУДИТОРИЯ: ${channel.audience}")
        if (channel.tone.isNotBlank()) appendLine("ТОН: ${channel.tone}")
        appendLine("ОБЪЁМ: ${channel.minChars}–${channel.maxChars} символов, без заголовка-названия канала.")
        appendLine(
            when (channel.emojiLevel) {
                0 -> "ЭМОДЗИ: не использовать вообще."
                2 -> "ЭМОДЗИ: использовать активно, но по делу."
                else -> "ЭМОДЗИ: 1–3 штуки на пост, уместно."
            }
        )
        appendLine()
        appendLine("ИНСТРУКЦИЯ КАНАЛА (главный приоритет):")
        appendLine(channel.instructions.ifBlank { "Публикуй полезные посты по теме канала." })
        if (channel.extraRules.isNotBlank()) {
            appendLine()
            appendLine("ДОПОЛНИТЕЛЬНЫЕ ПРАВИЛА:")
            appendLine(channel.extraRules)
        }
        if (channel.bannedTopics.isNotBlank()) {
            appendLine()
            appendLine("ЗАПРЕЩЕНО ПИСАТЬ ПРО: ${channel.bannedTopics}")
        }
        appendLine()
        appendLine("ФОРМАТИРОВАНИЕ: ${formatRules(channel.parseMode)}")
        appendLine("Не пиши служебных пояснений, не описывай, что ты делаешь. Только текст поста.")
        appendLine("Не выдумывай точные цифры, даты и цитаты, если их нет во входных данных.")
        appendLine()
        appendLine("ФОРМАТ ОТВЕТА — строго JSON без markdown-обёртки:")
        appendLine("""{"topic":"краткая тема поста 3-7 слов","text":"текст поста","hashtags":"#тег #тег","image_prompt":"описание картинки на английском"}""")
    }

    private fun formatRules(parseMode: String): String = when (parseMode.uppercase()) {
        "HTML" -> "разрешены только теги <b>, <i>, <u>, <s>, <code>, <pre>, <blockquote>, " +
            "<a href=\"...\">. Никакого markdown, никаких <br>, <p>, <ul>, <h1>. Абзацы — обычным переводом строки."
        "MARKDOWNV2" -> "обычный текст без разметки, приложение оформит его само."
        else -> "обычный текст без какой-либо разметки."
    }

    fun userPrompt(
        channel: ChannelEntity,
        rubric: RubricEntity?,
        recentTopics: List<String>,
        sourceItems: List<FeedItem>,
        manualBrief: String? = null,
    ): String = buildString {
        if (!manualBrief.isNullOrBlank()) {
            appendLine("ЗАДАНИЕ НА ЭТОТ ПОСТ (приоритетнее рубрики):")
            appendLine(manualBrief)
            appendLine()
        }
        if (rubric != null) {
            appendLine("РУБРИКА: ${rubric.title}")
            if (rubric.prompt.isNotBlank()) appendLine(rubric.prompt)
            appendLine()
        }
        if (sourceItems.isNotEmpty()) {
            appendLine("СВЕЖИЙ МАТЕРИАЛ ИЗ ИСТОЧНИКОВ (используй как фактуру, не копируй дословно):")
            sourceItems.take(8).forEachIndexed { index, item ->
                appendLine("${index + 1}. ${item.title}")
                if (item.summary.isNotBlank()) appendLine("   ${item.summary}")
                if (item.link.isNotBlank()) appendLine("   ${item.link}")
            }
            appendLine()
        }
        if (recentTopics.isNotEmpty()) {
            appendLine("УЖЕ БЫЛО В КАНАЛЕ (не повторяй эти темы и не пересказывай их заново):")
            recentTopics.take(channel.avoidRepeatLastN).forEach { appendLine("— $it") }
            appendLine()
        }
        appendLine("Напиши один новый пост. Верни только JSON указанного формата.")
    }

    /** Собирает финальный текст: пост + CTA + хэштеги + подпись. */
    fun assemble(channel: ChannelEntity, body: String, generatedHashtags: String): String = buildString {
        append(body.trim())
        if (channel.ctaText.isNotBlank()) {
            append("\n\n").append(channel.ctaText.trim())
        }
        val tags = channel.hashtags.trim().ifBlank { generatedHashtags.trim() }
        if (tags.isNotBlank()) {
            append("\n\n").append(tags)
        }
        if (channel.signature.isNotBlank()) {
            append("\n\n").append(channel.signature.trim())
        }
    }

    /** Промпт для разбора канала: что публиковать дальше. */
    fun analysisPrompt(channel: ChannelEntity, recentTopics: List<String>, publishedCount: Int): String =
        buildString {
            appendLine("Проанализируй контент-стратегию Telegram-канала «${channel.title}».")
            appendLine("Описание и инструкция канала: ${channel.instructions.ifBlank { "не задана" }}")
            if (channel.audience.isNotBlank()) appendLine("Аудитория: ${channel.audience}")
            appendLine("Всего опубликовано постов: $publishedCount")
            appendLine()
            if (recentTopics.isNotEmpty()) {
                appendLine("Темы последних постов:")
                recentTopics.forEach { appendLine("— $it") }
            } else {
                appendLine("Постов пока не было.")
            }
            appendLine()
            appendLine("Дай ответ по пунктам, кратко и по делу:")
            appendLine("1. Что не так с текущим набором тем (перекосы, повторы, дыры).")
            appendLine("2. 10 конкретных тем следующих постов.")
            appendLine("3. 3–5 рубрик с короткой инструкцией для каждой.")
            appendLine("4. Рекомендуемая частота и время публикаций для этой аудитории.")
            appendLine("Форматируй обычным текстом, без markdown-звёздочек.")
        }

    /** Промпт для генерации инструкции канала по краткому описанию. */
    fun instructionWizardPrompt(title: String, brief: String): String = buildString {
        appendLine("Составь инструкцию для нейросети, которая будет писать посты в Telegram-канал.")
        appendLine("Название канала: $title")
        appendLine("Что известно о канале: $brief")
        appendLine()
        appendLine("Инструкция должна описывать: о чём канал, для кого, какие форматы постов, ")
        appendLine("структуру поста, стиль, чего избегать. 700–1200 символов, сплошным текстом, ")
        appendLine("без markdown, без нумерованных списков — как техническое задание редактору.")
    }
}
