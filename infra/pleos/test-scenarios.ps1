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
    & $gradleScript '--project-dir=driving-evaluation' 'run' '--args=--trace build/scenarios/emulator/latest.json --verify-captured --output build/scenarios/emulator/replayed.json'
}
