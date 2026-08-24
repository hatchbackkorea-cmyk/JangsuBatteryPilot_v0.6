@echo off
setlocal
set "VER=8.9"
set "BASE=%~dp0.gradle-dist"
set "GHOME=%BASE%\gradle-%VER%"
set "ZIP=%BASE%\gradle-%VER%-bin.zip"
if exist "%GHOME%\bin\gradle.bat" goto RUN
if not exist "%BASE%" mkdir "%BASE%"
echo Gradle %VER% 다운로드 중...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $u='https://services.gradle.org/distributions/gradle-%VER%-bin.zip'; Invoke-WebRequest -Uri $u -OutFile '%ZIP%'; Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%BASE%' -Force"
if errorlevel 1 exit /b 1
:RUN
call "%GHOME%\bin\gradle.bat" %*
endlocal
