# 启动脚本封装：把参数透传给真正的 start.ps1，使 .\启动项目.ps1 -Fast 等价于 .\start.ps1 -Fast
param(
    [switch]$Fast,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Remaining
)

$argList = @()
if ($Fast) { $argList += '-Fast' }
if ($Remaining) { $argList += $Remaining }

& $PSScriptRoot\start.ps1 @argList
