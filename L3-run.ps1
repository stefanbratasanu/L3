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
#  To run one side only:  L3-run-login.bat / L3-run-game.bat, or -LoginOnly / -GameOnly.
#  The game server is the fast-iteration one: it recompiles dist\game\data\scripts at every
#  boot, so restarting it alone picks up new L3 agent/AI code.
#  Nothing here needs administrator rights. If Windows shows a UAC/SmartScreen
#  prompt for java.exe, it is your endpoint-security agent inspecting an unknown
#  portable exe, NOT a real elevation requirement — you can decline it safely.
# =============================================================================

param(
  [switch]$NoPull,          # skip the git pull (offline / local iteration)
  [switch]$NoCommit,        # skip the on-close DB commit+push
  [switch]$NoPush,          # commit the DB snapshot locally but do not push
  [switch]$FreshDb,         # drop & reload the DB from the snapshot before starting
  [switch]$LoginOnly,       # start ONLY the LoginServer
  [switch]$GameOnly,        # start ONLY the GameServer (fast iteration on L3 code)
  [string]$Database = 'l2jmobiusinterlude'
)

$ErrorActionPreference = 'Stop'

if ($LoginOnly -and $GameOnly) {
  throw "-LoginOnly and -GameOnly are mutually exclusive. Omit both to run the pair."
}
$runLogin = -not $GameOnly
$runGame  = -not $LoginOnly
$repoRoot   = $PSScriptRoot                      # C:\Agentic\L3
$serverDir  = Join-Path $repoRoot 'server'
$envScript  = Join-Path $repoRoot 'env.ps1'
$snapshot   = Join-Path $serverDir 'dist\db_snapshot\l2jmobiusinterlude.sql'

