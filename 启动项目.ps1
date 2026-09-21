#Requires -Version 5.1
# 兼容入口：重定向到 start.ps1
#
# 常用用法：
#   .\启动项目.ps1              # 打包（如需）后启动后端（40001）
#   .\启动项目.ps1 -Fast        # 快速启动（复用现有 JAR）
#   .\启动项目.ps1 -Clean       # 清理并重新打包后启动
#   .\启动项目.ps1 -Web         # 同时拉起前端 dev server（http://localhost:5173/）
#
# 前端是独立工程（agent-nutritionist-web），后端 JAR 不含其页面；
# 不加 -Web 时访问 5173 连不上属预期，不是后端故障。注意用 http，不是 https。
& $PSScriptRoot\start.ps1 @args