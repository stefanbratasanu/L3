# db-set-access.ps1 — Grant (or revoke) GM access on a character, straight in the DB.
#
# Why a script: to use the in-game //changelvl command you must ALREADY be a GM, which is a
# chicken-and-egg problem for the very first admin character. This sets it directly instead.
#
# Access levels come from dist/game/config/AccessLevels.xml:
#     0 = User (normal player)      70  = Admin   (isGM)
#    30 = General GM                100 = Master  (isGM, inherits everything) <-- default here
#
# IMPORTANT: run this while the character is LOGGED OUT. Player saves include accesslevel
# (UPDATE_CHARACTER in Player.java), so if the character is online its in-memory level gets
# written back on logout/auto-save and silently overwrites whatever we set here.
#
# Usage:
#   .\db-set-access.ps1 -Name admin                # make 'admin' a Master (level 100)
#   .\db-set-access.ps1 -Name admin -Level 0       # back to a normal player
#   .\db-set-access.ps1 -List                      # show all characters and their access level

param(
  [string]$Name,
  [int]$Level       = 100,
  [switch]$List,
  [string]$Database = 'l2jmobiusinterlude',
  [int]$Port        = $(if ($env:L3_DB_PORT) { [int]$env:L3_DB_PORT } else { 3306 }),
  [string]$MariaDir = $env:L3_MARIADB
)

$ErrorActionPreference = 'Stop'

if (-not $MariaDir) { $MariaDir = 'C:\Agentic\tools\mariadb' }
$client = Join-Path $MariaDir 'bin\mariadb.exe'
if (-not (Test-Path $client)) { throw "mariadb.exe not found at $client (is the toolchain installed?)" }

$common = @('--no-defaults', '--host=127.0.0.1', "--port=$Port", '--user=root', '--skip-ssl', $Database)

if ($List) {
  Write-Host "Characters and access levels:" -ForegroundColor Cyan
  & $client @common --execute="SELECT char_name, accesslevel, online FROM characters ORDER BY accesslevel DESC, char_name;"
  exit $LASTEXITCODE
}

if (-not $Name) { throw "Specify -Name <character> (or -List to see all characters)." }

# Keep it simple and injection-proof: L2 character names are alphanumeric.
if ($Name -notmatch '^[A-Za-z0-9_]{1,35}$') { throw "Refusing unusual character name '$Name' (expected letters/digits/underscore)." }

# Warn if that character is currently flagged online — the change would likely be overwritten.
$onlineCheck = (& $client @common --skip-column-names --batch --execute="SELECT online FROM characters WHERE char_name='$Name';") 2>$null
if ($LASTEXITCODE -ne 0) { throw "Could not query the database. Is MariaDB running (db-start.ps1)?" }

if ([string]::IsNullOrWhiteSpace($onlineCheck)) {
  Write-Host "Character '$Name' does not exist in $Database." -ForegroundColor Red
  Write-Host "Create it in the client first (log in, make the character), then re-run this." -ForegroundColor Yellow
  exit 1
}

if ($onlineCheck.Trim() -ne '0') {
  Write-Host "WARNING: '$Name' is flagged ONLINE." -ForegroundColor Yellow
  Write-Host "  Log that character out first, or its save will overwrite this change." -ForegroundColor Yellow
}

Write-Host "Setting access level of '$Name' to $Level ..." -ForegroundColor Cyan
& $client @common --execute="UPDATE characters SET accesslevel=$Level WHERE char_name='$Name';"
if ($LASTEXITCODE -ne 0) { throw "UPDATE failed (exit $LASTEXITCODE)." }

$now = (& $client @common --skip-column-names --batch --execute="SELECT accesslevel FROM characters WHERE char_name='$Name';")
Write-Host "  '$Name' accesslevel is now $($now.Trim())." -ForegroundColor Green
Write-Host ""
if ($Level -ge 70) {
  Write-Host "Log in as '$Name' (relog if already in-game) and // commands will work, e.g.:" -ForegroundColor Gray
  Write-Host "  //l3spawn        spawn an L3 puppet next to you" -ForegroundColor Gray
  Write-Host "  //l3spawn clean  remove the puppets again" -ForegroundColor Gray
} else {
  Write-Host "'$Name' is now a normal player (no GM commands)." -ForegroundColor Gray
}

# Guard against L3-run.ps1 step 4 silently undoing this. It re-restores the snapshot whenever the
# snapshot file's hash differs from .last-restored-hash — and the snapshot still holds the OLD
# access level, so a change made while the servers are down can be wiped on the next launch.
$snapshot = Join-Path $PSScriptRoot 'dist\db_snapshot\l2jmobiusinterlude.sql'
$stamp    = Join-Path $PSScriptRoot 'dist\db_snapshot\.last-restored-hash'
if (Test-Path $snapshot) {
  $curHash  = (Get-FileHash $snapshot -Algorithm SHA256).Hash
  $lastHash = if (Test-Path $stamp) { (Get-Content $stamp -Raw).Trim() } else { '' }
  if ($curHash -ne $lastHash) {
    Write-Host ""
    Write-Host "HEADS UP: the next L3-run launch will restore the DB snapshot, which still has the" -ForegroundColor Yellow
    Write-Host "old access level and would undo this change. Either:" -ForegroundColor Yellow
    Write-Host "  * run  .\db-dump.ps1  now, so the snapshot carries this change, or" -ForegroundColor Yellow
    Write-Host "  * make this change while the servers are already running (character logged out)." -ForegroundColor Yellow
  }
}
