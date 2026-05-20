/**
 * 远程服务器管理模块
 * 
 * 支持华为云、腾讯云、内网机房、政务云等远程服务器的：
 * - SSH 连接管理（密码/密钥认证）
 * - 远程部署执行
 * - 远程日志实时查看（tail -f）
 * - 远程服务健康监控
 * - 多服务器批量操作
 * 
 * 注意：SSH 功能依赖 ssh2 包，需在 package.json 中添加依赖：
 *   npm install ssh2
 * 
 * 如未安装 ssh2，降级为通过后端 API 代理执行远程命令。
 */

const { spawn } = require('child_process');
const path = require('path');
const log = require('electron-log');

class RemoteServerModule {
    constructor(deps) {
        this.mainWindow = deps.mainWindow;
        this.currentCwd = deps.currentCwd;
        this.decodeOutput = deps.decodeOutput;

        // 已注册的远程服务器
        this.servers = new Map(); // serverId -> { name, host, port, username, authType, cloud, tags, ... }

        // 活跃的 SSH 连接
        this.connections = new Map(); // serverId -> { conn, connected, lastActivity }

        // 活跃的远程日志 tail 进程
        this.logTails = new Map(); // serverId -> { proc, logBuffer, startTime }

        // 远程监控定时器
        this.monitorTimers = new Map(); // serverId -> intervalId

        // 加载已保存的服务器配置
        this._loadServerConfig();
    }

    setMainWindow(win) {
        this.mainWindow = win;
    }

    // ==================== 服务器注册管理 ====================

    /**
     * 注册远程服务器
     * @param {Object} server - 服务器信息
     * @param {string} server.name - 显示名称
     * @param {string} server.host - 主机地址
     * @param {number} server.port - SSH 端口（默认22）
     * @param {string} server.username - SSH 用户名
     * @param {string} server.authType - 认证方式: password | privateKey
     * @param {string} server.password - 密码（authType=password时）
     * @param {string} server.privateKeyPath - 密钥路径（authType=privateKey时）
     * @param {string} server.cloud - 云类型: huawei | tencent | aws | aliyun | internal | government | custom
     * @param {string[]} server.tags - 标签
     * @param {Object} server.deployConfig - 部署配置
     */
    registerServer(server) {
        const id = server.id || 'SRV-' + Date.now();
        const entry = {
            id,
            name: server.name || server.host,
            host: server.host,
            port: server.port || 22,
            username: server.username || 'root',
            authType: server.authType || 'password',
            password: server.password || '',
            privateKeyPath: server.privateKeyPath || '',
            cloud: server.cloud || 'custom',
            tags: server.tags || [],
            deployConfig: server.deployConfig || {
                deployScript: '/opt/deploy.sh',
                rollbackScript: '/opt/rollback.sh',
                logPath: '/var/log/app/application.log',
                healthCheckUrl: 'http://localhost:8080/actuator/health',
                appPort: 8080,
            },
            createdAt: Date.now(),
            lastConnected: null,
        };
        this.servers.set(id, entry);
        this._saveServerConfig();
        log.info(`[Remote] 注册服务器: ${entry.name} (${entry.host}), cloud=${entry.cloud}`);
        return { status: 'success', serverId: id };
    }

    /**
     * 更新服务器配置
     */
    updateServer(serverId, updates) {
        const server = this.servers.get(serverId);
        if (!server) return { status: 'error', message: '服务器不存在' };
        Object.assign(server, updates);
        this._saveServerConfig();
        return { status: 'success' };
    }

    /**
     * 删除服务器
     */
    removeServer(serverId) {
        this.disconnectServer(serverId);
        this.stopLogTail(serverId);
        this.stopMonitoring(serverId);
        this.servers.delete(serverId);
        this._saveServerConfig();
        return { status: 'success' };
    }

    /**
     * 获取所有已注册服务器
     */
    getServers() {
        const result = [];
        for (const [id, srv] of this.servers) {
            const conn = this.connections.get(id);
            result.push({
                ...srv,
                password: undefined, // 不返回密码
                connected: conn?.connected || false,
                lastActivity: conn?.lastActivity || srv.lastConnected,
            });
        }
        return result;
    }

    // ==================== SSH 连接管理 ====================

