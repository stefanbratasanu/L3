# db-stop.ps1 — Cleanly stop the portable MariaDB started by db-start.ps1.
#
# Prefers a graceful shutdown via mariadb-admin; falls back to stopping the PID.

param(
  [string]$DataDir  = $env:L3_DB_DATA,
  [int]$Port        = $(if ($env:L3_DB_PORT) { [int]$env:L3_DB_PORT } else { 3306 }),
  [string]$MariaDir = $env:L3_MARIADB
)

if (-not $DataDir)  { $DataDir  = 'C:\Agentic\data\mariadb' }
if (-not $MariaDir) { $MariaDir = 'C:\Agentic\tools\mariadb' }

$admin = Join-Path $MariaDir 'bin\mariadb-admin.exe'

# Try graceful shutdown first (root, no password, local trust).
if (Test-Path $admin) {
  Write-Host "Requesting graceful shutdown via mariadb-admin ..."
  & $admin "--no-defaults" "--host=127.0.0.1" "--port=$Port" "--user=root" "--skip-ssl" shutdown 2>$null
  Start-Sleep -Seconds 3
}

# Verify / fall back to PID stop.
$logDir = Join-Path $DataDir '..\mariadb-logs'
$pidFile = Join-Path $logDir 'mariadbd.pid'
$stillListening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($stillListening) {
  Write-Host "Still listening; stopping process directly."
  $pids = @()
  if (Test-Path $pidFile) { $pids += (Get-Content $pidFile | Select-Object -First 1) }
  $pids += (Get-Process mariadbd -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
  foreach ($procId in ($pids | Sort-Object -Unique)) {
    if ($procId) { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue; Write-Host "  stopped PID $procId" }
  }
  Start-Sleep -Seconds 2
}

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
  Write-Host "WARNING: something is still listening on port $Port." -ForegroundColor Yellow
} else {
  Write-Host "MariaDB stopped." -ForegroundColor Green
}
if (Test-Path $pidFile) { Remove-Item $pidFile -Force -ErrorAction SilentlyContinue }
