#!/bin/bash

# 🚀 yunxi-agent-platform 优化验证脚本
# 验证PMD代码质量修复和各项性能优化效果

echo "=================================================="
echo "🔍 yunxi-agent-platform 优化效果验证"
echo "=================================================="

# 配置颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 获取当前目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo -e "${BLUE}项目根目录: $PROJECT_ROOT${NC}"

# 1. 验证项目结构
check_project_structure() {
    echo -e "\n${BLUE}1. 验证项目结构${NC}"
    
    local missing_files=()
    
    # 检查PMD文件是否存在
    if [ ! -f "$PROJECT_ROOT/pmd-rules.xml" ]; then
        missing_files+=("pmd-rules.xml")
    fi
    
    # 检查配置文件
    if [ ! -f "$PROJECT_ROOT/src/main/resources/application.yml" ]; then
        missing_files+=("application.yml")
    fi
    
    if [ ! -d "$PROJECT_ROOT/src/main/resources/config" ]; then
        missing_files+=("config/目录")
    fi
    
    # 检查配置文件
    config_files=("server.yml" "cache.yml" "async.yml")
    for config in "${config_files[@]}"; do
        if [ ! -f "$PROJECT_ROOT/src/main/resources/config/$config" ]; then
            missing_files+=("config/$config")
        fi
    done
    
    # 检查脚本文件
    script_files=("performance-test.java" "performance-monitor.groovy" "deployment-guide.md")
    for script in "${script_files[@]}"; do
        if [ ! -f "$PROJECT_ROOT/scripts/$script" ]; then
            missing_files+=("scripts/$script")
        fi
    done
    
    if [ ${#missing_files[@]} -eq 0 ]; then
        echo -e "${GREEN}✅ 项目结构完整${NC}"
    else
        echo -e "${YELLOW}⚠️  缺少文件: ${missing_files[*]}${NC}"
    fi
}

# 2. 验证代码质量
check_code_quality() {
    echo -e "\n${BLUE}2. 验证代码质量${NC}"
    
    cd "$PROJECT_ROOT"
    
    # 检查是否能编译
    echo -e "${YELLOW}编译项目...${NC}"
    if mvn compile -q; then
        echo -e "${GREEN}✅ 编译成功${NC}"
    else
        echo -e "${RED}❌ 编译失败${NC}"
        return 1
    fi
    
    # 运行单元测试
    echo -e "${YELLOW}运行单元测试...${NC}"
    if mvn test -q; then
        echo -e "${GREEN}✅ 单元测试通过${NC}"
    else
        echo -e "${RED}❌ 单元测试失败${NC}"
    fi
    
    # 运行PMD检查
    echo -e "${YELLOW}运行PMD代码质量检查...${NC}"
    if mvn pmd:check -q; then
        echo -e "${GREEN}✅ PMD检查通过${NC}"
    else
        echo -e "${RED}❌ PMD检查发现代码质量问题${NC}"
    fi
}

# 3. 验证性能配置
check_performance_config() {
    echo -e "\n${BLUE}3. 验证性能配置${NC}"
    
    cd "$PROJECT_ROOT"
    
    # 检查JVM配置
    if grep -q "Xms" "$PROJECT_ROOT/pom.xml" || grep -q "Xmx" "$PROJECT_ROOT/pom.xml"; then
        echo -e "${GREEN}✅ JVM内存配置已设置${NC}"
    else
        echo -e "${YELLOW}⚠️  JVM内存配置未在pom.xml中设置${NC}"
    fi
    
    # 检查数据库连接池配置
    if grep -q "hikari" "$PROJECT_ROOT/src/main/resources/config/server.yml"; then
        echo -e "${GREEN}✅ HikariCP连接池配置已设置${NC}"
    else
        echo -e "${YELLOW}⚠️  HikariCP连接池配置未找到${NC}"
    fi
    
    # 检查异步配置
    if grep -q "async" "$PROJECT_ROOT/src/main/resources/config/async.yml"; then
        echo -e "${GREEN}✅ 异步处理配置已设置${NC}"
    else
        echo -e "${YELLOW}⚠️  异步处理配置未找到${NC}"
    fi
    
    # 检查缓存配置
    if grep -q "cache" "$PROJECT_ROOT/src/main/resources/config/cache.yml"; then
        echo -e "${GREEN}✅ 缓存配置已设置${NC}"
    else
        echo -e "${YELLOW}⚠️  缓存配置未找到${NC}"
    fi
}

# 4. 验证监控配置
check_monitoring_config() {
    echo -e "\n${BLUE}4. 验证监控配置${NC}"
    
    # 检查Actuator端点
    if grep -q "management" "$PROJECT_ROOT/src/main/resources/application.yml"; then
        echo -e "${GREEN}✅ Spring Boot Actuator已配置${NC}"
    else
        echo -e "${YELLOW}⚠️  Spring Boot Actuator未配置${NC}"
    fi
    
    # 检查性能监控工具
    if [ -f "$PROJECT_ROOT/scripts/performance-monitor.groovy" ]; then
        echo -e "${GREEN}✅ 性能监控脚本已创建${NC}"
    else
        echo -e "${YELLOW}⚠️  性能监控脚本未创建${NC}"
    fi
    
    # 检查线程池监控
    if grep -q "ThreadPoolMonitor" "$PROJECT_ROOT" -r; then
        echo -e "${GREEN}✅ 线程池监控工具已创建${NC}"
    else
        echo -e "${YELLOW}⚠️  线程池监控工具未创建${NC}"
    fi
}

# 5. 验证部署配置
check_deployment_config() {
    echo -e "\n${BLUE}5. 验证部署配置${NC}"
    
    # 检查Docker配置
    if [ -f "$PROJECT_ROOT/Dockerfile" ]; then
        echo -e "${GREEN}✅ Dockerfile已创建${NC}"
    else
        echo -e "${YELLOW}⚠️  Dockerfile未创建${NC}"
    fi
    
    # 检查部署脚本
    if [ -f "$PROJECT_ROOT/scripts/deployment-guide.md" ]; then
        echo -e "${GREEN}✅ 部署指南已创建${NC}"
    else
        echo -e "${YELLOW}⚠️  部署指南未创建${NC}"
    fi
    
    # 检查启动脚本
    if [ -f "$PROJECT_ROOT/scripts/startup.sh" ]; then
        echo -e "${GREEN}✅ 启动脚本已创建${NC}"
    else
        echo -e "${YELLOW}⚠️  启动脚本未创建${NC}"
    fi
}

# 6. 生成验证报告
generate_report() {
    echo -e "\n${BLUE}6. 生成验证报告${NC}"
    
    local report_file="$PROJECT_ROOT/optimization-validation-report.txt"
    
    cat > "$report_file" <<EOF
yunxi-agent-platform 优化验证报告
生成时间: $(date)
项目版本: 1.0.0
验证结果汇总:

1. 项目结构验证: $(if [ ${#missing_files[@]} -eq 0 ]; then echo "通过"; else echo "警告"; fi)
   - 缺少文件: ${missing_files[*]}

2. 代码质量验证: 
   - 编译状态: $(if mvn compile -q &>/dev/null; then echo "成功"; else echo "失败"; fi)
   - 测试状态: $(if mvn test -q &>/dev/null; then echo "成功"; else echo "失败"; fi)
   - PMD检查: $(if mvn pmd:check -q &>/dev/null; then echo "通过"; else echo "失败"; fi)

3. 性能配置验证:
   - JVM配置: $(if grep -q "Xms" "$PROJECT_ROOT/pom.xml"; then echo "已设置"; else echo "未设置"; fi)
   - 连接池: $(if grep -q "hikari" "$PROJECT_ROOT/src/main/resources/config/server.yml"; then echo "已配置"; else echo "未配置"; fi)
   - 异步处理: $(if grep -q "async" "$PROJECT_ROOT/src/main/resources/config/async.yml"; then echo "已配置"; else echo "未配置"; fi)
   - 缓存配置: $(if grep -q "cache" "$PROJECT_ROOT/src/main/resources/config/cache.yml"; then echo "已配置"; else echo "未配置"; fi)

4. 监控配置验证:
   - Actuator: $(if grep -q "management" "$PROJECT_ROOT/src/main/resources/application.yml"; then echo "已配置"; else echo "未配置"; fi)
   - 性能监控: $(if [ -f "$PROJECT_ROOT/scripts/performance-monitor.groovy" ]; then echo "已创建"; else echo "未创建"; fi)
   - 线程池监控: $(if grep -q "ThreadPoolMonitor" "$PROJECT_ROOT" -r; then echo "已创建"; else echo "未创建"; fi)

5. 部署配置验证:
   - Docker配置: $(if [ -f "$PROJECT_ROOT/Dockerfile" ]; then echo "已创建"; else echo "未创建"; fi)
   - 部署指南: $(if [ -f "$PROJECT_ROOT/scripts/deployment-guide.md" ]; then echo "已创建"; else echo "未创建"; fi)
   - 启动脚本: $(if [ -f "$PROJECT_ROOT/scripts/startup.sh" ]; then echo "已创建"; else echo "未创建"; fi)

优化建议:
$(generate_optimization_suggestions)

---
报告生成完成，详细结果请查看各验证项日志。
EOF
    
    echo -e "${GREEN}✅ 验证报告已生成: $report_file${NC}"
}

# 生成优化建议
generate_optimization_suggestions() {
    cat <<EOF
1. 持续优化建议:
   - 定期运行性能测试，监控QPS和响应时间
   - 配置APM工具（如SkyWalking、Pinpoint）进行全链路监控
   - 设置自动化的健康检查和自愈机制
   - 建立性能基准测试，每次发布前进行对比

2. 扩展性建议:
   - 考虑微服务架构拆分，降低单体复杂度
   - 实现配置中心动态配置管理
   - 加入服务发现和负载均衡机制
   - 建立统一日志收集和分析平台

3. 安全性建议:
   - 实施API认证和授权机制
   - 配置HTTPS和网络访问控制
   - 定期进行安全扫描和漏洞修复
   - 建立数据备份和灾备方案
EOF
}

# 7. 性能基准测试（可选）
run_performance_benchmark() {
    echo -e "\n${BLUE}7. 执行性能基准测试${NC}"
    
    if command -v ab >/dev/null 2>&1; then
        echo -e "${YELLOW}运行Apache Bench测试...${NC}"
        # 这里可以添加实际的性能测试命令
        echo -e "${GREEN}✅ 性能测试框架已就绪${NC}"
    else
        echo -e "${YELLOW}⚠️  Apache Bench未安装，跳过性能测试${NC}"
    fi
}

# 主执行函数
main() {
    echo -e "${BLUE}开始优化验证过程...${NC}"
    
    # 执行各项验证
    check_project_structure
    check_code_quality
    check_performance_config
    check_monitoring_config
    check_deployment_config
    run_performance_benchmark
    generate_report
    
    echo -e "\n${GREEN}==================================================${NC}"
    echo -e "${GREEN}🎉 优化验证完成！请查看验证报告了解详细结果${NC}"
    echo -e "${GREEN}==================================================${NC}"
    
    # 显示项目构建命令
    echo -e "\n${YELLOW}📋 下一步操作建议:${NC}"
    echo -e "${YELLOW}1. 构建项目: mvn clean package${NC}"
    echo -e "${YELLOW}2. 运行测试: mvn test${NC}"
    echo -e "${YELLOW}3. 部署应用: 参考scripts/deployment-guide.md${NC}"
    echo -e "${YELLOW}4. 性能监控: 运行scripts/performance-monitor.groovy${NC}"
}

# 异常处理
handle_error() {
    echo -e "${RED}❌ 验证过程中发生错误${NC}"
    exit 1
}

# 设置错误处理
trap 'handle_error' ERR

# 检查必需的工具
check_requirements() {
    if ! command -v mvn >/dev/null 2>&1; then
        echo -e "${RED}❌ Maven未安装，请先安装Maven${NC}"
        exit 1
    fi
    
    if ! command -v java >/dev/null 2>&1; then
        echo -e "${RED}❌ Java未安装，请先安装Java${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✅ 环境检查通过${NC}"
}

# 显示帮助信息
show_help() {
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  -h, --help     显示此帮助信息"
    echo "  -v, --verbose  详细输出模式"
    echo "  -q, --quiet    静默模式"
    echo ""
    echo "功能:"
    echo "  验证yunxi-agent-platform项目的优化效果，包括:"
    echo "  • 项目结构完整性"
    echo "  • 代码质量（编译、测试、PMD检查）"
    echo "  • 性能配置（JVM、连接池、异步、缓存）"
    echo "  • 监控配置（Actuator、性能监控）"
    echo "  • 部署配置（Docker、脚本、指南）"
    echo ""
}

# 解析命令行参数
VERBOSE=false
QUIET=false

while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            exit 0
            ;;
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -q|--quiet)
            QUIET=true
            shift
            ;;
        *)
            echo -e "${RED}未知参数: $1${NC}"
            show_help
            exit 1
            ;;
    esac
done

# 主程序入口
if [ "$QUIET" = true ]; then
    main > /dev/null 2>&1
else
    check_requirements
    main
fi