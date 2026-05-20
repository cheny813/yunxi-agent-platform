#!/bin/bash
# deep-scan.sh - Linux 深度盘查脚本（3-10min）
# 用法: bash deep-scan.sh [output.json]

OUTPUT="${1:-/dev/stdout}"

echo '{' > "$OUTPUT"
echo '  "timestamp": "'$(date -Iseconds)'",' >> "$OUTPUT"
echo '  "scanType": "deep",' >> "$OUTPUT"

# 1. 先执行快速扫描获取基础数据
echo '  "quickScan":' >> "$OUTPUT"
bash "$(dirname "$0")/quick-scan.sh" /dev/stdout >> "$OUTPUT"
echo ',' >> "$OUTPUT"

# 2. 定时任务
echo '  "crontab": {' >> "$OUTPUT"
echo '    "root": "'$(crontab -l 2>/dev/null | grep -v '^#' | grep -v '^$' | head -20 | tr '\n' '|' | sed 's/|$/\\n/g')'",' >> "$OUTPUT"
# 系统定时任务
echo '    "system": [' >> "$OUTPUT"
ls /etc/cron.d/ 2>/dev/null | while read f; do
    echo "      \"$f\","
done | sed '$ s/,$//' >> "$OUTPUT"
echo '    ]' >> "$OUTPUT"
echo '  },' >> "$OUTPUT"

# 3. 环境变量（脱敏）
echo '  "environment": [' >> "$OUTPUT"
env | grep -viE '(password|secret|token|key|credential)' | sort | head -50 | while read line; do
    echo "    \"$line\","
done | sed '$ s/,$//' >> "$OUTPUT"
echo '  ],' >> "$OUTPUT"

# 4. 最近登录
echo '  "recentLogins": [' >> "$OUTPUT"
last -n 20 -w 2>/dev/null | while read line; do
    echo "    \"$line\","
done | sed '$ s/,$//' >> "$OUTPUT"
echo '  ],' >> "$OUTPUT"

# 5. 项目目录探测
echo '  "projectPaths": [' >> "$OUTPUT"
for dir in /opt /srv /home /var/www /data /usr/local/src; do
    if [ -d "$dir" ]; then
        find "$dir" -maxdepth 2 -name "pom.xml" -o -name "package.json" -o -name "requirements.txt" -o -name "Dockerfile" -o -name "docker-compose.yml" 2>/dev/null | while read p; do
            projDir=$(dirname "$p")
            projType="unknown"
            case "$p" in
                */pom.xml) projType="java-maven" ;;
                */package.json) projType="nodejs" ;;
                */requirements.txt) projType="python" ;;
                */Dockerfile|*/docker-compose.yml) projType="docker" ;;
            esac
            echo "    {\"path\": \"$projDir\", \"type\": \"$projType\"},"
        done
    fi
done | sed '$ s/,$//' >> "$OUTPUT"
echo '  ],' >> "$OUTPUT"

# 6. 日志路径
echo '  "logPaths": [' >> "$OUTPUT"
for dir in /var/log /opt/*/logs /srv/*/logs /data/logs; do
    if [ -d "$dir" ]; then
        echo "    {\"path\": \"$dir\", \"type\": \"log-dir\"},"
    fi
done | sed '$ s/,$//' >> "$OUTPUT"
echo '  ],' >> "$OUTPUT"

# 7. Docker 容器
echo '  "dockerContainers": [' >> "$OUTPUT"
if command -v docker &>/dev/null; then
    docker ps --format '{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}' 2>/dev/null | while IFS='|' read name image status ports; do
        echo "    {\"name\": \"$name\", \"image\": \"$image\", \"status\": \"$status\", \"ports\": \"$ports\"},"
    done | sed '$ s/,$//'
fi
echo '  ],' >> "$OUTPUT"

# 8. 防火墙规则
echo '  "firewallRules": [' >> "$OUTPUT"
if command -v firewall-cmd &>/dev/null; then
    firewall-cmd --list-all 2>/dev/null | while read line; do
        echo "    \"$line\","
    done | sed '$ s/,$//'
elif command -v iptables &>/dev/null; then
    iptables -L -n --line-numbers 2>/dev/null | head -30 | while read line; do
        echo "    \"$line\","
    done | sed '$ s/,$//'
fi
echo '  ],' >> "$OUTPUT"

# 9. SSL 证书检查
echo '  "sslCerts": [' >> "$OUTPUT"
find /etc /opt -name "*.pem" -o -name "*.crt" -o -name "*.cert" 2>/dev/null | head -20 | while read cert; do
    expiry=$(openssl x509 -enddate -noout -in "$cert" 2>/dev/null | cut -d= -f2)
    if [ -n "$expiry" ]; then
        echo "    {\"path\": \"$cert\", \"expiry\": \"$expiry\"},"
    fi
done | sed '$ s/,$//' >> "$OUTPUT"
echo '  ]' >> "$OUTPUT"

echo '}' >> "$OUTPUT"

echo "深度扫描完成: $OUTPUT" >&2
