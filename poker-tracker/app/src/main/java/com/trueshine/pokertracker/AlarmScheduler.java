package com.trueshine.pokertracker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Ставит и снимает точные будильники: чек-ин каждые N минут и конец перерыва. */
public final class AlarmScheduler {

    public static final String ACTION_CHECKIN = "com.trueshine.pokertracker.CHECKIN";
    public static final String ACTION_BREAK_OVER = "com.trueshine.pokertracker.BREAK_OVER";

    private static final int RC_CHECKIN = 1001;
    private static final int RC_BREAK = 1002;

    private AlarmScheduler() {}

    private static PendingIntent pi(Context ctx, String action, int rc) {
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, rc, i, flags);
    }

    /** Следующий чек-ин через обычный интервал. */
    public static void scheduleNextCheckin(Context ctx) {
        Prefs p = new Prefs(ctx);
        scheduleCheckinAt(ctx, System.currentTimeMillis() + p.getIntervalMin() * 60_000L);
    }

    public static void scheduleCheckinAt(Context ctx, long at) {
        Prefs p = new Prefs(ctx);
        p.setNextAlarm(at);
        exact(ctx, at, pi(ctx, ACTION_CHECKIN, RC_CHECKIN));
    }

    public static void cancelCheckin(Context ctx) {
        new Prefs(ctx).setNextAlarm(0);
        am(ctx).cancel(pi(ctx, ACTION_CHECKIN, RC_CHECKIN));
    }

    public static void scheduleBreakEnd(Context ctx, long at) {
        exact(ctx, at, pi(ctx, ACTION_BREAK_OVER, RC_BREAK));
    }

    public static void cancelBreakEnd(Context ctx) {
        am(ctx).cancel(pi(ctx, ACTION_BREAK_OVER, RC_BREAK));
    }

    public static void cancelAll(Context ctx) {
        cancelCheckin(ctx);
        cancelBreakEnd(ctx);
    }

    private static AlarmManager am(Context ctx) {
        return (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
    }

    private static void exact(Context ctx, long at, PendingIntent p) {
        AlarmManager am = am(ctx);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // Точные будильники запрещены пользователем — ставим неточный, чтобы не потерять напоминание.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
                return;
            }
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        }
    }

    public static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return am(ctx).canScheduleExactAlarms();
    }
}
