@echo off
REM ============================================================================
REM  L3-run.bat  -  DOUBLE-CLICK THIS to run the L3 servers on the test machine.
REM
REM  It just hands off to L3-run.ps1, which:
REM    git pull  ->  start MariaDB + both servers  ->  (you test)  ->
REM    on close: dump the DB and commit/push it so both machines stay in sync.
REM
REM  No administrator rights are needed. If Windows shows a UAC/SmartScreen
REM  prompt for java.exe, that's your security software inspecting a portable
REM  exe -- you can decline it; the server does not need elevation.
REM ============================================================================
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0L3-run.ps1" %*
echo.
pause
