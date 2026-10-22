@echo off
chcp 65001 >nul
title build PRTS 3.5.0
cd /d "%~dp0"

echo ==========================================
echo   PRTS Virus  v3.5.0
echo   + floating window (overlay counter)
echo   + anti-kill (battery whitelist + revive)
echo ==========================================
echo.
echo Keystore password will be asked next.
echo It is NOT written to any file.
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-v6.ps1"

echo.
echo done. APK is in out\v6\
pause
