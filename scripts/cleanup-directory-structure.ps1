# yunxi-agent-platform 目录结构清理脚本
# 此脚本用于整理项目目录结构，建议逐步执行

Write-Host "=== yunxi-agent-platform 目录结构清理脚本 ===" -ForegroundColor Cyan
Write-Host "请先备份整个项目再执行清理操作！" -ForegroundColor Yellow
Write-Host ""

# 第一步：创建标准的分类目录
Write-Host "1. 创建标准目录结构..." -ForegroundColor Green

# 创建脚本分类目录
New-Item -ItemType Directory -Force -Path "scripts/startup" | Out-Null
New-Item -ItemType Directory -Force -Path "scripts/shutdown" | Out-Null
New-Item -ItemType Directory -Force -Path "scripts/management" | Out-Null
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
Write-Host "   建议移动: 启动规则引擎.bat        -> scripts/startup/"
Write-Host "   建议移动: 启动AgentScopeStudio.bat -> scripts/startup/"  
Write-Host "   建议移动: 强制停止进程.bat        -> scripts/shutdown/"
Write-Host "   建议移动: config-template.txt     -> config/templates/"
Write-Host ""

Write-Host "3. 建议的重命名操作..." -ForegroundColor Green
Write-Host "   (以下操作涉及依赖关系，需谨慎测试)"
Write-Host ""
Write-Host "   建议重命名: sdk-js/ -> sdk/javascript/"
Write-Host "   建议重命名: skills/ -> resources/skills/"
Write-Host "   建议重命名: sql/    -> resources/sql/"
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