@echo off
title PXP2 Content Hub Server
color 0b
cd /d "%~dp0"

echo ==========================================================
echo          PXP2 Wireless Content Hub Server
echo       PSP ISOs, UMD Video, Homebrew & PS1 Games
echo ==========================================================
echo.

where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Python not found in system PATH.
    echo Please install Python 3.8+ or add python.exe to your PATH.
    pause
    exit /b 1
)

:: Automatically configure ADB port reversal for all active devices
where adb >nul 2>nul
if %errorlevel% equ 0 (
    echo [*] Setting up wireless reverse port forwarding (8080)...
    for /f "tokens=1" %%d in ('adb devices ^| findstr /r /c:"[0-9a-zA-Z].*device$"') do (
        adb -s %%d reverse tcp:8080 tcp:8080 >nul 2>nul
    )
)

echo [*] Starting Python Content Hub Server on port 8080...
echo [*] PSP Library: G:\GAMES\PSP (GAMES, VIDEOS, HOMEBREW)
echo [*] PS1 Library: G:\GAMES\PS1 (Auto-Convert to PSP EBOOT)
echo.

start "" http://localhost:8080
python pxp_server.py

pause
