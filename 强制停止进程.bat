@echo off
chcp 65001 >nul
echo ========================================
echo   Stop yunxi-agent-platform Process
echo ========================================
echo.

echo [1/2] Stopping yunxi-agent-platform processes...
powershell -Command "$processes = Get-WmiObject Win32_Process -Filter \"name='java.exe'\" | Where-Object {$_.CommandLine -like '*yunxi-agent-platform*'}; if ($processes) { foreach ($p in $processes) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue; Write-Host \"Stopped PID: $($p.ProcessId)\" } } else { Write-Host \"No yunxi-agent-platform process found\" }"
echo.

echo [2/2] Cleaning target directory...
if exist "target" (
    rmdir /s /q target 2>nul
    if exist "target" (
        echo Target cleanup failed, trying force delete...
        powershell -Command "Remove-Item -Path 'd:\work\code\yunxi-agent-platform\target' -Recurse -Force -ErrorAction SilentlyContinue"
    ) else (
        echo Target cleaned successfully
    )
) else (
    echo Target directory does not exist
)
echo.

echo ========================================
echo   Done!
echo ========================================
pause