    /**
     * 连接远程服务器
     * 优先使用 ssh2 库，降级使用系统 ssh 命令
     */
    async connectServer(serverId) {
        const server = this.servers.get(serverId);
        if (!server) return { status: 'error', message: '服务器不存在' };

        // 已连接则跳过
        if (this.connections.has(serverId) && this.connections.get(serverId).connected) {
            return { status: 'success', message: '已连接' };
        }

        log.info(`[Remote] 连接服务器: ${server.name} (${server.host})`);

        // 尝试使用 ssh2 库
        try {
            const Client = require('ssh2').Client;
            return await this._connectViaSSH2(serverId, server, Client);
        } catch (e) {
            log.info('[Remote] ssh2 库未安装，降级使用系统 ssh 命令');
            return this._connectViaSystemSSH(serverId, server);
        }
    }

    /**
     * 通过 ssh2 库连接（原生 SSH）
     */
    _connectViaSSH2(serverId, server, Client) {
        return new Promise((resolve) => {
            const conn = new Client();
            const connectOpts = {
                host: server.host,
                port: server.port,
                username: server.username,
                readyTimeout: 15000,
            };

            if (server.authType === 'privateKey') {
                const fs = require('fs');
                try {
                    connectOpts.privateKey = fs.readFileSync(server.privateKeyPath);
                } catch (e) {
                    return resolve({ status: 'error', message: `密钥文件读取失败: ${e.message}` });
                }
            } else {
                connectOpts.password = server.password;
            }

            conn.on('ready', () => {
                this.connections.set(serverId, { conn, connected: true, lastActivity: Date.now() });
                server.lastConnected = Date.now();
                this._saveServerConfig();
                log.info(`[Remote] SSH 连接成功: ${server.name}`);
                this._emit('remote-event', { type: 'connected', serverId, serverName: server.name, timestamp: Date.now() });
                resolve({ status: 'success', message: `已连接 ${server.name}` });
            });

            conn.on('error', (err) => {
                log.error(`[Remote] SSH 连接失败: ${err.message}`);
                this._emit('remote-event', { type: 'connect_failed', serverId, error: err.message, timestamp: Date.now() });
                resolve({ status: 'error', message: `连接失败: ${err.message}` });
            });

            conn.on('close', () => {
                const c = this.connections.get(serverId);
                if (c) c.connected = false;
                this._emit('remote-event', { type: 'disconnected', serverId, timestamp: Date.now() });
            });

            conn.connect(connectOpts);
        });
    }

    /**
     * 降级方案：通过系统 ssh 命令建立持久连接
     */
    _connectViaSystemSSH(serverId, server) {
        // 使用 SSH ControlMaster 建立持久连接
        const controlPath = path.join(require('os').tmpdir(), `yunxi-ssh-${serverId}`);

        const args = [
            '-o', 'StrictHostKeyChecking=no',
            '-o', `ControlPath=${controlPath}`,
            '-o', 'ControlMaster=auto',
            '-o', 'ControlPersist=10m',
            '-p', String(server.port),
            '-l', server.username,
            server.host,
            'echo', 'SSH_CONNECTION_OK'
        ];

        if (server.authType === 'privateKey') {
            args.splice(0, 0, '-i', server.privateKeyPath);
        }

        return new Promise((resolve) => {
            const proc = spawn('ssh', args, { shell: true });
            let output = '';
            proc.stdout.on('data', (data) => { output += this.decodeOutput(data); });
            proc.stderr.on('data', (data) => { output += this.decodeOutput(data); });

            const timeout = setTimeout(() => {
                proc.kill();
                resolve({ status: 'error', message: 'SSH 连接超时（请确认 ssh 命令可用）' });
            }, 15000);

            proc.on('close', (code) => {
                clearTimeout(timeout);
                if (code === 0 || output.includes('SSH_CONNECTION_OK')) {
                    this.connections.set(serverId, {
                        conn: null, // 系统SSH没有持久对象
                        controlPath,
                        connected: true,
                        lastActivity: Date.now(),
                        useSystemSSH: true
                    });
                    server.lastConnected = Date.now();
                    this._saveServerConfig();
                    this._emit('remote-event', { type: 'connected', serverId, serverName: server.name, timestamp: Date.now() });
                    resolve({ status: 'success', message: `已连接 ${server.name} (系统SSH)` });
                } else {
                    resolve({ status: 'error', message: `连接失败: ${output.substring(0, 200)}` });
                }
            });

            proc.on('error', (err) => {
                clearTimeout(timeout);
                resolve({ status: 'error', message: `SSH 命令不可用: ${err.message}。请安装 ssh2 包: npm install ssh2` });
            });
        });
    }

