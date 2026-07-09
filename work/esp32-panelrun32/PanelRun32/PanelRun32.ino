#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Arduino_GFX_Library.h>
#include <Wire.h>
#include "esp_lcd_touch_axs5106l.h"

#define DEVICE_NAME "PanelRun32"
#define SERVICE_UUID "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define WRITE_UUID "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define NOTIFY_UUID "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

#define GFX_BL 46
#define LCD_RST 40
#define Touch_I2C_SDA 42
#define Touch_I2C_SCL 41
#define Touch_RST 47
#define Touch_INT 48
#define BAT_ADC_PIN 12

#define COLOR_BG 0xEF7D
#define COLOR_PANEL 0xFFFF
#define COLOR_DARK 0x1183
#define COLOR_MUTED 0x632C
#define COLOR_ACCENT 0xBFE8
#define COLOR_TEAL 0x07E0
#define COLOR_BLUE 0x04DF
#define COLOR_RED 0xF986
#define COLOR_YELLOW 0xFFE0
#define COLOR_GRAY 0x8410

const unsigned long SCREEN_SLEEP_MS = 180000UL;
const unsigned long BATTERY_READ_MS = 3000UL;
const unsigned long AUTO_VISUAL_DELAY_MS = 5000UL;
const uint8_t BACKLIGHT_BRIGHTNESS = 255;

Arduino_DataBus *bus = new Arduino_ESP32SPI(45 /* DC */, 21 /* CS */, 38 /* SCK */, 39 /* MOSI */);
Arduino_GFX *gfx = new Arduino_ST7789(
  bus,
  LCD_RST /* RST */,
  0 /* rotation */,
  false /* IPS */,
  172 /* width */,
  320 /* height */,
  34 /* col_offset1 */,
  0 /* row_offset1 */,
  34 /* col_offset2 */,
  0 /* row_offset2 */
);

bool connected = false;
BLECharacteristic *notifyCharacteristic = nullptr;

String lineBuffer;
float kmh = 0.0;
float km = 0.0;
String averageSpeed = "0.0 km/h";
long secondsElapsed = 0;
bool running = false;
unsigned long lastPacketMs = 0;
bool layoutDrawn = false;
uint8_t currentScreen = 0;

const int MAX_ROUTE_POINTS = 90;
float routeLat[MAX_ROUTE_POINTS];
float routeLon[MAX_ROUTE_POINTS];
int routeCount = 0;
bool startCommandSent = false;
bool connectPromptSent = false;
bool screenSleeping = false;
unsigned long lastActivityMs = 0;
unsigned long lastBatteryReadMs = 0;
unsigned long movingSinceMs = 0;
float batteryVoltage = 0.0f;
int batteryPercent = 0;
bool touchWakeOnly = false;
bool touchDown = false;
bool touchSwipeHandled = false;
uint16_t touchStartX = 0;
uint16_t touchStartY = 0;
uint16_t touchLastX = 0;
uint16_t touchLastY = 0;
unsigned long touchStartMs = 0;
String debugI2cScan = "I2C ?";
uint8_t debugRaw[8] = {0};
unsigned long lastRawTouchReadMs = 0;
uint32_t lastRawSignature = 0;
uint32_t idleRawSignature = 0;
uint8_t rawMotionHits = 0;
unsigned long lastRawSwitchMs = 0;
bool lastTouchIntActive = false;

