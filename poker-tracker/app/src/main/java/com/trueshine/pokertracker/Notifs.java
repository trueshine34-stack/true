package com.trueshine.pokertracker;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

public final class Notifs {

    public static final String CH_SESSION = "session";
    public static final String CH_ALARM = "alarm_v2";

    public static final int ID_SESSION = 11;
    public static final int ID_ALARM = 12;

    private Notifs() {}

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        NotificationChannel session = new NotificationChannel(
                CH_SESSION, "Текущая сессия", NotificationManager.IMPORTANCE_LOW);
        session.setDescription("Таймер сессии и текущий результат в BB");
        session.setShowBadge(false);
        session.setSound(null, null);
        nm.createNotificationChannel(session);

        // Звук и вибрацию ведём сами (зацикленно), поэтому у канала их выключаем.
        NotificationChannel alarm = new NotificationChannel(
                CH_ALARM, "Напоминание о результате", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("Будильник каждые N минут с вопросом о результате");
        alarm.setSound(null, null);
        alarm.enableVibration(false);
        alarm.setBypassDnd(true);
        alarm.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alarm);
    }
}
