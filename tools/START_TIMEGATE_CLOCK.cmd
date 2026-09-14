@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start_timegate_precision_clock.ps1"
if errorlevel 1 (
  echo.
  echo [ERROR] TimeGate precision clock failed to start.
  pause
  exit /b 1
)
echo.
echo TimeGate precision clock is running.
pause
