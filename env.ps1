# env.ps1 — L3 reproducible build/run environment (Windows PowerShell).
# Dot-source this at the start of any session:  . .\env.ps1
# Sets JAVA_HOME + PATH for the portable toolchain under C:\Agentic\tools.
# All tool binaries live OUTSIDE the repo (C:\Agentic\tools) and are gitignored;
# this script is the single source of truth for where they are.

$ErrorActionPreference = 'Stop'

# --- Roots ---
$Global:L3_ROOT   = 'C:\Agentic\L3'
$Global:L3_TOOLS  = 'C:\Agentic\tools'
$Global:L3_SERVER = Join-Path $L3_ROOT 'server'

# --- Toolchain locations (portable, machine-local) ---
$Global:JAVA_HOME = Join-Path $L3_TOOLS 'jdk-25'          # Temurin JDK 25 (LTS) — required by build.xml
$Global:ANT_HOME  = Join-Path $L3_TOOLS 'ant'             # Apache Ant 1.10.x
$Global:L3_MARIADB = Join-Path $L3_TOOLS 'mariadb'        # MariaDB 11.4 LTS (portable ZIP)
$Global:L3_LLM    = Join-Path $L3_ROOT  'llm'             # local LLM runner (llama.cpp) + prompts/schemas

# git: prefer the portable MinGit on the build box; on machines that installed
# Git normally (test box) it is not there, so fall back to git already on PATH.
$__mingit = Join-Path $L3_TOOLS 'MinGit\cmd'
if (Test-Path (Join-Path $__mingit 'git.exe')) {
  $Global:L3_GIT = $__mingit                                       # portable git dir
} else {
  $__sysgit = (Get-Command git -ErrorAction SilentlyContinue)
  $Global:L3_GIT = if ($__sysgit) { Split-Path $__sysgit.Source } else { $null }   # system git dir, or none
}

# --- MariaDB local data dir (machine-local; gitignored at repo root) ---
$Global:L3_DB_DATA = 'C:\Agentic\data\mariadb'
$Global:L3_DB_PORT = 3306

# Set process env
$env:JAVA_HOME = $JAVA_HOME
$env:ANT_HOME  = $ANT_HOME

# Prepend tool bins to PATH (idempotent-ish: only add if missing).
# $L3_GIT is $null when git is already on PATH (system install) — skip empties.
$bins = @(
  (Join-Path $JAVA_HOME 'bin'),
  (Join-Path $ANT_HOME  'bin'),
  (Join-Path $L3_MARIADB 'bin'),
  $L3_GIT
)
foreach ($b in $bins) {
  if ([string]::IsNullOrEmpty($b)) { continue }
  if ($env:Path -notlike "*$b*") { $env:Path = "$b;$env:Path" }
}

# --- Report ---
Write-Host "L3 environment ready:" -ForegroundColor Green
Write-Host "  JAVA_HOME = $env:JAVA_HOME"
Write-Host "  ANT_HOME  = $env:ANT_HOME"
Write-Host "  MariaDB   = $L3_MARIADB  (data: $L3_DB_DATA, port: $L3_DB_PORT)"
Write-Host "  Server    = $L3_SERVER"
Write-Host ""
Write-Host "Quick checks:  java -version | ant -version | mariadbd --version | git --version"
