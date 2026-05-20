@echo off
REM ========================================
REM   内存异常报警脚本
REM   当JVM发生内存溢出时自动触发
REM ========================================

chcp 65001 >nul

echo.
echo ⚡⚡⚡ 内存异常警报：JVM发生OutOfMemoryError ⚡⚡⚡
echo 时间: %date% %time%
echo PID: %1
echo 堆内存转储文件已保存到: .\heapdumps\
echo.

echo [INFO] 正在收集系统内存信息...
echo.

REM 检查系统内存使用情况
tasklist /fi "memusage gt 100" /fo table
echo.

echo [INFO] 正在检查Java进程内存使用...
wmic process where "name='java.exe'" get processid,commandline,workingsetsize,virtualsize /format:table
echo.

echo [ACTION] 请检查以下文件进行分析：
echo 1. 堆内存转储文件: .\heapdumps\
echo 2. GC日志文件: .\logs\gc.log
echo 3. 应用日志文件: .\logs\application.log
echo.

echo [建议] 优化措施：
echo - 检查内存泄漏的代码（特别是缓存和大对象）
echo - 调整JVM内存参数（-Xmx需要适当增加）
echo - 分析堆内存转储文件

REM 发送本地通知（如果系统支持）
powershell -NoProfile -Command "[System.Media.SystemSounds]::Beep.Play()"

exit /b 0