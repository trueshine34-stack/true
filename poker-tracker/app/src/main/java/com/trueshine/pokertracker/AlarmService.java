package com.trueshine.pokertracker;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Звонит как будильник: зацикленный сигнал + вибрация + полноэкранное уведомление,
 * которое открывает экран ввода результата. Сам себя глушит через 90 секунд.
 */
public class AlarmService extends Service {

    public static final String ACTION_RING = "ring";
    public static final String ACTION_STOP = "stop";
    public static final String EXTRA_KIND = "kind";

    public static final int KIND_CHECKIN = 0;
    public static final int KIND_BREAK_OVER = 1;

    private static final long AUTO_STOP_MS = 90_000L;

    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopSelf;

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, AlarmService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int kind = intent == null ? KIND_CHECKIN : intent.getIntExtra(EXTRA_KIND, KIND_CHECKIN);
        Notifs.ensureChannels(this);
        startForeground(Notifs.ID_ALARM, buildNotification(this, kind));

        acquireWakeLock();
        ring();
        handler.removeCallbacks(autoStop);
        handler.postDelayed(autoStop, AUTO_STOP_MS);
        return START_NOT_STICKY;
    }

    /** Уведомление будильника. Статическое — им же пользуется запасной путь без сервиса. */
    public static Notification buildNotification(Context ctx, int kind) {
        Prefs p = new Prefs(ctx);

        Intent target = new Intent(ctx, CheckInActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(CheckInActivity.EXTRA_FROM_BREAK, kind == KIND_BREAK_OVER);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(ctx, 2001, target, flags);

        String title = kind == KIND_BREAK_OVER
                ? "Перерыв окончен — возвращаемся"
                : "Какой результат в покере?";
        String text = kind == KIND_BREAK_OVER
                ? "Сессия продолжается · " + Fmt.bbUnit(p.totalBb())
                : "Отметь результат за последние " + p.getIntervalMin() + " мин · сейчас "
                        + Fmt.bbUnit(p.totalBb());

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, Notifs.CH_ALARM)
                : new Notification.Builder(ctx);

        b.setSmallIcon(R.drawable.ic_stat_spade)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setAutoCancel(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(0xFFE9B949);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_MAX);

        return b.build();
    }

    private void ring() {
        if (player != null) return;
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri != null) {
                player = new MediaPlayer();
                player.setDataSource(this, uri);
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                player.setLooping(true);
                player.prepare();
                player.start();
            }
        } catch (Exception e) {
            player = null;
        }

        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            long[] pattern = {0, 500, 700, 500, 1500};
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0),
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .build());
                } else {
                    vibrator.vibrate(pattern, 0, new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM).build());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pokertracker:alarm");
            wakeLock.acquire(AUTO_STOP_MS + 10_000L);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(autoStop);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(Notifs.ID_ALARM);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
