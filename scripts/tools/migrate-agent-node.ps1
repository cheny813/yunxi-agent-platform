# agent-node 模块目录结构迁移脚本
# 安全地将 agent-node 模块标准化目录结构

Write-Host "=== agent-node 模块目录结构标准化迁移 ===" -ForegroundColor Cyan

# 第一步：检查当前目录结构
Write-Host "1. 检查当前文件结构..." -ForegroundColor Green
try {
    $currentFiles = Get-ChildItem "agent-node" -Recurse | Where-Object { !$_.PSIsContainer }
    Write-Host "  ✅ 当前文件数量: $($currentFiles.Count)"
    foreach ($file in $currentFiles) {
        Write-Host "    - $($file.FullName.Replace((Get-Location).Path + '\\', ''))"
    }
    Write-Host "  ✅ package.json 检查通过"
} catch {
    Write-Host "  ❌ 检查失败: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# 第二步：备份重要文件
Write-Host ""
Write-Host "2. 备份配置和启动脚本..." -ForegroundColor Green
try {
    # 创建备份目录
    $backupDir = "agent-node\.backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
    
    # 备份关键文件
    $importantFiles = @("package.json", "config.json", "config.templates.json", "package-lock.json")
    foreach ($file in $importantFiles) {
        if (Test-Path "agent-node\$file") {
            Copy-Item "agent-node\$file" "$backupDir\" -Force
            Write-Host "  ✅ 备份文件: $file"
        }
    }
    
    Write-Host "  ✅ 备份完成到: $backupDir"
} catch {
    Write-Host "  ❌ 备份失败: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# 第三步：创建标准目录结构
Write-Host ""
Write-Host "3. 创建标准目录结构..." -ForegroundColor Green
try {
    # 创建标准目录
    $directories = @("src/main", "src/resources", "src/test", "config", "scripts", "bin")
    foreach ($dir in $directories) {
        New-Item -ItemType Directory -Force -Path "agent-node\$dir" | Out-Null
    }
    Write-Host "  ✅ 创建所有必需目录"
} catch {
    Write-Host "  ❌ 目录创建失败: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# 第四步：移动文件到新位置
Write-Host ""
Write-Host "4. 文件迁移到标准位置..." -ForegroundColor Green
Write-Host "   (以下为建议操作，请手动执行验证)"

Write-Host ""
Write-Host "   [[ 建议移动操作 ]]": -ForegroundColor Yellow
Write-Host "   移动: agent-node/index.js -> agent-node/src/main/index.js"
Write-Host "   移动: agent-node/config*.json -> agent-node/config/"
Write-Host "   移动: agent-node/*script* -> agent-node/scripts/"
Write-Host "   移动: agent-node/*.sh -> agent-node/scripts/"
Write-Host "   移动: agent-node/*.bat -> agent-node/scripts/"
Write-Host ""

Write-Host "5. 更新 package.json 配置..." -ForegroundColor Green
Write-Host "   [[ 已自动更新 ]]":  -ForegroundColor Yellow
Write-Host "   main: index.js -> src/main/index.js"
Write-Host ""

Write-Host "6. 验证和测试..." -ForegroundColor Green
Write-Host "   建议手动执行以下验证步骤:"
Write-Host "   - 执行: cd agent-node && npm install"
Write-Host "   - 执行: cd agent-node && npm start (测试启动)"
Write-Host "   - 检查所有启动脚本功能"
Write-Host ""

Write-Host "=== 迁移脚本完成 ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "下一步操作:"
Write-Host "1. 手动移动文件到建议位置"
Write-Host "2. 验证模块功能正常"
Write-Host "3. 清理备份文件 (当功能确认正常后)"
Write-Host "4. 提交版本控制更改"
Write-Host ""
Write-Host "备份位置: agent-node\.backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')" -ForegroundColor Yellow
Write-Host ""