#!/bin/bash
# install-service.sh - 将 agent-node 安装为 Linux systemd 服务
# 用法: sudo bash install-service.sh

set -e

SERVICE_NAME="yunxi-agent-node"
INSTALL_DIR="/opt/yunxi-agent-node"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== 安装 $SERVICE_NAME 服务 ==="

# 1. 复制文件到安装目录
echo "复制文件到 $INSTALL_DIR ..."
mkdir -p "$INSTALL_DIR"
cp -r "$SCRIPT_DIR"/* "$INSTALL_DIR/"

# 2. 安装 Node.js 依赖
echo "安装依赖..."
cd "$INSTALL_DIR"
npm install --production

# 3. 创建 systemd 服务文件
cat > /etc/systemd/system/${SERVICE_NAME}.service <<EOF
[Unit]
Description=yunxi Agent Node - Headless Node Client
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=${INSTALL_DIR}
ExecStart=/usr/bin/node ${INSTALL_DIR}/index.js
Restart=always
RestartSec=10
Environment=NODE_ENV=production

# 安全限制
NoNewPrivileges=no
ProtectSystem=false

# 日志
StandardOutput=journal
StandardError=journal
SyslogIdentifier=${SERVICE_NAME}

[Install]
WantedBy=multi-user.target
EOF

# 4. 启用并启动服务
echo "启用服务..."
systemctl daemon-reload
systemctl enable ${SERVICE_NAME}

echo ""
echo "=== 安装完成 ==="
echo "启动服务: systemctl start ${SERVICE_NAME}"
echo "查看状态: systemctl status ${SERVICE_NAME}"
echo "查看日志: journalctl -u ${SERVICE_NAME} -f"
echo ""
echo "请编辑 ${INSTALL_DIR}/config.json 配置服务器地址和标签"
