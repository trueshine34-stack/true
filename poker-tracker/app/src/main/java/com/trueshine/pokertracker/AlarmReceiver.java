package com.trueshine.pokertracker;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        Prefs p = new Prefs(ctx);

        if (AlarmScheduler.ACTION_BREAK_OVER.equals(action)) {
            p.setBreakEnd(0);
            if (!p.isRunning()) return;
            p.resume();
            // После перерыва отсчёт интервала начинается заново.
            p.setLastAsk(System.currentTimeMillis());
            AlarmScheduler.scheduleNextCheckin(ctx);
            SessionService.start(ctx);
            ring(ctx, AlarmService.KIND_BREAK_OVER);
            return;
        }

        if (AlarmScheduler.ACTION_CHECKIN.equals(action)) {
            if (!p.isRunning() || p.isPaused() || p.isOnBreak()) return;
            // Сразу ставим следующий будильник: если вопрос проигнорируют,
            // цепочка напоминаний всё равно не оборвётся.
            AlarmScheduler.scheduleNextCheckin(ctx);
            ring(ctx, AlarmService.KIND_CHECKIN);
        }
    }

    private void ring(Context ctx, int kind) {
        Intent i = new Intent(ctx, AlarmService.class)
                .setAction(AlarmService.ACTION_RING)
                .putExtra(AlarmService.EXTRA_KIND, kind);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            // Система не дала поднять сервис из фона — показываем хотя бы уведомление.
            Notifs.ensureChannels(ctx);
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(Notifs.ID_ALARM, AlarmService.buildNotification(ctx, kind));
            }
        }
    }
}
