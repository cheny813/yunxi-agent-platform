#!/bin/bash
# quick-scan.sh - Linux 快速采集脚本（<30s）
# 用法: bash quick-scan.sh [output.json]

OUTPUT="${1:-/dev/stdout}"

cat <<'SCAN_EOF' | bash > "$OUTPUT"
#!/bin/bash
echo '{'
echo '  "timestamp": "'$(date -Iseconds)'",'

# 基本系统信息
echo '  "hostname": "'$(hostname)'",'
echo '  "os": "'$(uname -s) $(uname -r)'",'
echo '  "arch": "'$(uname -m)'",'

# CPU
echo '  "cpu": {'
echo '    "model": "'$(grep 'model name' /proc/cpuinfo | head -1 | cut -d: -f2 | xargs)'",'
echo '    "cores": '$(nproc)','
echo '    "load": "'$(cat /proc/loadavg | cut -d' ' -f1-3)'"'
echo '  },'

# 内存
echo '  "memory": {'
echo '    "totalMB": '$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)','
echo '    "freeMB": '$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)','
echo '    "swapTotalMB": '$(awk '/SwapTotal/ {print int($2/1024)}' /proc/meminfo)','
echo '    "swapFreeMB": '$(awk '/SwapFree/ {print int($2/1024)}' /proc/meminfo)'
echo '  },'

# 磁盘
echo '  "disk": ['
df -h --output=target,size,used,pcent -x tmpfs -x devtmpfs 2>/dev/null | tail -n +2 | while read line; do
    mount=$(echo "$line" | awk '{print $1}')
    size=$(echo "$line" | awk '{print $2}')
    used=$(echo "$line" | awk '{print $3}')
    pcent=$(echo "$line" | awk '{print $4}')
    echo "    {\"mount\": \"$mount\", \"size\": \"$size\", \"used\": \"$used\", \"usePercent\": \"$pcent\"},"
done | sed '$ s/,$//'
echo '  ],'

# 网络
echo '  "network": {'
echo '    "hostname": "'$(hostname)'",'
echo '    "ip": "'$(hostname -I 2>/dev/null | cut -d' ' -f1)'",'
echo '    "dns": "'$(grep nameserver /etc/resolv.conf 2>/dev/null | head -1 | awk '{print $2}')'"'
echo '  },'

# 已安装软件
echo '  "software": ['
for cmd in git node java python3 docker mysql redis-cli nginx mvn supervisord systemctl composer php go rust cargo; do
    p=$(which $cmd 2>/dev/null)
    if [ -n "$p" ]; then
        ver=$($cmd --version 2>/dev/null | head -1 | tr -d '"' | cut -c1-50)
        echo "    {\"name\": \"$cmd\", \"path\": \"$p\", \"version\": \"$ver\"},"
    fi
done | sed '$ s/,$//'
echo '  ],'

# 服务状态
echo '  "services": ['
for svc in nginx docker mysql redis sshd firewalld cron supervisord httpd postgresql mongod; do
    status=$(systemctl is-active $svc 2>/dev/null)
    if [ "$status" = "active" ]; then
        echo "    {\"name\": \"$svc\", \"status\": \"active\"},"
    fi
done | sed '$ s/,$//'
echo '  ],'

# 监听端口
echo '  "listeningPorts": ['
ss -tlnp 2>/dev/null | grep LISTEN | while read line; do
    addr=$(echo "$line" | awk '{print $4}')
    port=$(echo "$addr" | rev | cut -d: -f1 | rev)
    proc=$(echo "$line" | grep -oP 'users:\(\("\K[^"]+' || echo "unknown")
    echo "    {\"port\": $port, \"address\": \"$addr\", \"process\": \"$proc\"},"
done | sort -t: -k2 -n | uniq | head -30 | sed '$ s/,$//'
echo '  ]'

echo '}'
SCAN_EOF
