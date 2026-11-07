[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFile = Join-Path $root '.env'
$database = 'videopilot_m4_gate'
if ($database -ne 'videopilot_m4_gate') { throw 'Refusing to reset an unexpected database.' }

if (-not (Test-Path $envFile)) { throw 'The repository .env file is required for local integration settings.' }
$settings = @{}
foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
    $name, $value = $line -split '=', 2
    $settings[$name.Trim()] = $value.Trim().Trim('"').Trim("'")
    [Environment]::SetEnvironmentVariable($name.Trim(), $settings[$name.Trim()], 'Process')
}
if ([string]::IsNullOrWhiteSpace($settings['MYSQL_ROOT_PASSWORD'])) {
    throw 'MYSQL_ROOT_PASSWORD is required to create the isolated M4 gate database.'
}
$databaseUser = $settings['MYSQL_USER']
if ($databaseUser -notmatch '^[A-Za-z0-9_]+$') {
    throw 'MYSQL_USER must contain only letters, numbers, and underscores.'
}

$env:MYSQL_HOST = '127.0.0.1'
$env:MYSQL_DATABASE = $database
$env:REDIS_HOST = '127.0.0.1'
$env:RABBITMQ_HOST = '127.0.0.1'
$env:MINIO_ENDPOINT = "http://127.0.0.1:$($settings['MINIO_API_PORT'])"
$env:MINIO_PUBLIC_ENDPOINT = $env:MINIO_ENDPOINT
$env:QDRANT_BASE_URL = "http://127.0.0.1:$($settings['QDRANT_HTTP_PORT'])"
$env:OCR_ENABLED = 'false'
$env:ASR_ENABLED = 'false'
$env:IDENTITY_SECURE_COOKIE = 'true'

Push-Location $root
try {
    docker compose exec -T mysql mysql -uroot "-p$($settings['MYSQL_ROOT_PASSWORD'])" `
        -e "DROP DATABASE IF EXISTS $database; CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON $database.* TO '$databaseUser'@'%';"
    if ($LASTEXITCODE -ne 0) { throw 'Could not reset the isolated M4 gate database.' }
} finally {
    Pop-Location
}

function Invoke-Gate {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string[]]$Command
    )

    Write-Host "M4 gate: $Name"
    Push-Location $WorkingDirectory
    try {
        $executable = $Command[0]
        $arguments = $Command[1..($Command.Length - 1)]
        & $executable @arguments
        if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
}

Invoke-Gate -Name 'provider, budget, pool and illegal-transition boundaries' -WorkingDirectory "$root/server" -Command @(
    '.\mvnw.cmd', '-q',
    '-Dtest=JobStateMachineTest,ExecutionBudgetPolicyTest,SiliconFlowLanguageModelAdapterTest,SiliconFlowEmbeddingAdapterTest,AnalysisJobExecutionHandlerTest',
    'test'
)

Invoke-Gate -Name '20-sample recovery, concurrency, Redis, RabbitMQ, Outbox and Qdrant matrix' -WorkingDirectory "$root/server" -Command @(
    '.\mvnw.cmd', '-q', 'test-compile', 'failsafe:integration-test', 'failsafe:verify',
    '-Dit.test=M4RecoveryReleaseGateIT,AnalysisJobCreationIT,AnalysisJobPersistenceAdapterIT,ApplicationRestartRecoveryIT,AnalysisMessageConsumerIT,OutboxRelayIT,AsrRecoveryIT,OcrRecoveryIT,QdrantVectorIndexAdapterIT'
)

Write-Host 'M4 reliability gate passed.'
