# stop-stack.ps1
# 关闭由 start-stack.ps1 拉起的外部依赖栈（幂等，可重复运行）：
#   1. aistio 控制面（docker compose --profile aistio down）
#   2. 5 个核心 MCP 服务（按端口 kill 对应 java 进程）
# 默认不碰 yunxi 本身(40001)；加 -IncludeYunxi 一并关闭。
# 说明：本脚本只负责外部依赖栈，yunxi 请用 -IncludeYunxi 或手动按端口关闭。

param(
    # 跳过 aistio 控制面关闭
    [switch]$SkipAistio,
    # 一并关闭 yunxi 平台(40001，通常由「启动项目.ps1」拉起)
    [switch]$IncludeYunxi
)

$ErrorActionPreference = 'Stop'

# ---------- 工具函数 ----------
function Stop-Port($port) {
    $killed = $false
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object {
            $pidv = $_.OwningProcess
            try {
                $proc = Get-Process -Id $pidv -ErrorAction SilentlyContinue
                $name = if ($proc) { $proc.ProcessName } else { 'unknown' }
                Write-Host "    kill PID $pidv ($name) on port $port" -ForegroundColor Yellow
                Stop-Process -Id $pidv -Force
                $killed = $true
            } catch {
                Write-Host "    failed to kill PID $pidv : $_" -ForegroundColor Red
            }
        }
    if (-not $killed) {
        Write-Host "    port $port not listening, nothing to do" -ForegroundColor DarkGray
    }
}

$mcpPorts = @(40101, 40102, 40103, 40602, 40601)

# ---------- 入口 ----------
Write-Host '==================================================' -ForegroundColor Cyan
Write-Host '  yunxi stack stopper (MCP x5 + aistio)' -ForegroundColor Cyan
Write-Host '==================================================' -ForegroundColor Cyan

# ---------- 1) aistio 控制面 (docker) ----------
if (-not $SkipAistio) {
    Write-Host '==> stopping aistio via docker compose --profile aistio down ...' -ForegroundColor Cyan
    try {
        docker compose --profile aistio down
        if ($LASTEXITCODE -ne 0) { Write-Host '    docker compose down returned non-zero' -ForegroundColor Yellow }
    } catch {
        Write-Host "    docker compose down failed: $_" -ForegroundColor Red
    }
}

# ---------- 2) 5 个核心 MCP 服务 (java 进程) ----------
Write-Host '==> stopping MCP services by port ...' -ForegroundColor Cyan
foreach ($port in $mcpPorts) { Stop-Port $port }

# ---------- 3) yunxi 平台 (opt-in) ----------
if ($IncludeYunxi) {
    Write-Host '==> stopping yunxi platform (40001) ...' -ForegroundColor Cyan
    Stop-Port 40001
}

Write-Host 'done.' -ForegroundColor Green
