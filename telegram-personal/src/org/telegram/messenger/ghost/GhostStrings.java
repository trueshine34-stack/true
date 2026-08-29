package org.telegram.messenger.ghost;

import org.telegram.messenger.LocaleController;

import java.util.HashMap;

/**
 * Strings for the screens added by this build. They live here instead of the shared resource
 * bundles so that pulling a newer upstream release never conflicts on strings.xml.
 */
public class GhostStrings {

    private static final HashMap<String, String> EN = new HashMap<>();
    private static final HashMap<String, String> RU = new HashMap<>();

    private static void put(String key, String en, String ru) {
        EN.put(key, en);
        RU.put(key, ru);
    }

    static {
        put("Title", "Privacy & tracking", "Приватность и слежение");
        put("SettingsSummary", "Ghost mode, online history, message archive", "Невидимка, история онлайна, архив сообщений");

        put("GhostHeader", "Ghost mode", "Режим невидимки");
        put("HideOnline", "Never show me as online", "Никогда не показывать меня в сети");
        put("HideOnlineInfo", "Your status stays \"last seen\" as it was before you opened the app. Telegram never receives an \"online\" ping from this device.", "Ваш статус остаётся таким же, как до открытия приложения. Telegram не получает от этого устройства сигнал «в сети».");
        put("HideTyping", "Never send \"typing…\"", "Не отправлять «печатает…»");
        put("HideRead", "Never send read receipts", "Не отправлять отметки о прочтении");
        put("GhostInfo", "These only affect what this device reports about you. Everything in the app keeps working as usual.", "Это влияет только на то, что устройство сообщает о вас. Всё остальное в приложении работает как обычно.");

        put("TrackingHeader", "Local history", "Локальная история");
        put("TrackOnline", "Remember online times", "Запоминать время онлайна");
        put("TrackOnlineInfo", "Whenever Telegram tells the app that someone's status changed, the moment is written down on this device.", "Каждый раз, когда Telegram сообщает приложению об изменении статуса, момент записывается на этом устройстве.");
        put("SaveDeleted", "Keep deleted messages", "Сохранять удалённые сообщения");
        put("SaveEdited", "Keep edit history", "Сохранять историю правок");
        put("SaveInfo", "A copy is written before the app applies a deletion or an edit, so the original stays readable here.", "Копия записывается до того, как приложение применит удаление или правку, поэтому оригинал остаётся здесь.");

        put("OpenOnline", "Online history", "История онлайна");
        put("OpenArchive", "Deleted & edited messages", "Удалённые и изменённые");

        put("DataHeader", "Stored data", "Сохранённые данные");
        put("ClearOnline", "Clear online history", "Очистить историю онлайна");
        put("ClearArchive", "Clear message archive", "Очистить архив сообщений");
        put("ClearConfirm", "Delete this data from the device? It cannot be restored.", "Удалить эти данные с устройства? Восстановить будет нельзя.");
        put("Clear", "Clear", "Очистить");
        put("Cancel", "Cancel", "Отмена");
        put("Cleared", "Deleted", "Удалено");
        put("DataInfo", "Everything is stored in an app-private database on this phone only. Nothing is uploaded.", "Всё хранится в приватной базе данных приложения только на этом телефоне. Никуда не отправляется.");

        put("PeopleTracked", "%1$s tracked", "Отслеживается: %1$s");
        put("DeletedCount", "%1$s deleted", "Удалённых: %1$s");
        put("EditedCount", "%1$s edited", "Изменённых: %1$s");

        put("OnlineEmpty", "Nothing recorded yet", "Пока ничего не записано");
        put("OnlineEmptyInfo", "Status changes appear here as soon as Telegram reports them for people you have chats with.", "Изменения статуса появятся здесь, как только Telegram сообщит о них для людей, с которыми у вас есть чаты.");
        put("ArchiveEmpty", "Archive is empty", "Архив пуст");
        put("ArchiveEmptyInfo", "Deleted or edited messages will be listed here.", "Здесь появятся удалённые или изменённые сообщения.");

        put("Now", "online now", "сейчас в сети");
        put("LastOnline", "last online %1$s", "последний онлайн %1$s");
        put("NeverOnline", "never seen online", "онлайн не зафиксирован");
        put("StatusRecently", "hidden (recently)", "скрыт (недавно)");
        put("StatusWeek", "hidden (within a week)", "скрыт (на этой неделе)");
        put("StatusMonth", "hidden (within a month)", "скрыт (в этом месяце)");
        put("StatusHidden", "hidden", "скрыт");
        put("StatusOffline", "offline", "не в сети");
        put("StatusOnline", "online", "в сети");

        put("Today", "Today", "Сегодня");
        put("Yesterday", "Yesterday", "Вчера");
        put("SessionsHeader", "Online sessions", "Сеансы онлайна");
        put("TotalToday", "Total today: %1$s", "Всего сегодня: %1$s");
        put("TotalPeriod", "Total: %1$s", "Всего: %1$s");
        put("SessionRange", "%1$s – %2$s", "%1$s – %2$s");
        put("Ongoing", "in progress", "продолжается");
        put("DurationMin", "%1$d min", "%1$d мин");
        put("DurationHourMin", "%1$d h %2$d min", "%1$d ч %2$d мин");
        put("DurationSec", "%1$d s", "%1$d с");
        put("Points", "%1$s status changes recorded", "Записано изменений статуса: %1$s");
        put("Period7", "7 days", "7 дней");
        put("Period30", "30 days", "30 дней");
        put("PeriodAll", "All time", "Всё время");

        put("FilterAll", "All", "Все");
        put("FilterDeleted", "Deleted", "Удалённые");
        put("FilterEdited", "Edited", "Изменённые");
        put("VersionOriginal", "Original", "Оригинал");
        put("VersionEdited", "Edit %1$d", "Правка %1$d");
        put("VersionDeleted", "Before deletion", "Перед удалением");
        put("WasDeleted", "deleted", "удалено");
        put("WasEdited", "edited %1$d×", "изменено %1$d×");
        put("NoText", "(no text)", "(без текста)");
        put("OpenChat", "Open chat", "Открыть чат");
        put("CopyText", "Copy text", "Скопировать текст");
        put("Copied", "Copied", "Скопировано");
        put("MessageHistory", "Message history", "История сообщения");

        put("ApiIdRejected",
                "Telegram refused the sign-in because of the api_id this build was compiled with. "
                        + "Register your own at my.telegram.org (API development tools) and rebuild with it.",
                "Telegram отклонил вход из-за api_id, с которым собрано это приложение. "
                        + "Заведи свой на my.telegram.org (API development tools) и пересобери с ним.");
    }

    private static boolean isRussian() {
        try {
            LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && info.shortName != null) {
                String name = info.shortName.toLowerCase();
                return name.startsWith("ru") || name.startsWith("be") || name.startsWith("uk");
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    public static String get(String key) {
        String value = (isRussian() ? RU : EN).get(key);
        return value != null ? value : key;
    }

    public static String format(String key, Object... args) {
        try {
            return String.format(get(key), args);
        } catch (Exception e) {
            return get(key);
        }
    }
}
