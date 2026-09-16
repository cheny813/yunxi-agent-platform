# start-stack.ps1
# 一键拉起 yunxi 核心外部依赖栈（幂等，可重复运行）：
#   1. aistio 控制面（控制台 8081 / 网关 18080）——若未运行则用 docker compose 拉起
#   2. 5 个核心 MCP 服务（database/redis/milvus/nutrition/formfill）
# 端口已 LISTENING 的服务自动跳过；用 -Force 可重启对应 MCP 服务。
# 说明：本脚本不负责启动 yunxi 本身，yunxi 请用仓库根的「启动项目.ps1」。

param(
    # yunxi-mcp-servers 仓库根（各 MCP 模块的上级工程）
    [string]$McpServersRoot = '',
    # AgentScope-Java 仓库根（含 agentscope-service），供 docker compose 引用。
    # 留空则自动探测同级目录下的 agentscope-java / agentscope-java-2.0GA。
    [string]$AistioRepoRoot = '',
    # yunxi 仓库根
    [string]$YunxiRoot = '',
    [string]$AistioVersion = '2.0.3-SNAPSHOT',
    [switch]$SkipAistio,   # 跳过 aistio 控制面检查/启动
    [switch]$SkipMcp,      # 跳过 5 个 MCP 服务启动
    [switch]$Force,        # 强制重启（MCP 端口占用则先 kill；不强制重建 aistio 容器）
    [switch]$BuildMcp,     # 若目标 jar 不存在，自动 mvn package 构建
    [switch]$OpenConsole    # 启动完成后用默认浏览器打开 aistio 控制台
)

# ---------- 同级目录探测 ----------
# 兼容两种仓库目录命名：官方克隆名 agentscope-java，以及带版本后缀的本地命名。
function Resolve-SiblingDir {
    param([string]$Parent, [string[]]$Candidates, [string]$Label)
    foreach ($name in $Candidates) {
        $p = Join-Path $Parent $name
        if (Test-Path $p) { return (Resolve-Path $p).Path }
    }
    return $null
}

if ([string]::IsNullOrWhiteSpace($YunxiRoot)) {
    $YunxiRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}
$SiblingRoot = (Resolve-Path (Join-Path $YunxiRoot '..')).Path

if ([string]::IsNullOrWhiteSpace($McpServersRoot)) {
    $McpServersRoot = Resolve-SiblingDir -Parent $SiblingRoot -Candidates @('yunxi-mcp-servers') -Label 'yunxi-mcp-servers'
}
if ([string]::IsNullOrWhiteSpace($AistioRepoRoot)) {
    $AistioRepoRoot = Resolve-SiblingDir -Parent $SiblingRoot -Candidates @('agentscope-java', 'agentscope-java-2.0GA') -Label 'AgentScope-Java'
}

$ErrorActionPreference = 'Stop'

# ---------- 工具函数 ----------
function Test-PortListening($port) {
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $iar = $tcp.BeginConnect('127.0.0.1', $port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne(800)
        if ($ok -and $tcp.Connected) { $tcp.EndConnect($iar); $tcp.Close(); return $true }
        $tcp.Close(); return $false
    } catch { return $false }
}

