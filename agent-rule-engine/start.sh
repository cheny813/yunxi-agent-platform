#!/bin/bash

# ========================================
#   Agent Rule Engine 本地启动脚本
# ========================================

echo "========================================"
echo "  Agent Rule Engine"
echo "========================================"
echo ""

# 设置环境变量
export MYSQL_HOST=192.168.10.153
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=root

# 切换到模块根目录
cd "$(dirname "$0")/.."

# 检查是否已打包
if [ ! -f "target/agent-rule-engine-1.0.0.jar" ]; then
    echo "[INFO] 规则引擎未打包，正在执行打包..."
    echo ""
    mvn clean package -DskipTests
    if [ $? -ne 0 ]; then
        echo "[ERROR] 打包失败！"
        exit 1
    fi
    echo ""
    echo "[SUCCESS] 打包完成"
    echo ""
fi

echo "[INFO] 正在启动规则引擎..."
echo ""
echo "[提示] 规则引擎端口: 48081"
echo "[提示] REST API: http://localhost:48081/api/rules"
echo ""

java -Xmx512m -Xms256m \
     -Dfile.encoding=UTF-8 \
     -Dsun.stdout.encoding=UTF-8 \
     -Dsun.stderr.encoding=UTF-8 \
     -Dconsole.encoding=UTF-8 \
     -jar target/agent-rule-engine-1.0.0.jar
