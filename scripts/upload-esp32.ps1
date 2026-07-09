param(
  [string]$Port = "COM6",
  [string]$Fqbn = "esp32:esp32:esp32s3"
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$SketchDir = Join-Path $RepoRoot "work\esp32-panelrun32\PanelRun32"
$ArduinoCli = "C:\Program Files\Arduino CLI\arduino-cli.exe"

if (-not (Test-Path $ArduinoCli)) {
  throw "No encontre Arduino CLI en $ArduinoCli"
}

& $ArduinoCli compile --fqbn $Fqbn $SketchDir
& $ArduinoCli upload -p $Port --fqbn $Fqbn $SketchDir

