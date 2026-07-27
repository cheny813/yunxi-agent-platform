@chcp 65001>nul
@REM Ensure start.ps1 has UTF-8 BOM (Windows PowerShell parses UTF-8 without BOM as GBK on Chinese Windows)
@powershell -NoProfile -ExecutionPolicy Bypass -Command "$f='%~dp0start.ps1'; $b=[System.IO.File]::ReadAllBytes($f); if($b.Length -lt 3 -or $b[0]-ne0xEF -or $b[1]-ne0xBB -or $b[2]-ne0xBF){ $s=[System.IO.File]::ReadAllText($f,[System.Text.Encoding]::UTF8); [System.IO.File]::WriteAllText($f,$s,(New-Object System.Text.UTF8Encoding 1)) }"
@powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*