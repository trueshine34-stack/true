package com.trueshine.threadsposter.domain

import com.trueshine.threadsposter.data.db.AccountEntity
import com.trueshine.threadsposter.data.db.LeadEntity
import com.trueshine.threadsposter.data.db.QueryEntity
import com.trueshine.threadsposter.data.db.RubricEntity
import com.trueshine.threadsposter.data.remote.ThreadsPost

/** Промпты для DeepSeek: посты, оценка находок, ответы. */
object PromptBuilder {

    // --- Собственные посты ---

    fun systemPrompt(account: AccountEntity): String = buildString {
        appendLine("Ты ведёшь аккаунт в Threads: @${account.username.ifBlank { account.displayName }}.")
        appendLine("Пишешь готовые к публикации посты.")
        appendLine()
        appendLine("ЯЗЫК: ${account.language.ifBlank { "русский" }}.")
        if (account.audience.isNotBlank()) appendLine("АУДИТОРИЯ: ${account.audience}")
        if (account.tone.isNotBlank()) appendLine("ТОН: ${account.tone}")
        appendLine("ОБЪЁМ: ${account.minChars}–${account.maxChars} символов.")
        appendLine(
            "ФОРМАТ: Threads — это лента коротких мыслей. Никакой разметки: ни markdown, " +
                "ни HTML, ни звёздочек, ни решёток. Только обычный текст и переводы строк."
        )
        appendLine(
            when (account.emojiLevel) {
                0 -> "ЭМОДЗИ: не использовать вообще."
                2 -> "ЭМОДЗИ: использовать активно, но по делу."
                else -> "ЭМОДЗИ: 1–2 штуки на пост, уместно."
            }
        )
        appendLine()
        appendLine("ИНСТРУКЦИЯ АККАУНТА (главный приоритет):")
        appendLine(account.instructions.ifBlank { "Публикуй короткие полезные посты по теме аккаунта." })
        if (account.extraRules.isNotBlank()) {
            appendLine()
            appendLine("ДОПОЛНИТЕЛЬНЫЕ ПРАВИЛА:")
            appendLine(account.extraRules)
        }
        if (account.bannedTopics.isNotBlank()) {
            appendLine()
            appendLine("ЗАПРЕЩЕНО ПИСАТЬ ПРО: ${account.bannedTopics}")
        }
        appendLine()
        appendLine("Первая строка должна цеплять — это всё, что видно в ленте.")
        appendLine("Не выдумывай точные цифры, даты и цитаты, если их нет во входных данных.")
        appendLine("Не пиши служебных пояснений — только текст поста.")
        appendLine()
        appendLine("ФОРМАТ ОТВЕТА — строго JSON без обёрток:")
        appendLine("""{"topic":"тема поста 3-7 слов","text":"текст поста","hashtags":"#тег #тег"}""")
    }

    fun userPrompt(
        account: AccountEntity,
        rubric: RubricEntity?,
        recentTopics: List<String>,
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
        if (recentTopics.isNotEmpty()) {
            appendLine("УЖЕ БЫЛО В ЛЕНТЕ (не повторяй эти темы):")
            recentTopics.take(account.avoidRepeatLastN).forEach { appendLine("— $it") }
            appendLine()
        }
        appendLine("Напиши один новый пост. Верни только JSON указанного формата.")
    }

    /** Собирает финальный текст: пост + призыв + хэштеги + подпись. */
    fun assemble(account: AccountEntity, body: String, generatedHashtags: String): String = buildString {
        append(body.trim())
        if (account.ctaText.isNotBlank()) append("\n\n").append(account.ctaText.trim())
        val tags = account.hashtags.trim().ifBlank { generatedHashtags.trim() }
        if (tags.isNotBlank()) append("\n\n").append(tags)
        if (account.signature.isNotBlank()) append("\n\n").append(account.signature.trim())
    }

    // --- Оценка находок поиска ---

    /**
     * Одним запросом оцениваем сразу пачку найденных постов: это заметно
     * дешевле, чем отдельный вызов на каждый пост.
     */
    fun scoringPrompt(
        account: AccountEntity,
        query: QueryEntity,
        posts: List<LeadEntity>,
    ): String = buildString {
        appendLine("Ты помогаешь вести аккаунт в Threads и решаешь, под какими чужими постами")
        appendLine("уместно оставить полезный комментарий от лица этого аккаунта.")
        appendLine()
        appendLine("ОБ АККАУНТЕ: ${account.instructions.ifBlank { account.displayName }}")
        if (account.audience.isNotBlank()) appendLine("АУДИТОРИЯ: ${account.audience}")
        appendLine("ПОИСКОВЫЙ ЗАПРОС: ${query.query}")
        if (query.relevanceBrief.isNotBlank()) {
            appendLine("ЧТО СЧИТАЕМ ПОДХОДЯЩИМ: ${query.relevanceBrief}")
        }
        appendLine()
        appendLine("Оцени каждый пост от 1 до 10 по тому, насколько там уместен и полезен")
        appendLine("наш комментарий. Ставь низкую оценку, если пост не по теме, это спам,")
        appendLine("реклама, токсичная перепалка, политика или разговор, в который лезть неловко.")
        appendLine()
        posts.forEachIndexed { index, lead ->
            appendLine("[$index] @${lead.authorUsername}: ${lead.text.take(400)}")
        }
        appendLine()
        appendLine("Верни строго JSON:")
        appendLine("""{"results":[{"i":0,"score":8,"reason":"почему"}]}""")
    }

