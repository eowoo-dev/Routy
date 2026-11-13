param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs = @('assembleDebug')
)

$ErrorActionPreference = 'Stop'
$composePath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../compose.dev.yaml'))
& docker compose -f $composePath run --rm --no-deps pleos @GradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle container failed with exit code $LASTEXITCODE." }
