<#
.SYNOPSIS
  Starts the whole Tether stack on localhost and opens the app in the browser.

.DESCRIPTION
  Postgres + Redis (Docker), then auth-service (8081), incident-service (8082), sync-service (8083)
  and the frontend (3000), each in its own PowerShell window. Safe to run twice: a service that is
  already healthy is reused.

.PARAMETER Reset
  Wipes the Postgres data first (docker compose down -v) so the demo starts from an empty database.

.PARAMETER NoBrowser
  Do not open the browser at the end.

.EXAMPLE
  .\scripts\start-demo.ps1
  .\scripts\start-demo.ps1 -Reset
#>
param(
    [switch]$Reset,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$PidFile = Join-Path $env:TEMP 'tether-demo-pids.json'
$Started = @()

function Write-Step([string]$Text) { Write-Host "`n== $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text)   { Write-Host "  OK  $Text" -ForegroundColor Green }
function Write-Warn([string]$Text) { Write-Host "  !!  $Text" -ForegroundColor Yellow }
function Fail([string]$Text)       { Write-Host "`nSTOPPED: $Text" -ForegroundColor Red; exit 1 }

function Test-PortOpen([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect('localhost', $Port, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne(400) -and $client.Connected) { return $true }
        return $false
    } catch { return $false } finally { $client.Close() }
}

function Test-Http([string]$Url) {
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
        return ($r.StatusCode -ge 200 -and $r.StatusCode -lt 300)
    } catch { return $false }
}

function Wait-Http([string]$Url, [string]$Name, [int]$TimeoutSec = 300) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    Write-Host -NoNewline "  waiting for $Name "
    while ((Get-Date) -lt $deadline) {
        if (Test-Http $Url) { Write-Host ' ready' -ForegroundColor Green; return }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 2
    }
    Write-Host ''
    Fail "$Name did not become ready within $TimeoutSec s. Look at its window for the error."
}

function Wait-Container([string]$Name, [int]$TimeoutSec = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    Write-Host -NoNewline "  waiting for $Name "
    while ((Get-Date) -lt $deadline) {
        $status = (cmd /c "docker inspect -f {{.State.Health.Status}} $Name 2>nul" | Out-String).Trim()
        if ($status -eq 'healthy') { Write-Host ' healthy' -ForegroundColor Green; return }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 2
    }
    Write-Host ''
    Fail "$Name did not become healthy. Run 'docker compose logs' in the repo root."
}

function Start-Window([string]$Title, [string]$Dir, [string]$Command) {
    $script = "`$Host.UI.RawUI.WindowTitle = '$Title'; Set-Location -LiteralPath '$Dir'; $Command"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
    return Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoExit', '-NoProfile', '-EncodedCommand', $encoded -PassThru
}

function Start-TetherService([string]$Name, [int]$Port, [string]$Dir, [string]$Command, [string]$HealthUrl) {
    if (Test-PortOpen $Port) {
        if (Test-Http $HealthUrl) { Write-Ok "$Name already running on :$Port, reusing it"; return }
        Fail "Port $Port is taken by something that is not $Name. Close it (see: netstat -ano | findstr :$Port) and run again."
    }
    Write-Host "  starting $Name on :$Port (first run downloads dependencies, can take a few minutes)"
    $proc = Start-Window "Tether $Name" $Dir $Command
    $script:Started += [pscustomobject]@{ name = $Name; pid = $proc.Id }
    Wait-Http $HealthUrl $Name
}

# ---------------------------------------------------------------- prerequisites
Write-Step 'Checking prerequisites'

$javaVersionLine = (cmd /c 'java -version 2>&1' | Out-String)
$javaExit = $LASTEXITCODE
$javaMajor = 0
if ($javaExit -eq 0 -and $javaVersionLine -match 'version "(\d+)') { $javaMajor = [int]$Matches[1] }
if ($javaMajor -eq 0) { Fail 'Java was not found. Install a JDK 17 and reopen the terminal.' }
if ($javaMajor -lt 17) { Fail "Java $javaMajor found, but the services need Java 17 or newer." }
Write-Ok "Java $javaMajor"

if ($env:JAVA_HOME -and -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Write-Warn "JAVA_HOME points to a folder that does not exist ($env:JAVA_HOME). Ignoring it for this run."
    Remove-Item Env:JAVA_HOME
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Fail 'Node.js was not found. Install Node 18 or newer.' }
Write-Ok "Node $((cmd /c 'node --version' | Out-String).Trim())"

