package com.trueshine.pokertracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Экран будильника: сколько BB выиграно или проиграно за интервал. */
public class CheckInActivity extends Activity {

    public static final String EXTRA_FROM_BREAK = "from_break";

    private static final double[] QUICK = {-20, -10, -5, -2, 0, 2, 5, 10, 20, 40};

    private Prefs prefs;
    private double value = 0;
    private TextView amount;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showOverLockscreen();
        setContentView(R.layout.activity_checkin);
        Ui.applyInsets(findViewById(R.id.root));

        prefs = new Prefs(this);
        AlarmService.stop(this);

        boolean fromBreak = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_FROM_BREAK, false);

        amount = findViewById(R.id.amount);

        if (fromBreak) {
            showBreakOver();
            return;
        }

        TextView subtitle = findViewById(R.id.subtitle);
        long since = System.currentTimeMillis() - prefs.getLastAsk();
        int mins = (int) Math.max(1, Math.round(since / 60000.0));
        subtitle.setText("За последние " + mins + " мин, в больших блайндах");

        buildChips();
        render();

        findViewById(R.id.minus).setOnClickListener(v -> bump(-1));
        findViewById(R.id.plus).setOnClickListener(v -> bump(1));
        findViewById(R.id.customBtn).setOnClickListener(v -> askCustom());
        findViewById(R.id.saveBtn).setOnClickListener(v -> save(false));
        findViewById(R.id.saveBreakBtn).setOnClickListener(v -> save(true));
        findViewById(R.id.skipBtn).setOnClickListener(v -> skip());
    }

    /**
     * Экран после перерыва: результата за это время быть не может,
     * поэтому спрашивать нечего — только вернуть человека за стол.
     */
    private void showBreakOver() {
        ((TextView) findViewById(R.id.title)).setText("Перерыв окончен");
        ((TextView) findViewById(R.id.subtitle)).setText(
                Fmt.clock(prefs.elapsed()) + " за столом · " + Fmt.bbUnit(prefs.totalBb())
                        + "\nСледующий вопрос через " + prefs.getIntervalMin() + " мин");

        findViewById(R.id.inputCard).setVisibility(View.GONE);
        findViewById(R.id.chipsRow1).setVisibility(View.GONE);
        findViewById(R.id.chipsRow2).setVisibility(View.GONE);
        findViewById(R.id.customBtn).setVisibility(View.GONE);
        findViewById(R.id.saveBreakBtn).setVisibility(View.GONE);
        findViewById(R.id.skipBtn).setVisibility(View.GONE);

        TextView save = findViewById(R.id.saveBtn);
        save.setText("ВЕРНУТЬСЯ ЗА СТОЛ");
        save.setOnClickListener(v -> finish());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AlarmService.stop(this);
    }

    private void showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void bump(int dir) {
        double step = Math.abs(value) >= 20 ? 5 : (Math.abs(value) >= 5 ? 1 : 0.5);
        value = round(value + dir * step);
        render();
    }

    private static double round(double v) { return Math.round(v * 10.0) / 10.0; }

    private void buildChips() {
        LinearLayout row1 = findViewById(R.id.chipsRow1);
        LinearLayout row2 = findViewById(R.id.chipsRow2);
        for (int i = 0; i < QUICK.length; i++) {
            final double v = QUICK[i];
            TextView chip = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    Ui.dp(chip, 46), 1f);
            if (i % 5 != 0) lp.leftMargin = Ui.dp(chip, 8);
            chip.setLayoutParams(lp);
            chip.setBackgroundResource(R.drawable.chip);
            chip.setGravity(Gravity.CENTER);
            chip.setText(Fmt.bb(v));
            chip.setTextSize(14);
            chip.setTextColor(v > 0 ? 0xFF34D399 : (v < 0 ? 0xFFF87171 : 0xFF89A69B));
            chip.setOnClickListener(x -> {
                value = v;
                render();
            });
            (i < 5 ? row1 : row2).addView(chip);
        }
    }

    private void render() {
        amount.setText(Fmt.bb(value));
        amount.setTextColor(value > 0 ? 0xFF34D399 : (value < 0 ? 0xFFF87171 : 0xFFECF6F1));
    }

    private void askCustom() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setHint("например -12,5");
        int pad = Ui.dp(input, 20);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Результат в BB")
                .setView(input)
                .setPositiveButton("Ок", (d, w) -> {
                    try {
                        value = round(Double.parseDouble(
                                input.getText().toString().trim().replace(',', '.')));
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Не похоже на число", Toast.LENGTH_SHORT).show();
                    }
                    render();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void save(boolean thenBreak) {
        if (!prefs.isRunning()) {
            Toast.makeText(this, "Сессия не идёт", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        prefs.addEntry(value);
        prefs.setLastAsk(System.currentTimeMillis());

        if (thenBreak) {
            startBreak(this, prefs);
            startActivity(new Intent(this, BreakActivity.class));
        } else {
            AlarmScheduler.scheduleNextCheckin(this);
            SessionService.start(this);
            Toast.makeText(this, "Записано: " + Fmt.bbUnit(value)
                    + " · всего " + Fmt.bbUnit(prefs.totalBb()), Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void skip() {
        if (prefs.isRunning()) {
            prefs.setLastAsk(System.currentTimeMillis());
            AlarmScheduler.scheduleNextCheckin(this);
            SessionService.start(this);
        }
        finish();
    }

    /** Ставит сессию на паузу на время перерыва и заводит будильник на его конец. */
    public static void startBreak(Context ctx, Prefs prefs) {
        long end = System.currentTimeMillis() + prefs.getBreakMin() * 60_000L;
        prefs.setBreakEnd(end);
        prefs.pause();
        AlarmScheduler.cancelCheckin(ctx);
        AlarmScheduler.scheduleBreakEnd(ctx, end);
        SessionService.start(ctx);
    }

    @Override
    protected void onDestroy() {
        AlarmService.stop(this);
        super.onDestroy();
    }
}
