# db-dump.ps1 — Dump the live MariaDB into a single git-friendly SQL snapshot.
#
# This is how the database travels between the build box and the test box: NOT the
# binary data dir (huge, unmergeable), but one logical .sql file that git can diff.
# The one-click runner calls this on server close so the test box's characters /
# items / agent state get committed and stay in sync with the build box.
#
# Usage:
#   . .\env.ps1
#   .\db-dump.ps1                 # dump l2jmobiusinterlude -> dist\db_snapshot\l2jmobiusinterlude.sql
#   .\db-dump.ps1 -Database foo   # dump a different DB

param(
  [string]$Database = 'l2jmobiusinterlude',
  [int]$Port        = $(if ($env:L3_DB_PORT) { [int]$env:L3_DB_PORT } else { 3306 }),
  [string]$MariaDir = $env:L3_MARIADB,
  [string]$OutFile  = (Join-Path $PSScriptRoot 'dist\db_snapshot\l2jmobiusinterlude.sql')
)

if (-not $MariaDir) { $MariaDir = 'C:\Agentic\tools\mariadb' }
$dump = Join-Path $MariaDir 'bin\mariadb-dump.exe'
if (-not (Test-Path $dump)) { throw "mariadb-dump.exe not found at $dump" }

$outDir = Split-Path $OutFile -Parent
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# --single-transaction: consistent snapshot without locking (InnoDB).
# --skip-dump-date: omit the "Dump completed on <timestamp>" trailer so an unchanged
#                   DB produces a byte-identical file -> no spurious git diffs.
# --hex-blob: safe round-trip of binary columns as hex literals.
# --routines --events --triggers: capture everything, not just table data.
$dumpArgs = @(
  '--no-defaults', '--host=127.0.0.1', "--port=$Port", '--user=root', '--skip-ssl',
  '--single-transaction', '--skip-dump-date', '--hex-blob',
  '--routines', '--events', '--triggers',
  '--databases', $Database
)

$err = "$OutFile.err"
Write-Host "Dumping $Database -> $OutFile ..." -ForegroundColor Cyan
# Dump to a temp file first, then move into place, so a failed dump never clobbers a good snapshot.
$tmpOut = "$OutFile.tmp"
$proc = Start-Process -FilePath $dump -ArgumentList $dumpArgs -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $tmpOut -RedirectStandardError $err
$errText = Get-Content $err -Raw -ErrorAction SilentlyContinue
Remove-Item $err -Force -ErrorAction SilentlyContinue

if ($proc.ExitCode -ne 0) {
  Write-Host "  DUMP FAILED (exit $($proc.ExitCode)): $errText" -ForegroundColor Red
  if (Test-Path $tmpOut) { Remove-Item $tmpOut -Force -ErrorAction SilentlyContinue }
  throw "mariadb-dump failed."
}

Move-Item $tmpOut $OutFile -Force
$size = [math]::Round((Get-Item $OutFile).Length / 1KB, 1)
Write-Host "  Wrote $size KB." -ForegroundColor Green
if ($errText) { Write-Host "  (warnings) $errText" -ForegroundColor DarkYellow }
