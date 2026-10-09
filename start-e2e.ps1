#Requires -Version 5.1
<#
.SYNOPSIS
    yunxi-agent-platform 端到端一键启动脚本
.DESCRIPTION
    按正确依赖顺序拉起整套端到端环境，规避“依赖未就绪导致启动卡顿/失败”：
      1. 确保 Docker 守护进程就绪
      2. docker compose up -d 拉起基础设施（MySQL / Redis / Milvus / Nacos / OTel）
      3. 等待基础设施全部健康（重点等 Milvus——否则 mcp-milvus 会因连接超时秒挂）
      4. 启动 5 个 MCP 服务（scripts/start-stack.ps1 -BuildMcp）
      5. 等待 MCP 服务全部 LISTENING
      6. 后台启动 yunxi 应用（start.ps1 -Fast，依赖 JAR 已构建；缺失则自动打包）
      7. 等待 40001 就绪 + /actuator/health == UP，并做一次 e2e-test-agent 冒烟对话
.PARAMETER WithAistio
    一并拉起 aistio 控制面（默认跳过，保持轻量）
.PARAMETER RebuildApp
    强制重新打包 agent-app（默认仅当 JAR 缺失时才打包）
.PARAMETER SkipInfra
    跳过 docker compose（基础设施已由你手动启动）
.PARAMETER SkipMcp
    跳过 MCP 服务（仅启动应用）
.EXAMPLE
    .\start-e2e.ps1                 # 一键拉起完整端到端环境（跳过 aistio）
    .\start-e2e.ps1 -WithAistio     # 含 aistio 控制面
    .\start-e2e.ps1 -RebuildApp     # 先重新打包应用
#>

param(
    [switch]$WithAistio,
    [switch]$RebuildApp,
    [switch]$SkipInfra,
    [switch]$SkipMcp
)

# 统一 UTF-8 编码，消除中文日志乱码
try { chcp 65001 | Out-Null } catch { }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

$APP_PORT   = 40001
$MILVUS_PORT = 19530
$LogDir     = Join-Path $scriptDir 'logs'
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }

function Write-Step($msg) { Write-Host "`n========== $msg ==========" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host ("  [OK] " + $msg) -ForegroundColor Green }
function Write-Warn($msg) { Write-Host ("  [WARN] " + $msg) -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host ("  [ERR] " + $msg) -ForegroundColor Red }

function Test-PortListening($port) {
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $iar = $tcp.BeginConnect('127.0.0.1', $port, $null, $null)
        $ok  = $iar.AsyncWaitHandle.WaitOne(800)
        if ($ok -and $tcp.Connected) { $tcp.EndConnect($iar); $tcp.Close(); return $true }
        $tcp.Close(); return $false
    } catch { return $false }
}

function Wait-Port($port, $timeout = 300, $label = '') {
    $t0 = Get-Date
    while ((Get-Date) - $t0 -lt [TimeSpan]::FromSeconds($timeout)) {
        if (Test-PortListening $port) {
            Write-Ok ("$label 端口 $port 就绪（$([int]((Get-Date) - $t0).TotalSeconds)s）")
            return $true
        }
        Start-Sleep -Seconds 2
    }
    Write-Err ("$label 端口 $port 在 ${timeout}s 内未就绪")
    return $false
}

# 等待名称匹配指定模式的容器全部进入 healthy 状态（无 healthcheck 的容器视为已满足）
function Wait-DockerHealthy($namePattern, $timeout = 300) {
    $t0 = Get-Date
    while ((Get-Date) - $t0 -lt [TimeSpan]::FromSeconds($timeout)) {
        $names = docker ps --format '{{.Names}}' 2>$null
        $hits  = $names | Where-Object { $_ -match $namePattern }
        if ($hits) {
            $allHealthy = $true
            foreach ($n in $hits) {
                $st = docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' $n 2>$null
                if ($st -eq 'nohealth') { continue }      # 无健康检查的容器，不阻塞
                if ($st -ne 'healthy') { $allHealthy = $false; break }
            }
            if ($allHealthy) {
                Write-Ok ("容器 $namePattern 全部 healthy（$([int]((Get-Date) - $t0).TotalSeconds)s）")
                return $true
            }
        }
        Start-Sleep -Seconds 3
    }
    Write-Warn ("容器 $namePattern 在 ${timeout}s 内未全部 healthy（继续，不影响后续）")
    return $false
}

