#Requires -Version 5.1
<#
.SYNOPSIS
    yunxi-agent-platform 统一启动脚本（PowerShell 版）
.DESCRIPTION
    多模块启动脚本，支持 normal / fast / maven / clean 四种模式。
    启动前会强制释放 40001 端口（结束占用该端口的任意旧实例），避免端口冲突导致静默失败。
.EXAMPLE
    .\启动项目.ps1           # 自动打包后启动（推荐）
    .\启动项目.ps1 -Fast     # 快速启动（已打包）
    .\启动项目.ps1 -Maven    # 使用 Maven spring-boot:run
    .\启动项目.ps1 -Clean    # 清理并重新打包启动
    .\启动项目.ps1 -NoSync   # 跳过 sdk-js 静态资源同步
    .\启动项目.ps1 -NoPause  # 非交互/后台启动时不阻塞在结尾“按任意键继续”
#>

param(
    [switch]$Fast,
    [switch]$Maven,
    [switch]$Clean,
    [switch]$NoSync,
    [switch]$NoPause
)

# 设置控制台输出编码为 UTF-8，解决 Maven/javac 中文警告乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ===== 辅助函数 =====

# 按监听端口结束占用进程（最精准：覆盖 jar / mvn / 任意方式拉起的实例，不误伤其他 java）
function Stop-ProcessByPort {
    param([int]$Port)
    try {
        $lines = netstat -ano | Select-String ":$Port\s" | Where-Object { $_ -match 'LISTENING' }
        $pids = @()
        foreach ($l in $lines) {
            if ($l -match '\s+(\d+)\s*$') { $pids += $Matches[1] }
        }
        $pids = $pids | Sort-Object -Unique
        foreach ($pid in $pids) {
            if ($pid -and [int]$pid -ne $PID) {
                Write-Host "[INFO] 端口 $Port 被 PID $pid 占用，正在终止..." -ForegroundColor Yellow
                # 先温和终止，1 秒后仍未退出再强制结束
                Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
            }
        }
    } catch {
        Write-Host "[WARN] 端口查询失败: $_" -ForegroundColor Yellow
    }
}

# 按命令行特征结束 agent-app 进程（兜底：覆盖“尚未监听端口但已启动”的情况）
function Stop-JavaProcess {
    Write-Host "[INFO] 停止已有的 agent-app 进程（释放端口 $SERVER_PORT）..."
    # 1) 释放端口（最精准，覆盖所有启动方式）
    Stop-ProcessByPort -Port $SERVER_PORT
    # 2) 按命令行特征兜底（jar / mvn 子进程）
    try {
        $procs = Get-CimInstance Win32_Process -Filter "name='java.exe'" -ErrorAction Stop |
            Where-Object { $_.CommandLine -like '*agent-app*' }
        foreach ($p in $procs) {
            if ([int]$p.ProcessId -ne $PID) {
                Write-Host "[INFO] 终止 PID: $($p.ProcessId) (命令行匹配 agent-app)" -ForegroundColor Yellow
                Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
            }
        }
    } catch {
        Write-Host "[WARN] CIM 查询失败，跳过命令行兜底: $_" -ForegroundColor Yellow
    }
    # 等待端口真正释放（最多 15s），避免新实例刚启动就端口冲突
    $waited = 0
    while ($waited -lt 15) {
        $still = netstat -ano | Select-String ":$SERVER_PORT\s" | Where-Object { $_ -match 'LISTENING' }
        if (-not $still) { break }
        Start-Sleep -Seconds 1; $waited++
    }
    if ($waited -ge 15) {
        Write-Host "[WARN] 等待端口 $SERVER_PORT 释放超时，将继续尝试启动（若仍冲突请手动检查占用进程）" -ForegroundColor Red
    } else {
        Write-Host "[INFO] 端口 $SERVER_PORT 已释放（等待 ${waited}s）" -ForegroundColor Green
    }
    Start-Sleep -Seconds 1
}

