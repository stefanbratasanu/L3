# db-start.ps1 — Start MariaDB as a PORTABLE foreground process (no Windows service, no admin).
#
# The UAC prompt some people hit with MariaDB comes from the *service installer*
# (mysql_upgrade_service / service registration). Running the daemon directly against
# a local data dir needs no elevation at all. This script does exactly that.
#
# Usage:
#   . .\env.ps1          # sets $L3_MARIADB, $L3_DB_DATA, $L3_DB_PORT
#   .\db-start.ps1       # starts in the background, returns; use db-stop.ps1 to stop
#   .\db-start.ps1 -Foreground   # runs in this console (Ctrl+C to stop)
#
# First-time only: if the data dir is empty, it is initialized automatically.

param(
  [switch]$Foreground,
  [string]$DataDir = $env:L3_DB_DATA,
  [int]$Port       = $(if ($env:L3_DB_PORT) { [int]$env:L3_DB_PORT } else { 3306 }),
  [string]$MariaDir = $env:L3_MARIADB
)

if (-not $DataDir)  { $DataDir  = 'C:\Agentic\data\mariadb' }
if (-not $MariaDir) { $MariaDir = 'C:\Agentic\tools\mariadb' }

$mariadbd    = Join-Path $MariaDir 'bin\mariadbd.exe'
$installDb   = Join-Path $MariaDir 'bin\mariadb-install-db.exe'
if (-not (Test-Path $mariadbd)) { throw "mariadbd.exe not found at $mariadbd" }

# Initialize data dir on first run (idempotent: only if 'mysql' system DB is absent)
if (-not (Test-Path (Join-Path $DataDir 'mysql'))) {
  Write-Host "Initializing MariaDB data dir at $DataDir ..." -ForegroundColor Yellow
  New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
  & $installDb "--datadir=$DataDir" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "mariadb-install-db failed (exit $LASTEXITCODE)" }
  Write-Host "Data dir initialized." -ForegroundColor Green
}

# Warn if something is already listening on the port
$inUse = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($inUse) { Write-Host "WARNING: port $Port already has a listener (PID $($inUse.OwningProcess)). MariaDB may already be running." -ForegroundColor Yellow }

# --skip-grant-tables = local-trust auth (fine for a dev box; the server connects as root/no-password).
$mariaArgs = @(
  '--no-defaults',
  "--datadir=$DataDir",
  "--port=$Port",
  '--bind-address=127.0.0.1',
  '--skip-grant-tables',
  '--console'
)

if ($Foreground) {
  Write-Host "Starting mariadbd in FOREGROUND on 127.0.0.1:$Port (Ctrl+C to stop)..." -ForegroundColor Green
  & $mariadbd @mariaArgs
} else {
  $logDir = Join-Path $DataDir '..\mariadb-logs'
  New-Item -ItemType Directory -Force -Path $logDir | Out-Null
  $out = Join-Path $logDir 'mariadbd.out.log'
  $err = Join-Path $logDir 'mariadbd.err.log'
  $proc = Start-Process -FilePath $mariadbd -ArgumentList $mariaArgs -PassThru -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err
  Start-Sleep -Seconds 5
  if ($proc.HasExited) {
    Write-Host "mariadbd EXITED early (code $($proc.ExitCode)). Last errors:" -ForegroundColor Red
    Get-Content $err -ErrorAction SilentlyContinue | Select-Object -Last 20 | ForEach-Object { Write-Host "  $_" }
    throw "MariaDB failed to start."
  }
  Write-Host "MariaDB running (PID $($proc.Id)) on 127.0.0.1:$Port." -ForegroundColor Green
  Write-Host "  data: $DataDir"
  Write-Host "  logs: $out"
  Write-Host "Stop it with:  .\db-stop.ps1"
  $proc.Id | Out-File (Join-Path $logDir 'mariadbd.pid') -Encoding ascii
}
