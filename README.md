# Ciclo Panel

App Android + firmware ESP32 para un ciclocomputador con pantalla Waveshare ESP32-S3-Touch-LCD-1.47.

## Estructura

- `work/running-companion/`: APK Android nativo.
- `work/esp32-panelrun32/PanelRun32/`: firmware Arduino para el ESP32-S3.
- `outputs/CicloPanel-historial-maxima-debug.apk`: APK debug actual.
- `scripts/build-apk.ps1`: compila el APK y copia el resultado a `outputs`.
- `scripts/upload-esp32.ps1`: compila y carga el firmware al ESP32.

## APK

Funciones principales:

- seguimiento GPS de ciclismo;
- conexion BLE automatica con `PanelRun32`;
- envio de velocidad, distancia, tiempo y promedio al ESP32;
- servicio en primer plano para seguir enviando datos con la app abierta y pantalla encendida;
- historial cronologico de salidas mayores a 5 minutos;
- velocidad maxima en vivo y en historial;
- mapa OSM e instantanea para compartir.

Compilar:

```powershell
.\scripts\build-apk.ps1
```

## ESP32

Hardware objetivo:

- Waveshare ESP32-S3-Touch-LCD-1.47.
- Touch AXS5106L por I2C.
- Bateria medida por `BAT_ADC` en GPIO12 con divisor desde `VBAT`.

Comportamiento actual:

- START/STOP desde la pantalla del ESP32.
- Si esta iniciada la salida, muestra velocidad aunque la bicicleta este frenada.
- Si detecta movimiento continuo, pasa automaticamente a visualizacion luego de 5 segundos.
- En pantalla START/STOP, tocar el boton manda START/STOP; tocar fuera cambia a visualizacion.
- Apagado de pantalla tras 3 minutos sin movimiento/toque.
- Brillo al 100%.

Cargar firmware:

```powershell
.\scripts\upload-esp32.ps1 -Port COM6
```

Si cambia el puerto, revisar con:

```powershell
& 'C:\Program Files\Arduino CLI\arduino-cli.exe' board list
```

