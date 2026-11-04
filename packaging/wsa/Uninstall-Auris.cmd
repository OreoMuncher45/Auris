@echo off
rem Takes Auris back off this PC. The subsystem itself stays, because other
rem Android apps may be using it.

setlocal
set "HERE=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%HERE%Auris-Wsa.ps1" -Uninstall
pause
endlocal
