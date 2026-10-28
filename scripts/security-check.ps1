[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path

Push-Location "$root/server"
try {
    & .\mvnw.cmd -B verify -Psecurity
    if ($LASTEXITCODE -ne 0) { throw "OWASP Dependency-Check failed." }
} finally {
    Pop-Location
}

Push-Location "$root/client"
try {
    npm.cmd audit --audit-level=high --registry=https://registry.npmjs.org
    if ($LASTEXITCODE -ne 0) { throw "npm audit found a high or critical vulnerability." }
} finally {
    Pop-Location
}

if (Get-Command gitleaks -ErrorAction SilentlyContinue) {
    gitleaks detect --source $root --redact
} else {
    $gitleaksImage = $env:GITLEAKS_IMAGE
    if (-not $gitleaksImage) {
        throw "Gitleaks is required locally; install it or set GITLEAKS_IMAGE to an approved mirror."
    }
    docker run --rm -v "${root}:/repo" $gitleaksImage detect --source=/repo --redact
}
if ($LASTEXITCODE -ne 0) { throw "Gitleaks detected a secret." }
