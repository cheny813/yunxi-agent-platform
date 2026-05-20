@echo off
REM 读取文件内容脚本（Windows版）
REM 用法: read-file.bat <文件路径>
REM 输出: JSON 格式

setlocal enabledelayedexpansion

set "FILE_PATH=%~1"

if "%FILE_PATH%"=="" (
    echo {"status": "error", "message": "请指定文件路径"}
    exit /b 1
)

if not exist "%FILE_PATH%" (
    echo {"status": "error", "message": "文件不存在: %FILE_PATH%"}
    exit /b 1
)

REM 获取文件大小
for %%A in ("%FILE_PATH%") do set "FILE_SIZE=%%~zA"

REM 检查文件大小（10MB限制）
if %FILE_SIZE% GTR 10485760 (
    echo {"status": "error", "message": "文件过大: %FILE_SIZE% bytes (最大 10MB)"}
    exit /b 1
)

REM 读取文件内容（前1000行）
set "CONTENT="
for /f "usebackq delims=" %%a in ("%FILE_PATH%") do (
    set "LINE=%%a"
    set "LINE=!LINE:"=\"!"
    set "LINE=!LINE:\=\\!"
    set "CONTENT=!CONTENT!!LINE!\n"
)

echo {"status": "success", "path": "%FILE_PATH%", "size": %FILE_SIZE%, "content": "%CONTENT%"}