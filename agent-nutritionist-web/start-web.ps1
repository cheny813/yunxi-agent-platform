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
Start-Process -FilePath "npm" -WorkingDirectory $webRoot -ArgumentList "run", "dev" `
  -RedirectStandardOutput (Join-Path $logDir "dev.out.log") `
  -RedirectStandardError (Join-Path $logDir "dev.err.log") `
  -WindowStyle Hidden

Write-Host "已启动。实时查看日志："
Write-Host "  Get-Content -Tail 50 -Wait '$logDir\dev.out.log'"
Write-Host "  Get-Content -Tail 50 -Wait '$logDir\dev.err.log'"