void lcd_reg_init(void) {
  static const uint8_t init_operations[] = {
    BEGIN_WRITE,
    WRITE_COMMAND_8, 0x11,
    END_WRITE,
    DELAY, 120,

    BEGIN_WRITE,
    WRITE_C8_D16, 0xDF, 0x98, 0x53,
    WRITE_C8_D8, 0xB2, 0x23,

    WRITE_COMMAND_8, 0xB7,
    WRITE_BYTES, 4,
    0x00, 0x47, 0x00, 0x6F,

    WRITE_COMMAND_8, 0xBB,
    WRITE_BYTES, 6,
    0x1C, 0x1A, 0x55, 0x73, 0x63, 0xF0,

    WRITE_C8_D16, 0xC0, 0x44, 0xA4,
    WRITE_C8_D8, 0xC1, 0x16,

    WRITE_COMMAND_8, 0xC3,
    WRITE_BYTES, 8,
    0x7D, 0x07, 0x14, 0x06, 0xCF, 0x71, 0x72, 0x77,

    WRITE_COMMAND_8, 0xC4,
    WRITE_BYTES, 12,
    0x00, 0x00, 0xA0, 0x79, 0x0B, 0x0A, 0x16, 0x79, 0x0B, 0x0A, 0x16, 0x82,

    WRITE_COMMAND_8, 0xC8,
    WRITE_BYTES, 32,
    0x3F, 0x32, 0x29, 0x29, 0x27, 0x2B, 0x27, 0x28,
    0x28, 0x26, 0x25, 0x17, 0x12, 0x0D, 0x04, 0x00,
    0x3F, 0x32, 0x29, 0x29, 0x27, 0x2B, 0x27, 0x28,
    0x28, 0x26, 0x25, 0x17, 0x12, 0x0D, 0x04, 0x00,

    WRITE_COMMAND_8, 0xD0,
    WRITE_BYTES, 5,
    0x04, 0x06, 0x6B, 0x0F, 0x00,

    WRITE_C8_D16, 0xD7, 0x00, 0x30,
    WRITE_C8_D8, 0xE6, 0x14,
    WRITE_C8_D8, 0xDE, 0x01,

    WRITE_COMMAND_8, 0xB7,
    WRITE_BYTES, 5,
    0x03, 0x13, 0xEF, 0x35, 0x35,

    WRITE_COMMAND_8, 0xC1,
    WRITE_BYTES, 3,
    0x14, 0x15, 0xC0,

    WRITE_C8_D16, 0xC2, 0x06, 0x3A,
    WRITE_C8_D16, 0xC4, 0x72, 0x12,
    WRITE_C8_D8, 0xBE, 0x00,
    WRITE_C8_D8, 0xDE, 0x02,

    WRITE_COMMAND_8, 0xE5,
    WRITE_BYTES, 3,
    0x00, 0x02, 0x00,

    WRITE_COMMAND_8, 0xE5,
    WRITE_BYTES, 3,
    0x01, 0x02, 0x00,

    WRITE_C8_D8, 0xDE, 0x00,
    WRITE_C8_D8, 0x35, 0x00,
    WRITE_C8_D8, 0x3A, 0x05,

    WRITE_COMMAND_8, 0x2A,
    WRITE_BYTES, 4,
    0x00, 0x22, 0x00, 0xCD,

    WRITE_COMMAND_8, 0x2B,
    WRITE_BYTES, 4,
    0x00, 0x00, 0x01, 0x3F,

    WRITE_C8_D8, 0xDE, 0x02,

    WRITE_COMMAND_8, 0xE5,
    WRITE_BYTES, 3,
    0x00, 0x02, 0x00,

    WRITE_C8_D8, 0xDE, 0x00,
    WRITE_C8_D8, 0x36, 0x00,
    WRITE_COMMAND_8, 0x21,
    END_WRITE,

    DELAY, 10,

    BEGIN_WRITE,
    WRITE_COMMAND_8, 0x29,
    END_WRITE
  };
  bus->batchOperation(init_operations, sizeof(init_operations));
}

String valueFor(const String &json, const String &key) {
  String needle = "\"" + key + "\":";
  int start = json.indexOf(needle);
  if (start < 0) return "";
  start += needle.length();
  while (start < json.length() && json[start] == ' ') start++;
  if (start < json.length() && json[start] == '"') {
    int end = json.indexOf('"', start + 1);
    if (end < 0) return "";
    return json.substring(start + 1, end);
  }
  int end = start;
  while (end < json.length() && json[end] != ',' && json[end] != '}') end++;
  return json.substring(start, end);
}

String formatTime(long totalSeconds) {
  long h = totalSeconds / 3600;
  long m = (totalSeconds % 3600) / 60;
  long s = totalSeconds % 60;
  char buffer[12];
  snprintf(buffer, sizeof(buffer), "%02ld:%02ld:%02ld", h, m, s);
  return String(buffer);
}

