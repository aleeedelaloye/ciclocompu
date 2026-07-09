package com.codex.runningcompanion;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.ScaleBarOverlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.compass.CompassOverlay;
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 70;
    private static final int COLOR_BG = Color.rgb(5, 14, 18);
    private static final int COLOR_PANEL = Color.rgb(16, 29, 35);
    private static final int COLOR_PANEL_SOFT = Color.rgb(22, 39, 46);
    private static final int COLOR_DARK = Color.rgb(246, 251, 252);
    private static final int COLOR_TEXT = Color.rgb(246, 251, 252);
    private static final int COLOR_MUTED = Color.rgb(150, 166, 171);
    private static final int COLOR_ACCENT = Color.rgb(37, 213, 220);
    private static final int COLOR_TEAL = Color.rgb(28, 180, 205);
    private static final int COLOR_RED = Color.rgb(255, 63, 73);
    private static final int COLOR_YOUTUBE = Color.rgb(255, 0, 0);
    private static final int COLOR_YOUTUBE_DARK = Color.rgb(150, 0, 0);
    private static final int COLOR_INK = Color.rgb(3, 14, 18);
    private static final int COLOR_BORDER = Color.rgb(42, 63, 70);
    private static final long MAP_CONTROLS_HIDE_MS = 3200L;
    private static final long MIN_HISTORY_SECONDS = 300L;
    private static final String PREFS_NAME = "cycling_history";
    private static final String PREF_HISTORY = "items";

    private TextView speedValue;
    private SpeedGaugeView speedGaugeView;
    private TextView averageValue;
    private TextView distanceValue;
    private TextView timeValue;
    private TextView accuracyValue;
    private TextView statusValue;
    private View rootView;
    private LinearLayout historyList;
    private Button primaryButton;
    private MapView osmMapView;
    private Polyline routeLine;
    private MyLocationNewOverlay myLocationOverlay;
    private Overlay locateOverlay;
    private Button followButton;
    private LinearLayout mapControlsContainer;
    private AudioManager audioManager;
    private final Handler mapControlsHandler = new Handler(Looper.getMainLooper());
    private final Handler esp32AutoHandler = new Handler(Looper.getMainLooper());
    private boolean running;
    private boolean esp32Connected;
    private boolean hasReceivedStats;
    private boolean followRunner = true;
    private boolean mapControlsVisible = true;
    private long latestSeconds;
    private float latestDistanceMeters;
    private float latestSpeedMps;
    private float latestAccuracyMeters;
    private double[] latestLatitudes = new double[0];
    private double[] latestLongitudes = new double[0];
    private final Runnable hideMapControlsRunnable = () -> setMapControlsVisible(false);
    private final Runnable autoConnectEsp32Runnable = this::autoConnectEsp32;

    private final BroadcastReceiver statsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!RunStats.ACTION_STATS.equals(intent.getAction())) return;
            boolean wasRunning = running;
            boolean nowRunning = intent.getBooleanExtra(RunStats.EXTRA_RUNNING, false);
            long seconds = intent.getLongExtra(RunStats.EXTRA_SECONDS, 0L);
            float distance = intent.getFloatExtra(RunStats.EXTRA_DISTANCE, 0f);
            float speed = intent.getFloatExtra(RunStats.EXTRA_SPEED, 0f);
            float accuracy = intent.getFloatExtra(RunStats.EXTRA_ACCURACY, 0f);
            if (hasReceivedStats && wasRunning && !nowRunning && seconds >= MIN_HISTORY_SECONDS) {
                saveRunHistory(seconds, distance);
            }
            running = nowRunning;
            hasReceivedStats = true;
            latestLatitudes = intent.getDoubleArrayExtra(RunStats.EXTRA_LATITUDES);
            latestLongitudes = intent.getDoubleArrayExtra(RunStats.EXTRA_LONGITUDES);
            updateOsmRoute();
            updateStats(seconds, distance, speed, accuracy);
        }
    };
    private final BroadcastReceiver esp32StatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!RunStats.ACTION_ESP32_STATUS.equals(intent.getAction())) return;
            runOnUiThreadEsp32Status(intent.getStringExtra(RunStats.EXTRA_ESP32_STATUS));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        configureSystemBars();
        Configuration.getInstance().setUserAgentValue(getPackageName());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setContentView(buildLayout());
        updateStats(0, 0f, 0f, 0f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (osmMapView != null) osmMapView.onResume();
        if (myLocationOverlay != null) myLocationOverlay.enableMyLocation();
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statsReceiver, new IntentFilter(RunStats.ACTION_STATS), RECEIVER_NOT_EXPORTED);
            registerReceiver(esp32StatusReceiver, new IntentFilter(RunStats.ACTION_ESP32_STATUS), RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statsReceiver, new IntentFilter(RunStats.ACTION_STATS));
            registerReceiver(esp32StatusReceiver, new IntentFilter(RunStats.ACTION_ESP32_STATUS));
        }
        scheduleAutoConnectEsp32(900L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (osmMapView != null) osmMapView.onPause();
        if (myLocationOverlay != null) myLocationOverlay.disableMyLocation();
        esp32AutoHandler.removeCallbacks(autoConnectEsp32Runnable);
        unregisterReceiver(statsReceiver);
        unregisterReceiver(esp32StatusReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private View buildLayout() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, 0, 0, dp(28));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(56));
        root.setBackgroundColor(COLOR_BG);
        scrollView.addView(root, new ScrollView.LayoutParams(-1, -2));

        statusValue = text("Listo para pedalear", 15, COLOR_MUTED, Typeface.BOLD);
        statusValue.setPadding(dp(2), 0, 0, dp(12));
        root.addView(statusValue);
        rootView = root;

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(16), dp(8), dp(16), dp(12));
        hero.setBackground(panelBackground(28));
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, dp(188));
        heroParams.setMargins(0, 0, 0, dp(12));
        root.addView(hero, heroParams);

        speedGaugeView = new SpeedGaugeView(this);
        hero.addView(speedGaugeView, new LinearLayout.LayoutParams(-1, dp(70)));

        speedValue = text("0.0", 68, COLOR_DARK, Typeface.BOLD);
        speedValue.setGravity(Gravity.CENTER);
        hero.addView(speedValue, new LinearLayout.LayoutParams(-1, dp(74)));

        TextView speedUnit = text("km/h", 18, COLOR_TEXT, Typeface.BOLD);
        speedUnit.setGravity(Gravity.CENTER);
        hero.addView(speedUnit);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, 0, 0, dp(8));
        root.addView(grid, new LinearLayout.LayoutParams(-1, dp(168)));

        LinearLayout row1 = row();
        averageValue = addMetric(row1, "Promedio", "0.0 km/h");
        distanceValue = addMetric(row1, "Distancia", "0.00 km");
        grid.addView(row1, new LinearLayout.LayoutParams(-1, dp(78)));

        LinearLayout row2 = row();
        timeValue = addMetric(row2, "Tiempo", "00:00:00");
        accuracyValue = addMetric(row2, "GPS", "esperando");
        grid.addView(row2, new LinearLayout.LayoutParams(-1, dp(78)));

        primaryButton = new Button(this);
        primaryButton.setText("INICIAR");
        stylePrimaryButton(primaryButton, false);
        primaryButton.setOnClickListener(v -> toggleRun());
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(-1, dp(58));
        primaryParams.setMargins(0, 0, 0, dp(12));
        root.addView(primaryButton, primaryParams);

        osmMapView = new MapView(this);
        osmMapView.setTileSource(TileSourceFactory.MAPNIK);
        osmMapView.setMultiTouchControls(true);
        osmMapView.setTilesScaledToDpi(true);
        osmMapView.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT);
        osmMapView.setMinZoomLevel(3.0);
        osmMapView.setMaxZoomLevel(20.0);
        osmMapView.getController().setZoom(16.0);
        routeLine = new Polyline();
        routeLine.getOutlinePaint().setColor(COLOR_TEAL);
        routeLine.getOutlinePaint().setStrokeWidth(9f);
        osmMapView.getOverlays().add(routeLine);

        myLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), osmMapView);
        myLocationOverlay.enableMyLocation();
        myLocationOverlay.enableFollowLocation();
        myLocationOverlay.runOnFirstFix(() -> runOnUiThread(this::centerOnCurrentLocation));
        osmMapView.getOverlays().add(myLocationOverlay);
        locateOverlay = new Overlay() {
            @Override
            public boolean onSingleTapConfirmed(android.view.MotionEvent event, MapView mapView) {
                boolean wasVisible = mapControlsVisible;
                showMapControlsTemporarily();
                int width = mapView.getWidth();
                if (wasVisible && event.getX() >= width - dp(62) && event.getY() <= dp(68)) {
                    centerOnCurrentLocation();
                    return true;
                }
                return false;
            }

            @Override
            public void draw(Canvas canvas, MapView mapView, boolean shadow) {
                if (shadow || !mapControlsVisible) return;
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                float size = dp(48);
                float left = mapView.getWidth() - size - dp(12);
                float top = dp(12);
                RectF rect = new RectF(left, top, left + size, top + size);
                paint.setColor(Color.argb(88, 0, 0, 0));
                canvas.drawRoundRect(new RectF(rect.left + dp(2), rect.top + dp(3), rect.right + dp(2), rect.bottom + dp(3)), dp(14), dp(14), paint);
                paint.setColor(Color.argb(245, 255, 255, 255));
                canvas.drawRoundRect(rect, dp(14), dp(14), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.4f));
                paint.setColor(COLOR_TEAL);
                canvas.drawCircle(rect.centerX(), rect.centerY(), dp(11), paint);
                canvas.drawLine(rect.centerX(), rect.top + dp(7), rect.centerX(), rect.top + dp(16), paint);
                canvas.drawLine(rect.centerX(), rect.bottom - dp(7), rect.centerX(), rect.bottom - dp(16), paint);
                canvas.drawLine(rect.left + dp(7), rect.centerY(), rect.left + dp(16), rect.centerY(), paint);
                canvas.drawLine(rect.right - dp(7), rect.centerY(), rect.right - dp(16), rect.centerY(), paint);
                paint.setStyle(Paint.Style.FILL);
            }
        };
        osmMapView.getOverlays().add(locateOverlay);

        CompassOverlay compass = new CompassOverlay(this, new InternalCompassOrientationProvider(this), osmMapView);
        compass.enableCompass();
        osmMapView.getOverlays().add(compass);

        ScaleBarOverlay scaleBar = new ScaleBarOverlay(osmMapView);
        scaleBar.setAlignBottom(true);
        scaleBar.setAlignRight(true);
        scaleBar.setScaleBarOffset(dp(18), dp(18));
        osmMapView.getOverlays().add(scaleBar);

        RotationGestureOverlay rotation = new RotationGestureOverlay(osmMapView);
        rotation.setEnabled(true);
        osmMapView.getOverlays().add(rotation);

        osmMapView.setBackground(rounded(COLOR_PANEL, 22));
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(-1, dp(224));
        mapParams.setMargins(0, 0, 0, dp(8));
        root.addView(osmMapView, mapParams);
        showMapControlsTemporarily();

        mapControlsContainer = row();
        followButton = compactButton("Seguir: si");
        followButton.setOnClickListener(v -> toggleFollowRunner());
        mapControlsContainer.addView(followButton, new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout.LayoutParams followParams = new LinearLayout.LayoutParams(-1, dp(44));
        followParams.setMargins(0, 0, 0, dp(8));
        root.addView(mapControlsContainer, followParams);

        Button shareButton = new Button(this);
        shareButton.setText("Compartir instantanea");
        shareButton.setAllCaps(false);
        styleSecondaryButton(shareButton);
        shareButton.setOnClickListener(v -> shareSnapshot());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(-1, dp(48));
        shareParams.setMargins(0, 0, 0, dp(12));
        root.addView(shareButton, shareParams);

        root.addView(sectionTitle("Historial"));
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        historyList.setPadding(dp(12), dp(8), dp(12), dp(8));
        historyList.setBackground(panelBackground(18));
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(-1, -2);
        historyParams.setMargins(0, 0, 0, dp(12));
        root.addView(historyList, historyParams);
        renderRunHistory();

        TextView musicTitle = text("Musica", 14, COLOR_MUTED, Typeface.BOLD);
        musicTitle.setPadding(0, 0, 0, dp(6));
        root.addView(musicTitle);

        LinearLayout musicRow = row();
        Button openMusic = youtubeButton("\u25B6  YouTube Music");
        openMusic.setOnClickListener(v -> openYouTubeMusic());
        musicRow.addView(openMusic, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout.LayoutParams musicOpenParams = new LinearLayout.LayoutParams(-1, dp(48));
        musicOpenParams.setMargins(0, 0, 0, dp(8));
        root.addView(musicRow, musicOpenParams);

        LinearLayout musicControlsRow = row();
        Button previous = mediaButton("\u23EE");
        previous.setOnClickListener(v -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        musicControlsRow.addView(previous, buttonWeightParams());

        Button playPause = youtubeButton("\u25B6 / \u23F8");
        playPause.setOnClickListener(v -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        musicControlsRow.addView(playPause, buttonWeightParams());

        Button next = mediaButton("\u23ED");
        next.setOnClickListener(v -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        musicControlsRow.addView(next, buttonWeightParams());
        root.addView(musicControlsRow, new LinearLayout.LayoutParams(-1, dp(46)));

        Button settingsButton = new Button(this);
        settingsButton.setText("Permisos / GPS");
        settingsButton.setAllCaps(false);
        styleSecondaryButton(settingsButton);
        settingsButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(-1, dp(48));
        settingsParams.setMargins(0, dp(12), 0, 0);
        root.addView(settingsButton, settingsParams);

        return scrollView;
    }

    private void configureSystemBars() {
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
    }

    private Button compactButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(COLOR_TEXT);
        button.setBackground(buttonBackground());
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private Button youtubeButton(String label) {
        Button button = compactButton(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setBackground(youtubeBackground());
        return button;
    }

    private Button mediaButton(String label) {
        Button button = compactButton(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(22);
        button.setBackground(mediaBackground());
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private TextView addMetric(LinearLayout row, String label, String initial) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setGravity(Gravity.CENTER);
        box.setBackground(panelBackground(18));

        TextView labelView = text(label, 12, COLOR_ACCENT, Typeface.BOLD);
        labelView.setGravity(Gravity.CENTER);
        TextView valueView = text(initial, 23, COLOR_TEXT, Typeface.BOLD);
        valueView.setGravity(Gravity.CENTER);

        box.addView(labelView);
        box.addView(valueView);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(box, params);
        return valueView;
    }

    private TextView text(String value, int sizeSp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private void styleSecondaryButton(Button button) {
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(buttonBackground());
    }

    private void stylePrimaryButton(Button button, boolean active) {
        button.setTextSize(18);
        button.setTextColor(active ? COLOR_TEXT : COLOR_INK);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(active ? rounded(COLOR_RED, 18) : accentBackground(18));
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable stroked(int color, int strokeColor, int radiusDp, int strokeDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private GradientDrawable panelBackground(int radiusDp) {
        return stroked(COLOR_PANEL, COLOR_BORDER, radiusDp, 1);
    }

    private GradientDrawable buttonBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{COLOR_PANEL_SOFT, Color.rgb(18, 34, 41)}
        );
        drawable.setCornerRadius(dp(16));
        drawable.setStroke(dp(1), COLOR_BORDER);
        return drawable;
    }

    private GradientDrawable accentBackground(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{COLOR_ACCENT, Color.rgb(35, 188, 204)}
        );
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable youtubeBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{COLOR_YOUTUBE, COLOR_YOUTUBE_DARK}
        );
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    private GradientDrawable mediaBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(37, 24, 28), Color.rgb(19, 18, 22)}
        );
        drawable.setCornerRadius(dp(16));
        drawable.setStroke(dp(1), Color.rgb(105, 32, 38));
        return drawable;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 15, COLOR_TEXT, Typeface.BOLD);
        view.setPadding(dp(2), dp(8), 0, dp(7));
        return view;
    }

    private LinearLayout.LayoutParams metricParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        return params;
    }

    private LinearLayout.LayoutParams buttonWeightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private void saveRunHistory(long seconds, float distanceMeters) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String date = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date());
        String entry = date + "|" + seconds + "|" + distanceMeters;
        String current = prefs.getString(PREF_HISTORY, "");
        String[] oldItems = current.isEmpty() ? new String[0] : current.split("\\n");
        StringBuilder builder = new StringBuilder(entry);
        int kept = 1;
        for (String item : oldItems) {
            if (item.trim().isEmpty()) continue;
            if (kept >= 8) break;
            builder.append('\n').append(item);
            kept++;
        }
        prefs.edit().putString(PREF_HISTORY, builder.toString()).apply();
        renderRunHistory();
    }

    private void renderRunHistory() {
        if (historyList == null) return;
        historyList.removeAllViews();
        String data = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_HISTORY, "");
        if (data == null || data.trim().isEmpty()) {
            TextView empty = text("Se guardan automaticamente las salidas de mas de 5 minutos.", 13, COLOR_MUTED, Typeface.NORMAL);
            empty.setPadding(0, dp(4), 0, dp(4));
            historyList.addView(empty);
            return;
        }
        String[] items = data.split("\\n");
        for (String item : items) {
            String[] parts = item.split("\\|");
            if (parts.length < 3) continue;
            long seconds = Long.parseLong(parts[1]);
            float meters = Float.parseFloat(parts[2]);
            TextView row = text(
                    parts[0] + "  |  " + String.format(Locale.US, "%.2f km", meters / 1000f)
                            + "\n" + formatTime(seconds) + "  |  " + formatAverageSpeed(seconds, meters),
                    13,
                    COLOR_TEXT,
                    Typeface.BOLD
            );
            row.setLineSpacing(dp(2), 1.05f);
            row.setPadding(0, dp(6), 0, dp(6));
            historyList.addView(row);
        }
    }

    private void toggleRun() {
        if (running) {
            stopRunFromUi();
            return;
        }
        startRunFromUi();
    }

    private void stopRunFromUi() {
        Intent intent = new Intent(this, RunTrackerService.class);
        intent.setAction(RunStats.ACTION_STOP);
        startService(intent);
    }

    private void startRunFromUi() {
        if (!hasLocationPermission()) {
            requestNeededPermissions();
            return;
        }

        Intent intent = new Intent(this, RunTrackerService.class);
        intent.setAction(RunStats.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void handleEsp32Command(String command) {
        if ("START".equalsIgnoreCase(command)) {
            if (!running) {
                startRunFromUi();
                Toast.makeText(this, "Salida iniciada desde ESP32", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "La salida ya esta en curso", Toast.LENGTH_SHORT).show();
            }
        } else if ("STOP".equalsIgnoreCase(command)) {
            if (running) {
                stopRunFromUi();
                Toast.makeText(this, "Salida detenida desde ESP32", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "La salida ya estaba detenida", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openYouTubeMusic() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.youtube.music");
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
            return;
        }

        Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.youtube.music"));
        try {
            startActivity(marketIntent);
        } catch (Exception ignored) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.youtube.music")));
        }
    }

    private void sendMediaKey(int keyCode) {
        if (audioManager == null) return;
        long now = System.currentTimeMillis();
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void updateOsmRoute() {
        if (osmMapView == null || routeLine == null || latestLatitudes == null || latestLongitudes == null) return;
        int count = Math.min(latestLatitudes.length, latestLongitudes.length);
        if (count == 0) return;
        ArrayList<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new GeoPoint(latestLatitudes[i], latestLongitudes[i]));
        }
        routeLine.setPoints(points);
        GeoPoint last = points.get(points.size() - 1);
        if (followRunner) {
            osmMapView.getController().animateTo(last);
            if (count < 4) osmMapView.getController().setZoom(17.0);
        }
        osmMapView.invalidate();
    }

    private void centerOnCurrentLocation() {
        showMapControlsTemporarily();
        followRunner = true;
        updateFollowButton();
        if (myLocationOverlay != null) myLocationOverlay.enableFollowLocation();
        if (latestLatitudes != null && latestLongitudes != null) {
            int count = Math.min(latestLatitudes.length, latestLongitudes.length);
            if (count > 0) {
                GeoPoint last = new GeoPoint(latestLatitudes[count - 1], latestLongitudes[count - 1]);
                osmMapView.getController().setZoom(Math.max(osmMapView.getZoomLevelDouble(), 17.0));
                osmMapView.getController().animateTo(last);
                return;
            }
        }
        if (myLocationOverlay != null && myLocationOverlay.getMyLocation() != null) {
            osmMapView.getController().setZoom(Math.max(osmMapView.getZoomLevelDouble(), 17.0));
            osmMapView.getController().animateTo(myLocationOverlay.getMyLocation());
        } else {
            Toast.makeText(this, "Esperando ubicacion GPS", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFollowRunner() {
        showMapControlsTemporarily();
        followRunner = !followRunner;
        updateFollowButton();
        if (myLocationOverlay != null) {
            if (followRunner) myLocationOverlay.enableFollowLocation();
            else myLocationOverlay.disableFollowLocation();
        }
        if (followRunner) centerOnCurrentLocation();
    }

    private void updateFollowButton() {
        if (followButton != null) followButton.setText(followRunner ? "Seguir: si" : "Seguir: no");
    }

    private void connectEsp32() {
        if (!hasBluetoothPermission()) {
            requestNeededPermissions();
            return;
        }
        Intent intent = new Intent(this, RunTrackerService.class);
        intent.setAction(RunStats.ACTION_CONNECT_ESP32);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void scheduleAutoConnectEsp32(long delayMs) {
        esp32AutoHandler.removeCallbacks(autoConnectEsp32Runnable);
        esp32AutoHandler.postDelayed(autoConnectEsp32Runnable, delayMs);
    }

    private void autoConnectEsp32() {
        if (!hasBluetoothPermission()) return;
        connectEsp32();
    }

    private void runOnUiThreadEsp32Status(String status) {
        runOnUiThread(() -> {
            esp32Connected = "ESP32 conectado".equals(status) || "ESP32 listo".equals(status);
            updateScreenAwakePolicy();
            if ("ESP32 desconectado".equals(status) || "ESP32 no encontrado".equals(status)) {
                scheduleAutoConnectEsp32(3500L);
            }
        });
    }

    private void updateScreenAwakePolicy() {
        boolean keepAwake = running || esp32Connected;
        if (keepAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (rootView != null) rootView.setKeepScreenOn(keepAwake);
        if (osmMapView != null) osmMapView.setKeepScreenOn(keepAwake);
    }

    private void showMapControlsTemporarily() {
        setMapControlsVisible(true);
        mapControlsHandler.removeCallbacks(hideMapControlsRunnable);
        mapControlsHandler.postDelayed(hideMapControlsRunnable, MAP_CONTROLS_HIDE_MS);
    }

    private void setMapControlsVisible(boolean visible) {
        mapControlsVisible = visible;
        if (followButton != null) followButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (mapControlsContainer != null) mapControlsContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (osmMapView != null) {
            osmMapView.getZoomController().setVisibility(
                    visible
                            ? CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
                            : CustomZoomButtonsController.Visibility.NEVER
            );
            osmMapView.invalidate();
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQUEST_PERMISSIONS);
        } else if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQUEST_PERMISSIONS);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && hasLocationPermission()) {
            Toast.makeText(this, "Permiso GPS concedido", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Necesita permiso de ubicacion para medir la salida", Toast.LENGTH_LONG).show();
        }
    }

    private void updateStats(long seconds, float distanceMeters, float speedMps, float accuracyMeters) {
        latestSeconds = seconds;
        latestDistanceMeters = distanceMeters;
        latestSpeedMps = speedMps;
        latestAccuracyMeters = accuracyMeters;
        float speedKmh = speedMps * 3.6f;
        if (speedGaugeView != null) speedGaugeView.setSpeed(speedKmh);
        speedValue.setText(String.format(Locale.US, "%.1f", speedKmh));
        distanceValue.setText(String.format(Locale.US, "%.2f km", distanceMeters / 1000f));
        timeValue.setText(formatTime(seconds));
        averageValue.setText(formatAverageSpeed(seconds, distanceMeters));
        accuracyValue.setText(accuracyMeters > 0 ? String.format(Locale.US, "+/- %.0f m", accuracyMeters) : "buscando");
        primaryButton.setText(running ? "DETENER" : "INICIAR");
        stylePrimaryButton(primaryButton, running);
        statusValue.setText(running ? "Salida en curso" : "Listo para pedalear");
        updateScreenAwakePolicy();
    }

    private void shareSnapshot() {
        try {
            Bitmap bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawSnapshot(canvas, bitmap.getWidth(), bitmap.getHeight());

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "ciclo-panel-" + System.currentTimeMillis() + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CicloPanel");
            }
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("No se pudo crear la imagen");
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException("No se pudo guardar la imagen");
                }
            }

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartir salida"));
        } catch (Exception ex) {
            Toast.makeText(this, "No pude crear la instantanea: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void drawSnapshot(Canvas canvas, int width, int height) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(COLOR_BG);

        paint.setColor(COLOR_TEXT);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(64f);
        canvas.drawText("Ciclo Panel", 64f, 108f, paint);

        paint.setColor(COLOR_PANEL);
        canvas.drawRoundRect(new RectF(48f, 150f, width - 48f, 430f), 42f, 42f, paint);
        paint.setColor(COLOR_ACCENT);
        canvas.drawRoundRect(new RectF(width - 270f, 176f, width - 78f, 228f), 26f, 26f, paint);
        paint.setColor(COLOR_DARK);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(28f);
        canvas.drawText("CICLISMO", width - 250f, 211f, paint);

        paint.setTextSize(148f);
        canvas.drawText(String.format(Locale.US, "%.1f", latestSpeedMps * 3.6f), 84f, 335f, paint);
        paint.setTextSize(42f);
        paint.setColor(COLOR_TEAL);
        canvas.drawText("km/h", 455f, 328f, paint);

        drawSnapshotMetric(canvas, paint, "Distancia", String.format(Locale.US, "%.2f km", latestDistanceMeters / 1000f), 72f, 500f);
        drawSnapshotMetric(canvas, paint, "Promedio", formatAverageSpeed(latestSeconds, latestDistanceMeters), 560f, 500f);
        drawSnapshotMetric(canvas, paint, "Tiempo", formatTime(latestSeconds), 72f, 630f);
        drawSnapshotMetric(canvas, paint, "GPS", latestAccuracyMeters > 0 ? String.format(Locale.US, "+/- %.0f m", latestAccuracyMeters) : "sin dato", 560f, 630f);

        RectF mapRect = new RectF(48f, 735f, width - 48f, 1198f);
        paint.setColor(COLOR_PANEL);
        canvas.drawRoundRect(mapRect, 40f, 40f, paint);
        drawVisibleMapOrRoute(canvas, mapRect);

        paint.setColor(COLOR_MUTED);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(36f);
        canvas.drawText("Compartido desde Ciclo Panel", 64f, 1265f, paint);
    }

    private void drawSnapshotMetric(Canvas canvas, Paint paint, String label, String value, float x, float y) {
        paint.setColor(COLOR_MUTED);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(34f);
        canvas.drawText(label, x, y, paint);
        paint.setColor(COLOR_DARK);
        paint.setTextSize(52f);
        canvas.drawText(value, x, y + 62f, paint);
    }

    private void drawVisibleMapOrRoute(Canvas canvas, RectF mapRect) {
        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(mapRect, 36f, 36f, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.translate(mapRect.left, mapRect.top);
        boolean drewMap = false;
        if (osmMapView != null && osmMapView.getWidth() > 0 && osmMapView.getHeight() > 0) {
            float scaleX = mapRect.width() / osmMapView.getWidth();
            float scaleY = mapRect.height() / osmMapView.getHeight();
            canvas.scale(scaleX, scaleY);
            osmMapView.draw(canvas);
            drewMap = true;
        }
        canvas.restore();

        if (!drewMap) {
            RouteMapView snapshotMap = new RouteMapView(this);
            snapshotMap.setRoute(latestLatitudes, latestLongitudes);
            snapshotMap.drawRoute(canvas, mapRect, false);
        }
    }

    private String formatTime(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatAverageSpeed(long totalSeconds, float distanceMeters) {
        if (totalSeconds <= 0 || distanceMeters < 5f) return "0.0 km/h";
        float kmhAverage = (distanceMeters / 1000f) / (totalSeconds / 3600f);
        return String.format(Locale.US, "%.1f km/h", kmhAverage);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

