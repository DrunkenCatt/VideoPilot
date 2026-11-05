[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$VideoPath,
    [string]$ApiBaseUrl = 'http://localhost:8080',
    [string]$Goal = 'Build a traceable teaching-video timeline.',
    [int]$TimeoutMinutes = 30
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$resolvedVideo = (Resolve-Path $VideoPath).Path
$envFile = Join-Path $repoRoot '.env'

if (-not (Test-Path $envFile)) {
    throw 'The repository .env file is required for local benchmark credentials.'
}

$settings = @{}
foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
    $name, $value = $line -split '=', 2
    $settings[$name.Trim()] = $value.Trim().Trim('"').Trim("'")
}

$username = $settings['IDENTITY_BOOTSTRAP_USERNAME']
$password = $settings['IDENTITY_BOOTSTRAP_PASSWORD']
if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
    throw 'IDENTITY_BOOTSTRAP_USERNAME and IDENTITY_BOOTSTRAP_PASSWORD must be configured.'
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$Method,
        [object]$Body,
        [hashtable]$Headers = @{}
    )

    $arguments = @{
        Uri = $Uri
        Method = $Method
        WebSession = $script:webSession
        Headers = $Headers
        ContentType = 'application/json'
    }
    if ($null -ne $Body) {
        $jsonBody = $Body | ConvertTo-Json -Depth 8 -Compress
        $arguments['Body'] = $jsonBody
    }
    try {
        Invoke-RestMethod @arguments
    } catch {
        $detail = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        throw "HTTP $Method $Uri failed: $detail"
    }
}

function Wait-Until {
    param(
        [Parameter(Mandatory)][scriptblock]$Read,
        [Parameter(Mandatory)][scriptblock]$Done,
        [Parameter(Mandatory)][string]$Description,
        [int]$PollSeconds = 2
    )

    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    do {
        $value = & $Read
        if (& $Done $value) { return $value }
        Start-Sleep -Seconds $PollSeconds
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Description."
}

$script:webSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$startedAt = Get-Date
$session = Invoke-JsonRequest -Uri "$ApiBaseUrl/api/v1/sessions" -Method Post -Body @{
    username = $username
    password = $password
}
$csrfHeaders = @{ 'X-CSRF-Token' = $session.csrfToken; Accept = 'application/json' }

$file = Get-Item $resolvedVideo
$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedVideo).Hash.ToLowerInvariant()
$upload = Invoke-JsonRequest -Uri "$ApiBaseUrl/api/v1/media-uploads" -Method Post -Headers $csrfHeaders -Body @{
    originalFilename = $file.Name
    contentLength = $file.Length
    sha256 = $sha256
    contentType = 'video/mp4'
}

$completedParts = [System.Collections.Generic.List[object]]::new()
$stream = [System.IO.File]::OpenRead($resolvedVideo)
$httpClient = [System.Net.Http.HttpClient]::new()
try {
    $partNumber = 1
    $buffer = [byte[]]::new([int]$upload.partSizeBytes)
    while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
        $part = Invoke-JsonRequest -Uri "$ApiBaseUrl$($upload.partsUrl)" -Method Post -Headers $csrfHeaders -Body @{
            partNumber = $partNumber
        }
        $payload = if ($read -eq $buffer.Length) { $buffer } else { $buffer[0..($read - 1)] }
        $response = $null
        $content = [System.Net.Http.ByteArrayContent]::new($payload)
        $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream')
        try {
            $response = $httpClient.PutAsync($part.uploadUrl, $content).GetAwaiter().GetResult()
            $response.EnsureSuccessStatusCode() | Out-Null
            $etag = $response.Headers.ETag.Tag
        } finally {
            $content.Dispose()
            if ($null -ne $response) { $response.Dispose() }
        }
        if ([string]::IsNullOrWhiteSpace($etag)) { throw "Part $partNumber did not return an ETag." }
        $completedParts.Add(@{ partNumber = $partNumber; etag = $etag })
        $partNumber++
    }
} finally {
    $stream.Dispose()
    $httpClient.Dispose()
}

$media = Invoke-JsonRequest -Uri "$ApiBaseUrl$($upload.completionUrl)" -Method Post -Headers $csrfHeaders -Body @{
    parts = $completedParts
}
$media = Wait-Until -Description 'media validation' -Read {
    Invoke-JsonRequest -Uri "$ApiBaseUrl/api/v1/media-assets/$($media.mediaId)" -Method Get
} -Done { param($candidate) $candidate.status -in @('READY', 'FAILED') }
if ($media.status -ne 'READY') {
    throw "Media validation failed with code $($media.failure.code)."
}

$jobSubmittedAt = Get-Date
$job = Invoke-JsonRequest -Uri "$ApiBaseUrl/api/v1/analysis-jobs" -Method Post -Headers ($csrfHeaders + @{
    'Idempotency-Key' = "m2-t7-$([guid]::NewGuid().ToString('N'))"
}) -Body @{
    mediaId = $media.mediaId
    goal = $Goal
    mode = 'STANDARD'
}
$job = Wait-Until -Description 'analysis job terminal state' -PollSeconds 3 -Read {
    Invoke-JsonRequest -Uri "$ApiBaseUrl$($job.links.self)" -Method Get
} -Done { param($candidate) $candidate.status -in @('READY', 'FAILED', 'CANCELED') }

[ordered]@{
    mediaId = $media.mediaId
    jobId = $job.jobId
    mediaDurationMs = $media.durationMs
    uploadPartCount = $completedParts.Count
    finalStatus = $job.status
    finalStage = $job.currentStage
    failureCode = $job.failure.code
    totalElapsedSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
    analysisElapsedSeconds = [math]::Round(((Get-Date) - $jobSubmittedAt).TotalSeconds, 1)
} | ConvertTo-Json
