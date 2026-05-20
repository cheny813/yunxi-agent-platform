@echo off
:: install-service.bat - 将 agent-node 安装为 Windows 服务
:: 需要: Node.js + node-windows (npm install -g node-windows)
:: 用法: install-service.bat

setlocal

set SERVICE_NAME=yunxi-agent-node
set INSTALL_DIR=%~dp0
set SCRIPT=%INSTALL_DIR%index.js

echo === 安装 %SERVICE_NAME% 服务 ===

:: 检查 node-windows
where node-windows-installer >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo 安装 node-windows ...
    npm install -g node-windows
)

:: 创建服务安装脚本
echo var Service = require('node-windows').Service; > "%INSTALL_DIR%install-svc.js"
echo var svc = new Service({ >> "%INSTALL_DIR%install-svc.js"
echo   name: '%SERVICE_NAME%', >> "%INSTALL_DIR%install-svc.js"
echo   description: 'yunxi Agent Node - Headless Node Client', >> "%INSTALL_DIR%install-svc.js"
echo   script: '%SCRIPT%', >> "%INSTALL_DIR%install-svc.js"
echo   nodeOptions: ['--harmony', '--max_old_space_size=4096'] >> "%INSTALL_DIR%install-svc.js"
echo }); >> "%INSTALL_DIR%install-svc.js"
echo svc.on('install', function(){ svc.start(); }); >> "%INSTALL_DIR%install-svc.js"
echo svc.install(); >> "%INSTALL_DIR%install-svc.js"

echo 安装服务...
node "%INSTALL_DIR%install-svc.js"

echo.
echo === 安装完成 ===
echo 请编辑 %INSTALL_DIR%config.json 配置服务器地址和标签
echo 服务管理: 在 Windows 服务管理器中查找 "%SERVICE_NAME%"

endlocal
