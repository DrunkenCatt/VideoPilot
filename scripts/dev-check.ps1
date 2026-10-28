[CmdletBinding()]
param(
    [int]$TimeoutSeconds = 120,
    [int]$ServerPort = 0
)

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot '../compose.yml'
if ($ServerPort -eq 0) {
    $portMapping = docker compose -f $composeFile port server 8080
    if ($LASTEXITCODE -ne 0 -or -not $portMapping) {
        throw "Could not discover the mapped server port. Is the server container running?"
    }
    $ServerPort = [int](($portMapping | Select-Object -First 1) -replace '^.*:', '')
}

function Get-HealthResponse([string]$Uri) {
    $body = & curl.exe --silent --max-time 5 $Uri
    if ($LASTEXITCODE -ne 0) {
        throw "Health request failed: $Uri"
    }
    return $body | ConvertFrom-Json
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$readinessUrl = "http://localhost:$ServerPort/actuator/health/readiness"
$diagnosticsUrl = "http://localhost:$ServerPort/actuator/health/dependencies"

while ((Get-Date) -lt $deadline) {
    try {
        $readiness = Get-HealthResponse $readinessUrl
        if ($readiness.status -eq 'UP') {
            Write-Host "VideoPilot readiness: UP"
            Write-Host (($readiness.components.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value.status)" }) -join ', ')
            exit 0
        }
    } catch {
        # The application may still be starting; diagnostics below explain a final failure.
    }
    Start-Sleep -Seconds 3
}

Write-Warning "VideoPilot readiness did not become UP within $TimeoutSeconds seconds."
try {
    $diagnostics = Get-HealthResponse $diagnosticsUrl
    Write-Warning (($diagnostics.components.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value.status)" }) -join ', ')
} catch {
    docker compose -f $composeFile ps
    throw "Could not query dependency diagnostics at $diagnosticsUrl."
}
throw "VideoPilot is not ready. Check the DOWN dependency with 'docker compose logs <service>'."
