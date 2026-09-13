# =============================================================================
#  L3-run.ps1 — ONE-CLICK run for the TEST machine.
# =============================================================================
#  What it does, in order:
#    1. git pull            -> get the latest code + DB snapshot from the build box
#    2. ensure toolchain    -> JDK (to run) + MariaDB present (bootstraps if missing)
#    3. start MariaDB        (portable, no admin, no service)
#    4. restore DB snapshot  (first run / whenever the pulled snapshot changed)
#    5. ensure jars          (use committed jars; build from source only if absent)
#    6. start LoginServer + GameServer  (their own GUI windows)
#    7. WAIT until you close both servers
#    8. on close: dump DB -> stop MariaDB -> git commit + push the snapshot
#       so the build box stays in sync with whatever you did while testing.
#
#  You normally launch this by double-clicking  L3-run.bat  (which just calls this).
#  Nothing here needs administrator rights. If Windows shows a UAC/SmartScreen
#  prompt for java.exe, it is your endpoint-security agent inspecting an unknown
#  portable exe, NOT a real elevation requirement — you can decline it safely.
# =============================================================================

param(
  [switch]$NoPull,          # skip the git pull (offline / local iteration)
  [switch]$NoCommit,        # skip the on-close DB commit+push
  [switch]$NoPush,          # commit the DB snapshot locally but do not push
  [switch]$FreshDb,         # drop & reload the DB from the snapshot before starting
  [string]$Database = 'l2jmobiusinterlude'
)

$ErrorActionPreference = 'Stop'
$repoRoot   = $PSScriptRoot                      # C:\Agentic\L3
$serverDir  = Join-Path $repoRoot 'server'
$envScript  = Join-Path $repoRoot 'env.ps1'
$snapshot   = Join-Path $serverDir 'dist\db_snapshot\l2jmobiusinterlude.sql'

