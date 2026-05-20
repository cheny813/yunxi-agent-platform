# 列出目录内容脚本（PowerShell版）
# 用法: .\list-dir.ps1 <目录路径>
# 输出: JSON 格式

param(
    [Parameter(Position=0)]
    [string]$Path = "."
)

# 检查目录是否存在
if (-not (Test-Path -Path $Path -PathType Container)) {
    Write-Output (@{status="error"; message="目录不存在: $Path"} | ConvertTo-Json -Compress)
    exit 1
}

try {
    $items = Get-ChildItem -Path $Path -Force -ErrorAction Stop
    $files = @()
    $dirs = @()

    foreach ($item in $items) {
        if ($item.PSIsContainer) {
            $dirs += $item.Name
        } else {
            $files += $item.Name
        }
    }

    $result = @{
        status = "success"
        path = $Path
        files = $files
        dirs = $dirs
        count = $items.Count
    }

    Write-Output ($result | ConvertTo-Json -Compress)
} catch {
    Write-Output (@{status="error"; message=$_.Exception.Message} | ConvertTo-Json -Compress)
    exit 1
}