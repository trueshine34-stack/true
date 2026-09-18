package ae.dressrent.studio.util

import ae.dressrent.studio.data.BookingCard
import ae.dressrent.studio.data.Client
import ae.dressrent.studio.data.Settings

/**
 * Ready-to-send WhatsApp texts. Every template is short, names the dress and the date,
 * and ends with one clear question — that is what makes a client answer.
 */
object Messages {

    fun confirmation(card: BookingCard, s: Settings): String = buildString {
        append("${card.clientName}, добрый день! ${s.businessName} на связи ✨\n\n")
        append("Бронь подтверждена:\n")
        append("• Образ: ${card.itemTitle}\n")
        append("• Даты: ${Dates.range(card.booking.start, card.booking.end)}\n")
        append("• Аренда: ${Money.format(card.booking.price, s.currency)}\n")
        if (card.booking.deposit > 0) {
            append("• Возвратный залог: ${Money.format(card.booking.deposit, s.currency)}\n")
        }
        if (card.outstanding > 0) {
            append("• К оплате: ${Money.format(card.outstanding, s.currency)}\n")
        }
        append("\nВыдача — ${Dates.relative(card.booking.start)}, ${s.showroomAddress}. ")
        append("Подскажите, вам удобнее забрать самой или привезти?")
    }

    fun pickupToday(card: BookingCard, s: Settings): String =
        "${card.clientName}, доброе утро! Напоминаю: сегодня забираем «${card.itemTitle}» " +
            (if (card.outstanding > 0) "(к оплате ${Money.format(card.outstanding, s.currency)}). " else ". ") +
            "Во сколько вам удобно?"

    fun returnDue(card: BookingCard, s: Settings): String =
        "${card.clientName}, надеюсь, вечер прошёл прекрасно 💫 " +
            "Сегодня возврат «${card.itemTitle}»" +
            (if (card.booking.deposit > 0) ", залог ${Money.format(card.booking.deposit, s.currency)} вернём сразу после осмотра. " else ". ") +
            "Когда удобно передать платье?"

    fun overdue(card: BookingCard, s: Settings, daysLate: Long): String {
        val d = Dates.plural(daysLate, "день", "дня", "дней")
        return "${card.clientName}, добрый день! «${card.itemTitle}» ждём обратно уже $daysLate $d. " +
            "Подскажите, когда сможете вернуть? Продление считаем по ${Money.format(card.booking.price / card.booking.days, s.currency)} в день."
    }

    fun leadFollowUp(card: BookingCard, s: Settings): String =
        "${card.clientName}, здравствуйте! Вы смотрели «${card.itemTitle}» на ${Dates.human(card.booking.start)}. " +
            "Пока дата свободна, но её часто разбирают. Забронировать за вами? " +
            "Аренда ${Money.format(card.booking.price, s.currency)}, бронь закрепляется предоплатой."

    fun reviewRequest(card: BookingCard, s: Settings): String =
        "${card.clientName}, спасибо, что выбрали ${s.businessName}! " +
            "Если образ понравился — буду очень благодарна за пару слов и фото в отзыв. " +
            "А за отзыв дарю ${s.promoCode} — скидку на следующую аренду 🤍"

    fun winBack(client: Client, s: Settings, daysSilent: Long): String {
        val d = Dates.plural(daysSilent, "день", "дня", "дней")
        return "${client.name}, давно не виделись — $daysSilent $d! " +
            "Завезли новые вечерние образы в ${s.businessName}. " +
            "Есть повод в ближайшее время? Держу для вас промокод ${s.promoCode}."
    }

    /** Posted to stories/status when the calendar has a hole — free days do not sell themselves. */
    fun freeDayPromo(s: Settings, gap: ae.dressrent.studio.data.Minor): String =
        "Свободные даты на этой неделе ✨ ${s.businessName}\n" +
            "Вечерние и коктейльные образы, примерка и доставка по Дубаю.\n" +
            "Пишите в WhatsApp — подберу образ под ваше мероприятие. " +
            (if (gap > 0) "Осталось ${Money.formatShort(gap, s.currency)} до плана дня — первым двум сегодня скидка." else "")
}
