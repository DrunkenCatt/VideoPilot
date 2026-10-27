$ErrorActionPreference = 'Stop'

function Assert-Version {
    param(
        [string]$Name,
        [string]$Actual,
        [version]$Minimum,
        [string]$Requirement
    )

    $parsed = [version]$Actual
    if ($parsed -lt $Minimum) {
        throw "$Name $Actual is below the required $Requirement."
    }
    Write-Host "$Name $Actual (required $Requirement)"
}

$javaExecutable = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    (Get-Command java -ErrorAction Stop).Source
}
if (-not (Test-Path -LiteralPath $javaExecutable)) {
    throw "Java executable not found at $javaExecutable. Check JAVA_HOME."
}
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersionOutput = (& $javaExecutable -version 2>&1 | Out-String)
$ErrorActionPreference = $previousPreference
$javaMatch = [regex]::Match($javaVersionOutput, 'version "(?<version>[0-9]+(?:\.[0-9]+){0,2})')
if (-not $javaMatch.Success) {
    throw "Unable to determine Java version from java -version."
}
Assert-Version -Name 'Java' -Actual $javaMatch.Groups['version'].Value -Minimum ([version]'21.0.0') -Requirement '21.x'

$nodeVersion = (& node --version).Trim().TrimStart('v')
Assert-Version -Name 'Node.js' -Actual $nodeVersion -Minimum ([version]'22.12.0') -Requirement '22.12+'

docker compose version | Out-Host

Push-Location (Join-Path $PSScriptRoot '..\server')
try {
    .\mvnw.cmd -version | Out-Host
    .\mvnw.cmd validate | Out-Host
}
finally {
    Pop-Location
}

Write-Host 'VideoPilot toolchain check passed.'
