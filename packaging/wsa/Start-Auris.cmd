@echo off
rem Auris for Windows (Android) - portable launcher.
rem Double-click this. It asks for administrator rights once, puts what is
rem missing in place, and opens Auris in a window with no frame.

setlocal
set "HERE=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%HERE%Auris-Wsa.ps1" %*
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" pause
endlocal & exit /b %CODE%
