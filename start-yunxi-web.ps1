#Requires -Version 5.1
<#
.SYNOPSIS
    启动 yunxi 前端 dev server（yunxi-nutritionist-web）。
.DESCRIPTION
    先强制释放目标端口（默认 5173，结束占用该端口的任意旧 vite 实例），
    再进入 yunxi-nutritionist-web 目录，缺失依赖时自动 npm install，并执行 npm run dev。
    vite 已配置 strictPort:true，端口被占会直接报错而非静默跳到 5174，
    故本脚本在启动前先清理端口，避免命中残留旧实例。
.EXAMPLE
    .\start-yunxi-web.ps1          # 释放 5173 后启动前端（前台，Ctrl+C 停止）
    .\start-yunxi-web.ps1 -Port 5173
#>

param(
    [int]$Port = 5173
)

$ErrorActionPreference = 'Stop'

# 统一控制台与子进程输出编码为 UTF-8，消除 npm/vite 中文日志乱码。
try { chcp 65001 | Out-Null } catch { }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 切换到脚本所在目录，避免相对路径错乱。
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

# 按监听端口结束占用进程（覆盖任意方式拉起的旧 vite 实例，不误伤其他进程）。
function Stop-ProcessByPort {
    param([int]$TargetPort)
    try {
        $lines = netstat -ano | Select-String ":$TargetPort\s" | Where-Object { $_ -match 'LISTENING' }
        $procIds = @()
        foreach ($l in $lines) {
            if ($l -match '\s+(\d+)\s*$') { $procIds += $Matches[1] }
        }
        $procIds = $procIds | Sort-Object -Unique
        # 注意：$procId 不能与 PowerShell 只读变量 $PID 同名，否则赋值会抛“无法覆盖变量 PID”。
        foreach ($procId in $procIds) {
            if ($procId -and [int]$procId -ne $PID) {
                Write-Host "[INFO] 端口 $TargetPort 被 PID $procId 占用，正在终止..." -ForegroundColor Yellow
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            }
        }
    } catch {
        Write-Host "[WARN] 端口查询失败: $_" -ForegroundColor Yellow
    }
}

# 核心：释放目标端口，避免旧 vite 实例占用导致静默跳端口、或命中旧实例。
Stop-ProcessByPort -TargetPort $Port
Start-Sleep -Seconds 1

# 进入前端目录。
$feDir = Join-Path $PSScriptRoot 'yunxi-nutritionist-web'
if (-not (Test-Path $feDir)) {
    Write-Error "未找到前端目录: $feDir"
    exit 1
}
Set-Location $feDir

# 缺失依赖时安装。
if (-not (Test-Path node_modules)) {
    Write-Host "==> 未检测到 node_modules，正在 npm install..."
    cmd /c 'npm install'
    if ($LASTEXITCODE -ne 0) { Write-Error "npm install 失败"; exit 1 }
}

# 前台启动 dev server（Ctrl+C 停止，日志实时可见）。
Write-Host "==> 正在启动前端 dev server（端口 $Port，Ctrl+C 停止）..."
cmd /c 'npm run dev'
