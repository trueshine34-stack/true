package com.trueshine.pokertracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Кольцевой индикатор обратного отсчёта. */
public class RingView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private float progress = 0f;   // 0..1 — сколько осталось
    private int color = 0xFFE9B949;

    public RingView(Context c) { this(c, null); }

    public RingView(Context c, AttributeSet a) {
        super(c, a);
        float w = dp(9);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(w);
        track.setColor(0x1AFFFFFF);

        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(w);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(color);

        cap.setStyle(Paint.Style.FILL);
        cap.setColor(color);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setProgress(float p) {
        float clamped = Math.max(0f, Math.min(1f, p));
        if (Math.abs(clamped - progress) < 0.0005f) return;
        progress = clamped;
        invalidate();
    }

    public void setColor(int c) {
        if (color == c) return;
        color = c;
        arc.setColor(c);
        cap.setColor(c);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = arc.getStrokeWidth() / 2f + dp(2);
        float size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2f + pad;
        float top = (getHeight() - size) / 2f + pad;
        box.set(left, top, left + size - pad * 2, top + size - pad * 2);

        canvas.drawArc(box, 0, 360, false, track);
        if (progress > 0f) {
            canvas.drawArc(box, -90, 360f * progress, false, arc);
        }
    }
}