function Say([string]$m, [string]$c = 'Gray') { Write-Host $m -ForegroundColor $c }
function Section([string]$m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

# --- Load the portable toolchain env (JAVA_HOME, MariaDB, git on PATH) ----------
if (-not (Test-Path $envScript)) { throw "env.ps1 not found at $envScript (are you in the L3 repo root?)" }
. $envScript

# Resolve git: portable MinGit dir from env.ps1 if present (build box), else
# whatever `git` is on PATH (test box installed Git normally). Fail loud if neither.
if ($L3_GIT -and (Test-Path (Join-Path $L3_GIT 'git.exe'))) {
  $git = Join-Path $L3_GIT 'git.exe'
} else {
  $gitCmd = Get-Command git -ErrorAction SilentlyContinue
  if (-not $gitCmd) { throw "git not found (no portable MinGit under C:\Agentic\tools, and 'git' is not on PATH). Install Git for Windows: https://git-scm.com/download/win" }
  $git = $gitCmd.Source
}
$javaExe  = Join-Path $JAVA_HOME 'bin\java.exe'
$setup    = Join-Path $repoRoot 'L3-setup.ps1'

# -----------------------------------------------------------------------------
# 1. git pull
# -----------------------------------------------------------------------------
Section '1/8  Pulling latest from GitHub'
if ($NoPull) {
  Say '  -NoPull set; skipping.' 'DarkYellow'
} else {
  Push-Location $repoRoot
  try {
    $hasRemote = (& $git remote) | Where-Object { $_ -eq 'origin' }
    if (-not $hasRemote) {
      Say '  No "origin" remote configured yet — skipping pull (nothing to pull from).' 'DarkYellow'
    } else {
      # --ff-only keeps history linear; if it fails, the two sides diverged and a human should look.
      & $git pull --ff-only origin (& $git rev-parse --abbrev-ref HEAD)
      if ($LASTEXITCODE -ne 0) {
        Say '  git pull --ff-only failed (histories diverged). Resolve manually, then re-run.' 'Red'
        Say '  Starting anyway with the code you have locally.' 'DarkYellow'
      }
    }
  } finally { Pop-Location }
}

# -----------------------------------------------------------------------------
# 2. ensure toolchain (JDK to run + MariaDB)
# -----------------------------------------------------------------------------
Section '2/8  Checking toolchain'
$needSetup = -not (Test-Path $javaExe) -or -not (Test-Path (Join-Path $L3_MARIADB 'bin\mariadbd.exe'))
if ($needSetup) {
  if (Test-Path $setup) {
    Say '  Toolchain missing — running L3-setup.ps1 to fetch portable JDK + MariaDB...' 'Yellow'
    & powershell -ExecutionPolicy Bypass -File $setup
    . $envScript
  } else {
    throw "Toolchain missing and L3-setup.ps1 not found. Cannot continue."
  }
} else {
  Say "  java : $javaExe" 'Green'
  Say "  db   : $(Join-Path $L3_MARIADB 'bin\mariadbd.exe')" 'Green'
}

# -----------------------------------------------------------------------------
# 3. start MariaDB (portable)
# -----------------------------------------------------------------------------
Section '3/8  Starting MariaDB'
& (Join-Path $serverDir 'db-start.ps1')

# -----------------------------------------------------------------------------
# 4. restore DB snapshot (first run, -FreshDb, or snapshot changed since last restore)
# -----------------------------------------------------------------------------
Section '4/8  Restoring database'
$client   = Join-Path $L3_MARIADB 'bin\mariadb.exe'
$common   = @('--no-defaults','--host=127.0.0.1',"--port=$L3_DB_PORT",'--user=root','--skip-ssl')
$stamp    = Join-Path $serverDir 'dist\db_snapshot\.last-restored-hash'

function Db-Empty {
  $q = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database';"
  $n = (& $client @common --batch --skip-column-names --execute=$q) 2>$null
  return ([int]($n | Select-Object -First 1) -eq 0)
}

if (Test-Path $snapshot) {
  $curHash  = (Get-FileHash $snapshot -Algorithm SHA256).Hash
  $lastHash = if (Test-Path $stamp) { (Get-Content $stamp -Raw).Trim() } else { '' }
  if ($FreshDb -or (Db-Empty) -or ($curHash -ne $lastHash)) {
    Say '  Restoring snapshot into the DB...' 'Yellow'
    & (Join-Path $serverDir 'db-restore.ps1') -Database $Database -Fresh:$FreshDb
    Set-Content -Path $stamp -Value $curHash -Encoding ascii
  } else {
    Say '  DB already matches the current snapshot — no restore needed.' 'Green'
  }
} else {
  Say '  No snapshot in repo yet — loading the stock Mobius schema instead.' 'Yellow'
  & (Join-Path $serverDir 'db-load-schema.ps1') -Database $Database
}

# -----------------------------------------------------------------------------
# 5. ensure jars (committed jars preferred; build from source only if missing)
# -----------------------------------------------------------------------------
Section '5/8  Preparing server jars'
$distLibs = Join-Path $serverDir 'dist\libs'
$loginJar = Join-Path $distLibs 'LoginServer.jar'
$gameJar  = Join-Path $distLibs 'GameServer.jar'
if ((Test-Path $loginJar) -and (Test-Path $gameJar)) {
  Say '  Using committed jars in server\dist\libs.' 'Green'
} else {
  $ant = Join-Path $ANT_HOME 'bin\ant.bat'
  if (Test-Path $ant) {
    Say '  Jars not committed — building from source with Ant...' 'Yellow'
    Push-Location $serverDir
    try { & $ant jar } finally { Pop-Location }
    Copy-Item (Join-Path $repoRoot 'build\dist\libs\LoginServer.jar') $loginJar -Force
    Copy-Item (Join-Path $repoRoot 'build\dist\libs\GameServer.jar')  $gameJar  -Force
  } else {
    throw "No jars and no Ant to build them. Commit the jars on the build box, or install Ant here."
  }
}

# -----------------------------------------------------------------------------
# 6. start LoginServer + GameServer (each in its own window/JVM)
# -----------------------------------------------------------------------------
Section '6/8  Launching servers'
function Start-Node([string]$name, [string]$sub, [string]$jar) {
  $dir = Join-Path $serverDir "dist\$sub"
  $cfg = (Get-Content (Join-Path $dir 'java.cfg') -Raw).Trim()
  $args = @($cfg -split '\s+') + @('-jar', "..\libs\$jar")
  Say "  starting $name ..." 'Green'
  # A normal (visible) window so you can watch/close it. Closing the window ends the JVM.
  return Start-Process -FilePath $javaExe -ArgumentList $args -WorkingDirectory $dir -PassThru
}
$loginProc = Start-Node 'LoginServer' 'login' 'LoginServer.jar'
Start-Sleep -Seconds 8    # let login bind :9014 before the game server dials in
$gameProc  = Start-Node 'GameServer'  'game'  'GameServer.jar'

Say "`n  LoginServer PID $($loginProc.Id)   GameServer PID $($gameProc.Id)" 'Cyan'
Say '  Servers are starting. Point the client at this machine, log in, and test.' 'Cyan'
Say '  >>> To finish: type  .sd  in game (GM), or close either server window. <<<' 'Yellow'
Say '      Either way this script then dumps + commits the DB.' 'Yellow'

# -----------------------------------------------------------------------------
# 7. wait for the session to end
# -----------------------------------------------------------------------------
# We wait for EITHER process to exit, then stop the other. That way one action ends the whole
# session: an in-game `.sd` (which only shuts down the game server), or closing either window.
# Waiting for both in sequence would hang forever after a `.sd`, because the login server would
# still be running with nobody left to close it.
Section '7/8  Running — waiting for shutdown (.sd in game, or close a server window)'
while (-not $loginProc.HasExited -and -not $gameProc.HasExited) {
  Start-Sleep -Seconds 2
}

$first = if ($gameProc.HasExited) { 'GameServer' } else { 'LoginServer' }
Say "  $first stopped - shutting the other one down too." 'Yellow'

foreach ($p in @($gameProc, $loginProc)) {
  if ($p.HasExited) { continue }
  # Ask politely first (Mobius closes its window cleanly), then insist.
  try { $null = $p.CloseMainWindow() } catch { }
  if (-not $p.WaitForExit(20000)) {
    Say "  PID $($p.Id) did not exit in 20s - terminating it." 'DarkYellow'
    try { $p.Kill() } catch { }
    try { $null = $p.WaitForExit(10000) } catch { }
  }
}
Say '  Both servers have stopped.' 'Green'

# -----------------------------------------------------------------------------
# 8. dump DB, stop MariaDB, commit + push the snapshot
# -----------------------------------------------------------------------------
Section '8/8  Syncing database + logs back to git'
try {
  & (Join-Path $serverDir 'db-dump.ps1') -Database $Database
  # record the hash we just dumped so the next start doesn't needlessly re-restore
  if (Test-Path $snapshot) {
    Set-Content -Path $stamp -Value (Get-FileHash $snapshot -Algorithm SHA256).Hash -Encoding ascii
  }
} catch {
  Say "  DB dump failed: $_" 'Red'
}

# stop MariaDB now that we've dumped
& (Join-Path $serverDir 'db-stop.ps1')

# ---------------------------------------------------------------------------
# Collect the server logs so the build box can read what actually happened.
# The live log dirs are gitignored (they churn constantly and hold lock files);
# we copy the useful files into test-logs/, which IS tracked.
# ---------------------------------------------------------------------------
$logRoot = Join-Path $repoRoot 'test-logs'
foreach ($node in @(@{ src = 'dist\game\log'; dst = 'game' }, @{ src = 'dist\login\log'; dst = 'login' })) {
  $src = Join-Path $serverDir $node.src
  $dst = Join-Path $logRoot   $node.dst
  if (-not (Test-Path $src)) { continue }
  New-Item -ItemType Directory -Force -Path $dst | Out-Null
  # Clear previous copies first, so a log that disappeared doesn't linger forever in git.
  foreach ($old in @(Get-ChildItem -LiteralPath $dst -File -ErrorAction SilentlyContinue)) {
    [System.IO.File]::Delete($old.FullName)
  }
  # Only real log files, and only non-empty ones. This deliberately matches the .gitignore
  # negations for test-logs/, so everything we copy is also something git will track.
  $keepExt = @('.log', '.csv', '.txt')
  foreach ($f in @(Get-ChildItem -LiteralPath $src -File | Where-Object { ($_.Length -gt 0) -and ($keepExt -contains $_.Extension) })) {
    Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $dst $f.Name) -Force
  }
}
Say "  logs collected into test-logs\ ." 'Green'