function Invoke-Pause {
    # 后台/非交互（stdin 被重定向，如 Start-Process 重定向输出）或显式 -NoPause 时，不阻塞
    if ($NoPause -or [Console]::IsInputRedirected) {
        Write-Host "[INFO] 非交互模式，跳过“按任意键继续”。" -ForegroundColor Gray
        return
    }
    Write-Host "Press any key to continue..."
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
}

# 同步 sdk-js 源码 -> 后端静态资源（演示页 /js/* 副本）
# 单一真相源：sdk-js/src 是 SDK 唯一源码，静态副本由本步骤生成，请勿手工编辑。
# 注意：静态副本在打包时（normal/clean 的 mvn package、maven 的 spring-boot:run）
#       才会进入运行产物；fast 模式使用现成 JAR，本步骤对运行中的演示页无影响。
function Sync-StaticResources {
    if ($NoSync) {
        Write-Host "[INFO] 跳过 sdk-js 静态资源同步（-NoSync）"
        return
    }
    $node = Get-Command node -ErrorAction SilentlyContinue
    if (-not $node) {
        Write-Host "[WARN] 未检测到 node，跳过 sdk-js 静态资源同步。" -ForegroundColor Yellow
        Write-Host "       如需更新演示页副本，请手动执行：cd sdk-js && npm run sync" -ForegroundColor Yellow
        return
    }
    $syncScript = Join-Path $scriptDir "sdk-js\scripts\sync-static.mjs"
    if (-not (Test-Path $syncScript)) {
        Write-Host "[WARN] 未找到同步脚本: $syncScript，跳过" -ForegroundColor Yellow
        return
    }
    Write-Host "[INFO] 同步 sdk-js -> 后端静态资源（演示页副本）..."
    Push-Location $scriptDir
    & node $syncScript
    Pop-Location
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[WARN] 静态资源同步失败（不影响启动），请检查 sdk-js 目录" -ForegroundColor Yellow
    } else {
        Write-Host "[INFO] 静态资源同步完成" -ForegroundColor Green
    }
    Write-Host ""
}

# ===== 开始 =====

# 切换到脚本所在目录
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

# 【智能体框架 服务端口】（提前定义，供停止旧进程逻辑使用）
$SERVER_PORT = "40001"

Write-Host "========================================"
Write-Host "  yunxi-agent-platform"
Write-Host "  Multi-module version"
Write-Host "========================================"
Write-Host ""

# 基础设施检查提示
Write-Host "[提示] 如基础设施（MySQL/Redis/Milvus/OTel Collector）未启动，请先执行："
Write-Host "       docker compose up -d"
Write-Host ""

# 停止旧进程（释放端口，避免端口冲突导致新实例静默失败）
Stop-JavaProcess

# 同步 sdk-js 静态资源副本（在打包前执行，确保进入运行产物）
Sync-StaticResources

# ===== 配置参数（集中管理） =====
# 【OpenTelemetry 可观测性】
#   如需启用，取消底部 $otelExtra 块的注释。默认值定义在此，统一管理：
$OTEL_OTLP_ENDPOINT = "http://127.0.0.1:4318"
$OTEL_TRACES_SAMPLER = "parentbased_always_on"
$OTEL_SERVICE_NAME = "yunxi-agent-platform"
# 【智能体框架 数据库】
$MYSQL_HOST     = "127.0.0.1"
$MYSQL_PORT     = "3306"
$MYSQL_DATABASE = "yunxi_agent_platform"
$MYSQL_USERNAME = "root"
$MYSQL_PASSWORD = "root"
# 【AI 大模型服务】
$DASHSCOPE_API_KEY = "sk-dd32b521e8d08a9e"
$LLM_MODEL        = "qwen-plus"
# 【向量数据库】
$MILVUS_HOST     = "127.0.0.1"
$MILVUS_PORT     = "19530"
$MILVUS_USERNAME = "root"
$MILVUS_PASSWORD = "Milvus"
# 【缓存】
$REDIS_HOST     = "127.0.0.1"
$REDIS_PORT     = "6379"
$REDIS_PASSWORD = "redispass"

