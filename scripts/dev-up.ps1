[CmdletBinding()]
param(
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot '../compose.yml'
$composeArgs = @('up', '-d')
if ($Build) {
    Push-Location "$PSScriptRoot/../server"
    try {
        & .\mvnw.cmd -B -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven failed to package the VideoPilot server."
        }
    } finally {
        Pop-Location
    }
    $composeArgs += '--build'
}

docker compose -f $composeFile @composeArgs
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose failed to start VideoPilot."
}

& "$PSScriptRoot/dev-check.ps1"
if ($LASTEXITCODE -ne 0) {
    throw "VideoPilot started but did not become ready."
}
