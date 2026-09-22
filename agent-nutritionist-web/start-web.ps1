# 前端 dev server 启动脚本
# 作用：把 vite 的 stdout/stderr 统一收口到仓库根 logs/nutritionist-web/，
#       避免 dev.out.log / dev.err.log 散落在前端目录下。
$ErrorActionPreference = "Stop"
$webRoot = $PSScriptRoot
$repoRoot = Split-Path -Parent $webRoot
$logDir = Join-Path $repoRoot "logs\nutritionist-web"
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

Write-Host "启动前端 dev server，日志收口至：$logDir"

# 以后台进程方式启动，输出重定向到 logs/，前端目录不再产生散落日志
# 不要直接 Start-Process "npm"：Windows 上 npm 可能是 npm.cmd（非 Win32 exe），也可能是
# nvm 等注入的 PowerShell 函数（此时 (Get-Command npm).Source 为空）。统一交给 cmd.exe /c，
# 由 cmd 的 PATHEXT 自行解析 npm，规避 "%1 is not a valid Win32 application"。
Start-Process -FilePath "cmd.exe" -WorkingDirectory $webRoot -ArgumentList "/c", "npm run dev" `
  -RedirectStandardOutput (Join-Path $logDir "dev.out.log") `
  -RedirectStandardError (Join-Path $logDir "dev.err.log") `
  -WindowStyle Hidden

Write-Host "已启动。实时查看日志："
Write-Host "  Get-Content -Tail 50 -Wait '$logDir\dev.out.log'"
Write-Host "  Get-Content -Tail 50 -Wait '$logDir\dev.err.log'"