void addRoutePoint(float lat, float lon) {
  if (isnan(lat) || isnan(lon) || abs(lat) < 0.000001 || abs(lon) < 0.000001) return;
  if (routeCount > 0) {
    float dLat = lat - routeLat[routeCount - 1];
    float dLon = lon - routeLon[routeCount - 1];
    if ((dLat * dLat + dLon * dLon) < 0.0000000001) return;
  }
  if (routeCount < MAX_ROUTE_POINTS) {
    routeLat[routeCount] = lat;
    routeLon[routeCount] = lon;
    routeCount++;
  } else {
    for (int i = 1; i < MAX_ROUTE_POINTS; i++) {
      routeLat[i - 1] = routeLat[i];
      routeLon[i - 1] = routeLon[i];
    }
    routeLat[MAX_ROUTE_POINTS - 1] = lat;
    routeLon[MAX_ROUTE_POINTS - 1] = lon;
  }
}

void drawBleDot() {
  gfx->fillCircle(282, 24, 7, connected ? COLOR_TEAL : COLOR_RED);
}

int batteryToPercent(float voltage) {
  if (voltage <= 3.20f) return 0;
  if (voltage >= 4.20f) return 100;
  return (int)roundf((voltage - 3.20f) * 100.0f);
}

bool updateBattery(bool force = false) {
  if (!force && millis() - lastBatteryReadMs < BATTERY_READ_MS) return false;
  lastBatteryReadMs = millis();

  float totalMv = 0.0f;
  const int samples = 8;
  for (int i = 0; i < samples; i++) {
    totalMv += analogReadMilliVolts(BAT_ADC_PIN);
    delay(2);
  }

  batteryVoltage = (totalMv / samples) * 3.0f / 1000.0f;
  batteryPercent = batteryToPercent(batteryVoltage);
  return true;
}

void drawBatteryStatus(bool refresh = true) {
  if (refresh) updateBattery(true);
  uint16_t fillColor = batteryPercent > 30 ? COLOR_TEAL : (batteryPercent > 15 ? COLOR_YELLOW : COLOR_RED);
  int fillHeight = map(batteryPercent, 0, 100, 0, 88);

  gfx->fillRoundRect(302, 10, 10, 92, 5, COLOR_BG);
  gfx->drawRoundRect(302, 10, 10, 92, 5, COLOR_DARK);
  gfx->fillRoundRect(304, 12 + (88 - fillHeight), 6, fillHeight, 3, fillColor);
}

void scanTouchI2c() {
  String found = "I2C";
  for (uint8_t addr = 1; addr < 127; addr++) {
    Wire.beginTransmission(addr);
    if (Wire.endTransmission() == 0) {
      char buf[8];
      snprintf(buf, sizeof(buf), " %02X", addr);
      found += buf;
    }
  }
  debugI2cScan = found;
  Serial.println(debugI2cScan);
}

uint16_t statusColor() {
  if (!running) return COLOR_GRAY;
  if (kmh < 2.5) return COLOR_YELLOW;
  return COLOR_TEAL;
}

void drawStatusBar() {
  gfx->fillRoundRect(10, 166, 300, 10, 5, statusColor());
}

void drawLayout() {
  currentScreen = 0;
  gfx->fillScreen(COLOR_BG);

  gfx->fillRoundRect(10, 8, 284, 92, 18, COLOR_PANEL);
  gfx->fillRoundRect(10, 108, 146, 52, 14, COLOR_PANEL);
  gfx->fillRoundRect(164, 108, 146, 52, 14, COLOR_PANEL);

  gfx->setTextColor(COLOR_MUTED);
  gfx->setTextSize(1);
  gfx->setCursor(28, 116);
  gfx->print("TIEMPO");
  gfx->setCursor(184, 116);
  gfx->print("PROM");

  layoutDrawn = true;
  drawBleDot();
  drawStatusBar();
}

void drawStartScreen() {
  currentScreen = 1;
  gfx->fillScreen(COLOR_BG);

  gfx->fillRoundRect(10, 8, 300, 154, 18, COLOR_PANEL);
  gfx->setTextColor(COLOR_MUTED);
  gfx->setTextSize(1);
  gfx->setCursor(24, 22);
  gfx->print(connected ? "APK conectado" : "Bluetooth sin conexion");

  bool canSend = connected;
  bool stopMode = running;
  uint16_t buttonColor = canSend ? (stopMode ? COLOR_RED : COLOR_TEAL) : COLOR_BLUE;
  gfx->fillRoundRect(48, 54, 224, 72, 24, buttonColor);
  gfx->setTextColor(COLOR_PANEL);
  if (!canSend) {
    gfx->setTextSize(3);
    gfx->setCursor(68, 78);
    gfx->print("CONECTAR");
  } else {
    gfx->setTextSize(4);
    gfx->setCursor(stopMode ? 94 : 86, 76);
    gfx->print(stopMode ? "STOP" : "START");
  }

  gfx->setTextColor(COLOR_MUTED);
  gfx->setTextSize(1);
  gfx->setCursor(54, 142);
  if (!connected && connectPromptSent) {
    gfx->print("Buscando desde el APK...");
  } else if (startCommandSent) {
    gfx->print("Comando enviado al celular");
  } else {
    gfx->print(!connected ? "Toca y abre conectar ESP32" : (stopMode ? "Toca para detener en el APK" : "Toca para iniciar en el APK"));
  }
  drawBleDot();
  drawStatusBar();
}

