@echo off
rem Double-click to start the whole Tether demo. Pass -Reset to start from an empty database.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-demo.ps1" %*
echo.
pause
