package com.trueshine.pokertracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Столбики результатов чек-инов вокруг нулевой линии. */
public class BarsView extends View {

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zero = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final List<Double> values = new ArrayList<>();

    private static final int GREEN = 0xFF34D399;
    private static final int RED = 0xFFF87171;
    private static final int FLAT = 0x33FFFFFF;

    public BarsView(Context c) { this(c, null); }

    public BarsView(Context c, AttributeSet a) {
        super(c, a);
        bar.setStyle(Paint.Style.FILL);
        zero.setColor(0x1FFFFFFF);
        zero.setStrokeWidth(Math.max(1f, dp(1)));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setValues(List<Double> v) {
        values.clear();
        // Показываем последние 24 записи, чтобы столбики не превращались в волоски.
        int from = Math.max(0, v.size() - 24);
        values.addAll(v.subList(from, v.size()));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float mid = h / 2f;
        canvas.drawLine(0, mid, w, mid, zero);
        if (values.isEmpty()) return;

        double max = 1;
        for (double v : values) max = Math.max(max, Math.abs(v));

        int n = values.size();
        // Сетка не сжимается под число записей: две штуки не должны разъезжаться на весь экран.
        int slots = Math.max(n, 12);
        float slot = (float) w / slots;
        float bw = Math.min(dp(11), Math.max(dp(3), slot * 0.62f));
        // Скругление фиксированное: иначе короткий столбик превращается в кружок.
        float radius = Math.min(dp(3), bw / 2f);
        float usable = mid - dp(6);

        for (int i = 0; i < n; i++) {
            double v = values.get(i);
            float cx = slot * i + slot / 2f;
            float len = (float) (Math.abs(v) / max) * usable;
            if (v == 0) {
                bar.setColor(FLAT);
                len = dp(3);
                r.set(cx - bw / 2f, mid - len / 2f, cx + bw / 2f, mid + len / 2f);
            } else {
                bar.setColor(v > 0 ? GREEN : RED);
                len = Math.max(len, dp(6));
                if (v > 0) r.set(cx - bw / 2f, mid - len, cx + bw / 2f, mid);
                else r.set(cx - bw / 2f, mid, cx + bw / 2f, mid + len);
            }
            canvas.drawRoundRect(r, radius, radius, bar);
        }
    }
}
