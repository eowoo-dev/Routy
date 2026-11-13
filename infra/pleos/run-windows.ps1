param(
    [ValidateSet('host', 'swiftshader')]
    [string]$Gpu = 'host'
)

$ErrorActionPreference = 'Stop'
$sdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$nativePath = Join-Path $env:LOCALAPPDATA 'Routy\windows'
$imagePath = Join-Path $nativePath 'system-image\crp_avd_out_x86_64'
$emulatorPath = Join-Path $sdkPath 'emulator\emulator.exe'
$avdName = 'Routy_Pleos_Windows'
$avdRoot = Join-Path $nativePath 'avd'
$avdPath = Join-Path $avdRoot "$avdName.avd"

foreach ($requiredPath in @($emulatorPath, (Join-Path $imagePath 'system.img'))) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Missing Windows test component: $requiredPath"
    }
}

$env:ANDROID_HOME = $sdkPath
$env:ANDROID_USER_HOME = Join-Path $nativePath 'android'
$env:ANDROID_AVD_HOME = $avdRoot
New-Item -ItemType Directory -Path $avdPath, $env:ANDROID_USER_HOME -Force | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $avdRoot "$avdName.ini"), @"
avd.ini.encoding=UTF-8
path=$avdPath
target=android-34
"@, $utf8)
$imageDirectory = $imagePath.Replace('\', '/') + '/'
[IO.File]::WriteAllText((Join-Path $avdPath 'config.ini'), @"
avd.ini.encoding=UTF-8
AvdId=$avdName
avd.ini.displayname=Routy Pleos Connect 2.1.0 Windows
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=4096
hw.lcd.width=2560
hw.lcd.height=1440
hw.lcd.density=160
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=$Gpu
hw.mainKeys=no
hw.device.name=Pleos_Connect_v2_0
hw.device.manufacturer=42dot
hw.sensors.orientation=no
hw.accelerometer=no
disk.dataPartition.size=8G
image.sysdir.1=$imageDirectory
tag.id=android-automotive
tag.display=Pleos Connect
PlayStore.enabled=false
fastboot.forceColdBoot=yes
fastboot.forceFastBoot=no
"@, $utf8)
Copy-Item -LiteralPath (Join-Path $imagePath 'advancedFeatures.ini') -Destination $avdPath -Force

# Keep the emulator window visible for interactive comparison with Docker.
$emulatorProcess = Start-Process -FilePath $emulatorPath -ArgumentList @(
    '-avd', $avdName, '-no-snapshot', '-no-audio', '-no-boot-anim',
    '-gpu', $Gpu, '-accel', 'on', '-memory', '4096', '-cores', '4',
    '-ports', '5554,5555', '-scale', '0.5'
) -RedirectStandardOutput (Join-Path $nativePath "emulator-$Gpu.stdout.log") `
  -RedirectStandardError (Join-Path $nativePath "emulator-$Gpu.stderr.log") -PassThru
Write-Output "Emulator launcher PID: $($emulatorProcess.Id)"
Write-Output "Logs: $nativePath"
