package com.trueshine.pokertracker;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;

/** Пятиминутный перерыв: кольцевой отсчёт, сессия на паузе. */
public class BreakActivity extends Activity {

    private Prefs prefs;
    private RingView ring;
    private TextView time, hint, stats;
    private long totalMs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!render()) return;
            handler.postDelayed(this, 250L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_break);
        Ui.applyInsets(findViewById(R.id.root));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true);

        prefs = new Prefs(this);
        ring = findViewById(R.id.breakRing);
        time = findViewById(R.id.breakTime);
        hint = findViewById(R.id.breakHint);
        stats = findViewById(R.id.breakStats);

        ring.setColor(0xFF34D399);

        if (!prefs.isOnBreak()) CheckInActivity.startBreak(this, prefs);
        totalMs = Math.max(1, prefs.getBreakEnd() - System.currentTimeMillis());

        findViewById(R.id.addMinBtn).setOnClickListener(v -> {
            long end = prefs.getBreakEnd() + 60_000L;
            prefs.setBreakEnd(end);
            AlarmScheduler.scheduleBreakEnd(this, end);
            totalMs = Math.max(totalMs, end - System.currentTimeMillis());
            SessionService.start(this);
            render();
        });

        findViewById(R.id.endBreakBtn).setOnClickListener(v -> finishBreak());
    }

    private void finishBreak() {
        AlarmScheduler.cancelBreakEnd(this);
        prefs.setBreakEnd(0);
        prefs.resume();
        prefs.setLastAsk(System.currentTimeMillis());
        AlarmScheduler.scheduleNextCheckin(this);
        SessionService.start(this);
        finish();
    }

    /** @return false, если перерыв закончился и экран пора закрыть. */
    private boolean render() {
        long left = prefs.getBreakEnd() - System.currentTimeMillis();
        if (left <= 0) {
            time.setText("00:00");
            ring.setProgress(0f);
            hint.setText("перерыв окончен");
            finish();
            return false;
        }
        time.setText(Fmt.mmss(left));
        ring.setProgress(left / (float) totalMs);
        hint.setText("сессия на паузе");
        stats.setText(Fmt.clock(prefs.elapsed()) + " за столом  ·  " + Fmt.bbUnit(prefs.totalBb()));
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }
}