if ($NoCommit) {
  Say '  -NoCommit set; leaving the snapshot uncommitted.' 'DarkYellow'
} else {
  Push-Location $repoRoot
  try {
    $syncPaths = @('server/dist/db_snapshot/l2jmobiusinterlude.sql', 'test-logs')
    & $git add -- $syncPaths
    $changed = (& $git status --porcelain -- $syncPaths)
    if ($changed) {
      $msg = "DB + logs from test run $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
      & $git commit -m $msg | Out-Null
      Say "  committed: $msg" 'Green'
      $hasRemote = (& $git remote) | Where-Object { $_ -eq 'origin' }
      if ($NoPush) {
        Say '  -NoPush set; not pushing.' 'DarkYellow'
      } elseif (-not $hasRemote) {
        Say '  No "origin" remote; committed locally only.' 'DarkYellow'
      } else {
        & $git push origin (& $git rev-parse --abbrev-ref HEAD)
        if ($LASTEXITCODE -eq 0) { Say '  pushed to origin.' 'Green' }
        else { Say '  push failed (commit is saved locally; push later).' 'Red' }
      }
    } else {
      Say '  DB unchanged since last sync — nothing to commit.' 'Green'
    }
  } finally { Pop-Location }
}

Section 'Done'
Say 'You can close this window.' 'Cyan'
