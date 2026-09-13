# run-server.ps1 — Launch L3 LoginServer and/or GameServer on Windows.
#
# Mirrors the stock GameServerTask.sh: working dir = dist/{login,game}, jar at
# ../libs/{Login,Game}Server.jar, JVM args from java.cfg. Copies freshly-built jars
# from the Ant output (build/dist/libs) into server/dist/libs first.
#
# Usage:
#   . .\env.ps1
#   .\db-start.ps1                 # DB must be up first
#   .\run-server.ps1 -Login        # start login server (foreground)
#   .\run-server.ps1 -Game         # start game server (foreground)
#   .\run-server.ps1 -Game -BootTest 40   # start game server, kill after 40s (headless boot check)

param(
  [switch]$Login,
  [switch]$Game,
  [int]$BootTest = 0,               # if >0, run for N seconds then stop (for headless verification)
  [string]$ServerRoot = $PSScriptRoot,
  [string]$BuildLibs  = 'C:\Agentic\L3\build\dist\libs'
)

if (-not $Login -and -not $Game) { throw "Specify -Login and/or -Game." }

$distLibs = Join-Path $ServerRoot 'dist\libs'

# Sync freshly-built jars into the runtime libs dir (Class-Path expects them at ../libs)
foreach ($jar in @('GameServer.jar','LoginServer.jar')) {
  $src = Join-Path $BuildLibs $jar
  if (Test-Path $src) {
    Copy-Item $src (Join-Path $distLibs $jar) -Force
  }
}

function Start-Node([string]$name, [string]$dir, [string]$jar) {
  $cfgPath = Join-Path $dir 'java.cfg'
  if (-not (Test-Path $cfgPath)) { throw "${name}: java.cfg not found in $dir" }
  $javaCfg = (Get-Content $cfgPath -Raw).Trim()
  $logDir = Join-Path $dir 'log'
  New-Item -ItemType Directory -Force -Path $logDir | Out-Null

  # Build argument list: java.cfg args + -jar ../libs/<jar>
  $argList = @()
  $argList += ($javaCfg -split '\s+')
  $argList += @('-jar', "..\libs\$jar")

  $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
  Write-Host "=== Starting $name ===" -ForegroundColor Cyan
  Write-Host "  cwd : $dir"
  Write-Host "  cmd : java $javaCfg -jar ..\libs\$jar"

  if ($BootTest -gt 0) {
    $out = Join-Path $logDir 'boottest.stdout.log'
    $err = Join-Path $logDir 'boottest.stderr.log'
    $proc = Start-Process -FilePath $java -ArgumentList $argList -WorkingDirectory $dir -PassThru -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err
    Write-Host "  pid : $($proc.Id)  (boot test: $BootTest s)" -ForegroundColor Yellow
    $elapsed = 0
    while ($elapsed -lt $BootTest -and -not $proc.HasExited) { Start-Sleep -Seconds 2; $elapsed += 2 }
    if ($proc.HasExited) {
      Write-Host "  $name EXITED after ~$elapsed s (code $($proc.ExitCode))" -ForegroundColor Red
    } else {
      Write-Host "  $name STILL RUNNING after $BootTest s -> stopping." -ForegroundColor Green
      Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
      Start-Sleep -Seconds 2
    }
    Write-Host "`n----- $name stdout (last 60 lines) -----"
    Get-Content $out -ErrorAction SilentlyContinue | Select-Object -Last 60 | ForEach-Object { Write-Host $_ }
    $errContent = Get-Content $err -ErrorAction SilentlyContinue
    if ($errContent) {
      Write-Host "`n----- $name stderr (last 30 lines) -----" -ForegroundColor Yellow
      $errContent | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
    }
  } else {
    & $java @argList
  }
}

if ($Login) { Start-Node 'LoginServer' (Join-Path $ServerRoot 'dist\login') 'LoginServer.jar' }
if ($Game)  { Start-Node 'GameServer'  (Join-Path $ServerRoot 'dist\game')  'GameServer.jar' }