# ---------- 1) Docker 守护进程 ----------
Write-Step "1/7 确保 Docker 守护进程就绪"
try { docker ps 2>&1 | Out-Null } catch { }
if ($LASTEXITCODE -ne 0) {
    Write-Warn "Docker 未就绪，尝试启动 Docker Desktop..."
    $dd = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (Test-Path $dd) { Start-Process $dd }
    $ok = $false
    for ($i = 0; $i -lt 30; $i++) {
        try { docker ps 2>&1 | Out-Null; if ($LASTEXITCODE -eq 0) { $ok = $true; break } } catch { }
        Start-Sleep -Seconds 5
    }
    if (-not $ok) { Write-Err "Docker 启动失败，请手动启动 Docker Desktop 后重试"; exit 1 }
}
Write-Ok "Docker 守护进程就绪"

# ---------- 2) 基础设施 ----------
Write-Step "2/7 启动基础设施 (docker compose up -d)"
if (-not $SkipInfra) {
    docker compose up -d
    if ($LASTEXITCODE -ne 0) { Write-Err "docker compose 失败"; exit 1 }
    Write-Ok "docker compose up -d 已执行"
} else {
    Write-Warn "跳过 docker compose（-SkipInfra）"
}

# ---------- 3) 等待基础设施健康（重点 Milvus）----------
Write-Step "3/7 等待基础设施健康（Milvus 是慢变量，必须等它 healthy 再起 mcp-milvus）"
Wait-Port 3306 300 'MySQL'
Wait-Port 6379 300 'Redis'
Wait-Port 8848 300 'Nacos'
Wait-Port $MILVUS_PORT 300 'Milvus'
Wait-DockerHealthy 'milvus' 300

# ---------- 4) MCP 服务栈 ----------
Write-Step "4/7 启动 5 个 MCP 服务（start-stack.ps1 -BuildMcp）"
# mcp-milvus 的 milvus.host 默认写死为某开发机 IP（192.168.11.48），本机未设 MILVUS_HOST 时
# 会连不到 Milvus（waiting_for_connection）。这里注入回环地址，子进程（start-stack.ps1）及其
# 拉起的 mcp-milvus java 进程会继承这些环境变量，使其连上本机 Milvus。
$env:MILVUS_HOST     = '127.0.0.1'
$env:MILVUS_PORT     = '19530'
$env:MILVUS_USERNAME = 'root'
$env:MILVUS_PASSWORD = 'Milvus'
if (-not $SkipMcp) {
    $mcpArgs = @('-NoProfile', '-File', (Join-Path $scriptDir 'scripts\start-stack.ps1'), '-BuildMcp', '-Force')
    if (-not $WithAistio) { $mcpArgs += '-SkipAistio' }
    $mcpOut = Join-Path $LogDir 'mcp_stack.out.log'
    $mcpErr = Join-Path $LogDir 'mcp_stack.err.log'
    Write-Host "  日志: $mcpOut"
    # -Wait 会阻塞到 start-stack.ps1 完成（其内部已逐端口 Wait-Port）
    Start-Process -FilePath 'powershell' -ArgumentList $mcpArgs -WorkingDirectory $scriptDir `
        -RedirectStandardOutput $mcpOut -RedirectStandardError $mcpErr -NoNewWindow -Wait
    Write-Ok "MCP 栈启动脚本执行完毕"
} else {
    Write-Warn "跳过 MCP 服务（-SkipMcp）"
}

# ---------- 5) 等待 MCP 端口 LISTENING ----------
Write-Step "5/7 等待 MCP 服务 LISTENING"
$mcpPorts = @{ database = 40101; redis = 40102; milvus = 40103; nutrition = 40602; formfill = 40601 }
foreach ($kv in $mcpPorts.GetEnumerator()) {
    $r = Wait-Port $kv.Value 200 $kv.Key
    if (-not $r -and $kv.Key -eq 'milvus') {
        Write-Warn "mcp-milvus 未就绪；若 Milvus 连接异常详见 logs/mcp/milvus.err.log（不影响其余验证）"
    }
}

# ---------- 6) 后台启动应用 ----------
Write-Step "6/7 后台启动 yunxi 应用"
$jarGlob = Join-Path $scriptDir 'agent-app\target\agent-app-*.jar'
if ($RebuildApp -or -not (Test-Path $jarGlob)) {
    Write-Warn "未找到 JAR 或指定 -RebuildApp，先严格编译打包 agent-app..."
    & mvn -pl agent-app -am clean install -DskipTests `
        '-Dmaven.compiler.compilerId=javac' '-Dmaven.compiler.failOnError=true' '-Djacoco.skip=true'
    if ($LASTEXITCODE -ne 0) { Write-Err "打包失败"; exit 1 }
    Write-Ok "打包完成"
}
$startArgs = @('-NoProfile', '-File', (Join-Path $scriptDir 'start.ps1'), '-Fast', '-NoPause')
Start-Process -FilePath 'powershell' -ArgumentList $startArgs -WorkingDirectory $scriptDir `
    -RedirectStandardOutput (Join-Path $LogDir 'app.out.log') `
    -RedirectStandardError  (Join-Path $LogDir 'app.err.log') -WindowStyle Hidden
