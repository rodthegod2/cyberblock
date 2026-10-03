@echo off
rem Removes exactly what install.bat installed (a file you changed since is left in place).
rem Double-click it, or drag your Cyberpunk 2077 folder onto it if it cannot find the game.
setlocal
set "GAME=%~1"
if defined GAME if "%GAME:~-1%"=="\" set "GAME=%GAME:~0,-1%"
if defined GAME (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Remove -Game "%GAME%"
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Remove
)
set "CODE=%ERRORLEVEL%"
echo.
if "%CODE%"=="0" (echo Done.) else (echo Cyberblock was NOT removed: see the message above. Exit code %CODE%.)
pause
exit /b %CODE%
