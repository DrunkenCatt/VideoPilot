[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot '../compose.yml'
docker compose -f $composeFile down
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose failed to stop VideoPilot."
}
Write-Host "VideoPilot stopped; named volumes were retained."
