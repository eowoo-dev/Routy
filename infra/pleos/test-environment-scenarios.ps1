param([ValidateSet('lane-change','lane-boundary','red-stop','following-gap')][string]$Scenario)
$ErrorActionPreference='Stop'
$rootPath=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $rootPath
try {
    $generatorArgs=@('infra/virtual-console/scenarios/generate.mjs')
    if($Scenario){$generatorArgs+=@('--scenario',$Scenario)}
    docker compose -f compose.dev.yaml run --rm --no-deps --entrypoint node web @generatorArgs
    if($LASTEXITCODE -ne 0){throw 'Scenario geometry verification failed.'}
    & (Join-Path $PSScriptRoot 'gradle.ps1') '--project-dir=driving-evaluation' test run '--args=--environment-suite build/environment-scenarios.json --output build/reports/environment-scenarios.json'
} finally {Pop-Location}
