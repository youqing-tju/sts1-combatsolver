#requires -version 5.1
<#
  Copy the built jar into the game's mods/ directory.
  Usage: powershell -File tools/install.ps1
  NOTE: keep this file ASCII-only (PowerShell 5.1 reads .ps1 as ANSI).
#>
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$jar  = Join-Path $root 'build/libs/STS1CombatSolver.jar'

$stsHome = 'C:/Users/22757/steam/steamapps/common/SlayTheSpire'
$localProps = Join-Path $root 'local.properties'
if (Test-Path $localProps) {
    Get-Content $localProps | ForEach-Object {
        if ($_ -match '^\s*sts\.home\s*=\s*(.+?)\s*$') { $stsHome = $Matches[1] }
    }
}

if (-not (Test-Path $jar)) { throw "Jar not found: $jar (run the build first)" }
$mods = Join-Path $stsHome 'mods'
if (-not (Test-Path $mods)) { throw "mods dir not found: $mods" }

Copy-Item $jar (Join-Path $mods 'STS1CombatSolver.jar') -Force
Write-Host "[install] $jar -> $mods\STS1CombatSolver.jar"
Write-Host "[install] Launch: start Steam, then run MTS-launch.cmd in the game folder"
