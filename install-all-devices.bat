@echo off
setlocal
cd /d "%~dp0"
echo ==========================================================
echo        PXP2 Multi-Device Wireless Installer (BAT)
echo ==========================================================
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-all-devices.ps1" %*
if errorlevel 1 (
    echo.
    echo Installation finished with warnings or errors.
) else (
    echo.
    echo Installation finished successfully!
)
pause
