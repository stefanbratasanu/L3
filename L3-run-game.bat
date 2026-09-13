@echo off
REM ============================================================================
REM  L3-run-game.bat  -  DOUBLE-CLICK to run ONLY the GameServer.
REM
REM  This is the fast iteration loop for L3 work: all the L3 code lives on the
REM  game side, and dist\game\data\scripts is recompiled at every boot, so
REM  restarting just this server picks up new agent/AI code without waiting for
REM  the login server as well.
REM
REM  A LoginServer must be running (see L3-run-login.bat) or clients cannot log
REM  in -- the game server registers with it on port 9014.
REM
REM  Finish with  .sd  in game, or by closing the window; the DB and logs are
REM  then dumped, committed and pushed as usual.
REM ============================================================================
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0L3-run.ps1" -GameOnly %*
echo.
pause
