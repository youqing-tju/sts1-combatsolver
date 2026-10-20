#requires -version 5.1
<#
  Sync third-party jars required for compilation into lib/.
  Usage: powershell -File tools/sync-libs.ps1
  NOTE: keep this file ASCII-only (PowerShell 5.1 reads .ps1 as ANSI).
#>
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$lib  = Join-Path $root 'lib'
$localProps = Join-Path $root 'local.properties'

$stsHome = 'C:/Users/22757/steam/steamapps/common/SlayTheSpire'
$mtsJar  = 'C:/Users/22757/steam/steamapps/workshop/content/646570/1605060445/ModTheSpire.jar'

if (Test-Path $localProps) {
    Get-Content $localProps | ForEach-Object {
        if ($_ -match '^\s*sts\.home\s*=\s*(.+?)\s*$') { $stsHome = $Matches[1] }
        if ($_ -match '^\s*mts\.jar\s*=\s*(.+?)\s*$')  { $mtsJar  = $Matches[1] }
    }
}

if (-not (Test-Path $lib)) { New-Item -ItemType Directory -Path $lib -Force | Out-Null }

$gameJar = Join-Path $stsHome 'desktop-1.0.jar'
if (-not (Test-Path $gameJar)) { throw "Game jar not found: $gameJar (check sts.home in local.properties)" }
if (-not (Test-Path $mtsJar))  { throw "ModTheSpire.jar not found: $mtsJar (check mts.jar in local.properties)" }

Copy-Item $gameJar (Join-Path $lib 'desktop-1.0.jar') -Force
Copy-Item $mtsJar  (Join-Path $lib 'ModTheSpire.jar') -Force

Write-Host "[sync-libs] desktop-1.0.jar  <- $gameJar"
Write-Host "[sync-libs] ModTheSpire.jar  <- $mtsJar"
Write-Host "[sync-libs] OK"
