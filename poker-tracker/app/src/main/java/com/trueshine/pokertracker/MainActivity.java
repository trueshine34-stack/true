package com.trueshine.pokertracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int[] INTERVALS = {5, 10, 15, 20, 30, 45, 60};
    private static final int[] BREAKS = {3, 5, 10, 15};
    private static final int RQ_NOTIF = 77;

    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", new Locale("ru"));
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("d MMM, HH:mm", new Locale("ru"));

    private Prefs prefs;

    private TextView timer, timerSub, balance, bbPerHour, statusPill;
    private TextView statCount, statBest, statWorst;
    private TextView nextTitle, nextText, ringText, intervalBtn, breakLenBtn, warning;
    private TextView primaryBtn, breakBtn, pauseBtn, stopBtn;
    private RingView ring;
    private BarsView bars;
    private LinearLayout entriesBox, historyBox, rowSecondary;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderLive();
            handler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        Ui.applyInsets(findViewById(R.id.root));

        prefs = new Prefs(this);
        Notifs.ensureChannels(this);
        bind();
        wire();
        askNotificationPermission();
    }

    private void bind() {
        timer = findViewById(R.id.timer);
        timerSub = findViewById(R.id.timerSub);
        balance = findViewById(R.id.balance);
        bbPerHour = findViewById(R.id.bbPerHour);
        statusPill = findViewById(R.id.statusPill);
        statCount = findViewById(R.id.statCount);
        statBest = findViewById(R.id.statBest);
        statWorst = findViewById(R.id.statWorst);
        nextTitle = findViewById(R.id.nextTitle);
        nextText = findViewById(R.id.nextText);
        ringText = findViewById(R.id.ringText);
        ring = findViewById(R.id.ring);
        bars = findViewById(R.id.bars);
        intervalBtn = findViewById(R.id.intervalBtn);
        breakLenBtn = findViewById(R.id.breakLenBtn);
        warning = findViewById(R.id.warning);
        primaryBtn = findViewById(R.id.primaryBtn);
        breakBtn = findViewById(R.id.breakBtn);
        pauseBtn = findViewById(R.id.pauseBtn);
        stopBtn = findViewById(R.id.stopBtn);
        rowSecondary = findViewById(R.id.rowSecondary);
        entriesBox = findViewById(R.id.entriesBox);
        historyBox = findViewById(R.id.historyBox);
    }

    private void wire() {
        primaryBtn.setOnClickListener(v -> {
            if (prefs.isRunning()) startActivity(new Intent(this, CheckInActivity.class));
            else startSession();
        });

        breakBtn.setOnClickListener(v -> {
            if (!prefs.isRunning()) {
                Toast.makeText(this, "Сначала начни сессию", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!prefs.isOnBreak()) CheckInActivity.startBreak(this, prefs);
            startActivity(new Intent(this, BreakActivity.class));
        });

        pauseBtn.setOnClickListener(v -> {
            if (!prefs.isRunning()) return;
            if (prefs.isOnBreak()) {
                startActivity(new Intent(this, BreakActivity.class));
                return;
            }
            if (prefs.isPaused()) {
                prefs.resume();
                AlarmScheduler.scheduleNextCheckin(this);
            } else {
                prefs.pause();
                AlarmScheduler.cancelCheckin(this);
            }
            SessionService.start(this);
            render();
        });

        stopBtn.setOnClickListener(v -> confirmStop());
        intervalBtn.setOnClickListener(v -> pickInterval());
        breakLenBtn.setOnClickListener(v -> pickBreak());
        warning.setOnClickListener(v -> fixNextIssue());
    }

    // ---- жизненный цикл сессии -------------------------------------------

    private void startSession() {
        prefs.startSession();
        AlarmScheduler.scheduleNextCheckin(this);
        SessionService.start(this);
        render();
        Toast.makeText(this, "Погнали. Спрошу через " + prefs.getIntervalMin() + " мин",
                Toast.LENGTH_SHORT).show();
    }

    private void confirmStop() {
        if (!prefs.isRunning()) return;
        String summary = Fmt.clock(prefs.elapsed()) + " за столом · " + Fmt.bbUnit(prefs.totalBb());
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Завершить сессию?")
                .setMessage(summary + "\nРезультат уйдёт в историю.")
                .setPositiveButton("Завершить", (d, w) -> {
                    Prefs.Session s = prefs.finishSession();
                    AlarmScheduler.cancelAll(this);
                    AlarmService.stop(this);
                    SessionService.stop(this);
                    render();
                    Toast.makeText(this, "Сессия закрыта: " + Fmt.bbUnit(s.bb)
                            + " за " + Fmt.human(s.durationMs), Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickInterval() {
        String[] items = new String[INTERVALS.length];
        int checked = 2;
        for (int i = 0; i < INTERVALS.length; i++) {
            items[i] = "каждые " + INTERVALS[i] + " мин";
            if (INTERVALS[i] == prefs.getIntervalMin()) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Как часто спрашивать")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    prefs.setIntervalMin(INTERVALS[which]);
                    if (prefs.isRunning() && !prefs.isPaused() && !prefs.isOnBreak()) {
                        AlarmScheduler.scheduleNextCheckin(this);
                        SessionService.start(this);
                    }
                    d.dismiss();
                    render();
                })
                .show();
    }

    private void pickBreak() {
        String[] items = new String[BREAKS.length];
        int checked = 1;
        for (int i = 0; i < BREAKS.length; i++) {
            items[i] = BREAKS[i] + " мин";
            if (BREAKS[i] == prefs.getBreakMin()) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Длина перерыва")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    prefs.setBreakMin(BREAKS[which]);
                    d.dismiss();
                    render();
                })
                .show();
    }

    // ---- отрисовка --------------------------------------------------------

    private void render() {
        renderLive();
        renderEntries();
        renderHistory();
        renderWarning();
    }

    /** Часть, которая обновляется каждые полсекунды. */
    private void renderLive() {
        boolean running = prefs.isRunning();
        boolean paused = prefs.isPaused();
        boolean onBreak = prefs.isOnBreak();

        timer.setText(Fmt.clock(prefs.elapsed()));

        double total = prefs.totalBb();
        balance.setText(Fmt.bbUnit(total));
        balance.setTextColor(total > 0 ? 0xFF34D399 : (total < 0 ? 0xFFF87171 : 0xFFECF6F1));

        double rate = prefs.bbPerHour();
        bbPerHour.setText(Fmt.bb(rate) + " BB/час");

        List<Prefs.Entry> entries = prefs.getEntries();
        statCount.setText(String.valueOf(entries.size()));

        double best = 0, worst = 0;
        boolean any = false;
        for (Prefs.Entry e : entries) {
            if (!any) {
                best = worst = e.bb;
                any = true;
            }
            best = Math.max(best, e.bb);
            worst = Math.min(worst, e.bb);
        }
        statBest.setText(any ? Fmt.bb(best) : "—");
        statWorst.setText(any ? Fmt.bb(worst) : "—");

        List<Double> vals = new ArrayList<>();
        for (Prefs.Entry e : entries) vals.add(e.bb);
        bars.setValues(vals);
        bars.setVisibility(vals.isEmpty() ? View.GONE : View.VISIBLE);

        if (!running) {
            statusPill.setText("ГОТОВ");
            statusPill.setBackgroundResource(R.drawable.pill_gold);
            statusPill.setTextColor(0xFFE9B949);
            timerSub.setText("Сессия не начата");
        } else if (onBreak) {
            statusPill.setText("ПЕРЕРЫВ");
            statusPill.setBackgroundResource(R.drawable.pill_gold);
            statusPill.setTextColor(0xFFE9B949);
            timerSub.setText("Перерыв до " + hhmm.format(new Date(prefs.getBreakEnd())));
        } else if (paused) {
            statusPill.setText("ПАУЗА");
            statusPill.setBackgroundResource(R.drawable.pill_gold);
            statusPill.setTextColor(0xFFE9B949);
            timerSub.setText("На паузе · старт в " + hhmm.format(new Date(prefs.getStart())));
        } else {
            statusPill.setText("ИДЁТ ИГРА");
            statusPill.setBackgroundResource(R.drawable.pill_live);
            statusPill.setTextColor(0xFF34D399);
            timerSub.setText("Старт в " + hhmm.format(new Date(prefs.getStart()))
                    + " · чек-инов " + entries.size());
        }

        renderRing(running, paused, onBreak);
        renderButtons(running, paused, onBreak);

        intervalBtn.setText("каждые " + prefs.getIntervalMin() + " мин");
        breakLenBtn.setText("перерыв " + prefs.getBreakMin() + " мин");
    }

    private void renderRing(boolean running, boolean paused, boolean onBreak) {
        long now = System.currentTimeMillis();

        if (onBreak) {
            long left = prefs.getBreakEnd() - now;
            ring.setColor(0xFF34D399);
            ring.setProgress(left / (float) Math.max(1, prefs.getBreakMin() * 60_000L));
            ringText.setText(Fmt.mmss(left));
            nextTitle.setText("Перерыв");
            nextText.setText("Вернусь к вопросам, когда таймер дойдёт до нуля");
            return;
        }

        ring.setColor(0xFFE9B949);

        if (!running || paused) {
            ring.setProgress(0f);
            ringText.setText("--:--");
            nextTitle.setText(paused ? "Пауза" : "Напоминания");
            nextText.setText(paused
                    ? "Будильник молчит, пока сессия на паузе"
                    : "Будильник будет спрашивать результат каждые "
                            + prefs.getIntervalMin() + " мин");
            return;
        }

        long left = prefs.getNextAlarm() - now;
        long span = prefs.getIntervalMin() * 60_000L;
        ring.setProgress(left / (float) Math.max(1, span));
        ringText.setText(Fmt.mmss(Math.max(0, left)));
        nextTitle.setText("Следующий вопрос");
        nextText.setText("В " + hhmm.format(new Date(prefs.getNextAlarm()))
                + " зазвонит будильник");
    }

    private void renderButtons(boolean running, boolean paused, boolean onBreak) {
        if (!running) {
            primaryBtn.setText("НАЧАТЬ СЕССИЮ");
            primaryBtn.setBackgroundResource(R.drawable.btn_green);
            primaryBtn.setTextColor(0xFF06231A);
            rowSecondary.setVisibility(View.GONE);
            stopBtn.setVisibility(View.GONE);
            return;
        }

        primaryBtn.setText("ЗАПИСАТЬ РЕЗУЛЬТАТ");
        primaryBtn.setBackgroundResource(R.drawable.btn_primary);
        primaryBtn.setTextColor(0xFF241900);
        rowSecondary.setVisibility(View.VISIBLE);
        stopBtn.setVisibility(View.VISIBLE);

        breakBtn.setText(onBreak ? "Открыть перерыв" : "Перерыв " + prefs.getBreakMin() + " мин");
        pauseBtn.setText(paused ? "Продолжить" : "Пауза");
        pauseBtn.setEnabled(!onBreak);
        pauseBtn.setAlpha(onBreak ? 0.4f : 1f);
    }

    private void renderEntries() {
        entriesBox.removeAllViews();
        List<Prefs.Entry> entries = prefs.getEntries();

        if (entries.isEmpty()) {
            entriesBox.addView(empty("Пока пусто. Первый вопрос прилетит по будильнику."));
            return;
        }

        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = entries.size() - 1; i >= 0; i--) {
            final int index = i;
            Prefs.Entry e = entries.get(i);
            View row = inf.inflate(R.layout.item_row, entriesBox, false);

            TextView mark = row.findViewById(R.id.rowMark);
            TextView title = row.findViewById(R.id.rowTitle);
            TextView value = row.findViewById(R.id.rowValue);

            mark.setText(e.bb > 0 ? "♥\uFE0E" : (e.bb < 0 ? "♠\uFE0E" : "♦\uFE0E"));
            mark.setTextColor(e.bb > 0 ? 0xFF34D399 : (e.bb < 0 ? 0xFFF87171 : 0xFF5B7169));
            title.setText("Чек-ин " + (i + 1) + " · " + hhmm.format(new Date(e.time)));
            value.setText(Fmt.bbUnit(e.bb));
            value.setTextColor(e.bb > 0 ? 0xFF34D399 : (e.bb < 0 ? 0xFFF87171 : 0xFF89A69B));

            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Удалить запись?")
                        .setMessage(Fmt.bbUnit(e.bb) + " в " + hhmm.format(new Date(e.time)))
                        .setPositiveButton("Удалить", (d, w) -> {
                            prefs.removeEntryAt(index);
                            render();
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
                return true;
            });

            entriesBox.addView(row);
            if (i > 0) entriesBox.addView(separator());
        }
    }

    private void renderHistory() {
        historyBox.removeAllViews();
        List<Prefs.Session> history = prefs.getHistory();

        if (history.isEmpty()) {
            historyBox.addView(empty("Завершённые сессии появятся здесь."));
            return;
        }

        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < history.size(); i++) {
            Prefs.Session s = history.get(i);
            View row = inf.inflate(R.layout.item_row, historyBox, false);

            TextView mark = row.findViewById(R.id.rowMark);
            TextView title = row.findViewById(R.id.rowTitle);
            TextView sub = row.findViewById(R.id.rowSub);
            TextView value = row.findViewById(R.id.rowValue);

            mark.setText(s.bb >= 0 ? "♦\uFE0E" : "♣\uFE0E");
            mark.setTextColor(s.bb >= 0 ? 0xFFE9B949 : 0xFF5B7169);
            title.setText(dayFmt.format(new Date(s.start)));
            sub.setVisibility(View.VISIBLE);
            sub.setText(Fmt.human(s.durationMs) + " · " + s.checkins + " чек-инов");
            value.setText(Fmt.bbUnit(s.bb));
            value.setTextColor(s.bb > 0 ? 0xFF34D399 : (s.bb < 0 ? 0xFFF87171 : 0xFF89A69B));

            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Очистить историю?")
                        .setMessage("Удалятся все прошлые сессии.")
                        .setPositiveButton("Очистить", (d, w) -> {
                            prefs.clearHistory();
                            render();
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
                return true;
            });

            historyBox.addView(row);
            if (i < history.size() - 1) historyBox.addView(separator());
        }
    }

    private View empty(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF5B7169);
        tv.setTextSize(13);
        int pad = Ui.dp(tv, 14);
        tv.setPadding(pad, pad, pad, pad);
        return tv;
    }

    private View separator() {
        View v = new View(this);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        v.setBackgroundColor(0x12FFFFFF);
        return v;
    }

    // ---- разрешения -------------------------------------------------------

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, RQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int rq, String[] p, int[] r) {
        super.onRequestPermissionsResult(rq, p, r);
        renderWarning();
    }

    /** Первая нерешённая проблема, из-за которой будильник может не сработать. */
    private String pendingIssue() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return "notif";
        }
        if (!AlarmScheduler.canScheduleExact(this)) return "exact";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && !getSystemService(android.app.NotificationManager.class)
                .canUseFullScreenIntent()) {
            return "fullscreen";
        }
        if (!isBatteryUnrestricted()) return "battery";
        return null;
    }

    private boolean isBatteryUnrestricted() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void renderWarning() {
        String issue = pendingIssue();
        if (issue == null) {
            warning.setVisibility(View.GONE);
            return;
        }
        String text;
        switch (issue) {
            case "notif":
                text = "Разреши уведомления — без них будильник не покажется. Нажми, чтобы включить.";
                break;
            case "exact":
                text = "Нужны точные будильники, иначе напоминание может опоздать. Нажми, чтобы разрешить.";
                break;
            case "fullscreen":
                text = "Разреши полноэкранные уведомления, чтобы вопрос будил экран. Нажми, чтобы включить.";
                break;
            default:
                text = "Отключи экономию батареи для приложения, иначе система усыпит будильник. Нажми, чтобы настроить.";
        }
        warning.setText(text);
        warning.setVisibility(View.VISIBLE);
    }

    private void fixNextIssue() {
        String issue = pendingIssue();
        if (issue == null) return;
        try {
            switch (issue) {
                case "notif":
                    askNotificationPermission();
                    return;
                case "exact":
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                    return;
                case "fullscreen":
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:" + getPackageName())));
                    return;
                default:
                    startActivity(new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    // ---- lifecycle --------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        render();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }
}
