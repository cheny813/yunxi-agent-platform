#Requires -Version 5.1
<#
.SYNOPSIS
    yunxi-agent-platform 统一启动脚本（PowerShell 版）
.DESCRIPTION
    多模块启动脚本，支持 normal / fast / maven / clean 四种模式
.EXAMPLE
    .\启动项目.ps1           # 自动打包后启动（推荐）
    .\启动项目.ps1 -Fast     # 快速启动（已打包）
    .\启动项目.ps1 -Maven    # 使用 Maven spring-boot:run
    .\启动项目.ps1 -Clean    # 清理并重新打包启动
#>

param(
    [switch]$Fast,
    [switch]$Maven,
    [switch]$Clean
)

# 设置控制台输出编码为 UTF-8，解决 Maven/javac 中文警告乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ===== 辅助函数 =====

function Stop-JavaProcess {
    Write-Host "[INFO] Stopping agent-app process..."
    try {
        $procs = Get-CimInstance Win32_Process -Filter "name='java.exe'" -ErrorAction Stop |
            Where-Object { $_.CommandLine -like '*agent-app-2.0.0.jar*' }
        foreach ($p in $procs) {
            Write-Host "[INFO] 终止 PID: $($p.ProcessId)"
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        }
    } catch {
        # CIM 查询失败时的回退方案
        $fallback = Get-Process -Name "java" -ErrorAction SilentlyContinue |
            Where-Object { $_.MainWindowTitle -eq "" }
        foreach ($p in $fallback) {
            Write-Host "[INFO] 终止 PID: $($p.Id) (回退)"
            $p.Kill()
        }
    }
    Start-Sleep -Seconds 3
}

function Invoke-Pause {
    Write-Host "Press any key to continue..."
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
}

# ===== 开始 =====

# 切换到脚本所在目录
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

Write-Host "========================================"
Write-Host "  yunxi-agent-platform"
Write-Host "  Multi-module version"
Write-Host "========================================"
Write-Host ""

# 基础设施检查提示
Write-Host "[提示] 如基础设施（MySQL/Redis/Milvus/OTel Collector）未启动，请先执行："
Write-Host "       docker compose up -d"
Write-Host ""

# 停止旧进程
Stop-JavaProcess

# ===== 配置参数（集中管理） =====
# 【智能体框架 服务端口】
$SERVER_PORT   = "40001"
# 【OpenTelemetry 可观测性】
#   如需启用，取消底部 $otelExtra 块的注释。默认值定义在此，统一管理：
$OTEL_OTLP_ENDPOINT = "http://127.0.0.1:4318/v1/traces"
$OTEL_TRACES_SAMPLER = "parentbased_always_on"
$OTEL_SERVICE_NAME = "yunxi-agent-platform"
# 【智能体框架 数据库】
$MYSQL_HOST     = "127.0.0.1"
$MYSQL_PORT     = "3306"
$MYSQL_DATABASE = "yunxi_agent_platform"
$MYSQL_USERNAME = "root"
$MYSQL_PASSWORD = "root"
# 【AI 大模型服务】
$DASHSCOPE_API_KEY = "sk-dd32b521ea808a9e"
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

# JAR 路径
$jarFile = Join-Path $scriptDir "agent-app\target\agent-app-2.0.0.jar"

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
# OTLP 导出到 Jaeger（取消下方注释启用）
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
        Write-Host "[提示] Service port: 40001"
        Write-Host "[提示] Health check: http://localhost:40001/actuator/health"
        Write-Host ""

        if (-not (Test-Path $jarFile)) {
            Write-Host "[ERROR] Project not packaged!" -ForegroundColor Red
            Write-Host ""
            Write-Host "Please run first: .\启动项目.ps1"
            Invoke-Pause
            exit 1
        }

        $allArgs = $javaArgs + @("-jar", $jarFile)
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

        Write-Host "[INFO] Packaging..."
        & mvn clean package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] Package failed!" -ForegroundColor Red
            Invoke-Pause
            exit $LASTEXITCODE
        }

        Write-Host "[SUCCESS] Package complete"
        Write-Host ""

        Write-Host "[INFO] Starting application..."
        Write-Host "[提示] Service port: 40001"
        Write-Host "[提示] Health check: http://localhost:40001/actuator/health"
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
            & mvn clean package -DskipTests
            if ($LASTEXITCODE -ne 0) {
                Write-Host "[ERROR] Package failed!" -ForegroundColor Red
                Invoke-Pause
                exit $LASTEXITCODE
            }
            Write-Host ""
            Write-Host "[SUCCESS] Package complete"
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

