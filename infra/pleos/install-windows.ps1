param(
    [string]$Serial = 'emulator-5554',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sdkPath = $env:ANDROID_HOME
if (-not $sdkPath) { $sdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $sdkPath 'platform-tools/adb.exe'
$apk = Join-Path $rootPath 'apps/pleos/app/build/outputs/apk/debug/app-debug.apk'

if (-not (Test-Path -LiteralPath $adb)) { throw "Windows Platform Tools not found: $adb" }
& $adb -s $Serial get-state
if ($LASTEXITCODE -ne 0) { throw "Emulator not connected: $Serial" }
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'gradle.ps1') ':app:assembleDebug' }
if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK not found: $apk" }

& $adb -s $Serial reverse tcp:3000 tcp:3000
if ($LASTEXITCODE -ne 0) { throw 'ADB reverse failed.' }
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK install failed.' }
& $adb -s $Serial shell am start -W -n 'com.noonmaru.routy.pleos.debug/com.noonmaru.routy.pleos.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'Activity launch failed.' }
