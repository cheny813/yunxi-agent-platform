/**
 * remote-servers-ui.js — 远程服务器管理面板逻辑
 *
 * 包含：服务器列表加载、注册、连接/断开、远程部署/日志/监控
 * 依赖 ctx: { escapeHtml, showToast, addOutput }
 */

export function initRemoteServersUI(ctx) {
    const { escapeHtml, showToast, addOutput } = ctx;

    // ==================== 服务器列表 ====================

    async function loadRemoteServers() {
        const listEl = document.getElementById('remoteServerList');
        if (!listEl) return;

        try {
            const result = await window.agentAPI.remoteGetServers();
            if (result.status !== 'success' || !result.servers?.length) {
                listEl.innerHTML = '<div class="status-empty">暂无远程服务器</div>';
                return;
            }

            const cloudLabels = { huawei: '华为云', tencent: '腾讯云', aliyun: '阿里云', aws: 'AWS', internal: '内网机房', government: '政务云', custom: '其他' };
            const cloudColors = { huawei: '#e74c3c', tencent: '#3498db', aliyun: '#f39c12', aws: '#ff9900', internal: '#2ecc71', government: '#9b59b6', custom: '#95a5a6' };

            listEl.innerHTML = result.servers.map(srv => `
                <div class="remote-server-card" style="background:var(--bg-secondary);padding:14px;border-radius:10px;border:1px solid var(--border-color)">
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
                        <div style="display:flex;align-items:center;gap:8px">
                            <span style="width:8px;height:8px;border-radius:50%;background:${srv.connected ? 'var(--success)' : 'var(--text-muted)'}"></span>
                            <strong style="font-size:14px">${escapeHtml(srv.name)}</strong>
                            <span style="padding:2px 8px;border-radius:10px;font-size:11px;background:${cloudColors[srv.cloud] || '#95a5a6'}22;color:${cloudColors[srv.cloud] || '#95a5a6'};border:1px solid ${cloudColors[srv.cloud] || '#95a5a6'}44">${cloudLabels[srv.cloud] || srv.cloud}</span>
                        </div>
                        <div style="display:flex;gap:6px">
                            ${!srv.connected ? `<button class="btn btn-sm btn-primary" onclick="window._remoteConnect('${srv.id}')">连接</button>` : `<button class="btn btn-sm btn-ghost" onclick="window._remoteDisconnect('${srv.id}')">断开</button>`}
                            <button class="btn btn-sm btn-ghost" style="color:var(--danger)" onclick="window._remoteRemove('${srv.id}')">删除</button>
                        </div>
                    </div>
                    <div style="font-size:12px;color:var(--text-secondary);display:flex;gap:16px;flex-wrap:wrap">
                        <span>${srv.username}@${srv.host}:${srv.port}</span>
                        ${srv.lastActivity ? `<span>最后活动: ${new Date(srv.lastActivity).toLocaleString()}</span>` : ''}
                    </div>
                    ${srv.connected ? `
                    <div style="display:flex;gap:6px;margin-top:10px;flex-wrap:wrap">
                        <button class="btn btn-sm btn-primary" onclick="window._remoteDeploy('${srv.id}')">远程部署</button>
                        <button class="btn btn-sm btn-secondary" onclick="window._remoteLogTail('${srv.id}')">查看日志</button>
                        <button class="btn btn-sm btn-ghost" onclick="window._remoteMonitor('${srv.id}')">服务监控</button>
                    </div>` : ''}
                </div>
            `).join('');
        } catch (e) {
            listEl.innerHTML = `<div class="status-empty">加载失败: ${e.message}</div>`;
        }
    }

    // ==================== 远程操作全局函数 ====================

    window._remoteConnect = async (serverId) => {
        showToast('正在连接...', 'info');
        const result = await window.agentAPI.remoteConnect(serverId);
        showToast(result.message, result.status === 'success' ? 'success' : 'error');
        loadRemoteServers();
    };
    window._remoteDisconnect = async (serverId) => {
        const result = await window.agentAPI.remoteDisconnect(serverId);
        showToast(result.message, 'info');
        loadRemoteServers();
    };
    window._remoteRemove = async (serverId) => {
        if (!confirm('确定删除此服务器配置？')) return;
        const result = await window.agentAPI.remoteRemoveServer(serverId);
        showToast(result.message, result.status === 'success' ? 'success' : 'error');
        loadRemoteServers();
    };
    window._remoteDeploy = async (serverId) => {
        showToast('开始远程部署...', 'info');
        const result = await window.agentAPI.remoteDeploy(serverId, {});
        showToast(result.message || (result.status === 'success' ? '部署完成' : '部署失败'), result.status === 'success' ? 'success' : 'error');
    };
    window._remoteLogTail = async (serverId) => {
        const panel = document.getElementById('remoteLogPanel');
        const output = document.getElementById('remoteLogOutput');
        if (panel) panel.style.display = 'block';
        if (output) output.innerHTML = '<div style="color:var(--info)">正在启动远程日志监控...</div>';
        const result = await window.agentAPI.remoteLogTailStart(serverId);
        if (result.status !== 'success') {
            if (output) output.innerHTML += `<div style="color:var(--danger)">${escapeHtml(result.message)}</div>`;
        }
    };
    window._remoteMonitor = async (serverId) => {
        const result = await window.agentAPI.remoteMonitorStart(serverId, 30000);
        showToast(result.status === 'success' ? '服务监控已启动' : result.message, result.status === 'success' ? 'success' : 'error');
    };

    // ==================== 注册服务器 ====================

    const btnRegisterServer = document.getElementById('btnRegisterServer');
    if (btnRegisterServer) {
        btnRegisterServer.addEventListener('click', async () => {
            const authType = document.getElementById('rsAuthType').value;
            const server = {
                name: document.getElementById('rsName').value.trim(),
                host: document.getElementById('rsHost').value.trim(),
                port: parseInt(document.getElementById('rsPort').value) || 22,
                username: document.getElementById('rsUsername').value.trim() || 'root',
                authType,
                password: authType === 'password' ? document.getElementById('rsPassword').value : '',
                privateKeyPath: authType === 'privateKey' ? document.getElementById('rsPrivateKey').value.trim() : '',
                cloud: document.getElementById('rsCloud').value,
                deployConfig: {
                    deployScript: document.getElementById('rsDeployScript').value.trim() || '/opt/deploy.sh',
                    logPath: document.getElementById('rsLogPath').value.trim() || '/var/log/app/application.log',
                    healthCheckUrl: document.getElementById('rsHealthUrl').value.trim() || '',
                },
            };

            if (!server.name || !server.host) {
                showToast('请填写服务器名称和主机地址', 'error');
                return;
            }

            const result = await window.agentAPI.remoteRegisterServer(server);
            showToast(result.status === 'success' ? '服务器注册成功' : result.message, result.status === 'success' ? 'success' : 'error');
            if (result.status === 'success') loadRemoteServers();
        });
    }

    // 认证方式切换
    document.getElementById('rsAuthType')?.addEventListener('change', (e) => {
        document.getElementById('rsPasswordGroup').style.display = e.target.value === 'password' ? '' : 'none';
        document.getElementById('rsKeyGroup').style.display = e.target.value === 'privateKey' ? '' : 'none';
    });

    // 远程日志停止按钮
    document.getElementById('btnRemoteLogStop')?.addEventListener('click', async () => {
        const result = await window.agentAPI.remoteGetServers();
        if (result.status === 'success') {
            for (const srv of result.servers) {
                await window.agentAPI.remoteLogTailStop(srv.id);
            }
        }
        const panel = document.getElementById('remoteLogPanel');
        if (panel) panel.style.display = 'none';
        showToast('日志监控已停止', 'info');
    });

    // ==================== 远程事件监听 ====================

    window.agentAPI.onRemoteEvent?.((data) => {
        if (data.type === 'log' || data.type === 'log_error') {
            const output = document.getElementById('remoteLogOutput');
            if (output) {
                const lineClass = data.type === 'log_error' || data.text?.includes('ERROR') ? 'error' : '';
                output.innerHTML += `<div class="${lineClass}" style="white-space:pre-wrap">${escapeHtml(data.text || '')}</div>`;
                output.scrollTop = output.scrollHeight;
            }
        }

        if (data.type === 'monitor') {
            const checks = data.checks;
            addOutput({
                status: 'info',
                command: 'remote-monitor',
                message: `[${data.serverId}] CPU: ${checks.cpuPercent?.toFixed(1) || 'N/A'}% | MEM: ${checks.memoryPercent?.toFixed(1) || 'N/A'}% | DISK: ${checks.diskPercent?.toFixed(1) || 'N/A'}% | Health: ${checks.healthStatus || 'N/A'}`
            });
        }

        if (data.type === 'connected' || data.type === 'disconnected') {
            loadRemoteServers();
        }
        if (data.type === 'deploy_completed' || data.type === 'deploy_failed') {
            showToast(data.message, data.type === 'deploy_completed' ? 'success' : 'error');
        }
    });

    return { loadRemoteServers };
}
