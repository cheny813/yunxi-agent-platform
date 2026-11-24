#Requires -Version 5.1
<#
.SYNOPSIS
    yunxi Agent Platform 统一启动脚本（PowerShell 版）
.DESCRIPTION
    构建并启动后端（yunxi-app），可选一并拉起前端 dev server（yunxi-nutritionist-web）。
    支持 Normal / Fast 两种模式：
      Normal：先 mvn 打包安装全部上游模块，再 spring-boot:run 启动；
      Fast：跳过打包，直接 spring-boot:run（要求此前已 install）。
    启动前会强制释放目标端口（结束占用该端口的任意旧实例），避免端口冲突导致静默失败。
    支持的大模型见 yunxi-config/src/main/resources/config/models.yml 的 yunxi.models（云端 DashScope + 本地 Ollama）。
.EXAMPLE
    .\start-yunxi.ps1            # 自动打包后启动（端口 40001，日志写入 tmp\app40001.log）
    .\start-yunxi.ps1 -Fast      # 快速启动（已打包）
    .\start-yunxi.ps1 -Web       # 一并拉起前端 dev server（http://localhost:5173）
    .\start-yunxi.ps1 -Port 8080 # 自定义后端端口
    .\start-yunxi.ps1 -Console   # 前台启动，日志直接输出到控制台（Ctrl+C 停止，不看文件）
#>

param(
    [switch]$Fast,
    [switch]$Web,
    [int]$Port = 40001,
    [switch]$Console,
    [string]$DashscopeKey = ''
)

$ErrorActionPreference = 'Stop'

# 统一控制台与 JVM 输出编码为 UTF-8，消除 Maven/javac 中文日志乱码。
# ① chcp 65001：External 进程直接写控制台时按 GBK 渲染，必须切代码页否则乱码；
# ② [Console]::OutputEncoding：PowerShell 读取外部进程输出按 UTF-8 解码；
# ③ MAVEN_OPTS / JAVA_TOOL_OPTIONS：Maven 主 JVM 与 fork 子 JVM（compiler 等）一律 UTF-8 输出。
try { chcp 65001 | Out-Null } catch { }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8Args = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:MAVEN_OPTS = $utf8Args
$env:JAVA_TOOL_OPTIONS = $utf8Args

