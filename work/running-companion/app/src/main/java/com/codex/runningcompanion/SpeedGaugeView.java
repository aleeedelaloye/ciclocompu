package com.codex.runningcompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public class SpeedGaugeView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float speedKmh = 0f;
    private float maxKmh = 30f;

    public SpeedGaugeView(Context context) {
        super(context);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(Color.rgb(23, 36, 42));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(Color.rgb(37, 213, 220));

        tickPaint.setColor(Color.rgb(170, 185, 190));
        tickPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setSpeed(float valueKmh) {
        speedKmh = Math.max(0f, valueKmh);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float stroke = dp(12);
        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);
        tickPaint.setTextSize(dp(11));

        float pad = stroke + dp(8);
        RectF oval = new RectF(pad, pad, getWidth() - pad, getHeight() * 2.05f - pad);
        canvas.drawArc(oval, 198f, 144f, false, trackPaint);
        float sweep = Math.min(144f, (speedKmh / maxKmh) * 144f);
        canvas.drawArc(oval, 198f, sweep, false, arcPaint);

        canvas.drawText("0", pad + dp(4), getHeight() - dp(8), tickPaint);
        canvas.drawText("30", getWidth() - pad - dp(4), getHeight() - dp(8), tickPaint);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