function Say([string]$m, [string]$c = 'Gray') { Write-Host $m -ForegroundColor $c }
function Section([string]$m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

# Record this console to test-logs/, which travels back to the build box with the server logs.
# Without it, everything PowerShell does here (DB restore, dump, commit, push, any failure) is
# invisible to anyone not sitting at this machine - which made one confusing session much harder to
# diagnose than it needed to be. Best-effort only: never fail a run over logging.
$runnerLog = Join-Path $repoRoot 'test-logs\runner.log'
function Stop-RunnerLog { try { Stop-Transcript | Out-Null } catch { } }
try {
  New-Item -ItemType Directory -Force -Path (Split-Path $runnerLog -Parent) | Out-Null
  Start-Transcript -Path $runnerLog -Force | Out-Null
} catch {
  Write-Host "(could not start the runner transcript: $_)" -ForegroundColor DarkYellow
}

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

# PowerShell parses this whole file into memory before running a line of it, so a `git pull` that
# updates L3-run.ps1 does NOT change the code currently executing - the new version would only take
# effect on the NEXT launch. That is a nasty trap: the pulled server-side scripts (compiled at boot)
# would be new while these steps stayed old. So we hash ourselves before and after the pull, and
# re-exec if we changed. See the restart block right after step 1.
$selfPath       = $PSCommandPath
$selfHashBefore = (Get-FileHash -LiteralPath $selfPath -Algorithm SHA256).Hash

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

# --- Did that pull update THIS script? Then re-exec so the new logic actually runs. -------------
# Without this, the server-side scripts (recompiled at boot) would be the new version while these
# steps ran the old one. That combination silently broke the shutdown sync once already: a pulled
# '.sd' shut down only the game server, while the still-old step 7 waited on the login server
# forever and never reached the commit.
if (-not $NoPull) {
  $selfHashAfter = (Get-FileHash -LiteralPath $selfPath -Algorithm SHA256).Hash
  if ($selfHashBefore -ne $selfHashAfter) {
    Say '  The pull updated L3-run.ps1 itself - restarting with the new version...' 'Yellow'

    # Rebuild the original invocation, and add -NoPull so the fresh run does not pull again.
    $relaunch = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $selfPath, '-NoPull')
    foreach ($kv in $PSBoundParameters.GetEnumerator()) {
      if ($kv.Key -eq 'NoPull') { continue }
      if ($kv.Value -is [switch]) {
        if ($kv.Value.IsPresent) { $relaunch += "-$($kv.Key)" }
      } else {
        $relaunch += @("-$($kv.Key)", [string]$kv.Value)
      }
    }

    # Hand the transcript over to the child, or both would fight over the same file.
    Stop-RunnerLog
    & powershell.exe $relaunch
    exit $LASTEXITCODE
  }
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
# Note whether the DB was ALREADY up. With -LoginOnly / -GameOnly you can have two of these
# scripts running at once, and the second one must not stop MariaDB (step 8) or reload the
# snapshot (step 4) underneath the first one's live server.
Section '3/8  Starting MariaDB'
$dbWasRunning = [bool](Get-NetTCPConnection -LocalPort $L3_DB_PORT -State Listen -ErrorAction SilentlyContinue)
if ($dbWasRunning) {
  Say "  MariaDB is already running on port $L3_DB_PORT - leaving it alone." 'Green'
} else {
  & (Join-Path $serverDir 'db-start.ps1')
}

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
  $dbEmpty  = Db-Empty
  if (-not ($FreshDb -or $dbEmpty -or ($curHash -ne $lastHash))) {
    Say '  DB already matches the current snapshot - no restore needed.' 'Green'
  } elseif ($dbWasRunning -and -not $dbEmpty -and -not $FreshDb) {
    # Refuse to reload under a possibly-live server: db-restore recreates the tables, which would
    # destroy whatever another running GameServer/LoginServer is using right now.
    Say '  Snapshot differs, but MariaDB was ALREADY running - not reloading it.' 'Yellow'
    Say '  Another L3-run instance may have a live server attached, and a reload would wipe it.' 'Yellow'
    Say '  Stop everything first, or pass -FreshDb to force the reload.' 'Yellow'
  } else {
    Say '  Restoring snapshot into the DB...' 'Yellow'
    & (Join-Path $serverDir 'db-restore.ps1') -Database $Database -Fresh:$FreshDb
    Set-Content -Path $stamp -Value $curHash -Encoding ascii
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
  # Not $args - that is an automatic variable inside a function, and assigning to it is asking
  # for trouble.
  $javaArgs = @($cfg -split '\s+') + @('-jar', "..\libs\$jar")
  Say "  starting $name ..." 'Green'
  # A normal (visible) window so you can watch/close it. Closing the window ends the JVM.
  return Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $dir -PassThru
}
# $nodes keeps only the servers this invocation actually started, so steps 7 and 8 behave the same
# whether you launched the pair, just the login server, or just the game server. Both are java.exe,
# so we carry our own label rather than relying on ProcessName.
$nodes = @()

if ($runLogin) {
  $nodes += [pscustomobject]@{ Name = 'LoginServer'; Proc = (Start-Node 'LoginServer' 'login' 'LoginServer.jar') }
  if ($runGame) {
    Start-Sleep -Seconds 8    # let login bind :9014 before the game server dials in
  }
}

if ($runGame) {
  $nodes += [pscustomobject]@{ Name = 'GameServer'; Proc = (Start-Node 'GameServer' 'game' 'GameServer.jar') }
}

Say ''
foreach ($n in $nodes) { Say "  $($n.Name) PID $($n.Proc.Id)" 'Cyan' }

if (-not $runGame) {
  Say '  LoginServer only. Clients can authenticate but there is no game world to enter.' 'Yellow'
  Say '  Start the game side in another window:  .\L3-run.ps1 -GameOnly' 'Yellow'
} elseif (-not $runLogin) {
  Say '  GameServer only. It needs a LoginServer to register with, or clients cannot log in.' 'Yellow'
  Say '  If one is not already running:  .\L3-run.ps1 -LoginOnly' 'Yellow'
} else {
  Say '  Servers are starting. Point the client at this machine, log in, and test.' 'Cyan'
}
Say '  >>> To finish: type  .sd  in game (GM), or close a server window. <<<' 'Yellow'
Say '      This script then dumps + commits the DB and logs.' 'Yellow'

# -----------------------------------------------------------------------------
# 7. wait for the session to end
# -----------------------------------------------------------------------------
# We wait for the FIRST of our processes to exit, then stop the rest. That way one action ends the
# session: an in-game `.sd` (which only shuts down the game server), or closing any server window.
# Waiting for them in sequence would hang forever after a `.sd`, because the login server would
# still be running with nobody left to close it.
Section '7/8  Running - waiting for shutdown (.sd in game, or close a server window)'
while (-not ($nodes | Where-Object { $_.Proc.HasExited })) {
  Start-Sleep -Seconds 2
}

$first = ($nodes | Where-Object { $_.Proc.HasExited } | Select-Object -First 1)
Say "  $($first.Name) (PID $($first.Proc.Id)) stopped." 'Yellow'
if ($nodes.Count -gt 1) { Say '  Shutting the other server down too.' 'Yellow' }

foreach ($p in ($nodes | ForEach-Object { $_.Proc })) {
  if ($p.HasExited) { continue }
  # Ask politely first (Mobius closes its window cleanly), then insist.
  try { $null = $p.CloseMainWindow() } catch { }
  if (-not $p.WaitForExit(20000)) {
    Say "  PID $($p.Id) did not exit in 20s - terminating it." 'DarkYellow'
    try { $p.Kill() } catch { }
    try { $null = $p.WaitForExit(10000) } catch { }
  }
}
Say '  Server(s) stopped.' 'Green'

# -----------------------------------------------------------------------------
# 8. dump DB, stop MariaDB, commit + push the snapshot
# -----------------------------------------------------------------------------
Section '8/8  Syncing database + logs back to git'

# A stale marker from the old, over-broad wipe implementation. That version rebuilt the entire
# schema - destroying the human account and its GM access along with the agents - so it is gone.
# Agent wipes now happen in-game (//l3wipe, //sdwipedb) and touch only agent characters.
$staleWipeMarker = Join-Path $serverDir 'dist\db_snapshot\.wipe-requested'
if (Test-Path $staleWipeMarker) {
  Say '  Ignoring a leftover .wipe-requested marker (full-DB wipe was removed; agents are wiped in-game).' 'DarkYellow'
  [System.IO.File]::Delete($staleWipeMarker)
}

try {
  & (Join-Path $serverDir 'db-dump.ps1') -Database $Database
  # record the hash we just dumped so the next start doesn't needlessly re-restore
  if (Test-Path $snapshot) {
    Set-Content -Path $stamp -Value (Get-FileHash $snapshot -Algorithm SHA256).Hash -Encoding ascii
  }
} catch {
  Say "  DB dump failed: $_" 'Red'
}

# Stop MariaDB now that we've dumped - but only if WE started it. If it was already up, another
# L3-run instance owns it (and may still have a live server attached), so leave it running.
if ($dbWasRunning) {
  Say '  Leaving MariaDB running (it was already up when this instance started).' 'Yellow'
} else {
  & (Join-Path $serverDir 'db-stop.ps1')
}

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

# Close the transcript now, before the commit, so the runner log is complete in what gets committed
# rather than captured mid-write.
Stop-RunnerLog

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
Stop-RunnerLog
