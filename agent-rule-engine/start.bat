@echo off
REM ========================================
REM   Agent Rule Engine 本地启动脚本
REM ========================================

chcp 65001 >nul

echo ========================================
echo   Agent Rule Engine
echo ========================================
echo.

REM 设置环境变量
set MYSQL_HOST=192.168.10.153
set MYSQL_USERNAME=root
set MYSQL_PASSWORD=root

REM 切换到模块根目录
cd ..

REM 检查是否已打包
if not exist "target\agent-rule-engine-1.0.0.jar" (
    echo [INFO] 规则引擎未打包，正在执行打包...
    echo.
    call mvn clean package -DskipTests
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
     -jar target\agent-rule-engine-1.0.0.jar

pause
