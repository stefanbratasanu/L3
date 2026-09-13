@echo off
REM ============================================================================
REM  L3-run-login.bat  -  DOUBLE-CLICK to run ONLY the LoginServer.
REM
REM  Pairs with L3-run-game.bat. Useful because the login server almost never
REM  changes: leave it running in this window and restart just the game server
REM  whenever L3 code changes, instead of waiting for both to boot.
REM
REM  Clients can authenticate, but there is no game world until a GameServer
REM  is running too.
REM ============================================================================
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0L3-run.ps1" -LoginOnly %*
echo.
pause