Write-Host "  应用后台启动中（日志: logs/app.out.log）..." -ForegroundColor Gray

# ---------- 7) 就绪 + 健康检查 + 端到端冒烟 ----------
Write-Step "7/7 等待应用就绪并做端到端冒烟验证"
Wait-Port $APP_PORT 300 'yunxi app'

# /actuator/health
$healthy = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $code = & curl.exe -s -o $null -w '%{http_code}' --max-time 10 "http://localhost:$APP_PORT/actuator/health"
        if ($code -eq '200') { $healthy = $true; Write-Ok "/actuator/health = 200"; break }
    } catch { }
    Start-Sleep -Seconds 3
}
if (-not $healthy) { Write-Warn "/actuator/health 未返回 200（应用可能仍在启动或配置有误）" }

# 代理健康
try {
    $ah = & curl.exe -s --max-time 10 "http://localhost:$APP_PORT/agents/health"
    Write-Ok ("/agents/health = " + $ah)
} catch { Write-Warn "/agents/health 获取失败" }

# e2e-test-agent 冒烟对话（验证 ingress -> 对话服务 -> AgentRuntime -> LLM 全链路）
Write-Host "  端到端冒烟对话 (agentName=e2e-test-agent)..." -ForegroundColor Cyan
$body = '{"message":"用一句话说明什么是校园食品安全","agentName":"e2e-test-agent","mode":"sync"}'
try {
    $resp = & curl.exe -s -X POST "http://localhost:$APP_PORT/api/conversations/chat" `
        -H "Content-Type: application/json" -d $body --max-time 90
    $j = $resp | ConvertFrom-Json
    Write-Ok ("冒烟回复: " + $j.reply)
    Write-Host "`n========== 端到端验证通过 ✅ ==========" -ForegroundColor Green
} catch {
    Write-Warn ("冒烟对话失败: " + $_.Exception.Message)
}

# ---------- 收尾状态汇总 ----------
Write-Host "`n========== 组件状态汇总 ==========" -ForegroundColor Cyan
Write-Host ("  MySQL(3306)        : " + $(if (Test-PortListening 3306) { 'UP' } else { 'DOWN' }))
Write-Host ("  Redis(6379)        : " + $(if (Test-PortListening 6379) { 'UP' } else { 'DOWN' }))
Write-Host ("  Milvus(19530)      : " + $(if (Test-PortListening $MILVUS_PORT) { 'UP' } else { 'DOWN' }))
Write-Host ("  Nacos(8848)        : " + $(if (Test-PortListening 8848) { 'UP' } else { 'DOWN' }))
foreach ($kv in $mcpPorts.GetEnumerator()) {
    Write-Host ("  mcp-$($kv.Key.PadRight(9)) : " + $(if (Test-PortListening $kv.Value) { "UP (:$($kv.Value))" } else { "DOWN (:$($kv.Value))" }))
}
Write-Host ("  yunxi app(40001)   : " + $(if (Test-PortListening $APP_PORT) { 'UP' } else { 'DOWN' }))
Write-Host "`n  访问地址: http://localhost:$APP_PORT/actuator/health" -ForegroundColor White
Write-Host "  对话端点: POST http://localhost:$APP_PORT/api/conversations/chat" -ForegroundColor White
