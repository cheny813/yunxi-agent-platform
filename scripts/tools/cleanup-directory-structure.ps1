# yunxi-agent-platform 目录结构清理脚本
# 此脚本用于整理项目目录结构，建议逐步执行
#
# ⚠️ 历史草稿：本脚本是早期目录整理规划，部分建议（如将 sdk-js/ 改名、把
#    skills/、sql/ 移动）当前未采纳。脚本分类的规范布局以 scripts/README.md
# 为准：scripts/{perf,deploy,verify,tools}。


Write-Host "=== yunxi-agent-platform 目录结构清理脚本 ===" -ForegroundColor Cyan
Write-Host "请先备份整个项目再执行清理操作！" -ForegroundColor Yellow
Write-Host ""

# 第一步：创建标准的分类目录
Write-Host "1. 创建标准目录结构..." -ForegroundColor Green

# 创建脚本分类目录（当前规范布局，详见 scripts/README.md）
New-Item -ItemType Directory -Force -Path "scripts/perf" | Out-Null
New-Item -ItemType Directory -Force -Path "scripts/deploy" | Out-Null
New-Item -ItemType Directory -Force -Path "scripts/verify" | Out-Null
New-Item -ItemType Directory -Force -Path "scripts/tools" | Out-Null
Write-Host "  ✅ 创建 scripts/ 子目录"

# 创建配置分类目录  
New-Item -ItemType Directory -Force -Path "config/templates" | Out-Null
New-Item -ItemType Directory -Force -Path "config/examples" | Out-Null
Write-Host "  ✅ 创建 config/ 子目录"

# 创建部署统一目录
New-Item -ItemType Directory -Force -Path "deployment/k8s" | Out-Null
New-Item -ItemType Directory -Force -Path "deployment/helm" | Out-Null
Write-Host "  ✅ 创建 deployment/ 标准目录"

Write-Host ""
Write-Host "2. 建议移动的零散文件..." -ForegroundColor Green
Write-Host "   (以下文件建议手动移动，确保功能正常后删除原文件)"
Write-Host ""
Write-Host "   说明: 启动/停止脚本(启动项目.ps1、start.ps1、*.bat)保留在仓库根目录，不纳入 scripts/"
Write-Host "   建议移动: config-template.txt     -> config/templates/"
Write-Host ""

Write-Host "3. 建议的重命名操作..." -ForegroundColor Green
Write-Host "   (以下操作涉及依赖关系，需谨慎测试)"
Write-Host ""
Write-Host "   (以下为早期草稿，当前未采纳: sdk-js/、skills/、sql/ 保持不变)"
Write-Host ""

Write-Host "4. 部署文件整合建议..." -ForegroundColor Green
Write-Host "   (高风险操作，需要更新所有引用路径)"
Write-Host ""
Write-Host "   建议移动: k8s/*     -> deployment/k8s/"
Write-Host "   建议移动: helm/*    -> deployment/helm/"
Write-Host ""

Write-Host "=== 清理操作完成建议 ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "执行建议:"
Write-Host "1. 手动移动零散文件(步骤2)，确保启动正常"
Write-Host "2. 测试重命名操作(步骤3)，验证依赖关系"  
Write-Host "3. 最后整合部署文件(步骤4)，更新配置文件"
Write-Host ""
Write-Host "每次操作后进行以下验证:"
Write-Host "- 运行 mvn clean compile"
Write-Host "- 测试启动脚本功能"
Write-Host "- 检查部署相关配置"
Write-Host ""
Write-Host "清理完成！建议保持 git status 监控变化" -ForegroundColor Green