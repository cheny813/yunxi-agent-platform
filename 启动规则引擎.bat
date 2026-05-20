@echo off
REM ========================================
REM   Agent Rule Engine 启动脚本
REM   多模块版本
REM ========================================
REM
REM 使用方法：
REM   启动规则引擎.bat           - 自动打包后启动（推荐）
REM   启动规则引擎.bat fast     - 快速启动（已打包）
REM   启动规则引擎.bat maven    - 使用 Maven spring-boot:run 启动
REM   启动规则引擎.bat clean    - 清理并重新打包启动
REM
REM ========================================

chcp 65001 >nul

echo ========================================
echo   Agent Rule Engine
echo   规则引擎模块
echo ========================================
echo.

REM ===== 停止旧进程 =====
call :stop_java_processes

REM 设置环境变量
set MYSQL_HOST=192.168.10.153
set MYSQL_USERNAME=root
set MYSQL_PASSWORD=root

REM 解析命令行参数
set MODE=normal
if "%1"=="fast" set MODE=fast
if "%1"=="maven" set MODE=maven
if "%1"=="clean" set MODE=clean

REM 根据模式执行不同启动方式
if "%MODE%"=="fast" goto :fast
if "%MODE%"=="maven" goto :maven
if "%MODE%"=="clean" goto :clean

REM ===== 正常模式 =====
::check_and_run
REM 检查是否已打包（多模块结构）
if not exist "agent-rule-engine\target\agent-rule-engine-1.0.0.jar" (
    echo [INFO] 规则引擎未打包，正在执行打包...
    echo.
    call mvn clean package -DskipTests -pl agent-rule-engine -am
    if %errorlevel% neq 0 (
        echo [ERROR] 打包失败！
        pause
        exit /b %errorlevel%
    )
    echo.
    echo [SUCCESS] 打包完成
    echo.
)

echo [INFO] 正在启动规则引擎...
echo.
echo [提示] 规则引擎端口: 40002
echo [提示] REST API: http://localhost:40002/api/rules
echo.

java -Xmx512m -Xms256m ^
     -Dfile.encoding=UTF-8 ^
     -Dsun.stdout.encoding=UTF-8 ^
     -Dsun.stderr.encoding=UTF-8 ^
     -Dconsole.encoding=UTF-8 ^
     -jar agent-rule-engine\target\agent-rule-engine-1.0.0.jar

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] 规则引擎启动失败！
)

pause
exit /b

REM ===== 快速模式（跳过打包） =====
::fast
if not exist "agent-rule-engine\target\agent-rule-engine-1.0.0.jar" (
    echo [ERROR] 规则引擎未打包！
    echo.
    echo 请先运行: 启动规则引擎.bat
    echo.
    pause
    exit /b 1
)

echo [INFO] 快速启动模式（跳过打包）...
echo.
echo [提示] 规则引擎端口: 40002
echo [提示] REST API: http://localhost:40002/api/rules
echo.

java -Xmx512m -Xms256m ^
     -Dfile.encoding=UTF-8 ^
     -Dsun.stdout.encoding=UTF-8 ^
     -Dsun.stderr.encoding=UTF-8 ^
     -Dconsole.encoding=UTF-8 ^
     -jar agent-rule-engine\target\agent-rule-engine-1.0.0.jar

pause
exit /b

REM ===== Maven 模式 =====
::maven
echo [INFO] Maven 启动模式（支持热重载）...
echo.
echo [提示] 多模块结构下，正在切换到 agent-rule-engine 模块启动...
echo.

cd agent-rule-engine
call mvn spring-boot:run -DskipTests

pause
exit /b

REM ===== 清理模式 =====
::clean
echo [INFO] 清理并重新打包模式...
echo.

echo [INFO] 清理旧文件...
rmdir /s /q agent-rule-engine\target 2>nul

echo [INFO] 正在打包规则引擎...
call mvn clean package -DskipTests -pl agent-rule-engine -am
if %errorlevel% neq 0 (
    echo [ERROR] 打包失败！
    pause
    exit /b %errorlevel%
)

echo [SUCCESS] 打包完成
echo.

echo [INFO] 正在启动规则引擎...
echo.
echo [提示] 规则引擎端口: 40002
echo [提示] REST API: http://localhost:40002/api/rules
echo.

java -Xmx512m -Xms256m ^
     -Dfile.encoding=UTF-8 ^
     -Dsun.stdout.encoding=UTF-8 ^
     -Dsun.stderr.encoding=UTF-8 ^
     -Dconsole.encoding=UTF-8 ^
     -jar agent-rule-engine\target\agent-rule-engine-1.0.0.jar

pause
exit /b

REM ===== 停止 Java 进程 =====
::stop_java_processes
echo [INFO] 检查并停止 agent-rule-engine 旧进程...

powershell -Command "$processes = Get-WmiObject Win32_Process -Filter \"name='java.exe'\" | Where-Object {$_.CommandLine -like '*agent-rule-engine*'}; if ($processes) { foreach ($p in $processes) { Write-Host \"[INFO] 发现进程 PID: $($p.Id)，正在停止...\"; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }; Write-Host \"[SUCCESS] 已停止 $($processes.Count) 个进程\" } else { Write-Host \"[INFO] 未发现运行的进程\" }"

echo.

exit /b