    /** Промпт для одного ответа под чужим постом. */
    fun replyPrompt(
        account: AccountEntity,
        query: QueryEntity?,
        lead: LeadEntity,
    ): String = buildString {
        appendLine("Напиши короткий комментарий под чужим постом в Threads от лица нашего аккаунта.")
        appendLine()
        appendLine("ОБ АККАУНТЕ: ${account.instructions.ifBlank { account.displayName }}")
        if (account.tone.isNotBlank()) appendLine("ТОН: ${account.tone}")
        appendLine("ЯЗЫК: тот же, на котором написан пост автора.")
        val instructions = query?.replyInstructions?.takeIf { it.isNotBlank() }
            ?: account.replyInstructions.takeIf { it.isNotBlank() }
        if (instructions != null) {
            appendLine()
            appendLine("КАК ОТВЕЧАТЬ: $instructions")
        }
        if (account.bannedTopics.isNotBlank()) {
            appendLine("НЕ ЗАТРАГИВАЙ: ${account.bannedTopics}")
        }
        appendLine()
        appendLine("ПОСТ АВТОРА @${lead.authorUsername}:")
        appendLine(lead.text.take(800))
        appendLine()
        appendLine("Правила комментария:")
        appendLine("— до 300 символов, обычный текст без разметки;")
        appendLine("— по существу поста, без общих слов и без лести;")
        appendLine("— не рекламируй ничего в лоб и не проси подписаться;")
        appendLine("— не начинай со слов «Отличный пост» и подобных;")
        appendLine("— если сказать нечего по делу, верни пустой text.")
        appendLine()
        appendLine("""Верни строго JSON: {"text":"текст комментария"}""")
    }

    /** Промпт для ответа на комментарий под нашим собственным постом. */
    fun ownReplyPrompt(
        account: AccountEntity,
        ourPostText: String,
        reply: ThreadsPost,
    ): String = buildString {
        appendLine("Ответь на комментарий под нашим постом в Threads от лица нашего аккаунта.")
        appendLine()
        appendLine("ОБ АККАУНТЕ: ${account.instructions.ifBlank { account.displayName }}")
        if (account.tone.isNotBlank()) appendLine("ТОН: ${account.tone}")
        if (account.replyInstructions.isNotBlank()) {
            appendLine("КАК ОТВЕЧАТЬ: ${account.replyInstructions}")
        }
        appendLine()
        appendLine("НАШ ПОСТ: ${ourPostText.take(500)}")
        appendLine("КОММЕНТАРИЙ @${reply.username}: ${reply.text.take(500)}")
        appendLine()
        appendLine("Правила: до 300 символов, обычный текст, по существу, на языке комментария.")
        appendLine("Если это оскорбление, спам или провокация — верни пустой text.")
        appendLine()
        appendLine("""Верни строго JSON: {"text":"текст ответа"}""")
    }

    // --- Вспомогательные мастера ---

    fun instructionWizardPrompt(name: String, brief: String): String = buildString {
        appendLine("Составь инструкцию для нейросети, которая будет писать посты в Threads.")
        appendLine("Аккаунт: $name")
        appendLine("Что известно: $brief")
        appendLine()
        appendLine("Опиши: о чём аккаунт, для кого, какие форматы постов, структуру поста, стиль,")
        appendLine("чего избегать. Учти, что пост в Threads — до 500 символов, без разметки.")
        appendLine("600–1000 символов сплошным текстом, без markdown и без списков.")
    }

    fun analysisPrompt(account: AccountEntity, recentTopics: List<String>, publishedCount: Int): String =
        buildString {
            appendLine("Проанализируй контент-стратегию аккаунта в Threads: ${account.displayName}.")
            appendLine("Инструкция аккаунта: ${account.instructions.ifBlank { "не задана" }}")
            if (account.audience.isNotBlank()) appendLine("Аудитория: ${account.audience}")
            appendLine("Всего опубликовано постов: $publishedCount")
            appendLine()
            if (recentTopics.isNotEmpty()) {
                appendLine("Темы последних постов:")
                recentTopics.forEach { appendLine("— $it") }
            } else {
                appendLine("Постов пока не было.")
            }
            appendLine()
            appendLine("Ответь по пунктам, кратко:")
            appendLine("1. Что не так с текущим набором тем.")
            appendLine("2. 10 конкретных тем следующих постов.")
            appendLine("3. 3–5 рубрик с короткой инструкцией для каждой.")
            appendLine("4. 5 поисковых запросов, по которым стоит искать чужие посты для комментариев.")
            appendLine("Обычный текст, без markdown.")
        }
}
