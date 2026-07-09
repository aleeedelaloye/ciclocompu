param(
  [string]$OutputName = "CicloPanel-historial-maxima-debug.apk"
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ProjectDir = Join-Path $RepoRoot "work\running-companion"
$OutputDir = Join-Path $RepoRoot "outputs"
$ApkSource = Join-Path $ProjectDir "app\build\outputs\apk\debug\app-debug.apk"
$ApkDest = Join-Path $OutputDir $OutputName

if (-not $env:JAVA_HOME) {
  $StudioJbr = "C:\Program Files\Android\Android Studio\jbr"
  if (Test-Path $StudioJbr) {
    $env:JAVA_HOME = $StudioJbr
  }
}

$SdkDir = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (Test-Path $SdkDir) {
  $LocalProperties = Join-Path $ProjectDir "local.properties"
  if (-not (Test-Path $LocalProperties)) {
    "sdk.dir=$($SdkDir.Replace('\', '\\'))" | Set-Content -Path $LocalProperties -Encoding ASCII
  }
}

$Gradle = Get-ChildItem -Path "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
  Sort-Object FullName -Descending |
  Select-Object -First 1 -ExpandProperty FullName

if (-not $Gradle) {
  throw "No encontre gradle.bat en ~/.gradle/wrapper/dists. Instala Gradle o abre el proyecto una vez en Android Studio."
}

Push-Location $ProjectDir
try {
  & $Gradle assembleDebug
} finally {
  Pop-Location
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
Copy-Item -Path $ApkSource -Destination $ApkDest -Force
Write-Host "APK listo: $ApkDest"

