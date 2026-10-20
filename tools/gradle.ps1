#requires -version 5.1
<#
  Locate the isolated Gradle install and forward all arguments to it.
  Usage: powershell -File tools/gradle.ps1 build
  NOTE: keep this file ASCII-only (PowerShell 5.1 reads .ps1 as ANSI).
#>
$ErrorActionPreference = 'Stop'

$candidates = @(
    'C:/Users/22757/.workbuddy/binaries/gradle/gradle-9.8.2/bin/gradle.bat'
)

$gradle = $null
foreach ($c in $candidates) {
    if (Test-Path $c) { $gradle = $c; break }
}
if (-not $gradle) {
    $onPath = Get-Command gradle.bat -ErrorAction SilentlyContinue
    if ($onPath) { $gradle = $onPath.Source }
}
if (-not $gradle) {
    throw 'Gradle not found. Install it or use the project wrapper: .\gradlew.bat build'
}

& $gradle @args
exit $LASTEXITCODE
