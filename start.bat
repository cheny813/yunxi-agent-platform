@chcp 65001>nul
@REM 确保 start.ps1 以 UTF-8 BOM 保存（Windows PowerShell 无 BOM 时按 GBK 解析会乱码报错）
@powershell -NoProfile -ExecutionPolicy Bypass -Command "$f='%~dp0start.ps1'; $b=[System.IO.File]::ReadAllBytes($f); if($b.Length -lt 3 -or $b[0]-ne0xEF -or $b[1]-ne0xBB -or $b[2]-ne0xBF){ $s=[System.IO.File]::ReadAllText($f,[System.Text.Encoding]::UTF8); [System.IO.File]::WriteAllText($f,$s,[System.Text.UTF8Encoding]::new($true)) }"
@powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*