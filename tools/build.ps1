#requires -version 5.1
<#
  Build the STS1 Combat Solver ModTheSpire mod.
  Usage:
    powershell -File tools/build.ps1 -Smoke
    powershell -File tools/build.ps1 -Configuration Release
  NOTE: keep this file ASCII-only (PowerShell 5.1 reads .ps1 as ANSI).
#>
param(
    [ValidateSet('Debug','Release')] [string]$Configuration = 'Release',
    [switch]$Smoke
)

$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$lib     = Join-Path $root 'lib'
$srcRoot = Join-Path $root 'src/main/java'
$resRoot = Join-Path $root 'src/main/resources'
$outDir  = Join-Path $root 'build'
$classes = Join-Path $outDir 'classes'

$localProps = Join-Path $root 'local.properties'
$modId = 'sts1combatsolver'
$modVersion = '0.0.1'
if (Test-Path $localProps) {
    Get-Content $localProps | ForEach-Object {
        if ($_ -match '^\s*mod\.id\s*=\s*(.+?)\s*$')      { $modId = $Matches[1] }
        if ($_ -match '^\s*mod\.version\s*=\s*(.+?)\s*$') { $modVersion = $Matches[1] }
    }
}

# ---- dependencies --------------------------------------------------------
if (-not (Test-Path (Join-Path $lib 'desktop-1.0.jar')) -or
    -not (Test-Path (Join-Path $lib 'ModTheSpire.jar'))) {
    Write-Host '[build] lib/ missing deps, running sync-libs.ps1'
    & (Join-Path $PSScriptRoot 'sync-libs.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'sync-libs failed' }
}

$javacCmd = (Get-Command javac.exe -ErrorAction SilentlyContinue)
if (-not $javacCmd) { throw 'javac.exe not found, check JDK on PATH' }
$jdkHome = Split-Path -Parent (Split-Path -Parent $javacCmd.Source)
$jarExe  = Join-Path $jdkHome 'bin/jar.exe'

$cp = (Join-Path $lib 'desktop-1.0.jar') + ';' + (Join-Path $lib 'ModTheSpire.jar')

if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

# ---- smoke: verify toolchain and classpath only --------------------------
if ($Smoke) {
    Write-Host '[build] SMOKE: JDK / --release 8 / classpath'
    $probeOut = Join-Path $outDir 'probe'
    if (Test-Path $probeOut) { Remove-Item $probeOut -Recurse -Force }
    New-Item -ItemType Directory -Path $probeOut -Force | Out-Null

    & $javacCmd.Source --release 8 -encoding UTF-8 -Xlint:-options -cp $cp -d $probeOut (Join-Path $PSScriptRoot 'probe/Probe.java')
    if ($LASTEXITCODE -ne 0) { throw 'probe compile failed' }

    Push-Location $root
    try { & java -cp "$probeOut;$cp" Probe } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw 'probe run failed' }
    Write-Host '[build] SMOKE OK'
    exit 0
}

# ---- real build ---------------------------------------------------------
$sources = @()
if (Test-Path $srcRoot) {
    $sources = Get-ChildItem -Path $srcRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }
}
if ($sources.Count -eq 0) {
    Write-Host '[build] no sources under src/main/java (expected before M0)'
    Write-Host '[build] use -Smoke to verify the build chain'
    exit 0
}

if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory -Path $classes -Force | Out-Null

$debug = @()
if ($Configuration -eq 'Debug') { $debug = @('-g') }

Write-Host "[build] compiling $($sources.Count) sources -> $classes"
& $javacCmd.Source --release 8 -encoding UTF-8 -Xlint:-options @debug -cp $cp -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

if (Test-Path $resRoot) {
    Copy-Item (Join-Path $resRoot '*') $classes -Recurse -Force
}

$jarPath = Join-Path $outDir 'STS1CombatSolver.jar'
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
& $jarExe --create --file $jarPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar packaging failed' }

Write-Host "[build] OK -> $jarPath (modid=$modId version=$modVersion)"
