# 执行系统命令脚本（PowerShell版）
# 用法: .\execute-command.ps1 <命令> [参数...]
# 输出: JSON 格式

param(
    [Parameter(Position=0)]
    [string]$Command,

    [Parameter(Position=1)]
    [string[]]$Args
)

if ([string]::IsNullOrEmpty($Command)) {
    Write-Output (@{status="error"; message="请指定要执行的命令"} | ConvertTo-Json -Compress)
    exit 1
}

# 白名单检查（简化版）
$whitelist = @("ls", "dir", "cat", "type", "git", "python", "python3", "node", "java", "mvn", "gradle", "npm", "pnpm", "yarn", "pip", "docker", "curl", "ping", "hostname", "whoami", "uname")

if ($whitelist -notcontains $Command) {
    Write-Output (@{status="error"; message="命令不在白名单中: $Command"} | ConvertTo-Json -Compress)
    exit 1
}

try {
    # 执行命令，设置超时
    $processInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processInfo.FileName = $Command
    if ($Args) {
        $processInfo.Arguments = $Args -join " "
    }
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $processInfo

    $stdout = ""
    $stderr = ""

    $process.OutputDataReceived = {
        if (!$EventArgs.Data) { return }
        $script:stdout += $EventArgs.Data + "`n"
    }
    $process.ErrorDataReceived = {
        if (!$EventArgs.Data) { return }
        $script:stderr += $EventArgs.Data + "`n"
    }

    $process.Start() | Out-Null
    $process.BeginOutputReadLine()
    $process.BeginErrorReadLine()

    # 等待完成（30秒超时）
    $completed = $process.WaitForExit(30000)

    if (-not $completed) {
        $process.Kill()
        Write-Output (@{status="error"; message="命令执行超时"; command=$Command} | ConvertTo-Json -Compress)
        exit 1
    }

    $exitCode = $process.ExitCode

    $result = @{
        status = if ($exitCode -eq 0) { "success" } else { "error" }
        command = $Command
        stdout = $stdout.Trim()
        stderr = $stderr.Trim()
        exitCode = $exitCode
    }

    Write-Output ($result | ConvertTo-Json -Compress -Depth 2)
} catch {
    Write-Output (@{status="error"; message=$_.Exception.Message; command=$Command} | ConvertTo-Json -Compress)
    exit 1
}