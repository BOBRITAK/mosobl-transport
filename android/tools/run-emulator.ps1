param(
  [string]$Apk = "",
  [string]$OutDir = ""
)

$ErrorActionPreference = 'Continue'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$adb = "$sdk\platform-tools\adb.exe"
$emulator = "$sdk\emulator\emulator.exe"
$avdmanager = "$sdk\cmdline-tools\latest\bin\avdmanager.bat"
$avdName = "atd33"

if (-not $OutDir) { $OutDir = "$PSScriptRoot\..\screenshots\emulator" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Output "=== 1. Распаковка образа ==="
$zip = "$env:LOCALAPPDATA\android-images\x86_64-33_r02.zip"
$target = "$sdk\system-images\android-33\aosp_atd\x86_64"
if (-not (Test-Path "$target\system.img")) {
  New-Item -ItemType Directory -Force -Path $target | Out-Null
  Expand-Archive -Path $zip -DestinationPath $target -Force
}
Write-Output "system.img: $(Test-Path "$target\system.img")"

Write-Output "=== 2. Создание AVD ==="
$avdHome = "$env:USERPROFILE\.android\avd"
New-Item -ItemType Directory -Force -Path $avdHome | Out-Null
if (-not (Test-Path "$avdHome\$avdName.avd")) {
  $create = "no`n"
  $create | & $avdmanager create avd -n $avdName -k "system-images;android-33;aosp_atd;x86_64" -d pixel_5 --force 2>&1 | Select-Object -Last 5
}
Write-Output "AVD: $(Test-Path "$avdHome\$avdName.avd")"

Write-Output "=== 3. Запуск эмулятора (headless) ==="
& $adb kill-server 2>&1 | Out-Null
& $adb start-server 2>&1 | Out-Null
$proc = Start-Process -FilePath $emulator -ArgumentList @(
  "-avd", $avdName, "-no-window", "-no-audio", "-no-boot-anim",
  "-gpu", "swiftshader_indirect", "-no-snapshot", "-netdelay", "none", "-netspeed", "full"
) -PassThru -WindowStyle Hidden
Write-Output "PID эмулятора: $($proc.Id)"

Write-Output "=== 4. Ожидание загрузки ==="
& $adb wait-for-device 2>&1 | Out-Null
$booted = $false
for ($i = 0; $i -lt 120; $i++) {
  $state = (& $adb shell getprop sys.boot_completed 2>$null) -join ''
  if ($state.Trim() -eq '1') { $booted = $true; break }
  Start-Sleep -Seconds 5
}
Write-Output "boot_completed: $booted (за $($i*5) c)"

if (-not $booted) {
  Write-Output "Эмулятор не загрузился"
  Get-Content "$env:USERPROFILE\.android\avd\$avdName.avd\emulator-user.ini" -ErrorAction SilentlyContinue
  exit 1
}

& $adb shell input keyevent 82 2>&1 | Out-Null
& $adb shell settings put global window_animation_scale 0 2>&1 | Out-Null
& $adb shell settings put global transition_animation_scale 0 2>&1 | Out-Null

Write-Output "=== 5. Установка APK ==="
if ($Apk -and (Test-Path $Apk)) {
  & $adb install -r -t $Apk 2>&1 | Select-Object -Last 3
}

Write-Output "=== 6. Запуск приложения ==="
& $adb shell am start -n "ru.podmoskovye.transport/.MainActivity" 2>&1 | Select-Object -Last 2
Start-Sleep -Seconds 8

Write-Output "=== 7. Скриншот ==="
$shot1 = "$OutDir\emulator-map.png"
& $adb exec-out screencap -p > $shot1
Write-Output "снимок: $shot1 ($([math]::Round((Get-Item $shot1).Length/1KB)) KB)"

Write-Output "=== 8. Проверка падения ==="
& $adb logcat -d -s AndroidRuntime:E 2>&1 | Select-Object -Last 10

Write-Output "=== 9. Скриншот схемы (переключение кнопкой) ==="
# кнопка «Схема» — правый верхний угол, рядом с поиском
$size = (& $adb shell wm size 2>$null) -join ''
Write-Output "размер экрана: $size"

Write-Output "готово"