function Wait-Port($port, $timeoutSec = 40) {
    $t0 = Get-Date
    while ((Get-Date) - $t0 -lt [TimeSpan]::FromSeconds($timeoutSec)) {
        if (Test-PortListening $port) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Kill-Port($port) {
    $pids = @()
    netstat -ano | Select-String ":$port\s" | ForEach-Object {
        $parts = ($_ -split '\s+') | Where-Object { $_ -ne '' }
        if ($parts.Count -ge 2 -and $parts[-2] -eq 'LISTENING') { $pids += $parts[-1] }
    }
    foreach ($pidv in ($pids | Sort-Object -Unique)) {
        Write-Host "    kill existing PID $pidv on port $port" -ForegroundColor Yellow
        taskkill /F /PID $pidv 2>$null | Out-Null
    }
}

function Test-Up($port) { if (Test-PortListening $port) { 'UP' } else { 'DOWN' } }

# ---------- 入口检查 ----------
Write-Host '==================================================' -ForegroundColor Cyan
Write-Host '  yunxi stack launcher (MCP x5 + aistio)' -ForegroundColor Cyan
Write-Host '==================================================' -ForegroundColor Cyan
Write-Host "  McpServersRoot : $McpServersRoot"
Write-Host "  AistioRepoRoot : $AistioRepoRoot"
Write-Host "  YunxiRoot      : $YunxiRoot"
Write-Host ''

# ---------- 1) aistio 控制面 ----------
if (-not $SkipAistio) {
    $aistioUp = (Test-PortListening 8081) -or (Test-PortListening 18080)
    if ($aistioUp) {
        Write-Host '==> aistio control plane already running (8081/18080), skip' -ForegroundColor DarkGray
    } elseif ([string]::IsNullOrWhiteSpace($AistioRepoRoot)) {
        Write-Host '==> WARN: AgentScope-Java repo not found next to this project; skip aistio.' -ForegroundColor Yellow
        Write-Host '    Clone it to a sibling dir (default names: agentscope-java or agentscope-java-2.0GA),' -ForegroundColor DarkGray
        Write-Host '    or pass -AistioRepoRoot <path>. Use -SkipAistio to silence this warning.' -ForegroundColor DarkGray
    } else {
        Write-Host '==> bringing up aistio via docker compose (existing images)...' -ForegroundColor Cyan
        $env:AISTIO_SRC = Join-Path $AistioRepoRoot 'agentscope-service'
        $env:AISTIO_ROOT = $AistioRepoRoot
        $env:AISTIO_VERSION = $AistioVersion
        Push-Location $YunxiRoot
        try {
            docker compose --profile aistio up -d
            if ($LASTEXITCODE -ne 0) { throw "docker compose failed (exit=$LASTEXITCODE)" }
        } finally { Pop-Location }
        if (-not ((Test-PortListening 8081) -or (Test-PortListening 18080))) {
            Write-Host '    console did not come up; run scripts/setup-aistio.ps1 to (re)build images first.' -ForegroundColor Yellow
        }
    }
}

# ---------- 2) 5 个核心 MCP 服务 ----------
$mcpServices = @(
    @{ Name = 'database';  Module = 'mcp-database';  Port = 40101; Timeout = 60;  Args = @() },
    @{ Name = 'redis';     Module = 'mcp-redis';     Port = 40102; Timeout = 60;  Args = @('--server.port=40102') },
    @{ Name = 'milvus';    Module = 'mcp-milvus';    Port = 40103; Timeout = 60;  Args = @('--server.port=40103') },
    @{ Name = 'nutrition'; Module = 'mcp-nutrition'; Port = 40602; Timeout = 180; Args = @('--server.port=40602') },
    @{ Name = 'formfill';  Module = 'mcp-formfill';  Port = 40601; Timeout = 60;  Args = @() }
)

$logDir = Join-Path (Join-Path $YunxiRoot 'logs') 'mcp'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (-not $SkipMcp) {
    foreach ($svc in $mcpServices) {
        $port = $svc.Port
        Write-Host "==> MCP $($svc.Name) (port $port) ..." -ForegroundColor Cyan

        if (Test-PortListening $port) {
            if ($Force) {
                Write-Host '    -Force: restarting...' -ForegroundColor Yellow
                Kill-Port $port
                Start-Sleep -Seconds 2
            } else {
                Write-Host '    already LISTENING, skip' -ForegroundColor DarkGray
                continue
            }
        }

        $moduleRoot = Join-Path $McpServersRoot $svc.Module
        if (-not (Test-Path $moduleRoot)) {
            Write-Host "    ERROR: module dir not found: $moduleRoot (pass -McpServersRoot)" -ForegroundColor Red
            continue
        }

        $jar = Get-ChildItem (Join-Path $moduleRoot 'target') -Filter "$($svc.Module)-*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
        if (-not $jar) {
            if ($BuildMcp) {
                Write-Host "    jar not found, building $($svc.Module) ..." -ForegroundColor Yellow
                Push-Location $McpServersRoot
                try {
                    mvn -q -pl $svc.Module -am package -DskipTests
                    if ($LASTEXITCODE -ne 0) { throw "mvn build failed (exit=$LASTEXITCODE)" }
                } finally { Pop-Location }
                $jar = Get-ChildItem (Join-Path $moduleRoot 'target') -Filter "$($svc.Module)-*.jar" |
                    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
            } else {
                Write-Host "    ERROR: jar missing at $moduleRoot\target (use -BuildMcp to auto-build)" -ForegroundColor Red
                continue
            }
        }

        $javaArgs = @('-Dfile.encoding=UTF-8', '-jar', (Join-Path 'target' $jar.Name)) + $svc.Args
        $log = Join-Path $logDir "$($svc.Name).out.log"
        $err = Join-Path $logDir "$($svc.Name).err.log"

        try {
            Write-Host "    starting $($jar.Name) ..."
            Start-Process -FilePath 'java' -ArgumentList $javaArgs -WorkingDirectory $moduleRoot `
                -RedirectStandardOutput $log -RedirectStandardError $err -WindowStyle Hidden -PassThru | Out-Null
        } catch {
            Write-Host "    ERROR: failed to launch java: $_" -ForegroundColor Red
            continue
        }

        if (Wait-Port $port $svc.Timeout) {
            Write-Host "    OK: LISTENING on $port" -ForegroundColor Green
        } else {
            Write-Host "    WARN: not listening after $($svc.Timeout)s, see $err" -ForegroundColor Yellow
        }
    }
}

# ---------- 3) 访问入口汇总 ----------
Write-Host ''
Write-Host '==================================================' -ForegroundColor Green
Write-Host '  Stack status & access points' -ForegroundColor Green
Write-Host '==================================================' -ForegroundColor Green
Write-Host ('  aistio console : http://localhost:8081  ({0})' -f (Test-Up 8081)) -ForegroundColor White
Write-Host ('  aistio gateway : http://localhost:18080 ({0})' -f (Test-Up 18080))
foreach ($svc in $mcpServices) {
    Write-Host ('  mcp-{0,-9}: http://localhost:{1}/mcp/sse ({2})' -f $svc.Name, $svc.Port, (Test-Up $svc.Port))
}
Write-Host ('  yunxi platform: http://localhost:40001 ({0})' -f (Test-Up 40001)) -ForegroundColor White
Write-Host '  (yunxi 本身请用仓库根「启动项目.ps1」启动; 本脚本只负责外部依赖栈)' -ForegroundColor DarkGray

if ($OpenConsole -and (Test-PortListening 8081)) {
    Start-Process 'http://localhost:8081'
}
