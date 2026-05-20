/**
 * settings.js — 设置面板逻辑
 *
 * 包含：本地服务器启停、远程服务器连接、AI解析服务配置、区县/数据库配置、目录切换
 * 依赖 ctx: { el, addOutput, addActivity, showToast }
 */

export function initSettings(ctx) {
    const { el, addOutput, showToast } = ctx;

    let localServerRunning = false;
    let isConnected = false;
    let mcpDatabaseUrl = localStorage.getItem('yunxi_mcp_db_url') || '';
    let dbConnected = false;
    let currentDistrict = null;

    // 恢复 localStorage 中的值
    const savedServerUrl = localStorage.getItem('yunxi_server_url');
    const savedMcpDbUrl = localStorage.getItem('yunxi_mcp_db_url');
    const savedAiParseUrl = localStorage.getItem('yunxi_ai_parse_url');
    if (savedServerUrl) el.serverUrl.value = savedServerUrl;
    else el.serverUrl.value = '';
    if (savedMcpDbUrl) el.mcpDatabaseUrl.value = savedMcpDbUrl;
    else el.mcpDatabaseUrl.value = '';
    if (savedAiParseUrl) el.aiParseUrl.value = savedAiParseUrl;
    else el.aiParseUrl.value = '';

    // ==================== 本地服务器 ====================

    el.btnStartServer.addEventListener('click', async () => {
        if (localServerRunning) {
            localServerRunning = false;
            el.btnStartServer.textContent = '启动';
            el.btnStartServer.className = 'btn btn-success';
            el.localServerUrl.value = '';
            if (!isConnected) {
                el.statusText.textContent = '未连接';
                el.connectionStatus.className = 'status-dot offline';
            }
            addOutput({ status: 'success', command: 'local-server', message: '本地服务器已停止' });
        } else {
            try {
                const result = await window.agentAPI.startLocalServer();
                localServerRunning = true;
                el.localServerUrl.value = result.url;
                el.btnStartServer.textContent = '停止';
                el.btnStartServer.className = 'btn btn-danger';
                if (!isConnected) {
                    el.statusText.textContent = '本地模式';
                    el.connectionStatus.className = 'status-dot online';
                }
                addOutput({ status: 'success', command: 'local-server', message: `本地服务器已启动: ${result.url}` });
            } catch (err) {
                addOutput({ status: 'error', command: 'local-server', message: err.message });
            }
        }
    });

    // ==================== 远程连接 ====================

    el.btnConnect.addEventListener('click', async () => {
        if (isConnected) {
            try { await window.agentAPI.disconnectRemoteServer(); } catch (e) {}
            el.connectionStatus.className = 'status-dot offline';
            el.statusText.textContent = localServerRunning ? '本地模式' : '未连接';
            el.btnConnect.textContent = '连接';
            isConnected = false;
            addOutput({ status: 'success', command: 'disconnect', message: '已断开服务器连接' });
        } else {
            const url = el.serverUrl.value;
            try {
                await window.agentAPI.setServerUrl(url);
                localStorage.setItem('yunxi_server_url', url);
                await window.agentAPI.connectRemoteServer();
                el.connectionStatus.className = 'status-dot online';
                el.statusText.textContent = '已连接';
                el.btnConnect.textContent = '断开';
                isConnected = true;
                addOutput({ status: 'success', command: 'connect', message: `已连接到 ${url}` });
            } catch (err) {
                addOutput({ status: 'error', command: 'connect', message: err.message });
            }
        }
    });

    // ==================== 目录切换 ====================

    el.btnSelectFolder.addEventListener('click', async () => {
        const folder = await window.agentAPI.selectFolder();
        if (folder.status === 'success') {
            await window.agentAPI.setCwd(folder.path);
            el.cwdInfo.textContent = folder.path.split(/[/\\]/).pop();
            el.cwdInfo.title = folder.path;
            addOutput({ status: 'success', command: 'cd', message: `已切换到: ${folder.path}` });
        }
    });

    el.cwdInfo.addEventListener('click', () => el.btnSelectFolder.click());

    // ==================== AI 解析服务 ====================

    el.btnSaveAiUrl.addEventListener('click', async () => {
        const url = el.aiParseUrl.value.trim() || 'http://127.0.0.1:40001';
        el.aiParseUrl.value = url;
        localStorage.setItem('yunxi_ai_parse_url', url);
        try {
            await window.agentAPI.setAiParseUrl(url);
            el.btnSaveAiUrl.textContent = '已保存';
            setTimeout(() => { el.btnSaveAiUrl.textContent = '保存'; }, 1500);
        } catch (err) {
            addOutput({ status: 'error', command: 'config', message: '保存 AI 服务地址失败: ' + err.message });
        }
    });

    // ==================== 区县/数据库配置 ====================

    el.btnConnectDb.addEventListener('click', async () => {
        const url = el.mcpDatabaseUrl.value.trim();
        if (!url) { el.dbStatus.textContent = '请输入配置中心地址'; return; }

        mcpDatabaseUrl = url;
        localStorage.setItem('yunxi_mcp_db_url', url);
        el.btnConnectDb.disabled = true;
        el.btnConnectDb.textContent = '连接中...';

        try {
            const res = await fetch(`${url}/api/districts`);
            const data = await res.json();

            if (data.districts && data.districts.length > 0) {
                dbConnected = true;
                el.dbStatus.innerHTML = `<span style="color: var(--success);">✓ 已连接 (${data.districts.length} 个区县)</span>`;
                el.btnConnectDb.textContent = '已连接';

                const select = el.districtSelect;
                select.innerHTML = '<option value="">选择区县...</option>';
                data.districts.forEach(d => {
                    const opt = document.createElement('option');
                    opt.value = d.id;
                    opt.textContent = d.name;
                    select.appendChild(opt);
                });

                addOutput({ status: 'success', command: 'db-connect', message: `已连接，发现 ${data.districts.length} 个区县` });
            } else {
                el.dbStatus.innerHTML = `<span style="color: var(--warning);">⚠ 已连接，但暂无区县配置</span>`;
                el.btnConnectDb.textContent = '已连接';
                dbConnected = true;
            }
        } catch (err) {
            el.dbStatus.textContent = `连接失败: ${err.message}`;
            el.btnConnectDb.textContent = '连接';
            el.btnConnectDb.disabled = false;
            addOutput({ status: 'error', command: 'db-connect', message: `连接失败: ${err.message}` });
        }
    });

    el.districtSelect.addEventListener('change', async (e) => {
        const districtId = e.target.value;
        if (!districtId) {
            el.districtInfo.textContent = '选择区县后自动加载数据库配置';
            el.dbList.innerHTML = '';
            currentDistrict = null;
            return;
        }
        try {
            const res = await fetch(`${mcpDatabaseUrl}/api/districts/${districtId}/databases`);
            const data = await res.json();
            currentDistrict = districtId;
            const districtName = e.target.options[e.target.selectedIndex].text;
            el.districtInfo.textContent = `当前: ${districtName}`;

            let html = '';
            if (data.databases && data.databases.length > 0) {
                data.databases.forEach(db => {
                    html += `<div class="db-item"><strong>${db.db_type}</strong>: ${db.host}:${db.port}/${db.database_name}</div>`;
                });
            } else {
                html = '<div class="db-item" style="border-left-color: var(--text-muted);">暂无数据库配置</div>';
            }
            el.dbList.innerHTML = html;
            addOutput({ status: 'success', command: 'select-district', message: `已加载 ${districtName} 的数据库配置` });
        } catch (err) {
            addOutput({ status: 'error', command: 'select-district', message: `加载失败: ${err.message}` });
        }
    });

    // ==================== 初始化服务器状态 ====================

    async function checkLocalServerStatus() {
        try {
            const status = await window.agentAPI.getLocalServerUrl();
            if (status.running) {
                localServerRunning = true;
                el.localServerUrl.value = status.url;
                el.btnStartServer.textContent = '停止';
                el.btnStartServer.className = 'btn btn-danger';
                if (!isConnected) {
                    el.statusText.textContent = '本地模式';
                    el.connectionStatus.className = 'status-dot online';
                }
            }
        } catch (err) {
            console.error('检查本地服务器状态失败:', err);
        }
    }

    return {
        checkLocalServerStatus,
        get isLocalServerRunning() { return localServerRunning; },
        get isConnectedState() { return isConnected; },
    };
}
