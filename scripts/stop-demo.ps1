<#
.SYNOPSIS
  Stops what start-demo.ps1 started.
.PARAMETER KeepInfra
  Leave Postgres and Redis containers running.
#>
param([switch]$KeepInfra)

$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
$PidFile = Join-Path $env:TEMP 'tether-demo-pids.json'

if (Test-Path $PidFile) {
    foreach ($entry in (Get-Content -LiteralPath $PidFile -Raw | ConvertFrom-Json)) {
        Write-Host "stopping $($entry.name) (window pid $($entry.pid))"
        cmd /c "taskkill /PID $($entry.pid) /T /F >nul 2>&1"
    }
    Remove-Item -LiteralPath $PidFile -Force
} else {
    Write-Host 'No record of a previous run. Checking the demo ports instead.'
}

# Fallback: anything still listening on the demo ports (for example a service started by hand)
foreach ($port in 8081, 8082, 8083, 3000) {
    $conns = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
        Write-Host "stopping process $($c.OwningProcess) still listening on :$port"
        cmd /c "taskkill /PID $($c.OwningProcess) /T /F >nul 2>&1"
    }
}

if (-not $KeepInfra) {
    Set-Location -LiteralPath $Root
    docker compose stop postgres redis
}
Write-Host 'Done.'
