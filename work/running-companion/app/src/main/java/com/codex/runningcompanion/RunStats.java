package com.codex.runningcompanion;

final class RunStats {
    static final String ACTION_STATS = "com.codex.runningcompanion.ACTION_STATS";
    static final String ACTION_START = "com.codex.runningcompanion.ACTION_START";
    static final String ACTION_STOP = "com.codex.runningcompanion.ACTION_STOP";
    static final String ACTION_CONNECT_ESP32 = "com.codex.runningcompanion.ACTION_CONNECT_ESP32";
    static final String ACTION_ESP32_STATUS = "com.codex.runningcompanion.ACTION_ESP32_STATUS";
    static final String EXTRA_RUNNING = "running";
    static final String EXTRA_SECONDS = "seconds";
    static final String EXTRA_DISTANCE = "distance";
    static final String EXTRA_SPEED = "speed";
    static final String EXTRA_ACCURACY = "accuracy";
    static final String EXTRA_LATITUDES = "latitudes";
    static final String EXTRA_LONGITUDES = "longitudes";
    static final String EXTRA_ESP32_STATUS = "esp32_status";

    private RunStats() {}
}
