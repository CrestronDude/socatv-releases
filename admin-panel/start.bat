@echo off
title SocaTV Nova Admin Panel
color 0B
cls

echo.
echo  ========================================
echo   SocaTV Nova Admin Panel
echo   http://localhost:7890
echo  ========================================
echo.

:: Check Node.js
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo  [ERROR] Node.js not found!
    echo.
    echo  Please install Node.js from https://nodejs.org/
    echo  Then re-run this script.
    echo.
    pause
    exit /b 1
)

for /f "tokens=*" %%v in ('node --version') do set NODE_VER=%%v
echo  [OK] Node.js %NODE_VER% found
echo.

:: Install dependencies if needed
if not exist "node_modules\express" (
    echo  [SETUP] Installing dependencies...
    call npm install
    if %errorlevel% neq 0 (
        echo  [ERROR] npm install failed.
        pause
        exit /b 1
    )
    echo  [OK] Dependencies installed
    echo.
)

:: Create data file if missing
if not exist "nova_data.json" (
    echo  [SETUP] Creating empty database...
    echo {} > nova_data.json
)

echo  Starting server...
echo.
echo  Admin Panel : http://localhost:7890
echo  API Base    : http://localhost:7890/api
echo.
echo  Press Ctrl+C to stop the server.
echo  ========================================
echo.

node server.js

pause
