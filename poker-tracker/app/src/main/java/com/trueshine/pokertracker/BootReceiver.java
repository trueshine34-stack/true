package com.trueshine.pokertracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки восстанавливаем будильник и уведомление сессии. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Prefs p = new Prefs(ctx);
        if (!p.isRunning() || p.isPaused()) return;

        long next = p.getNextAlarm();
        long now = System.currentTimeMillis();
        AlarmScheduler.scheduleCheckinAt(ctx, next > now ? next : now + 60_000L);
        SessionService.start(ctx);
    }
}
