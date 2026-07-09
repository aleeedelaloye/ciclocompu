package com.codex.runningcompanion;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class Esp32BleClient {
    interface Listener {
        void onEsp32Status(String status);
        void onEsp32Command(String command);
    }

    private static final String DEVICE_NAME = "PanelRun32";
    private static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID WRITE_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NOTIFY_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private boolean scanning;

    Esp32BleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void connect() {
        if (isConnected() || scanning) return;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            listener.onEsp32Status("Bluetooth apagado");
            return;
        }
        if (!hasBlePermission()) {
            listener.onEsp32Status("Falta permiso Bluetooth");
            return;
        }
        disconnect();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onEsp32Status("BLE no disponible");
            return;
        }
        listener.onEsp32Status("Buscando ESP32...");
        scanning = true;
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> {
            if (scanning) {
                stopScan();
                listener.onEsp32Status("ESP32 no encontrado");
            }
        }, 12000L);
    }

    void disconnect() {
        stopScan();
        writeCharacteristic = null;
        notifyCharacteristic = null;
        if (gatt != null) {
            if (hasBlePermission()) {
                gatt.disconnect();
                gatt.close();
            }
            gatt = null;
        }
    }

    boolean isConnected() {
        return writeCharacteristic != null && gatt != null;
    }

    boolean isBusy() {
        return scanning || isConnected();
    }

    void sendStats(long seconds, float distanceMeters, float speedMps, String averageSpeed, boolean running) {
        sendStats(seconds, distanceMeters, speedMps, averageSpeed, running, Double.NaN, Double.NaN);
    }

    void sendStats(long seconds, float distanceMeters, float speedMps, String averageSpeed, boolean running, double latitude, double longitude) {
        if (!isConnected() || !hasBlePermission()) return;
        String message = String.format(
                java.util.Locale.US,
                "{\"run\":%d,\"sec\":%d,\"km\":%.2f,\"kmh\":%.1f,\"avg\":\"%s\",\"lat\":%.6f,\"lon\":%.6f}\n",
                running ? 1 : 0,
                seconds,
                distanceMeters / 1000f,
                speedMps * 3.6f,
                averageSpeed.replace("\"", ""),
                latitude,
                longitude
        );
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(writeCharacteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            writeCharacteristic.setValue(bytes);
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            gatt.writeCharacteristic(writeCharacteristic);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (!hasBlePermission()) return;
            String name = device.getName();
            if (DEVICE_NAME.equals(name)) {
                stopScan();
                listener.onEsp32Status("Conectando ESP32...");
                gatt = device.connectGatt(context, false, gattCallback);
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onEsp32Status("ESP32 conectado");
                if (hasBlePermission()) gatt.requestMtu(185);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                writeCharacteristic = null;
                notifyCharacteristic = null;
                listener.onEsp32Status("ESP32 desconectado");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (hasBlePermission()) gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            writeCharacteristic = service != null ? service.getCharacteristic(WRITE_UUID) : null;
            notifyCharacteristic = service != null ? service.getCharacteristic(NOTIFY_UUID) : null;
            if (notifyCharacteristic != null && hasBlePermission()) {
                gatt.setCharacteristicNotification(notifyCharacteristic, true);
                BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CLIENT_CONFIG_UUID);
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                    }
                }
            }
            listener.onEsp32Status(writeCharacteristic != null ? "ESP32 listo" : "Servicio ESP32 no encontrado");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleNotification(characteristic, value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic, characteristic.getValue());
        }
    };

    private void handleNotification(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (characteristic == null || !NOTIFY_UUID.equals(characteristic.getUuid()) || value == null) return;
        String message = new String(value, StandardCharsets.UTF_8).trim();
        if (message.startsWith("CMD:")) {
            listener.onEsp32Command(message.substring(4));
        }
    }

    private void stopScan() {
        if (scanner != null && scanning && hasBlePermission()) {
            scanner.stopScan(scanCallback);
        }
        scanning = false;
    }

    private boolean hasBlePermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
}
