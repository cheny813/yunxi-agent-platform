@echo off
REM 列出目录内容脚本（Windows版）
REM 用法: list-dir.bat <目录路径>
REM 输出: JSON 格式

setlocal enabledelayedexpansion

set "DIR_PATH=%~1"
if "%DIR_PATH%"=="" set "DIR_PATH=."

REM 检查目录是否存在
if not exist "%DIR_PATH%" (
    echo {"status": "error", "message": "目录不存在: %DIR_PATH%"}
    exit /b 1
)

REM 列出目录内容
set "FILES="
for /f "delims=" %%i in ('dir /b "%DIR_PATH%" 2^>nul') do (
    set "FILES=!FILES!\"%%i\","
)

REM 移除最后的逗号
if defined FILES (
    set "FILES=%FILES:~0,-1%"
) else (
    set "FILES="
)

echo {"status": "success", "path": "%DIR_PATH%", "files": [%FILES%]}