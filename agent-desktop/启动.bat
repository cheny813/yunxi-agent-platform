@echo off
chcp 65001 >nul
title yunxiClaw Desktop Client

echo ========================================
echo   yunxiClaw Desktop Client Starting...
echo ========================================

cd /d "%~dp0"

REM Set environment variables for npm mirror (more reliable than npm config)
echo [INFO] Setting npm mirror...
set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
set npm_config_registry=https://registry.npmmirror.com

where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Node.js not found. Please install Node.js first.
    pause
    exit /b 1
)

REM Install dependencies if not exist
if not exist node_modules (
    echo [INFO] Installing dependencies...
    call npm install
    if %errorlevel% neq 0 (
        echo [ERROR] Failed to install dependencies
        pause
        exit /b 1
    )
)

REM Check and fix Electron installation
echo [INFO] Checking Electron installation...
set ELECTRON_PATH=node_modules\electron\dist\electron.exe

if not exist %ELECTRON_PATH% (
    echo [WARNING] Electron binary not found. Repairing...
    if exist node_modules\electron (
        rd /s /q node_modules\electron 2>nul
    )
    call npm install electron
    if %errorlevel% neq 0 (
        echo [ERROR] Failed to install Electron
        echo Try manual fix:
        echo   1. Delete node_modules\electron folder
        echo   2. Run: npm install electron
        pause
        exit /b 1
    )
)

REM Verify Electron works
echo [INFO] Verifying Electron...
node -e "require('./node_modules/electron')" 2>nul
if %errorlevel% neq 0 (
    echo [WARNING] Electron verification failed, reinstalling...
    if exist node_modules\electron rd /s /q node_modules\electron
    call npm install electron
)

if not exist %ELECTRON_PATH% (
    echo [ERROR] Electron binary still not found after repair
    pause
    exit /b 1
)

echo [INFO] Electron OK
echo.
echo [SUCCESS] Starting yunxiClaw...
echo [INFO] A desktop window should appear shortly
echo.

REM Start Electron
npm start

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Failed to start yunxiClaw
    pause
)

pause