    /**
     * 断开服务器连接
     */
    disconnectServer(serverId) {
        const connInfo = this.connections.get(serverId);
        if (!connInfo) return { status: 'success', message: '未连接' };

        if (connInfo.conn) {
            connInfo.conn.end();
        }
        if (connInfo.controlPath) {
            // 关闭 ControlMaster
            try {
                spawn('ssh', ['-o', `ControlPath=${connInfo.controlPath}`, '-O', 'exit', 'dummy'], { shell: true });
            } catch (e) {}
        }
        connInfo.connected = false;
        this.connections.delete(serverId);
        log.info(`[Remote] 断开服务器: ${serverId}`);
        return { status: 'success' };
    }

    // ==================== 远程命令执行 ====================

    /**
     * 在远程服务器上执行命令
     */
    async executeRemoteCommand(serverId, command, options = {}) {
        const connInfo = this.connections.get(serverId);
        if (!connInfo || !connInfo.connected) {
            return { status: 'error', message: '服务器未连接' };
        }

        const server = this.servers.get(serverId);
        log.info(`[Remote] 执行远程命令: ${server.name} -> ${command.substring(0, 80)}`);

        // 优先使用 ssh2
        if (connInfo.conn && !connInfo.useSystemSSH) {
            return this._execViaSSH2(connInfo.conn, command, options);
        }

        // 降级使用系统 SSH
        return this._execViaSystemSSH(server, connInfo, command, options);
    }

    _execViaSSH2(conn, command, options) {
        return new Promise((resolve) => {
            conn.exec(command, { pty: options.pty || false }, (err, stream) => {
                if (err) return resolve({ status: 'error', message: err.message });
                let stdout = '';
                let stderr = '';
                stream.on('data', (data) => {
                    stdout += data.toString('utf-8');
                    if (options.stream && this.mainWindow) {
                        this.mainWindow.webContents.send('remote-event', {
                            type: 'command_output', serverId: options.serverId,
                            text: data.toString('utf-8'), timestamp: Date.now()
                        });
                    }
                });
                stream.stderr.on('data', (data) => { stderr += data.toString('utf-8'); });
                stream.on('close', (code) => {
                    resolve({ status: code === 0 ? 'success' : 'error', exitCode: code, stdout, stderr });
                });
            });
        });
    }

    _execViaSystemSSH(server, connInfo, command, options) {
        const args = ['-o', `ControlPath=${connInfo.controlPath}`, '-p', String(server.port), '-l', server.username, server.host, command];
        if (server.authType === 'privateKey') args.splice(0, 0, '-i', server.privateKeyPath);

        return new Promise((resolve) => {
            const proc = spawn('ssh', args, { shell: true });
            let stdout = '';
            let stderr = '';
            proc.stdout.on('data', (data) => {
                const text = this.decodeOutput(data);
                stdout += text;
                if (options.stream && this.mainWindow) {
                    this.mainWindow.webContents.send('remote-event', {
                        type: 'command_output', serverId: options.serverId, text, timestamp: Date.now()
                    });
                }
            });
            proc.stderr.on('data', (data) => { stderr += this.decodeOutput(data); });
            const timeout = setTimeout(() => { proc.kill(); resolve({ status: 'error', message: '远程命令超时' }); }, options.timeout || 60000);
            proc.on('close', (code) => { clearTimeout(timeout); resolve({ status: code === 0 ? 'success' : 'error', exitCode: code, stdout, stderr }); });
            proc.on('error', (err) => { clearTimeout(timeout); resolve({ status: 'error', message: err.message }); });
        });
    }

    // ==================== 远程部署 ====================