void drawWidgets() {
  if (currentScreen != 0) return;
  if (!layoutDrawn) drawLayout();

  gfx->fillRect(22, 22, 252, 46, COLOR_PANEL);
  gfx->setTextColor(COLOR_DARK);
  gfx->setTextSize(5);
  gfx->setCursor(22, 25);
  gfx->print(kmh, 1);
  gfx->setTextColor(COLOR_DARK);
  gfx->setTextSize(3);
  gfx->setCursor(202, 36);
  gfx->print("km/h");

  gfx->fillRect(24, 70, 190, 22, COLOR_PANEL);
  gfx->setTextColor(COLOR_MUTED);
  gfx->setTextSize(1);
  gfx->setCursor(24, 75);
  gfx->print("DISTANCIA");
  gfx->setTextColor(COLOR_DARK);
  gfx->setTextSize(2);
  gfx->setCursor(100, 72);
  gfx->print(km, 2);
  gfx->setTextSize(1);
  gfx->setCursor(168, 78);
  gfx->print("km");

  gfx->fillRect(28, 132, 118, 22, COLOR_PANEL);
  gfx->fillRect(184, 132, 108, 22, COLOR_PANEL);
  gfx->setTextColor(COLOR_DARK);
  gfx->setTextSize(2);
  gfx->setCursor(28, 135);
  gfx->print(formatTime(secondsElapsed));
  gfx->setTextSize(2);
  gfx->setCursor(184, 135);
  gfx->print(averageSpeed);

  drawBleDot();
  drawBatteryStatus();
  drawStatusBar();
}

void printWidgets() {
  Serial.print("PanelRun32 | ");
  Serial.print(kmh, 1);
  Serial.print(" km/h | ");
  Serial.print(km, 2);
  Serial.print(" km | ");
  Serial.print(formatTime(secondsElapsed));
  Serial.print(" | ");
  Serial.print(averageSpeed);
  Serial.print(" | ");
  Serial.println(running ? "RUN" : "STOP");
}

bool isMoving() {
  return running && kmh >= 2.5f;
}

void redrawCurrentScreen() {
  if (currentScreen == 1) {
    drawStartScreen();
  } else {
    layoutDrawn = false;
    drawLayout();
    drawWidgets();
  }
}

void wakeScreen() {
  lastActivityMs = millis();
  if (!screenSleeping) return;
  screenSleeping = false;
  analogWrite(GFX_BL, BACKLIGHT_BRIGHTNESS);
  redrawCurrentScreen();
  Serial.println("Pantalla encendida");
}

void noteActivity() {
  wakeScreen();
}

void updateScreenSleep() {
  if (screenSleeping) return;
  if (isMoving()) {
    lastActivityMs = millis();
    return;
  }
  if (lastActivityMs > 0 && millis() - lastActivityMs >= SCREEN_SLEEP_MS) {
    screenSleeping = true;
    analogWrite(GFX_BL, 0);
    Serial.println("Pantalla apagada por inactividad");
  }
}

void applyAutoScreen() {
  startCommandSent = false;
  bool moving = isMoving();
  if (moving) {
    noteActivity();
    if (movingSinceMs == 0) movingSinceMs = millis();
  } else {
    movingSinceMs = 0;
  }
  if (screenSleeping) return;
  if (running) {
    bool movementDelayPassed = movingSinceMs > 0 && millis() - movingSinceMs >= AUTO_VISUAL_DELAY_MS;
    if (currentScreen == 1 && !movementDelayPassed) {
      drawStartScreen();
      return;
    }
    if (currentScreen != 0) {
      layoutDrawn = false;
      drawLayout();
    }
    drawWidgets();
  } else {
    drawStartScreen();
  }
}

