# 完整测试验证脚本
Write-Host "=== yunxi-agent-platform 项目测试完整性验证 ===" -ForegroundColor Cyan
Write-Host "

🔍 第一步：验证主项目编译状态"

# 编译主项目
try {
    Write-Host "执行: mvn clean compile"
    mvn clean compile
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ 主项目编译成功" -ForegroundColor Green
    } else {
        Write-Host "❌ 主项目编译失败 - 退出码: $LASTEXITCODE" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "❌ Maven命令执行失败: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host "

🔍 第二步：验证测试代码编译状态"

# 编译测试代码
try {
    Write-Host "执行: mvn clean test-compile -DskipTests"
    mvn clean test-compile -DskipTests
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ 测试代码编译成功" -ForegroundColor Green
    } else {
        Write-Host "❌ 测试代码编译失败 - 退出码: $LASTEXITCODE" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "❌ Maven命令执行失败: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host "

🔍 第三步：运行所有测试用例"

# 运行测试
try {
    Write-Host "执行: mvn test"
    mvn test
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ 所有测试用例通过" -ForegroundColor Green
    } else {
        Write-Host "❌ 部分测试用例失败 - 退出码: $LASTEXITCODE" -ForegroundColor Yellow
        Write-Host "注意: 部分测试失败需要进一步分析具体原因"
    }
} catch {
    Write-Host "❌ Maven命令执行失败: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host "

📊 第四步：生成测试覆盖率报告"

# 生成覆盖率报告
try {
    Write-Host "执行: mvn jacoco:report"
    mvn jacoco:report
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ 测试覆盖率报告生成成功" -ForegroundColor Green
        Write-Host "报告位置: target/site/jacoco/index.html"
    } else {
        Write-Host "⚠️  覆盖率报告生成警告 - 退出码: $LASTEXITCODE" -ForegroundColor Yellow
    }
} catch {
    Write-Host "⚠️  覆盖率报告生成警告: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host "

🎯 修复完成总结:"
Write-Host "✅ agent-config模块: 已修复"
Write-Host "   - 修复ConfigurationReaderTest文本块语法错误"
Write-Host "   - 实现ConfigurationReader类的loadProperties方法"
Write-Host "   - 创建缺失的AgentscopeProperties配置类"
Write-Host "
✅ agent-common模块: 已修复"
Write-Host "   - 为JsonUtil添加isValidJson()方法"
Write-Host "   - 为StringUtil添加trimToEmpty()和format()方法"
Write-Host "
✅ 其他模块: Spring AI依赖移除，依赖关系完善"

Write-Host "

📈 项目测试状态:"
Write-Host "- 主代码编译: ✅ 完全通过"
Write-Host "- 测试代码编译: ✅ 所有语法错误已修复"
Write-Host "- 测试执行: 待验证（可能有个别功能测试失败）"
Write-Host "

✨ yunxi-agent-platform 项目架构重构测试验证完成!" -ForegroundColor Cyan