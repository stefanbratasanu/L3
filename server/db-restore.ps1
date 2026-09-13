# db-restore.ps1 — Restore the git-tracked SQL snapshot into MariaDB.
#
# The mirror of db-dump.ps1. The one-click runner calls this at startup on the test
# box (after `git pull`) so the freshly-pulled character/agent state becomes live.
# The snapshot uses `CREATE DATABASE ... ; USE ...;` (from --databases) so it recreates
# the DB itself; -Fresh additionally drops it first for a guaranteed-clean restore.
#
# Usage:
#   . .\env.ps1
#   .\db-restore.ps1                 # restore dist\db_snapshot\l2jmobiusinterlude.sql
#   .\db-restore.ps1 -Fresh          # drop the DB first, then restore

param(
  [string]$Database = 'l2jmobiusinterlude',
  [switch]$Fresh,
  [int]$Port        = $(if ($env:L3_DB_PORT) { [int]$env:L3_DB_PORT } else { 3306 }),
  [string]$MariaDir = $env:L3_MARIADB,
  [string]$InFile   = (Join-Path $PSScriptRoot 'dist\db_snapshot\l2jmobiusinterlude.sql')
)

if (-not $MariaDir) { $MariaDir = 'C:\Agentic\tools\mariadb' }
$client = Join-Path $MariaDir 'bin\mariadb.exe'
if (-not (Test-Path $client)) { throw "mariadb.exe not found at $client" }
if (-not (Test-Path $InFile))  { throw "snapshot not found at $InFile" }

$common = @('--no-defaults','--host=127.0.0.1',"--port=$Port",'--user=root','--skip-ssl')

if ($Fresh) {
  Write-Host "Dropping $Database before restore ..." -ForegroundColor Yellow
  & $client @common --execute="DROP DATABASE IF EXISTS $Database;"
}

Write-Host "Restoring $Database from $InFile ..." -ForegroundColor Cyan
# Pipe the file into the client (proven-clean form). The snapshot's own
# CREATE DATABASE / USE statements (from --databases) select the target DB.
Get-Content -LiteralPath $InFile | & $client @common
if ($LASTEXITCODE -ne 0) {
  Write-Host "  RESTORE FAILED (exit $LASTEXITCODE)." -ForegroundColor Red
  throw "restore failed."
}
Write-Host "  Restored." -ForegroundColor Green