void parsePacket(const String &packet) {
  String runValue = valueFor(packet, "run");
  String secValue = valueFor(packet, "sec");
  String kmValue = valueFor(packet, "km");
  String kmhValue = valueFor(packet, "kmh");
  String averageValue = valueFor(packet, "avg");
  if (!averageValue.length()) averageValue = valueFor(packet, "pace");
  String latValue = valueFor(packet, "lat");
  String lonValue = valueFor(packet, "lon");

  if (runValue.length()) running = runValue.toInt() == 1;
  if (secValue.length()) secondsElapsed = secValue.toInt();
  if (kmValue.length()) km = kmValue.toFloat();
  if (kmhValue.length()) kmh = kmhValue.toFloat();
  if (averageValue.length()) averageSpeed = averageValue;
  if (latValue.length() && lonValue.length()) addRoutePoint(latValue.toFloat(), lonValue.toFloat());
  lastPacketMs = millis();
  printWidgets();
  applyAutoScreen();
}

void sendRunCommand() {
  if (!connected || notifyCharacteristic == nullptr) {
    BLEDevice::startAdvertising();
    connectPromptSent = true;
    startCommandSent = false;
    Serial.println("BLE anunciando; pulsa Conectar ESP32 en el APK");
    if (!screenSleeping) drawStartScreen();
    return;
  }
  connectPromptSent = false;
  const char *message = running ? "CMD:STOP\n" : "CMD:START\n";
  notifyCharacteristic->setValue((uint8_t *)message, strlen(message));
  notifyCharacteristic->notify();
  startCommandSent = true;
  Serial.println(running ? "Comando STOP enviado al APK" : "Comando START enviado al APK");
  if (!screenSleeping) drawStartScreen();
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    connected = true;
    startCommandSent = false;
    connectPromptSent = false;
    Serial.println("APK conectado por BLE");
    if (!screenSleeping) {
      drawBleDot();
      if (currentScreen == 1) drawStartScreen();
    }
  }

  void onDisconnect(BLEServer *server) override {
    connected = false;
    startCommandSent = false;
    connectPromptSent = false;
    Serial.println("APK desconectado; anunciando de nuevo");
    BLEDevice::startAdvertising();
    if (!screenSleeping) {
      drawBleDot();
      if (currentScreen == 1) drawStartScreen();
    }
  }
};

class WriteCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    String value = characteristic->getValue().c_str();
    for (int i = 0; i < value.length(); i++) {
      char c = value[i];
      if (c == '\n') {
        parsePacket(lineBuffer);
        lineBuffer = "";
      } else if (lineBuffer.length() < 220) {
        lineBuffer += c;
      }
    }
  }
};

void setupBle() {
  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);
  BLECharacteristic *writeCharacteristic = service->createCharacteristic(
    WRITE_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  writeCharacteristic->setCallbacks(new WriteCallbacks());
  writeCharacteristic->addDescriptor(new BLE2902());

  notifyCharacteristic = service->createCharacteristic(
    NOTIFY_UUID,
    BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ
  );
  notifyCharacteristic->addDescriptor(new BLE2902());

  service->start();
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("PanelRun32 iniciando...");

  pinMode(BAT_ADC_PIN, INPUT);
  analogReadResolution(12);
  analogSetPinAttenuation(BAT_ADC_PIN, ADC_11db);
  updateBattery(true);

  pinMode(LCD_RST, OUTPUT);
  digitalWrite(LCD_RST, LOW);
  delay(10);
  digitalWrite(LCD_RST, HIGH);
  delay(10);

  if (!gfx->begin()) {
    Serial.println("LCD init failed; usando Serial como salida.");
  } else {
    lcd_reg_init();
    gfx->setRotation(1);
    pinMode(GFX_BL, OUTPUT);
    analogWrite(GFX_BL, BACKLIGHT_BRIGHTNESS);
    lastActivityMs = millis();
    drawLayout();
  }

  Wire.begin(Touch_I2C_SDA, Touch_I2C_SCL);
  delay(100);
  bsp_touch_init(&Wire, Touch_RST, Touch_INT, gfx->getRotation(), gfx->width(), gfx->height());
  scanTouchI2c();

  setupBle();
  Serial.println("BLE listo: busca PanelRun32 desde el APK.");
  drawWidgets();
}