    /**
     * 远程部署
     */
    async remoteDeploy(serverId, deployOptions = {}) {
        const server = this.servers.get(serverId);
        if (!server) return { status: 'error', message: '服务器不存在' };

        const connInfo = this.connections.get(serverId);
        if (!connInfo?.connected) {
            const connectResult = await this.connectServer(serverId);
            if (connectResult.status !== 'success') return connectResult;
        }

        const taskId = 'RDEPLOY-' + Date.now();
        const config = { ...server.deployConfig, ...deployOptions };
        const steps = [];

        this._emit('remote-event', {
            type: 'deploy_started', serverId, taskId, serverName: server.name,
            message: `开始远程部署 ${server.name}...`, timestamp: Date.now()
        });

        // Step 1: 拉取最新代码/镜像
        if (config.deployType === 'docker') {
            this._emit('remote-event', { type: 'deploy_progress', serverId, taskId, step: 'pull', message: '拉取 Docker 镜像...', timestamp: Date.now() });
            const pullResult = await this.executeRemoteCommand(serverId, `docker pull ${config.image || 'latest'}`, { serverId, timeout: 180000 });
            steps.push({ step: 'pull', status: pullResult.status === 'success' ? 'ok' : 'failed' });
            if (pullResult.status !== 'success') {
                this._emit('remote-event', { type: 'deploy_failed', serverId, taskId, step: 'pull', message: pullResult.stderr?.substring(0, 200), timestamp: Date.now() });
                return { status: 'error', taskId, steps, message: '镜像拉取失败' };
            }
        } else {
            this._emit('remote-event', { type: 'deploy_progress', serverId, taskId, step: 'git_pull', message: '拉取最新代码...', timestamp: Date.now() });
            const pullResult = await this.executeRemoteCommand(serverId, `cd ${config.appDir || '/opt/app'} && git pull`, { serverId });
            steps.push({ step: 'git_pull', status: pullResult.status === 'success' ? 'ok' : 'failed' });
        }

        // Step 2: 执行部署脚本
        this._emit('remote-event', { type: 'deploy_progress', serverId, taskId, step: 'deploy_script', message: '执行部署脚本...', timestamp: Date.now() });
        const deployCmd = config.deployScript || '/opt/deploy.sh';
        const deployResult = await this.executeRemoteCommand(serverId, `bash ${deployCmd}`, { serverId, timeout: 300000 });
        steps.push({ step: 'deploy', status: deployResult.status === 'success' ? 'ok' : 'failed', output: deployResult.stdout?.substring(0, 500) });

        // Step 3: 健康检查
        if (deployResult.status === 'success' && config.healthCheckUrl) {
            this._emit('remote-event', { type: 'deploy_progress', serverId, taskId, step: 'health_check', message: '健康检查...', timestamp: Date.now() });
            const healthResult = await this.executeRemoteCommand(serverId, `curl -sf ${config.healthCheckUrl} -o /dev/null -w "%{http_code}"`, { serverId, timeout: 30000 });
            const httpCode = healthResult.stdout?.trim();
            steps.push({ step: 'health_check', status: httpCode === '200' ? 'ok' : 'warning', httpCode });
        }

        const finalStatus = deployResult.status === 'success' ? 'success' : 'failed';
        this._emit('remote-event', {
            type: finalStatus === 'success' ? 'deploy_completed' : 'deploy_failed',
            serverId, taskId, steps, message: finalStatus === 'success' ? `${server.name} 部署完成` : `${server.name} 部署失败`, timestamp: Date.now()
        });

        return { status: finalStatus, taskId, steps };
    }

    /**
     * 批量远程部署
     */
    async batchRemoteDeploy(serverIds, deployOptions = {}) {
        const batchId = 'BATCH-' + Date.now();
        this._emit('remote-event', { type: 'batch_started', batchId, serverIds, timestamp: Date.now() });

        const results = {};
        // 并行部署（最多5个并发）
        const concurrency = 5;
        for (let i = 0; i < serverIds.length; i += concurrency) {
            const chunk = serverIds.slice(i, i + concurrency);
            const promises = chunk.map(async (sid) => {
                const result = await this.remoteDeploy(sid, deployOptions);
                results[sid] = result;
            });
            await Promise.all(promises);
        }

        this._emit('remote-event', { type: 'batch_completed', batchId, results, timestamp: Date.now() });
        return { status: 'success', batchId, results };
    }

