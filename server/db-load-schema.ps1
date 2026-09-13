# db-load-schema.ps1 — Load the STOCK Mobius schema into MariaDB, unmodified.
#
# Unlike the embedded-H2 path, MariaDB accepts the stock .sql files as-is (that is
# why we chose it). This loads every file under db_installer/sql/{login,game} into
# the target database and reports any failures.
#
# Usage:
#   . .\env.ps1
#   .\db-start.ps1
#   .\db-load-schema.ps1                # loads into l2jmobiusinterlude
#   .\db-load-schema.ps1 -Fresh         # drops & recreates the DB first

param(
  [string]$Database = 'l2jmobiusinterlude',
  [switch]$Fresh,
  [string]$SqlRoot  = (Join-Path $PSScriptRoot 'dist\db_installer\sql'),
  [int]$Port        = $(if ($env:L3_DB_PORT) { [int]$env:L3_DB_PORT } else { 3306 }),
  [string]$MariaDir = $env:L3_MARIADB
)

if (-not $MariaDir) { $MariaDir = 'C:\Agentic\tools\mariadb' }
$client = Join-Path $MariaDir 'bin\mariadb.exe'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$tmp = Join-Path $env:TEMP 'l3_schema_load'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Invoke-Sql([string]$sqlText, [string]$db) {
  $sf = Join-Path $tmp ("stmt_{0}.sql" -f ([guid]::NewGuid().ToString('N')))
  [System.IO.File]::WriteAllText($sf, $sqlText, $utf8NoBom)
  $dbArg = @(); if ($db) { $dbArg = @("--database=$db") }
  $of = "$sf.out"; $ef = "$sf.err"
  $p = Start-Process -FilePath $client -ArgumentList (@("--no-defaults","--host=127.0.0.1","--port=$Port","--user=root","--skip-ssl","--batch") + $dbArg) `
       -RedirectStandardInput $sf -NoNewWindow -Wait -PassThru -RedirectStandardOutput $of -RedirectStandardError $ef
  $err = Get-Content $ef -Raw -ErrorAction SilentlyContinue
  Remove-Item $sf,$of,$ef -Force -ErrorAction SilentlyContinue
  return [pscustomobject]@{ Code = $p.ExitCode; Err = $err }
}

function Invoke-SqlFile([string]$path, [string]$db) {
  # Use `source` semantics by piping the file directly.
  $of = "$path.load.out"; $ef = "$path.load.err"
  $p = Start-Process -FilePath $client -ArgumentList @("--no-defaults","--host=127.0.0.1","--port=$Port","--user=root","--skip-ssl","--batch","--database=$db") `
       -RedirectStandardInput $path -NoNewWindow -Wait -PassThru -RedirectStandardOutput $of -RedirectStandardError $ef
  $err = Get-Content $ef -Raw -ErrorAction SilentlyContinue
  Remove-Item $of,$ef -Force -ErrorAction SilentlyContinue
  return [pscustomobject]@{ Code = $p.ExitCode; Err = $err }
}

if ($Fresh) {
  Write-Host "Dropping & recreating $Database ..." -ForegroundColor Yellow
  $r = Invoke-Sql "DROP DATABASE IF EXISTS `$Database`; CREATE DATABASE `$Database` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" $null
  # (backticks above are PS escapes; the DB name is a bareword, safe here)
  if ($r.Code -ne 0) { Write-Host "  drop/create failed: $($r.Err)" -ForegroundColor Red }
}

# Ensure DB exists
Invoke-Sql "CREATE DATABASE IF NOT EXISTS $Database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" $null | Out-Null

$groups = @('login','game')
$total = 0; $fails = @()
foreach ($g in $groups) {
  $dir = Join-Path $SqlRoot $g
  if (-not (Test-Path $dir)) { Write-Host "SKIP missing: $dir"; continue }
  $files = Get-ChildItem -Path $dir -Filter '*.sql' | Sort-Object Name
  Write-Host "Loading $($files.Count) files from $g ..." -ForegroundColor Cyan
  foreach ($f in $files) {
    $total++
    $r = Invoke-SqlFile $f.FullName $Database
    if ($r.Code -ne 0) {
      $firstLine = (($r.Err -split "`n") | Where-Object { $_ -match 'ERROR' } | Select-Object -First 1)
      if (-not $firstLine) { $firstLine = ($r.Err -split "`n" | Select-Object -First 1) }
      $fails += [pscustomobject]@{ Group=$g; File=$f.Name; Err=$firstLine }
    }
  }
}

Write-Host "`n================ SCHEMA LOAD SUMMARY ================"
Write-Host "Files processed: $total    Failed: $($fails.Count)"
foreach ($ff in $fails) { Write-Host ("[FAIL] {0}/{1}`n       {2}" -f $ff.Group,$ff.File,$ff.Err) -ForegroundColor Red }
if ($fails.Count -eq 0) { Write-Host "ALL SCHEMA FILES LOADED CLEANLY." -ForegroundColor Green }

# Table count
$tc = Invoke-Sql "SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema='$Database';" $null
Write-Host "`n--- Tables in $Database ---"
Write-Host $tc.Err.Trim()
$r2 = Invoke-Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database';" $Database