void loop() {
  handleTouch();
  if (updateBattery(false) && !screenSleeping) {
    if (currentScreen == 1) drawBatteryStatus(false);
  }
  if (connected && lastPacketMs > 0 && millis() - lastPacketMs > 5000) {
    lastPacketMs = millis();
    if (!screenSleeping) drawWidgets();
  }
  updateScreenSleep();
  delay(25);
}

void switchScreen() {
  if (currentScreen == 0) {
    startCommandSent = false;
    drawStartScreen();
  } else {
    layoutDrawn = false;
    drawLayout();
    drawWidgets();
  }
}

void registerTouchHit() {
  if (millis() - lastRawSwitchMs < 650) {
    return;
  }
  lastRawSwitchMs = millis();
  if (screenSleeping) {
    wakeScreen();
    touchWakeOnly = true;
    return;
  }
  noteActivity();
  if (currentScreen == 1) {
    return;
  }
  switchScreen();
}

bool isRunButtonArea(uint16_t x, uint16_t y) {
  return x >= 48 && x <= 272 && y >= 54 && y <= 126;
}

void handleStartScreenTouch(uint16_t x, uint16_t y) {
  if (millis() - lastRawSwitchMs < 650) return;
  lastRawSwitchMs = millis();
  if (screenSleeping) {
    wakeScreen();
    touchWakeOnly = true;
    return;
  }
  noteActivity();
  if (isRunButtonArea(x, y)) {
    sendRunCommand();
  } else {
    switchScreen();
  }
}

void handleTouch() {
  touch_data_t touchData;
  bsp_touch_read();
  bool touchIntActive = digitalRead(Touch_INT) == LOW;
  if (touchIntActive && !lastTouchIntActive) {
    registerTouchHit();
  }
  lastTouchIntActive = touchIntActive;

  if (millis() - lastRawTouchReadMs > 80) {
    lastRawTouchReadMs = millis();
    if (bsp_touch_read_raw(debugRaw, sizeof(debugRaw))) {
      uint32_t signature = 0;
      for (int i = 0; i < 6; i++) {
        signature = (signature * 131) + debugRaw[i];
      }
      if (idleRawSignature == 0 && millis() > 1200) {
        idleRawSignature = signature;
      }
      if (idleRawSignature != 0 && signature != idleRawSignature) {
        rawMotionHits++;
      } else {
        rawMotionHits = 0;
      }
      if (rawMotionHits >= 2) {
        rawMotionHits = 0;
        idleRawSignature = signature;
        registerTouchHit();
        if (touchWakeOnly) {
          touchWakeOnly = false;
          return;
        }
      }
      lastRawSignature = signature;
    }
  }
  bool pressed = bsp_touch_get_coordinates(&touchData);
  if (pressed && touchData.touch_num > 0) {
    uint16_t x = touchData.coords[0].x;
    uint16_t y = touchData.coords[0].y;
    if (!touchDown) {
      bool wasStartScreen = currentScreen == 1;
      if (wasStartScreen) {
        handleStartScreenTouch(x, y);
      } else {
        registerTouchHit();
      }
      if (touchWakeOnly) {
        touchWakeOnly = false;
        touchDown = true;
        touchSwipeHandled = true;
        touchStartX = x;
        touchStartY = y;
        touchLastX = x;
        touchLastY = y;
        touchStartMs = millis();
        return;
      }
      touchDown = true;
      touchSwipeHandled = wasStartScreen;
      touchStartX = x;
      touchStartY = y;
      touchLastX = x;
      touchLastY = y;
      touchStartMs = millis();
    } else {
      touchLastX = x;
      touchLastY = y;
      int dx = (int)touchLastX - (int)touchStartX;
      int dy = (int)touchLastY - (int)touchStartY;
      if (!touchSwipeHandled && millis() - touchStartMs < 1200 && (abs(dx) > 28 || abs(dy) > 28)) {
        touchSwipeHandled = true;
        switchScreen();
      }
    }
  } else if (touchDown) {
    touchDown = false;
    int dx = (int)touchLastX - (int)touchStartX;
    int dy = (int)touchLastY - (int)touchStartY;
    if (!touchSwipeHandled && millis() - touchStartMs < 1200 && (abs(dx) > 28 || abs(dy) > 28)) {
      switchScreen();
    } else if (!touchSwipeHandled && millis() - touchStartMs < 700) {
      switchScreen();
    }
  }
}