    // ==================== 远程日志查看 ====================

    /**
     * 开始远程日志 tail
     */
    async startLogTail(serverId, logPath) {
        const server = this.servers.get(serverId);
        if (!server) return { status: 'error', message: '服务器不存在' };

        // 先停止已有的 tail
        this.stopLogTail(serverId);

        const config = server.deployConfig;
        const remoteLogPath = logPath || config.logPath || '/var/log/app/application.log';
        const taskId = 'LOGTAIL-' + Date.now();

        log.info(`[Remote] 开始日志 tail: ${server.name} -> ${remoteLogPath}`);

        const connInfo = this.connections.get(serverId);
        if (!connInfo?.connected) {
            const connectResult = await this.connectServer(serverId);
            if (connectResult.status !== 'success') return connectResult;
        }

        // 使用 ssh2 的 exec + tail -f
        if (connInfo.conn && !connInfo.useSystemSSH) {
            return this._startLogTailViaSSH2(serverId, connInfo.conn, remoteLogPath, taskId);
        }

        // 降级：系统 ssh + tail -f
        return this._startLogTailViaSystemSSH(serverId, server, connInfo, remoteLogPath, taskId);
    }

    _startLogTailViaSSH2(serverId, conn, logPath, taskId) {
        return new Promise((resolve) => {
            conn.exec(`tail -f -n 100 ${logPath}`, { pty: false }, (err, stream) => {
                if (err) return resolve({ status: 'error', message: err.message });

                const logBuffer = [];
                this.logTails.set(serverId, { stream, logBuffer, startTime: Date.now(), taskId });

                stream.on('data', (data) => {
                    const text = data.toString('utf-8');
                    logBuffer.push(text);
                    if (this.mainWindow) {
                        this.mainWindow.webContents.send('remote-event', {
                            type: 'log', serverId, taskId, text, timestamp: Date.now()
                        });
                    }
                });

                stream.stderr.on('data', (data) => {
                    const text = data.toString('utf-8');
                    if (this.mainWindow) {
                        this.mainWindow.webContents.send('remote-event', {
                            type: 'log_error', serverId, taskId, text, timestamp: Date.now()
                        });
                    }
                });

                resolve({ status: 'success', taskId, message: `日志监控已启动: ${logPath}` });
            });
        });
    }

    _startLogTailViaSystemSSH(serverId, server, connInfo, logPath, taskId) {
        const args = ['-o', `ControlPath=${connInfo.controlPath}`, '-p', String(server.port),
            '-l', server.username, server.host, `tail -f -n 100 ${logPath}`];
        if (server.authType === 'privateKey') args.splice(0, 0, '-i', server.privateKeyPath);

        const proc = spawn('ssh', args, { shell: true });
        const logBuffer = [];
        this.logTails.set(serverId, { proc, logBuffer, startTime: Date.now(), taskId });

        proc.stdout.on('data', (data) => {
            const text = this.decodeOutput(data);
            logBuffer.push(text);
            if (this.mainWindow) {
                this.mainWindow.webContents.send('remote-event', {
                    type: 'log', serverId, taskId, text, timestamp: Date.now()
                });
            }
        });

        proc.stderr.on('data', (data) => {
            const text = this.decodeOutput(data);
            if (this.mainWindow) {
                this.mainWindow.webContents.send('remote-event', {
                    type: 'log_error', serverId, taskId, text, timestamp: Date.now()
                });
            }
        });

        proc.on('close', () => {
            this.logTails.delete(serverId);
            this._emit('remote-event', { type: 'log_stopped', serverId, taskId, timestamp: Date.now() });
        });

        return Promise.resolve({ status: 'success', taskId, message: `日志监控已启动: ${logPath} (系统SSH)` });
    }

    /**
     * 停止日志 tail
     */
    stopLogTail(serverId) {
        const tail = this.logTails.get(serverId);
        if (!tail) return { status: 'success', message: '无活跃的日志监控' };

        if (tail.stream) {
            try { tail.stream.close(); } catch (e) {}
        }
        if (tail.proc) {
            try { tail.proc.kill(); } catch (e) {}
        }
        this.logTails.delete(serverId);
        return { status: 'success', message: '日志监控已停止' };
    }

    // ==================== 远程服务监控 ====================

