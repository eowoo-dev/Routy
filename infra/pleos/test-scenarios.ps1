param([switch]$Emulator, [switch]$Environment, [string]$Serial = 'emulator-5554')

$ErrorActionPreference = 'Stop'
$gradleScript = Join-Path $PSScriptRoot 'gradle.ps1'
& $gradleScript '--project-dir=driving-evaluation' 'test' 'run' '--args=--scenarios --output build/reports/scenarios.json'

if ($Emulator -or $Environment) {
    & python -X utf8 -m unittest discover -s $PSScriptRoot -p 'test_*.py'
    if ($LASTEXITCODE -ne 0) { throw 'Integration oracle regression tests failed.' }
    $scenarioArgs = @('--serial', $Serial)
    if ($Environment) { $scenarioArgs += '--environment' }
    & python -X utf8 (Join-Path $PSScriptRoot 'emulator_scenarios.py') @scenarioArgs
    if ($LASTEXITCODE -ne 0) { throw 'Pleos emulator scenario failed; see driving-evaluation/build/scenarios/emulator/result.json.' }
    $traceIndex = Get-Content (Join-Path $PSScriptRoot '../../apps/pleos/driving-evaluation/build/scenarios/emulator/latest.json') -Raw | ConvertFrom-Json
    if ($traceIndex.storage -eq 'SEGMENTED') {
        for ($windowIndex=0; $windowIndex -lt $traceIndex.segments; $windowIndex++) {
            & $gradleScript '--project-dir=driving-evaluation' 'run' "--args=--trace build/scenarios/emulator/windows/window-$windowIndex.json --verify-captured --output build/scenarios/emulator/replayed-$windowIndex.json"
        }
    } else {
        & $gradleScript '--project-dir=driving-evaluation' 'run' '--args=--trace build/scenarios/emulator/latest.json --verify-captured --output build/scenarios/emulator/replayed.json'
    }
}
