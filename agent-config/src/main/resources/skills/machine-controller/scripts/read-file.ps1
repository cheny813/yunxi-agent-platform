# 读取文件内容脚本（PowerShell版）
# 用法: .\read-file.ps1 <文件路径>
# 输出: JSON 格式

param(
    [Parameter(Position=0)]
    [string]$FilePath
)

if ([string]::IsNullOrEmpty($FilePath)) {
    Write-Output (@{status="error"; message="请指定文件路径"} | ConvertTo-Json -Compress)
    exit 1
}

if (-not (Test-Path -Path $FilePath -PathType Leaf)) {
    Write-Output (@{status="error"; message="文件不存在: $FilePath"} | ConvertTo-Json -Compress)
    exit 1
}

try {
    $fileInfo = Get-Item -Path $FilePath -ErrorAction Stop
    $fileSize = $fileInfo.Length

    # 检查文件大小（10MB限制）
    if ($fileSize -gt 10485760) {
        Write-Output (@{status="error"; message="文件过大: $fileSize bytes (最大 10MB)"} | ConvertTo-Json -Compress)
        exit 1
    }

    # 读取文件内容
    $content = Get-Content -Path $FilePath -Raw -Encoding UTF8 -ErrorAction Stop

    # 限制内容大小
    if ($content.Length -gt 100000) {
        $content = $content.Substring(0, 100000)
    }

    $result = @{
        status = "success"
        path = $FilePath
        size = $fileSize
        content = $content
    }

    Write-Output ($result | ConvertTo-Json -Compress -Depth 3)
} catch {
    Write-Output (@{status="error"; message=$_.Exception.Message} | ConvertTo-Json -Compress)
    exit 1
}