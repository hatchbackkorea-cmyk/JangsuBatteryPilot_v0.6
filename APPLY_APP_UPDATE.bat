@echo off
setlocal
cd /d "%~dp0"

if exist ".github\workflows\fair-timing-tests.yml" del /q ".github\workflows\fair-timing-tests.yml"
if exist ".github\workflows\timegate-remove-tg-fairness-v03478.yml" del /q ".github\workflows\timegate-remove-tg-fairness-v03478.yml"

echo.
echo TimeGate Android update files applied.
echo Return to GitHub Desktop and confirm changed files before commit.
pause
