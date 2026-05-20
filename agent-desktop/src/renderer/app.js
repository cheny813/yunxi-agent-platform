/**
 * yunxiClaw - 渲染进程脚本
 * 
 * 处理前端界面交互和与主进程的通信
 * @version 1.0.0
 */

// 等待 DOM 加载完成
document.addEventListener('DOMContentLoaded', async () => {
    // DOM 元素引用
    const elements = {
        // 状态
        connectionStatus: document.getElementById('connectionStatus'),
        statusText: document.getElementById('statusText'),
        systemInfo: document.getElementById('systemInfo'),
        cwdInfo: document.getElementById('cwdInfo'),
        versionInfo: document.getElementById('versionInfo'),
        
        // 本地服务器
        localServerUrl: document.getElementById('localServerUrl'),
        btnStartServer: document.getElementById('btnStartServer'),
        localServerStatus: document.getElementById('localServerStatus'),
        
        // 远程服务器
        serverUrl: document.getElementById('serverUrl'),
        btnConnect: document.getElementById('btnConnect'),
        
        // 自定义命令
        customCommand: document.getElementById('customCommand'),
        btnExecute: document.getElementById('btnExecute'),
        
        // 输出
        outputContent: document.getElementById('outputContent'),
        btnClearOutput: document.getElementById('btnClearOutput'),
        
        // 底部按钮
        btnSelectFolder: document.getElementById('btnSelectFolder'),
        btnHelp: document.getElementById('btnHelp'),
        
        // 操作按钮
        actionBtns: document.querySelectorAll('.action-btn'),

        // 数据库
        mcpDatabaseUrl: document.getElementById('mcpDatabaseUrl'),
        btnConnectDb: document.getElementById('btnConnectDb'),
        dbStatus: document.getElementById('dbStatus')
    };

    // MCP Database 连接状态
    let mcpDatabaseUrl = 'http://localhost:40101';
    let dbConnected = false;
    let currentDistrict = null;
    let localServerRunning = false;

    // 远程连接状态
    let isConnected = false;

    // 初始化系统信息
    async function initSystemInfo() {
        try {
            const info = await window.agentAPI.getSystemInfo();
            
            // 系统显示名称
            const platformMap = {
                'win32': 'Windows',
                'darwin': 'macOS',
                'linux': 'Linux'
            };
            elements.systemInfo.textContent = platformMap[info.platform] || info.platform;
            elements.versionInfo.textContent = `v${info.electronVersion}`;
            
            // 工作目录
            const cwd = await window.agentAPI.getCwd();
            elements.cwdInfo.textContent = cwd.split(/[/\\]/).pop() || cwd;
            elements.cwdInfo.title = cwd;
            
        } catch (err) {
            console.error('获取系统信息失败:', err);
        }
    }

    // 添加输出
    function addOutput(result) {
        const placeholder = elements.outputContent.querySelector('.placeholder');
        if (placeholder) {
            placeholder.remove();
        }

        const entry = document.createElement('div');
        entry.className = 'result-entry';

        const isSuccess = result.status === 'success';
        
        let content = `<div class="command">$ ${escapeHtml(result.command || result.operation || 'command')}</div>`;
        
        if (isSuccess) {
            const outputText = result.stdout || result.message || '执行成功';
            content += `<div class="success">${escapeHtml(outputText.substring(0, 2000))}</div>`;
        } else {
            const errorText = result.stderr || result.message || '执行失败';
            content += `<div class="error">${escapeHtml(errorText.substring(0, 2000))}</div>`;
        }

        if (result.exitCode !== undefined) {
            content += `<div class="exit-code">退出码: ${result.exitCode}</div>`;
        }

        entry.innerHTML = content;
        elements.outputContent.appendChild(entry);
        elements.outputContent.scrollTop = elements.outputContent.scrollHeight;
    }

    // HTML 转义
    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // 清空输出
    elements.btnClearOutput.addEventListener('click', () => {
        elements.outputContent.innerHTML = '<div class="placeholder">等待命令执行...</div>';
    });

    // 本地服务器启动/停止
    elements.btnStartServer.addEventListener('click', async () => {
        if (localServerRunning) {
            // 停止 - 实际只是刷新状态，不真正关闭服务器
            localServerRunning = false;
            elements.btnStartServer.textContent = '启动';
            elements.btnStartServer.className = 'btn btn-success';
            elements.localServerStatus.textContent = '已停止';
            elements.localServerStatus.className = 'status-badge';
            // 本地服务器停止时，顶部状态保持不变（显示远程连接状态）
            addOutput({
                status: 'success',
                command: 'local-server',
                message: '本地服务器已停止'
            });
        } else {
            // 启动
            try {
                const result = await window.agentAPI.startLocalServer();
                localServerRunning = true;
                elements.localServerUrl.value = result.url;
                elements.btnStartServer.textContent = '停止';
                elements.btnStartServer.className = 'btn btn-danger';
                elements.localServerStatus.textContent = '运行中';
                elements.localServerStatus.className = 'status-badge running';
                
                // 本地服务器启动时，更新顶部状态提示用户
                if (!isConnected) {
                    elements.statusText.textContent = '本地模式';
                }
                
                addOutput({
                    status: 'success',
                    command: 'local-server',
                    message: `本地服务器已启动: ${result.url}`
                });
            } catch (err) {
                addOutput({
                    status: 'error',
                    command: 'local-server',
                    message: err.message
                });
            }
        }
    });

    // 检查本地服务器状态
    async function checkLocalServerStatus() {
        try {
            const status = await window.agentAPI.getLocalServerUrl();
            if (status.running) {
                localServerRunning = true;
                elements.localServerUrl.value = status.url;
                elements.btnStartServer.textContent = '停止';
                elements.btnStartServer.className = 'btn btn-danger';
                elements.localServerStatus.textContent = '运行中';
                elements.localServerStatus.className = 'status-badge running';
                // 本地服务器运行时显示状态
                if (!isConnected) {
                    elements.statusText.textContent = '本地模式';
                }
            }
        } catch (err) {
            console.error('检查本地服务器状态失败:', err);
        }
    }

    // 切换连接状态（远程服务器）
    elements.btnConnect.addEventListener('click', async () => {
        if (isConnected) {
            // 断开
            try {
                await window.agentAPI.disconnectRemoteServer();
            } catch (e) {}
            elements.connectionStatus.className = 'status-dot offline';
            // 断开后，如果本地服务器运行则显示本地模式，否则显示未连接
            elements.statusText.textContent = localServerRunning ? '本地模式' : '未连接';
            elements.btnConnect.textContent = '连接';
            isConnected = false;
            addOutput({
                status: 'success',
                command: 'disconnect',
                message: '已断开服务器连接'
            });
        } else {
            // 连接
            const url = elements.serverUrl.value;
            try {
                await window.agentAPI.setServerUrl(url);
                await window.agentAPI.connectRemoteServer();
                elements.connectionStatus.className = 'status-dot online';
                elements.statusText.textContent = '已连接';
                elements.btnConnect.textContent = '断开';
                isConnected = true;
                addOutput({
                    status: 'success',
                    command: 'connect',
                    message: `已连接到 ${url}`
                });
            } catch (err) {
                addOutput({
                    status: 'error',
                    command: 'connect',
                    message: err.message
                });
            }
        }
    });

    // 执行自定义命令
    elements.btnExecute.addEventListener('click', async () => {
        const cmdStr = elements.customCommand.value.trim();
        if (!cmdStr) {
            addOutput({ status: 'error', command: '', message: '请输入命令' });
            return;
        }

        // 解析命令和参数
        const parts = cmdStr.split(' ');
        const command = parts[0];
        const args = parts.slice(1);

        addOutput({ status: 'success', command: cmdStr, message: '执行中...' });

        try {
            const result = await window.agentAPI.executeCommand(command, args);
            addOutput(result);
        } catch (err) {
            addOutput({ status: 'error', command: cmdStr, message: err.message });
        }
    });

    // 回车执行命令
    elements.customCommand.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            elements.btnExecute.click();
        }
    });

    // 快捷操作按钮
    elements.actionBtns.forEach(btn => {
        btn.addEventListener('click', async () => {
            const action = btn.dataset.action;
            addOutput({ status: 'success', command: action, message: '执行中...' });

            try {
                let result;
                
                switch (action) {
                    case 'browse':
                        const file = await window.agentAPI.selectFile({
                            filters: [
                                { name: '所有文件', extensions: ['*'] },
                                { name: 'Java', extensions: ['java'] },
                                { name: '前端', extensions: ['js', 'ts', 'vue', 'html', 'css'] }
                            ]
                        });
                        if (file.status === 'success') {
                            const content = await window.agentAPI.readFile(file.path);
                            addOutput({
                                status: 'success',
                                command: `cat ${file.path}`,
                                stdout: content.content ? `文件大小: ${content.size} 字节\n\n${content.content.substring(0, 1000)}...` : content.message
                            });
                        }
                        return;

                    case 'git-status':
                        result = await window.agentAPI.gitOperation('status', []);
                        break;

                    case 'git-pull':
                        result = await window.agentAPI.gitOperation('pull', ['origin', 'main']);
                        break;

                    case 'run-test':
                        result = await window.agentAPI.executeCommand('mvn', ['test']);
                        break;

                    case 'mvn-build':
                        result = await window.agentAPI.executeCommand('mvn', ['clean', 'package', '-DskipTests']);
                        break;

                    case 'npm-install':
                        result = await window.agentAPI.executeCommand('npm', ['install']);
                        break;

                    case 'mvn-clean':
                        result = await window.agentAPI.executeCommand('mvn', ['clean']);
                        break;

                    case 'system-info':
                        const info = await window.agentAPI.getSystemInfo();
                        addOutput({
                            status: 'success',
                            command: 'system-info',
                            stdout: `应用: ${info.name} v${info.version}
平台: ${info.platform} (${info.arch})
Node: ${info.nodeVersion}
Electron: ${info.electronVersion}
Chrome: ${info.chromeVersion}
工作目录: ${info.userDataDir}`
                        });
                        return;

                    default:
                        addOutput({ status: 'error', command: action, message: '未知操作' });
                        return;
                }

                if (result) {
                    addOutput(result);
                }
            } catch (err) {
                addOutput({ status: 'error', command: action, message: err.message });
            }
        });
    });

    // 切换目录
    elements.btnSelectFolder.addEventListener('click', async () => {
        const folder = await window.agentAPI.selectFolder();
        if (folder.status === 'success') {
            await window.agentAPI.setCwd(folder.path);
            elements.cwdInfo.textContent = folder.path.split(/[/\\]/).pop();
            elements.cwdInfo.title = folder.path;
            addOutput({
                status: 'success',
                command: 'cd',
                message: `已切换到: ${folder.path}`
            });
        }
    });

    // 点击工作目录也可以切换
    elements.cwdInfo.addEventListener('click', () => {
        elements.btnSelectFolder.click();
    });

    // 帮助按钮
    elements.btnHelp.addEventListener('click', async () => {
        await window.agentAPI.showMessage({
            type: 'info',
            title: 'yunxiClaw 帮助',
            message: 'yunxiClaw 小龙虾 智能助手',
            detail: `版本: 1.0.0

功能说明:
• 连接服务器后，AI 可以控制您的电脑
• 快捷操作：一键执行常用命令
• 自定义命令：输入任意命令执行
• 目录切换：选择不同的工作目录

快捷键:
• Ctrl+Shift+C: 显示窗口

支持的命令:
• git: status, pull, push, commit 等
• mvn: test, package, clean 等
• npm: install, run, test 等
• 其他系统命令

© 2026 阳光智园科技有限公司`
        });
    });

    // 监听命令结果（来自菜单等）
    window.agentAPI.onCommandResult((result) => {
        addOutput(result);
    });

    // 监听文件选择
    window.agentAPI.onFileSelected(async (data) => {
        const content = await window.agentAPI.readFile(data.path);
        addOutput({
            status: 'success',
            command: `open ${data.path}`,
            stdout: content.content || content.message
        });
    });

    // 监听本地服务器启动
    window.agentAPI.onServerStarted((data) => {
        localServerRunning = true;
        elements.localServerUrl.value = data.url;
        elements.btnStartServer.textContent = '停止';
        elements.btnStartServer.className = 'btn btn-danger';
        elements.localServerStatus.textContent = '运行中';
        elements.localServerStatus.className = 'status-badge running';
    });

    // 监听 AI 连接
    window.agentAPI.onAIConnected((data) => {
        addOutput({
            status: 'success',
            command: 'ai-connected',
            message: `AI 已连接 (${data.count} 个连接)`
        });
    });

    // 监听 AI 断开
    window.agentAPI.onAIDisconnected((data) => {
        addOutput({
            status: 'success',
            command: 'ai-disconnected',
            message: `AI 已断开 (${data.count} 个连接)`
        });
    });

    // 监听 AI 命令
    window.agentAPI.onAICommand((data) => {
        addOutput({
            status: data.result && data.result.status === 'success' ? 'success' : 'error',
            command: data.type,
            message: data.result && data.result.stdout ? data.result.stdout : (data.result && data.result.message || '命令已执行')
        });
    });

    // 数据库/区县配置连接
    elements.btnConnectDb.addEventListener('click', async () => {
        const url = elements.mcpDatabaseUrl.value.trim();
        if (!url) {
            elements.dbStatus.textContent = '请输入配置中心地址';
            return;
        }

        mcpDatabaseUrl = url;
        elements.btnConnectDb.disabled = true;
        elements.btnConnectDb.textContent = '连接中...';

        try {
            // 获取区县列表
            const res = await fetch(`${url}/api/districts`);
            const data = await res.json();

            if (data.districts && data.districts.length > 0) {
                dbConnected = true;
                elements.dbStatus.innerHTML = `<span style="color: green;">✓ 已连接 (${data.districts.length} 个区县)</span>`;
                elements.btnConnectDb.textContent = '已连接';

                // 填充区县下拉框
                const select = document.getElementById('districtSelect');
                select.innerHTML = '<option value="">选择区县...</option>';
                data.districts.forEach(d => {
                    const opt = document.createElement('option');
                    opt.value = d.id;
                    opt.textContent = d.name;
                    select.appendChild(opt);
                });

                addOutput({
                    status: 'success',
                    command: 'db-connect',
                    message: `已连接到配置中心，发现 ${data.districts.length} 个区县`
                });
            } else {
                elements.dbStatus.innerHTML = `<span style="color: orange;">⚠ 已连接，但暂无区县配置</span>`;
                elements.btnConnectDb.textContent = '已连接';
                dbConnected = true;
            }
        } catch (err) {
            elements.dbStatus.textContent = `连接失败: ${err.message}`;
            elements.btnConnectDb.textContent = '连接';
            elements.btnConnectDb.disabled = false;
            addOutput({
                status: 'error',
                command: 'db-connect',
                message: `连接失败: ${err.message}`
            });
        }
    });

    // 区县选择变化
    document.getElementById('districtSelect').addEventListener('change', async (e) => {
        const districtId = e.target.value;
        if (!districtId) {
            document.getElementById('districtInfo').textContent = '选择区县后自动加载数据库配置';
            document.getElementById('dbList').innerHTML = '';
            currentDistrict = null;
            return;
        }

        try {
            // 获取区县下的数据库
            const res = await fetch(`${mcpDatabaseUrl}/api/districts/${districtId}/databases`);
            const data = await res.json();

            currentDistrict = districtId;
            const districtName = e.target.options[e.target.selectedIndex].text;
            document.getElementById('districtInfo').textContent = `当前: ${districtName}`;

            // 显示数据库列表
            let html = '<div style="margin-top: 8px; padding: 8px; background: #f5f5f5; border-radius: 4px;">';
            html += '<strong>数据库配置:</strong>';
            if (data.databases && data.databases.length > 0) {
                data.databases.forEach(db => {
                    html += `<div style="margin: 5px 0; padding: 5px; background: #fff; border-left: 3px solid #4CAF50;">
                        <strong>${db.db_type}</strong>: ${db.host}:${db.port}/${db.database_name}
                    </div>`;
                });
            } else {
                html += '<div style="color: #888;">暂无数据库配置</div>';
            }
            html += '</div>';
            document.getElementById('dbList').innerHTML = html;

            addOutput({
                status: 'success',
                command: 'select-district',
                message: `已加载 ${districtName} 的数据库配置 (${data.databases?.length || 0} 个)`
            });
        } catch (err) {
            addOutput({
                status: 'error',
                command: 'select-district',
                message: `加载失败: ${err.message}`
            });
        }
    });

    // 初始化
    await initSystemInfo();
    await checkLocalServerStatus();
    console.log('yunxiClaw UI initialized');
});