    /**
     * 启动远程服务健康监控
     */
    startMonitoring(serverId, intervalMs = 30000) {
        if (this.monitorTimers.has(serverId)) {
            return { status: 'success', message: '监控已运行' };
        }

        const server = this.servers.get(serverId);
        if (!server) return { status: 'error', message: '服务器不存在' };

        log.info(`[Remote] 启动服务监控: ${server.name}, 间隔=${intervalMs}ms`);

        const timerId = setInterval(async () => {
            const config = server.deployConfig;
            const checks = {};

            // CPU 使用率
            const cpuResult = await this.executeRemoteCommand(serverId, "top -bn1 | grep 'Cpu(s)' | awk '{print $2}'", { serverId, timeout: 10000 });
            checks.cpuPercent = parseFloat(cpuResult.stdout?.trim()) || null;

            // 内存使用率
            const memResult = await this.executeRemoteCommand(serverId, "free -m | awk 'NR==2{printf \"%.1f\", $3*100/$2}'", { serverId, timeout: 10000 });
            checks.memoryPercent = parseFloat(memResult.stdout?.trim()) || null;

            // 磁盘使用率
            const diskResult = await this.executeRemoteCommand(serverId, "df -h / | awk 'NR==2{print $5}' | tr -d '%'", { serverId, timeout: 10000 });
            checks.diskPercent = parseFloat(diskResult.stdout?.trim()) || null;

            // 应用端口
            if (config.appPort) {
                const portResult = await this.executeRemoteCommand(serverId, `ss -tlnp | grep :${config.appPort} | wc -l`, { serverId, timeout: 10000 });
                checks.appListening = parseInt(portResult.stdout?.trim()) > 0;
            }

            // 健康检查 URL
            if (config.healthCheckUrl) {
                const healthResult = await this.executeRemoteCommand(serverId, `curl -sf ${config.healthCheckUrl} -o /dev/null -w "%{http_code}"`, { serverId, timeout: 10000 });
                checks.healthStatus = healthResult.stdout?.trim() === '200' ? 'UP' : 'DOWN';
            }

            this._emit('remote-event', {
                type: 'monitor', serverId, checks, timestamp: Date.now()
            });
        }, intervalMs);

        this.monitorTimers.set(serverId, timerId);
        return { status: 'success', message: '监控已启动' };
    }

    /**
     * 停止监控
     */
    stopMonitoring(serverId) {
        const timerId = this.monitorTimers.get(serverId);
        if (timerId) {
            clearInterval(timerId);
            this.monitorTimers.delete(serverId);
        }
        return { status: 'success' };
    }

    // ==================== 配置持久化 ====================

    _getConfigPath() {
        const { app } = require('electron');
        return path.join(app.getPath('userData'), 'remote-servers.json');
    }

    _loadServerConfig() {
        try {
            const fs = require('fs');
            const configPath = this._getConfigPath();
            if (fs.existsSync(configPath)) {
                const data = JSON.parse(fs.readFileSync(configPath, 'utf8'));
                for (const srv of data.servers || []) {
                    this.servers.set(srv.id, srv);
                }
                log.info(`[Remote] 加载了 ${this.servers.size} 个服务器配置`);
            }
        } catch (e) {
            log.warn('[Remote] 加载服务器配置失败:', e.message);
        }
    }

    _saveServerConfig() {
        try {
            const fs = require('fs');
            const configPath = this._getConfigPath();
            const data = { servers: Array.from(this.servers.values()) };
            fs.writeFileSync(configPath, JSON.stringify(data, null, 2), 'utf8');
        } catch (e) {
            log.warn('[Remote] 保存服务器配置失败:', e.message);
        }
    }

    // 事件发送封装
    _emit(channel, data) {
        if (this.mainWindow && !this.mainWindow.isDestroyed()) {
            this.mainWindow.webContents.send(channel, data);
        }
    }

    // 清理
    cleanup() {
        for (const [serverId] of this.connections) {
            this.disconnectServer(serverId);
        }
        for (const [serverId] of this.logTails) {
            this.stopLogTail(serverId);
        }
        for (const [serverId] of this.monitorTimers) {
            this.stopMonitoring(serverId);
        }
    }
}

module.exports = RemoteServerModule;
