@echo off
chcp 65001 >nul

echo ============================================
echo   AgentScope Studio 启动脚本
echo ============================================
echo.
echo 正在启动 AgentScope Studio...
echo 启动后访问：http://localhost:3000
echo.
echo 如果启动失败，请确保已安装 Node.js
echo 安装地址：https://nodejs.org/
echo.
echo 按 Ctrl+C 停止服务
echo.

npx @agentscope/studio
pause
