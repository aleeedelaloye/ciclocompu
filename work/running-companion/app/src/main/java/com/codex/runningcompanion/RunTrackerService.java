package com.codex.runningcompanion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;

public class RunTrackerService extends Service implements LocationListener {
    private static final String CHANNEL_ID = "cycling_tracker";
    private static final int NOTIFICATION_ID = 41;
    private static final int MAX_ROUTE_POINTS = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Location> route = new ArrayList<>();
    private LocationManager locationManager;
    private Location lastLocation;
    private Esp32BleClient esp32BleClient;
    private String esp32Status = "ESP32 sin conectar";
    private long startedAtMs;
    private long elapsedSeconds;
    private float distanceMeters;
    private float currentSpeedMps;
    private float accuracyMeters;
    private boolean running;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            broadcastStats();
            if (running) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        esp32BleClient = new Esp32BleClient(this, new Esp32BleClient.Listener() {
            @Override
            public void onEsp32Status(String status) {
                esp32Status = status;
                broadcastEsp32Status(status);
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
                if ("ESP32 desconectado".equals(status) || "ESP32 no encontrado".equals(status)) {
                    handler.postDelayed(() -> {
                        if (esp32BleClient != null && !esp32BleClient.isBusy()) esp32BleClient.connect();
                    }, 3500L);
                }
            }

            @Override
            public void onEsp32Command(String command) {
                handler.post(() -> {
                    if ("START".equalsIgnoreCase(command)) {
                        startTracking();
                    } else if ("STOP".equalsIgnoreCase(command)) {
                        stopTracking();
                    }
                });
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : RunStats.ACTION_START;
        if (RunStats.ACTION_CONNECT_ESP32.equals(action)) {
            startForeground(NOTIFICATION_ID, buildNotification());
            connectEsp32();
            return START_STICKY;
        }
        if (RunStats.ACTION_STOP.equals(action)) {
            stopTracking();
            return START_STICKY;
        }
        startTracking();
        connectEsp32();
        return START_STICKY;
    }

    private void connectEsp32() {
        if (esp32BleClient != null && !esp32BleClient.isBusy()) {
            esp32BleClient.connect();
        }
    }

    private void startTracking() {
        if (running) return;
        running = true;
        startedAtMs = System.currentTimeMillis();
        elapsedSeconds = 0L;
        distanceMeters = 0f;
        currentSpeedMps = 0f;
        accuracyMeters = 0f;
        lastLocation = null;
        route.clear();

        startForeground(NOTIFICATION_ID, buildNotification());

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this);
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 3f, this);
            } catch (SecurityException ignored) {
            } catch (IllegalArgumentException ignored) {
            }
        }
        handler.post(ticker);
    }

    private void stopTracking() {
        elapsedSeconds = Math.max(0, (System.currentTimeMillis() - startedAtMs) / 1000L);
        running = false;
        handler.removeCallbacks(ticker);
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
        broadcastStats();
    }

    @Override
    public void onLocationChanged(Location location) {
        accuracyMeters = location.hasAccuracy() ? location.getAccuracy() : 0f;
        boolean accurateEnough = !location.hasAccuracy() || location.getAccuracy() <= 40f;
        if (accurateEnough) {
            route.add(new Location(location));
            if (route.size() > MAX_ROUTE_POINTS) route.remove(0);
        }
        if (lastLocation != null && accurateEnough) {
            float delta = lastLocation.distanceTo(location);
            if (delta > 0.5f && delta < 80f) {
                distanceMeters += delta;
            }
        }
        currentSpeedMps = location.hasSpeed() ? location.getSpeed() : estimateSpeed(location);
        lastLocation = location;
        broadcastStats();
    }

    private float estimateSpeed(Location location) {
        if (lastLocation == null) return 0f;
        long dt = location.getTime() - lastLocation.getTime();
        if (dt <= 0) return 0f;
        return lastLocation.distanceTo(location) / (dt / 1000f);
    }

    private void broadcastStats() {
        long seconds = running ? Math.max(0, (System.currentTimeMillis() - startedAtMs) / 1000L) : elapsedSeconds;
        if (running) elapsedSeconds = seconds;
        Intent intent = new Intent(RunStats.ACTION_STATS);
        intent.setPackage(getPackageName());
        intent.putExtra(RunStats.EXTRA_RUNNING, running);
        intent.putExtra(RunStats.EXTRA_SECONDS, seconds);
        intent.putExtra(RunStats.EXTRA_DISTANCE, distanceMeters);
        intent.putExtra(RunStats.EXTRA_SPEED, currentSpeedMps);
        intent.putExtra(RunStats.EXTRA_ACCURACY, accuracyMeters);
        intent.putExtra(RunStats.EXTRA_LATITUDES, latitudes());
        intent.putExtra(RunStats.EXTRA_LONGITUDES, longitudes());
        sendBroadcast(intent);
        sendStatsToEsp32(seconds);

        if (running) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void sendStatsToEsp32(long seconds) {
        if (esp32BleClient == null || !esp32BleClient.isConnected()) return;
        double latitude = Double.NaN;
        double longitude = Double.NaN;
        if (!route.isEmpty()) {
            Location last = route.get(route.size() - 1);
            latitude = last.getLatitude();
            longitude = last.getLongitude();
        }
        esp32BleClient.sendStats(
                seconds,
                distanceMeters,
                currentSpeedMps,
                formatAverageSpeed(seconds, distanceMeters),
                running,
                latitude,
                longitude
        );
    }

    private void broadcastEsp32Status(String status) {
        Intent intent = new Intent(RunStats.ACTION_ESP32_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(RunStats.EXTRA_ESP32_STATUS, status);
        sendBroadcast(intent);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        String text = formatDistance(distanceMeters) + " - " + formatSpeed(currentSpeedMps) + " - " + esp32Status;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Ciclo Panel activo")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Ciclo Panel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private String formatDistance(float meters) {
        return String.format(java.util.Locale.US, "%.2f km", meters / 1000f);
    }

    private String formatSpeed(float mps) {
        return String.format(java.util.Locale.US, "%.1f km/h", mps * 3.6f);
    }

    private String formatAverageSpeed(long seconds, float meters) {
        if (seconds <= 0 || meters <= 0f) return "0.0 km/h";
        return String.format(java.util.Locale.US, "%.1f km/h", (meters / 1000f) / (seconds / 3600f));
    }

    private double[] latitudes() {
        double[] values = new double[route.size()];
        for (int i = 0; i < route.size(); i++) values[i] = route.get(i).getLatitude();
        return values;
    }

    private double[] longitudes() {
        double[] values = new double[route.size()];
        for (int i = 0; i < route.size(); i++) values[i] = route.get(i).getLongitude();
        return values;
    }

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (esp32BleClient != null) esp32BleClient.disconnect();
        super.onDestroy();
    }
}
