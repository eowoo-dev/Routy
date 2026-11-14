param([string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'gradle.ps1') ':app:testDebugUnitTest' ':app:assembleDebug' ':app:lintDebug'
    & (Join-Path $PSScriptRoot 'install-windows.ps1') -Serial $Serial -SkipBuild
}
& python -X utf8 -m unittest discover -s $PSScriptRoot -p 'test_driving_service.py'
if ($LASTEXITCODE -ne 0) { throw 'Driving service oracle tests failed.' }
& python -X utf8 (Join-Path $PSScriptRoot 'driving_service.py') --serial $Serial
if ($LASTEXITCODE -ne 0) { throw 'Driving service integration failed; see driving-evaluation/build/scenarios/driving-service/result.json.' }
