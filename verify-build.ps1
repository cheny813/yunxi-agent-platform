# 验证项目构建状态的脚本
Write-Host "开始验证 yunxi-agent-platform 项目构建状态..."
Write-Host "

第一步：检出当前目录和pom.xml文件"
Write-Host "当前目录: $(Get-Location)"
Write-Host "pom.xml 存在: $(Test-Path 'pom.xml')"

Write-Host "
第二步：尝试编译主项目"
try {
    mvn clean compile -DskipTests
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ 主项目编译成功" -ForegroundColor Green
    } else {
        Write-Host "❌ 主项目编译失败" -ForegroundColor Red
        Write-Host "退出码: $LASTEXITCODE"
    }
} catch {
    Write-Host "❌ Maven命令执行失败: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "
第三步：尝试编译测试代码"
try {
    mvn clean test-compile -DskipTests
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ 测试代码编译成功" -ForegroundColor Green
    } else {
        Write-Host "❌ 测试代码编译失败" -ForegroundColor Red
        Write-Host "退出码: $LASTEXITCODE"
    }
} catch {
    Write-Host "❌ Maven命令执行失败: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "
验证完成"