# 按监听端口结束占用进程（覆盖 jar / mvn / 任意方式拉起的实例，不误伤其他 java）。
function Stop-ProcessByPort {
    param([int]$TargetPort)
    try {
        $lines = netstat -ano | Select-String ":$TargetPort\s" | Where-Object { $_ -match 'LISTENING' }
        $procIds = @()
        foreach ($l in $lines) {
            if ($l -match '\s+(\d+)\s*$') { $procIds += $Matches[1] }
        }
        $procIds = $procIds | Sort-Object -Unique
        # 注意：$pid 与 PowerShell 只读变量 $PID 不区分大小写，循环变量必须用其他名字（如 $procId），
        # 否则赋值会抛“无法覆盖变量 PID，因为该变量为只读变量或常量”，导致端口检测被跳过。
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

# 切换到脚本所在目录，避免相对路径错乱。
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

# 大模型密钥：本脚本是本地 dev 启动器，固定内置一个 dev key，并强制以进程级变量写入，
# 不读取任何预置于系统/用户/会话作用域的旧值，避免机器上残留的旧值劫持发往模型服务的 key。
# 如需临时切换模型 key，通过脚本参数 -DashscopeKey 显式传入（变量传递，不落地、不持久化），
# 而非设置系统环境变量。生产部署不通过此脚本启动，应通过网关/环境变量显式设置真实 key。
# 安全提示：真实密钥写入脚本后请勿提交含密钥的版本；泄露请立即在 DashScope 控制台吊销并更换。
$DevDashscopeKey = 'sk-49c0e25bd3'
# 仅在本进程内写入，作为子进程（后端 JVM）的环境变量传递给模型服务；仅在本次启动进程内生效，不持久化。
$env:DASHSCOPE_API_KEY = if ($DashscopeKey) { $DashscopeKey } else { $DevDashscopeKey }
# 本地开发默认值，与前端 dev 代理（vite.config.js 注入的 admin:yunxi-dev-pass）保持一致，
# 使浏览器对 /api 的请求被代理透明鉴权、不弹出原生密码框。
# 此脚本是本地 dev 启动器，且前端 dev 代理（vite.config.js）硬编码注入 admin:yunxi-dev-pass，
# 故强制以进程级变量写入同一 dev 口令，不读取任何预置于系统/用户/会话作用域的旧值，
# 避免启动终端里残留的旧口令（如旧随机口令）劫持导致前后端口令不一致而 401。
# 口令仅在本次启动进程内生效、传递给后端，不持久化到系统或用户作用域；生产部署不通过此脚本启动，经网关鉴权。
$env:YUNXI_SECURITY_USER_PASSWORD = 'yunxi-dev-pass'

# 释放目标端口，避免旧实例端口冲突导致静默失败。
Stop-ProcessByPort -TargetPort $Port

# 构建（Normal 模式）：安装全部上游模块到本地仓库。
if (-not $Fast) {
    Write-Host "==> 正在打包安装后端模块（mvn -o -pl yunxi-app -am install -DskipTests）..."
    cmd /c "mvn -o -pl yunxi-app -am install -DskipTests"
    if ($LASTEXITCODE -ne 0) { Write-Error "mvn install 失败"; exit 1 }
}

# 前台控制台模式：直接在控制台跑 spring-boot:run，日志实时可见（Ctrl+C 停止）。
# 不写文件、不做后台轮询；用户可在控制台直接观察启动与请求日志。
if ($Console) {
    Write-Host "==> 前台启动后端（端口 $Port，日志输出到控制台，Ctrl+C 停止）..."
    Write-Host "    管理员口令: $env:YUNXI_SECURITY_USER_PASSWORD"
    cmd /c "mvn -o -pl yunxi-app spring-boot:run -Dspring-boot.run.arguments=--server.port=$Port"
    exit $LASTEXITCODE
}

# 后台启动后端（spring-boot:run），日志写入 tmp\app<Port>.log。
Write-Host "==> 正在启动后端（端口 $Port）..."
$backendLog = Join-Path $PSScriptRoot "tmp\app$Port.log"
New-Item -ItemType Directory -Force -Path (Split-Path $backendLog) | Out-Null
$arg = "mvn -o -pl yunxi-app spring-boot:run -Dspring-boot.run.arguments=--server.port=$Port > `"$backendLog`" 2>&1"
$backendProc = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $arg -PassThru
Write-Host "后端 PID = $($backendProc.Id)；日志: $backendLog"

# 轮询健康检查，直到就绪或超时。
Write-Host "==> 等待后端就绪（轮询 /actuator/health）..."
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $code = (curl.exe -s -o NUL -w '%{http_code}' "http://localhost:$Port/actuator/health")
        if ($code -eq '200') { $ready = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ready) { Write-Error "后端未在预期时间内就绪，详见 $backendLog"; exit 1 }
Write-Host "==> 后端就绪: http://localhost:$Port/actuator/health"

# 可选：一并拉起前端 dev server。
if ($Web) {
    Write-Host "==> 正在启动前端（yunxi-nutritionist-web）..."
    # 释放 5173，避免残留的旧 vite 实例占用端口导致新 dev server 静默跳到 5174，
    # 而浏览器仍访问 5173 命中旧实例（vite 默认会在端口被占时自动 +1，不报错）。
    Stop-ProcessByPort -TargetPort 5173
    $feDir = Join-Path $PSScriptRoot 'yunxi-nutritionist-web'
    Push-Location $feDir
    if (-not (Test-Path node_modules)) { cmd /c 'npm install' }
    $frontendProc = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'npm run dev' -PassThru
    Pop-Location
    Write-Host "前端 PID = $($frontendProc.Id)；请访问 http://localhost:5173"
}

Write-Host ''
Write-Host '==> 启动完成。'
Write-Host "    后端: http://localhost:$Port  （管理员口令: $env:YUNXI_SECURITY_USER_PASSWORD）"
if ($Web) { Write-Host '    前端: http://localhost:5173' }
Write-Host "    停止后端: Get-NetTCPConnection -LocalPort $Port | ForEach-Object { Stop-Process -Id `$_.OwningProcess -Force }"