cmd /c 'docker info >nul 2>&1'
if ($LASTEXITCODE -ne 0) { Fail 'Docker is not running. Start Docker Desktop, wait until it says "running", then try again.' }
Write-Ok 'Docker is running'

# ---------------------------------------------------------------- infrastructure
Write-Step 'Starting Postgres and Redis'
Set-Location -LiteralPath $Root

if ($Reset) {
    Write-Warn 'Reset requested: deleting the demo database'
    docker compose down -v
}

$pgRunning = (cmd /c 'docker ps --filter name=incidentsync-postgres --format {{.Names}}' | Out-String).Trim()
if (-not $pgRunning -and (Test-PortOpen 5432)) {
    Fail 'Port 5432 is used by a local Postgres. Stop that service (services.msc), or use it by creating user/db "incidentsync", then run again.'
}
$redisRunning = (cmd /c 'docker ps --filter name=incidentsync-redis --format {{.Names}}' | Out-String).Trim()
if (-not $redisRunning -and (Test-PortOpen 6379)) {
    Fail 'Port 6379 is used by a local Redis. Stop it, then run again.'
}

docker compose up -d postgres redis
if ($LASTEXITCODE -ne 0) { Fail 'docker compose could not start Postgres and Redis.' }
Wait-Container 'incidentsync-postgres'
Wait-Container 'incidentsync-redis'

# ---------------------------------------------------------------- services
Write-Step 'Starting the backend services (in this order: auth, incident, sync)'
Start-TetherService 'auth-service'     8081 (Join-Path $Root 'auth-service')     '.\mvnw.cmd spring-boot:run' 'http://localhost:8081/actuator/health'
Start-TetherService 'incident-service' 8082 (Join-Path $Root 'incident-service') '.\mvnw.cmd spring-boot:run' 'http://localhost:8082/actuator/health'
Start-TetherService 'sync-service'     8083 (Join-Path $Root 'sync-service')     '.\mvnw.cmd spring-boot:run' 'http://localhost:8083/health'

# ---------------------------------------------------------------- frontend
Write-Step 'Starting the frontend'
$frontend = Join-Path $Root 'frontend'
if (-not (Test-Path (Join-Path $frontend 'node_modules'))) {
    Write-Host '  installing frontend packages (first run only)'
    Push-Location $frontend
    cmd /c 'npm install'
    $npmExit = $LASTEXITCODE
    Pop-Location
    if ($npmExit -ne 0) { Fail 'npm install failed.' }
}
if (Test-PortOpen 3000) {
    if (Test-Http 'http://localhost:3000') { Write-Ok 'frontend already running on :3000, reusing it' }
    else { Fail 'Port 3000 is taken by something else. Close it and run again.' }
} else {
    $proc = Start-Window 'Tether frontend' $frontend 'npm run dev'
    $Started += [pscustomobject]@{ name = 'frontend'; pid = $proc.Id }
    Wait-Http 'http://localhost:3000' 'frontend' 120
}

if ($Started.Count -gt 0) {
    # Remember what this run started, so stop-demo.ps1 can close exactly those windows
    $Started | ConvertTo-Json | Set-Content -LiteralPath $PidFile
}

# ---------------------------------------------------------------- done
Write-Step 'Everything is up'
Write-Host @'

  App (open this)      http://localhost:3000
  Auth API docs        http://localhost:8081/swagger-ui.html
  Sync metrics         http://localhost:8083/actuator/prometheus

  Two-responder demo (about 3 minutes):
   1. Tab 1: Create account > New organization > e.g. Alice, Acme Corp. Note the JOIN CODE in the header.
   2. Tab 1: Declare "Checkout API returning 500s", SEV2.
   3. Tab 2: Create account > Join with code > e.g. Bob + the code. Open the same incident.
   4. Both tabs say "2 responders active". Type in one tab, it appears in the other.
   5. Change status/owner in one tab. The other updates and the timeline shows who did it.
   6. Outage drill: close the "Tether sync-service" window. The badge turns Offline, keep typing,
      reload (notes persist). Start sync-service again (or run this script again) and the other tab receives everything.

  Stop everything later with:  .\scripts\stop-demo.ps1

'@

if (-not $NoBrowser) {
    Start-Process 'http://localhost:3000'
    Start-Sleep -Seconds 2
    Start-Process 'http://localhost:3000'   # second tab = second responder (each tab keeps its own login)
}
