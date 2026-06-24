@echo off
chcp 65001 >nul
title 乗り過ごし防止 — Train Tracker
cd /d "%~dp0"

echo ============================================
echo    乗り過ごし防止  Train Tracker
echo ============================================
echo.
echo    Server starting on  http://localhost:3000
echo    Your browser will open automatically.
echo.
echo    Keep this window open while using the app.
echo    Press Ctrl+C (or close this window) to stop.
echo.

REM Open the browser ~2s after the server has had time to start
start "" cmd /c "timeout /t 2 >nul & start "" http://localhost:3000"

node server.js

echo.
echo --------------------------------------------
echo  Server stopped.
echo  If you saw "EADDRINUSE", port 3000 was busy
echo  (the server may already be running).
echo --------------------------------------------
pause >nul
