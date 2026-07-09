package com.codex.runningcompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

public class RouteMapView extends View {
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double[] latitudes = new double[0];
    private double[] longitudes = new double[0];

    public RouteMapView(Context context) {
        super(context);
        backgroundPaint.setColor(Color.rgb(17, 19, 24));
        gridPaint.setColor(Color.argb(60, 255, 255, 255));
        gridPaint.setStrokeWidth(1f);
        routePaint.setColor(Color.rgb(18, 184, 134));
        routePaint.setStrokeWidth(8f);
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);
        dotPaint.setColor(Color.WHITE);
        textPaint.setColor(Color.rgb(210, 218, 226));
        textPaint.setTextSize(34f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setRoute(double[] lats, double[] lons) {
        latitudes = lats != null ? lats : new double[0];
        longitudes = lons != null ? lons : new double[0];
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawRoute(canvas, new RectF(0, 0, getWidth(), getHeight()), true);
    }

    public void drawRoute(Canvas canvas, RectF bounds, boolean showEmptyText) {
        canvas.drawRoundRect(bounds, 18f, 18f, backgroundPaint);
        for (int i = 1; i < 4; i++) {
            float x = bounds.left + bounds.width() * i / 4f;
            float y = bounds.top + bounds.height() * i / 4f;
            canvas.drawLine(x, bounds.top + 18f, x, bounds.bottom - 18f, gridPaint);
            canvas.drawLine(bounds.left + 18f, y, bounds.right - 18f, y, gridPaint);
        }

        if (latitudes.length < 2 || longitudes.length < 2) {
            if (showEmptyText) {
                canvas.drawText("Mapa del recorrido", bounds.centerX(), bounds.centerY() - 10f, textPaint);
                canvas.drawText("esperando GPS", bounds.centerX(), bounds.centerY() + 34f, textPaint);
            }
            return;
        }

        double minLat = latitudes[0], maxLat = latitudes[0], minLon = longitudes[0], maxLon = longitudes[0];
        int count = Math.min(latitudes.length, longitudes.length);
        for (int i = 1; i < count; i++) {
            minLat = Math.min(minLat, latitudes[i]);
            maxLat = Math.max(maxLat, latitudes[i]);
            minLon = Math.min(minLon, longitudes[i]);
            maxLon = Math.max(maxLon, longitudes[i]);
        }
        double latSpan = Math.max(0.00001, maxLat - minLat);
        double lonSpan = Math.max(0.00001, maxLon - minLon);
        float pad = 34f;
        RectF area = new RectF(bounds.left + pad, bounds.top + pad, bounds.right - pad, bounds.bottom - pad);
        Path path = new Path();
        for (int i = 0; i < count; i++) {
            float x = (float) (area.left + ((longitudes[i] - minLon) / lonSpan) * area.width());
            float y = (float) (area.bottom - ((latitudes[i] - minLat) / latSpan) * area.height());
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, routePaint);

        float startX = (float) (area.left + ((longitudes[0] - minLon) / lonSpan) * area.width());
        float startY = (float) (area.bottom - ((latitudes[0] - minLat) / latSpan) * area.height());
        float endX = (float) (area.left + ((longitudes[count - 1] - minLon) / lonSpan) * area.width());
        float endY = (float) (area.bottom - ((latitudes[count - 1] - minLat) / latSpan) * area.height());
        canvas.drawCircle(startX, startY, 9f, dotPaint);
        canvas.drawCircle(endX, endY, 15f, dotPaint);
    }
}
