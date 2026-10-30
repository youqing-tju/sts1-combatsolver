#requires -version 5.1
<#
  Summarize (and optionally follow) the solver log for a real-machine run.

  Usage:
    powershell -File tools\watch-solver.ps1                 # summarize today's log
    powershell -File tools\watch-solver.ps1 -Follow         # live tail (Ctrl+C to stop)
    powershell -File tools\watch-solver.ps1 -Date 2026-10-27

  Exit code: 0 when the log has no ERROR line, 2 when it has.
  NOTE: keep this file ASCII-only (PowerShell 5.1 reads .ps1 as ANSI).
#>
param(
    [string]$Date = (Get-Date -Format 'yyyy-MM-dd'),
    [string]$GameRoot = 'C:/Users/22757/steam/steamapps/common/SlayTheSpire',
    [switch]$Follow
)

$ErrorActionPreference = 'Stop'
$log = Join-Path $GameRoot ("solver-logs/sts1-combatsolver-" + $Date + ".log")

if (-not (Test-Path $log)) {
    Write-Host "[watch] no log for $Date -> $log"
    Write-Host "[watch] (the game writes it as soon as a combat is captured)"
    exit 1
}

if ($Follow) {
    Write-Host "[watch] following $log   (Ctrl+C to stop)"
    Get-Content $log -Wait -Tail 40 | ForEach-Object {
        if ($_ -match 'ERROR|WARN|MISMATCH|ActionRejected|Exception') {
            Write-Host $_ -ForegroundColor Yellow
        } else {
            Write-Host $_
        }
    }
    exit 0
}

$lines = Get-Content $log
$err    = @($lines | Where-Object { $_ -match '\[ERROR\]' })
$warn   = @($lines | Where-Object { $_ -match '\[WARN\]' })
# 'DIFF <card> MATCH  (a/b)' / 'DIFF <card> MISMATCH n field(s)' -- the bare
# 'DIFF SUMMARY a match / b mismatch' line must NOT be counted as an action diff.
$match  = @($lines | Where-Object { $_ -match 'DIFF \S+ MATCH  \(' })
$mism   = @($lines | Where-Object { $_ -match 'DIFF \S+ MISMATCH \d+ field' })
$summ   = @($lines | Where-Object { $_ -match 'DIFF SUMMARY (\d+) match / (\d+) mismatch' })
$reject = @($lines | Where-Object { $_ -match 'ActionRejected' })
$combat = @($lines | Where-Object { $_ -match 'panel sees combat start' })
$plans  = @($lines | Where-Object { $_ -match 'PLAN ready: \d+ steps.*? (\d+) ms' })

Write-Host ''
Write-Host "==== solver log $Date ===="
Write-Host ("  file      : {0}" -f $log)
Write-Host ("  lines     : {0}" -f $lines.Count)
Write-Host ("  combats   : {0}" -f $combat.Count)
Write-Host ("  plans     : {0}" -f $plans.Count)
if ($plans.Count -gt 0) {
    $ms = @()
    foreach ($p in $plans) {
        if ($p -match ' (\d+) ms') { $ms += [int]$Matches[1] }
    }
    $avg = [int](($ms | Measure-Object -Average).Average)
    $max = ($ms | Measure-Object -Maximum).Maximum
    Write-Host ("  plan ms   : avg {0} / max {1}" -f $avg, $max)
}
# ---- search shape: beam width portfolio + novelty exploration (2026-10-30) ----
$port = @($lines | Where-Object { $_ -match 'PORTFOLIO .*adopted=(\S+)' })
$nov  = @($lines | Where-Object { $_ -match 'NOVELTY stop=(\w+)' })
$slow = @($lines | Where-Object { $_ -match 'SEARCH slow:' })
if ($port.Count -gt 0) {
    $adopted = @{}
    foreach ($p in $port) {
        if ($p -match 'adopted=(\S+)') {
            $k = $Matches[1]
            if ($adopted.ContainsKey($k)) { $adopted[$k] = $adopted[$k] + 1 } else { $adopted[$k] = 1 }
        }
    }
    $parts = @()
    $adopted.GetEnumerator() | Sort-Object -Property Value -Descending | ForEach-Object {
        $parts += ("{0} x{1}" -f $_.Key, $_.Value)
    }
    Write-Host ("  portfolio : {0} runs -> {1}" -f $port.Count, ($parts -join ', '))
}
if ($nov.Count -gt 0) {
    $stops = @{}
    foreach ($n in $nov) {
        if ($n -match 'NOVELTY stop=(\w+)') {
            $k = $Matches[1]
            if ($stops.ContainsKey($k)) { $stops[$k] = $stops[$k] + 1 } else { $stops[$k] = 1 }
        }
    }
    $parts = @()
    $stops.GetEnumerator() | Sort-Object -Property Value -Descending | ForEach-Object {
        $parts += ("{0} x{1}" -f $_.Key, $_.Value)
    }
    Write-Host ("  novelty   : {0} runs -> {1}" -f $nov.Count, ($parts -join ', '))
}
Write-Host ("  slow(>2s) : {0}" -f $slow.Count)

$total = $match.Count + $mism.Count
if ($total -gt 0) {
    $rate = [math]::Round(100.0 * $match.Count / $total, 2)
    Write-Host ("  diff      : MATCH {0} / MISMATCH {1}  ({2}% match)" -f $match.Count, $mism.Count, $rate)
} else {
    Write-Host "  diff      : (no DIFF lines)"
}
if ($summ.Count -gt 0) {
    Write-Host ("  summaries : {0} (last: {1})" -f $summ.Count, ($summ[-1] -replace '^.*DIFF SUMMARY ', ''))
}
Write-Host ("  rejected  : {0}" -f $reject.Count)
Write-Host ("  warnings  : {0}" -f $warn.Count)
Write-Host ("  errors    : {0}" -f $err.Count)

if ($mism.Count -gt 0) {
    Write-Host ''
    Write-Host '---- first 10 mismatches ----'
    $mism | Select-Object -First 10 | ForEach-Object { Write-Host ('  ' + $_) }
}

if ($reject.Count -gt 0) {
    Write-Host ''
    Write-Host '---- distinct rejection reasons ----'
    $reasons = @{}
    foreach ($r in $reject) {
        $idx = $r.IndexOf('ActionRejected')
        $tail = $r.Substring($idx)
        if ($tail.Length -gt 90) { $tail = $tail.Substring(0, 90) }
        if ($reasons.ContainsKey($tail)) { $reasons[$tail] = $reasons[$tail] + 1 }
        else { $reasons[$tail] = 1 }
    }
    $reasons.GetEnumerator() | Sort-Object -Property Value -Descending | ForEach-Object {
        Write-Host ("  {0,5}x  {1}" -f $_.Value, $_.Key)
    }
}

if ($err.Count -gt 0) {
    Write-Host ''
    Write-Host '---- first 3 errors ----'
    $err | Select-Object -First 3 | ForEach-Object { Write-Host ('  ' + $_) }
    Write-Host ''
    Write-Host '[watch] RESULT: FAILED (log has ERROR lines)'
    exit 2
}

Write-Host ''
Write-Host '[watch] RESULT: OK (no ERROR lines)'
exit 0
