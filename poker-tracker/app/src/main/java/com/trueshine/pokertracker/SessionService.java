package com.trueshine.pokertracker;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Живёт, пока идёт сессия. Держит процесс тёплым (будильники надёжнее)
 * и показывает постоянное уведомление с таймером и текущим BB.
 */
public class SessionService extends Service {

    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_RESUME = "resume";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!prefs.isRunning()) {
                stopSelf();
                return;
            }
            notifyNow();
            handler.postDelayed(this, 1000L);
        }
    };

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, SessionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, SessionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        Notifs.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            prefs.pause();
            AlarmScheduler.cancelCheckin(this);
        } else if (ACTION_RESUME.equals(action)) {
            prefs.resume();
            AlarmScheduler.scheduleNextCheckin(this);
        }

        startForeground(Notifs.ID_SESSION, build());
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private void notifyNow() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(Notifs.ID_SESSION, build());
    }

    private Notification build() {
        boolean paused = prefs.isPaused();
        boolean onBreak = prefs.isOnBreak();

        String title = Fmt.clock(prefs.elapsed()) + "  ·  " + Fmt.bbUnit(prefs.totalBb());
        String text;
        if (onBreak) {
            text = "Перерыв · осталось " + Fmt.mmss(prefs.getBreakEnd() - System.currentTimeMillis());
        } else if (paused) {
            text = "Пауза";
        } else {
            long left = prefs.getNextAlarm() - System.currentTimeMillis();
            text = "Следующий вопрос через " + Fmt.mmss(Math.max(0, left));
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent open = PendingIntent.getActivity(this, 3001,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, Notifs.CH_SESSION)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_stat_spade)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) b.setColor(0xFFE9B949);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_LOW);

        PendingIntent record = PendingIntent.getActivity(this, 3002,
                new Intent(this, CheckInActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags);
        Icon icon = Icon.createWithResource(this, R.drawable.ic_stat_spade);
        b.addAction(new Notification.Action.Builder(icon, "Записать BB", record).build());

        if (!onBreak) {
            Intent toggle = new Intent(this, SessionService.class)
                    .setAction(paused ? ACTION_RESUME : ACTION_PAUSE);
            PendingIntent tp = PendingIntent.getService(this, 3003, toggle, flags);
            b.addAction(new Notification.Action.Builder(
                    icon, paused ? "Продолжить" : "Пауза", tp).build());
        }

        return b.build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
