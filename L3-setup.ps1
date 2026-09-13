# =============================================================================
#  L3-setup.ps1 — FIRST-TIME toolchain bootstrap for the test machine.
# =============================================================================
#  The repo does NOT contain the JDK or MariaDB (too big, machine-specific, and
#  gitignored). This script fetches portable copies into C:\Agentic\tools so the
#  test box can RUN the servers with no installer and no admin rights.
#
#  You only need this once per machine. L3-run.ps1 calls it automatically if the
#  tools are missing, but you can also run it by hand:  powershell -File L3-setup.ps1
#
#  Downloads (portable zips, no install):
#    - Temurin JDK 25 (to run the Java servers)
#    - MariaDB 11.4 LTS (portable, to run the database)
#
#  NOTE: git itself is assumed present (you cloned the repo). If you cloned with a
#  portable MinGit, point env.ps1's $L3_GIT at it; otherwise system git is fine.
# =============================================================================

param(
  [string]$ToolsDir = 'C:\Agentic\tools'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
$tmp = Join-Path $env:TEMP 'l3-setup'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Fetch([string]$url, [string]$outFile) {
  Write-Host "  downloading $url" -ForegroundColor DarkGray
  Invoke-WebRequest -Uri $url -OutFile $outFile -UseBasicParsing
}

# --- JDK 25 (Temurin, portable zip) ------------------------------------------
$jdkDir = Join-Path $ToolsDir 'jdk-25'
if (Test-Path (Join-Path $jdkDir 'bin\java.exe')) {
  Write-Host "JDK 25 already present at $jdkDir" -ForegroundColor Green
} else {
  Write-Host "Installing portable JDK 25 ..." -ForegroundColor Cyan
  # Adoptium "latest" redirector for JDK 25 GA, Windows x64 zip.
  $jdkUrl = 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'
  $jdkZip = Join-Path $tmp 'jdk25.zip'
  Fetch $jdkUrl $jdkZip
  $extract = Join-Path $tmp 'jdk-extract'
  if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
  Expand-Archive -Path $jdkZip -DestinationPath $extract -Force
  # The zip contains a single top-level folder like jdk-25.0.x+y; move its contents to jdk-25.
  $inner = Get-ChildItem $extract -Directory | Select-Object -First 1
  New-Item -ItemType Directory -Force -Path $jdkDir | Out-Null
  Copy-Item (Join-Path $inner.FullName '*') $jdkDir -Recurse -Force
  Write-Host "  JDK 25 -> $jdkDir" -ForegroundColor Green
}

# --- MariaDB 11.4 LTS (portable zip) -----------------------------------------
$mariaDir = Join-Path $ToolsDir 'mariadb'
if (Test-Path (Join-Path $mariaDir 'bin\mariadbd.exe')) {
  Write-Host "MariaDB already present at $mariaDir" -ForegroundColor Green
} else {
  Write-Host "Installing portable MariaDB 11.4 ..." -ForegroundColor Cyan
  # Pinned to the version verified on the build box. If this URL 404s (mirror rotation),
  # grab the current 'winx64.zip' from https://mariadb.org/download/ and extract to $mariaDir.
  $mariaUrl = 'https://archive.mariadb.org/mariadb-11.4.13/winx64-packages/mariadb-11.4.13-winx64.zip'
  $mariaZip = Join-Path $tmp 'mariadb.zip'
  Fetch $mariaUrl $mariaZip
  $extract = Join-Path $tmp 'maria-extract'
  if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
  Expand-Archive -Path $mariaZip -DestinationPath $extract -Force
  $inner = Get-ChildItem $extract -Directory | Select-Object -First 1
  New-Item -ItemType Directory -Force -Path $mariaDir | Out-Null
  Copy-Item (Join-Path $inner.FullName '*') $mariaDir -Recurse -Force
  Write-Host "  MariaDB -> $mariaDir" -ForegroundColor Green
}

Write-Host "`nToolchain ready under $ToolsDir." -ForegroundColor Green
Write-Host "You can now run L3-run.bat (or .\L3-run.ps1)." -ForegroundColor Cyan
