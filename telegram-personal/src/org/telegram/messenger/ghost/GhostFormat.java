package org.telegram.messenger.ghost;

import org.telegram.messenger.LocaleController;

import java.util.Calendar;

/** Date and duration formatting shared by the screens this build adds. */
public class GhostFormat {

    /** "Today 14:32", "Yesterday 09:11", "12 Mar 14:32" or "12 Mar 2024 14:32". */
    public static String dateTime(int unixSeconds) {
        if (unixSeconds <= 0) {
            return "—";
        }
        long millis = unixSeconds * 1000L;
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);

        int nowDay = now.get(Calendar.DAY_OF_YEAR);
        int nowYear = now.get(Calendar.YEAR);
        int thenDay = then.get(Calendar.DAY_OF_YEAR);
        int thenYear = then.get(Calendar.YEAR);

        String time = LocaleController.getInstance().getFormatterDay().format(millis);
        if (nowYear == thenYear && nowDay == thenDay) {
            return GhostStrings.get("Today") + " " + time;
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == thenYear && now.get(Calendar.DAY_OF_YEAR) == thenDay) {
            return GhostStrings.get("Yesterday") + " " + time;
        }
        if (nowYear == thenYear) {
            return LocaleController.getInstance().getFormatterDayMonth().format(millis) + " " + time;
        }
        return LocaleController.getInstance().getFormatterYear().format(millis) + " " + time;
    }

    public static String time(int unixSeconds) {
        if (unixSeconds <= 0) {
            return "—";
        }
        return LocaleController.getInstance().getFormatterDay().format(unixSeconds * 1000L);
    }

    public static String dayLabel(int unixSeconds) {
        long millis = unixSeconds * 1000L;
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return GhostStrings.get("Today");
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return GhostStrings.get("Yesterday");
        }
        if (Calendar.getInstance().get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            return LocaleController.getInstance().getFormatterDayMonth().format(millis);
        }
        return LocaleController.getInstance().getFormatterYear().format(millis);
    }

    /** Start of the local day a timestamp falls in, as unix seconds. */
    public static int dayStart(int unixSeconds) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(unixSeconds * 1000L);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return (int) (c.getTimeInMillis() / 1000L);
    }

    public static String duration(int seconds) {
        if (seconds < 60) {
            return GhostStrings.format("DurationSec", Math.max(seconds, 0));
        }
        int minutes = seconds / 60;
        if (minutes < 60) {
            return GhostStrings.format("DurationMin", minutes);
        }
        return GhostStrings.format("DurationHourMin", minutes / 60, minutes % 60);
    }

    public static String stateName(int state) {
        switch (state) {
            case GhostStore.STATE_ONLINE:
                return GhostStrings.get("StatusOnline");
            case GhostStore.STATE_OFFLINE:
                return GhostStrings.get("StatusOffline");
            case GhostStore.STATE_RECENTLY:
                return GhostStrings.get("StatusRecently");
            case GhostStore.STATE_LAST_WEEK:
                return GhostStrings.get("StatusWeek");
            case GhostStore.STATE_LAST_MONTH:
                return GhostStrings.get("StatusMonth");
            default:
                return GhostStrings.get("StatusHidden");
        }
    }

    /** One-line summary shown under a name in the tracked-people list. */
    public static String summary(GhostStore.TrackedUser user) {
        int now = (int) (System.currentTimeMillis() / 1000L);
        if (user.lastState == GhostStore.STATE_ONLINE && user.lastExpires > now) {
            return GhostStrings.get("Now");
        }
        if (user.lastOnline > 0) {
            return GhostStrings.format("LastOnline", dateTime(user.lastOnline));
        }
        return stateName(user.lastState);
    }
}
