<#
.SYNOPSIS
    自动选型端到端验证脚本。

.DESCRIPTION
    编译 yunxi 依赖模块与 yunxi-integration-test，并只运行 AutoRoutingSelectionE2ETest，
    验证 auto-assistant 在「纯文本 / 含图 / 含视频」三类请求下选中的模型是否符合预期：
      - 纯文本  -> 成本档最低的纯文本模型（dashscope:qwen3.8-flash）
      - 含图    -> 具备 vision 的多模态模型（dashscope:qwen3.8-omni-flash）
      - 含视频  -> 具备 video 的多模态模型（dashscope:qwen3.8-omni-flash）

    该测试复用 application.yml 中真实的模型 catalog / routing，仅把最终模型解析替换为
    recording 桩，不发起真实 API 调用、不依赖外部服务，可离线确定性运行。

.PARAMETER SkipBuild
    若已编译过依赖，可传 -SkipBuild 跳过 -am 的重新编译，仅运行测试。
#>
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $logDir = Join-Path $repoRoot 'tmp'
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
    $log = Join-Path $logDir 'verify-auto-routing.log'

    # -Dtest 过滤在整个 reactor 生效：依赖模块无此测试类会被跳过（failIfNoSpecifiedTests=false），
    # 因此不会触发其他模块的偶发测试（如 Windows 上 Mockito 自 attach 偶发失败）。
    $cmd = 'mvn -o -pl yunxi-integration-test -am test ' +
           '-Dtest=AutoRoutingSelectionE2ETest ' +
           '-Dsurefire.failIfNoSpecifiedTests=false'
    if ($SkipBuild) {
        $cmd = 'mvn -o -pl yunxi-integration-test surefire:test ' +
               '-Dtest=AutoRoutingSelectionE2ETest ' +
               '-Dsurefire.failIfNoSpecifiedTests=false'
    }

    Write-Host ">>> 运行：$cmd" -ForegroundColor Cyan
    Invoke-Expression "$cmd > `"$log`" 2>&1"
    $exit = $LASTEXITCODE

    Write-Host ">>> 完整日志：$log" -ForegroundColor DarkGray
    if ($exit -eq 0) {
        Write-Host ">>> 自动选型端到端验证：通过 (PASS)" -ForegroundColor Green
        # 摘录关键断言对应的选中结果
        Select-String -Path $log -Pattern 'Tests run:' | ForEach-Object { Write-Host $_.Line }
    } else {
        Write-Host ">>> 自动选型端到端验证：失败 (FAIL, exit=$exit)" -ForegroundColor Red
        Write-Host "    查看：$log" -ForegroundColor Yellow
    }
    exit $exit
} finally {
    Pop-Location
}