Write-Host "[INFO] Current config:"
Write-Host "       Database: $MYSQL_HOST`:$MYSQL_PORT/$MYSQL_DATABASE"
Write-Host "       Service port: $SERVER_PORT"
Write-Host "       向量Database: $MILVUS_HOST`:$MILVUS_PORT"
Write-Host "       Cache: $REDIS_HOST`:$REDIS_PORT"
Write-Host ""

# ===== 确定模式 =====
$mode = "normal"
if ($Fast)  { $mode = "fast" }
if ($Maven) { $mode = "maven" }
if ($Clean) { $mode = "clean" }

# JAR 路径（动态匹配打包产物，避免版本号升版后硬编码失效）
$jarFile = Join-Path $scriptDir "agent-app\target\agent-app-*.jar"
$candidateJars = @(Resolve-Path -Path $jarFile -ErrorAction SilentlyContinue)
if ($candidateJars.Count -eq 0) {
    Write-Host "[ERROR] 未在 agent-app\target 找到打包产物 agent-app-*.jar，请先执行 mvn package" -ForegroundColor Red
    Read-Host "Press any key to continue..."
    exit 1
}
if ($candidateJars.Count -gt 1) {
    Write-Host "[WARN] 发现多个 agent-app-*.jar，默认使用最新修改的一个" -ForegroundColor Yellow
    $jarFile = ($candidateJars | Sort-Object { (Get-Item $_).LastWriteTime } -Descending | Select-Object -First 1).Path
} else {
    $jarFile = $candidateJars[0].Path
}

# 通用 JVM 参数
$javaArgs = @(
    "-Xmx1024m", "-Xms512m",
    "-Dfile.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
    "-Dconsole.encoding=UTF-8",
    "-Djava.net.preferIPv4Stack=true"
)

# 配置 JVM 参数（用于 normal / clean 模式）
# 注意：数据库 URL 不能直接覆盖完整字符串，否则会丢失 datasource.yml 中的
# createDatabaseIfNotExist=true 等连接参数。改用单个变量让 YAML 自己组装。
$configArgs = @(
    "-Dspring.config.import=optional:config/application-local.yml",
    "-Dserver.port=$SERVER_PORT",
    "-DMYSQL_HOST=$MYSQL_HOST",
    "-DMYSQL_PORT=$MYSQL_PORT",
    "-DMYSQL_DATABASE=$MYSQL_DATABASE",
    "-DMYSQL_USERNAME=$MYSQL_USERNAME",
    "-DMYSQL_PASSWORD=$MYSQL_PASSWORD",
    "-Dagentscope.core.api-key=$DASHSCOPE_API_KEY",
    "-Dagentscope.core.model-name=$LLM_MODEL",
    "-Dmilvus.host=$MILVUS_HOST",
    "-Dmilvus.port=$MILVUS_PORT",
    "-Dmilvus.username=$MILVUS_USERNAME",
    "-Dmilvus.password=$MILVUS_PASSWORD",
    "-Dspring.data.redis.host=$REDIS_HOST",
    "-Dspring.data.redis.port=$REDIS_PORT",
    "-Dspring.data.redis.password=$REDIS_PASSWORD",
    "-Dotel.service.name=$OTEL_SERVICE_NAME"
)
# OTLP 导出到 Jaeger（确保 docker-compose 已启动 collector）
$otelExtra = @(
    "-Dotel.exporter.otlp.endpoint=$OTEL_OTLP_ENDPOINT",
    "-Dotel.traces.sampler=$OTEL_TRACES_SAMPLER"
)
$configArgs += $otelExtra

# ===== 模式执行 =====
switch ($mode) {

    "fast" {
        Write-Host "[INFO] Fast mode (skip packaging)..."
        Write-Host ""
        Write-Host "[提示] Service port: $SERVER_PORT"
        Write-Host "[提示] Health check: http://localhost:$SERVER_PORT/actuator/health"
        Write-Host ""

        if (-not (Test-Path $jarFile)) {
            Write-Host "[ERROR] Project not packaged!" -ForegroundColor Red
            Write-Host ""
            Write-Host "Please run first: .\启动项目.ps1"
            Invoke-Pause
            exit 1
        }

        Write-Host "[提示] fast 模式直接使用已打包 JAR，启动时已同步的 sdk-js 静态副本"
        Write-Host "       不会进入运行中的演示页；若改过 SDK 源码并需演示页生效，请改用"
        Write-Host "       .\启动项目.ps1 -Clean 重新打包。"
        Write-Host ""

        $allArgs = $javaArgs + $configArgs + @("-jar", $jarFile)
        & "java" $allArgs

        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] Application failed to start!" -ForegroundColor Red
        }
        Invoke-Pause
    }

    "maven" {
        Write-Host "[INFO] Maven mode (hot reload)..."
        Write-Host ""
        Write-Host "[提示] Switching to agent-app module..."
        Write-Host ""

        Push-Location "agent-app"
        & mvn spring-boot:run -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] Maven start failed!" -ForegroundColor Red
        }
        Pop-Location
        Invoke-Pause
    }

    "clean" {
        Write-Host "[INFO] Clean and repackage mode..."
        Write-Host ""

        Write-Host "[INFO] Stopping process..."
        Stop-JavaProcess

        Write-Host "[INFO] Cleaning old files..."
        $targetDirs = @("agent-app", "agent-core",
                        "agent-text2sql", "agent-spi", "agent-config")
        foreach ($dir in $targetDirs) {
            $target = Join-Path $scriptDir "$dir\target"
            if (Test-Path $target) {
                Remove-Item -Recurse -Force $target
                Write-Host "  Deleted: $dir\target"
            }
        }

        Write-Host "[INFO] Packaging (clean install)..."
        & mvn clean install -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] Package failed!" -ForegroundColor Red
            Invoke-Pause
            exit $LASTEXITCODE
        }

        Write-Host "[SUCCESS] Package complete"
        Write-Host ""

        Write-Host "[INFO] Starting application..."
        Write-Host "[提示] Service port: $SERVER_PORT"
        Write-Host "[提示] Health check: http://localhost:$SERVER_PORT/actuator/health"
        Write-Host ""

        $allArgs = $javaArgs + $configArgs + @("-jar", $jarFile)
        & "java" $allArgs

        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] Application failed to start!" -ForegroundColor Red
        }
        Invoke-Pause
    }

    default {
        # normal 模式
        if (-not (Test-Path $jarFile)) {
            Write-Host "[INFO] Not packaged, executing packaging..."
            Write-Host ""
            & mvn clean install -DskipTests
            if ($LASTEXITCODE -ne 0) {
                Write-Host "[ERROR] Package failed!" -ForegroundColor Red
                Invoke-Pause
                exit $LASTEXITCODE
            }
            Write-Host ""
            Write-Host "[SUCCESS] Package complete"
            Write-Host ""
        } else {
            Write-Host "[INFO] 已检测到 JAR，跳过打包（已同步的 sdk-js 静态副本未重新打入 JAR）。"
            Write-Host "       若改过 SDK 源码并需演示页生效，请改用 .\启动项目.ps1 -Clean。"
            Write-Host ""
        }

        Write-Host "[INFO] Starting application..."
        Write-Host ""
        Write-Host "[配置] Service port: $SERVER_PORT"
        Write-Host "[配置] Database: $MYSQL_HOST`:$MYSQL_PORT/$MYSQL_DATABASE"
        Write-Host "[配置] 向量Database: $MILVUS_HOST`:$MILVUS_PORT"
        Write-Host "[提示] Health check: http://localhost:$SERVER_PORT/actuator/health"
        Write-Host ""

        $allArgs = $javaArgs + $configArgs + @("-jar", $jarFile)
        & "java" $allArgs

        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] Application failed to start!" -ForegroundColor Red
        }
        Invoke-Pause
    }
}
