/**
 * 部署与流水线模块
 * 
 * 包含：本地脚本流式执行、部署命令、回滚命令、自动化流水线
 * 从 index.js 拆分而来，通过依赖注入获取全局状态
 */

const { spawn } = require('child_process');
const path = require('path');
const log = require('electron-log');

class DeployModule {
    constructor(deps) {
        this.mainWindow = deps.mainWindow;
        this.currentCwd = deps.currentCwd;
        this.aiParseBaseUrl = deps.aiParseBaseUrl;
        this.decodeOutput = deps.decodeOutput;

        // 进程池
        this.runningProcesses = new Map();

        // 流水线状态
        this.pipelineState = {
            active: false,
            taskId: null,
            stage: 'idle',
            logBuffer: [],
            logMonitorTimer: null,
            logMonitorProc: null,
            suggestions: [],
            env: null,
            version: null,
        };
    }

    // 更新 mainWindow 引用
    setMainWindow(win) {
        this.mainWindow = win;
    }

    // 获取流水线状态
    getPipelineState() {
        return { ...this.pipelineState, runningCount: this.runningProcesses.size };
    }

    // 获取运行中的进程列表
    getRunningProcesses() {
        const processes = [];
        for (const [pid, info] of this.runningProcesses) {
            processes.push({
                pid,
                type: info.type,
                taskId: info.taskId,
                elapsed: Date.now() - info.startTime
            });
        }
        return processes;
    }

    // 终止进程
    killProcess(pid) {
        const info = this.runningProcesses.get(pid);
        if (!info) return { status: 'error', message: `进程 ${pid} 不存在` };
        try {
            info.proc.kill();
            this.runningProcesses.delete(pid);
            return { status: 'success', message: `进程 ${pid} 已终止` };
        } catch (e) {
            return { status: 'error', message: e.message };
        }
    }

    // 清理所有进程
    cleanup() {
        this.stopLogMonitoring();
        for (const [pid, info] of this.runningProcesses) {
            try { info.proc.kill(); } catch (e) {}
        }
        this.runningProcesses.clear();
    }

    // ==================== 配置加载 ====================

    loadNodeDeployConfig() {
        const fs = require('fs');
        const candidates = [
            path.join(this.currentCwd, 'agent-node', 'config.json'),
            path.join('D:', 'work', 'code', 'yunxi-agent-platform', 'agent-node', 'config.json'),
        ];
        for (const p of candidates) {
            if (fs.existsSync(p)) {
                try {
                    return JSON.parse(fs.readFileSync(p, 'utf8'));
                } catch (e) {
                    log.warn('加载节点配置失败:', e.message);
                }
            }
        }
        return {};
    }

    // ==================== 部署命令 ====================

    async executeDeployCommand(data) {
        const method = data.method || 'script';
        const environment = data.environment || 'dev';
        const imageVersion = data.imageVersion || 'latest';
        const nodeConfig = this.loadNodeDeployConfig();
        const containerName = data.containerName || nodeConfig.deploy?.defaultContainerName || 'yunxi-app';

        log.info(`执行部署: method=${method}, env=${environment}, image=${imageVersion}`);

        if (method === 'docker') {
            const pullResult = await this.executeCommandSync('docker', ['pull', imageVersion], undefined);
            if (pullResult.status !== 'success') {
                return { status: 'error', message: '镜像拉取失败: ' + (pullResult.stderr || pullResult.message), steps: [{ step: 'pull', status: 'failed' }] };
            }
            await this.executeCommandSync('docker', ['stop', containerName], undefined);
            await this.executeCommandSync('docker', ['rm', containerName], undefined);
            const runCmd = data.runCommand || `docker run -d --name ${containerName} --restart=always ${imageVersion}`;
            const runParts = runCmd.split(' ');
            const runResult = await this.executeCommandSync(runParts[0], runParts.slice(1), undefined);
            return {
                status: runResult.status === 'success' ? 'success' : 'error',
                method, environment, imageVersion,
                steps: [
                    { step: 'pull', status: 'ok' },
                    { step: 'stop', status: 'ok' },
                    { step: 'rm', status: 'ok' },
                    { step: 'run', status: runResult.status === 'success' ? 'ok' : 'failed', output: runResult.stdout?.substring(0, 200) }
                ]
            };
        } else if (method === 'script') {
            const scriptPath = data.scriptPath || nodeConfig.deploy?.deployScriptPath || '/opt/deploy.sh';
            const isWin = process.platform === 'win32';
            const isBat = /\.(bat|cmd|ps1)$/i.test(scriptPath);
            let cmd, args;
            if (isWin && isBat) {
                cmd = scriptPath;
                args = [environment, imageVersion];
            } else if (isWin) {
                cmd = 'sh';
                args = [scriptPath, environment, imageVersion];
            } else {
                cmd = 'bash';
                args = [scriptPath, environment, imageVersion];
            }
            const scriptResult = await this.executeCommandSync(cmd, args, undefined);
            return {
                status: scriptResult.status === 'success' ? 'success' : 'error',
                method, environment, imageVersion,
                steps: [{
                    step: 'script',
                    status: scriptResult.status === 'success' ? 'ok' : 'failed',
                    output: scriptResult.stdout?.substring(0, 500)
                }]
            };
        }
        return { status: 'error', message: `不支持的部署方式: ${method}` };
    }

    async executeRollbackCommand(data) {
        const method = data.method || 'docker';
        const containerName = data.containerName || 'yunxi-app';
        const nodeConfig = this.loadNodeDeployConfig();
        log.info(`执行回滚: method=${method}, container=${containerName}`);

        if (method === 'docker') {
            const previousImage = data.previousImage;
            if (!previousImage) {
                return { status: 'error', message: '回滚需要指定 previousImage' };
            }
            await this.executeCommandSync('docker', ['stop', containerName], undefined);
            await this.executeCommandSync('docker', ['rm', containerName], undefined);
            const runResult = await this.executeCommandSync('docker', ['run', '-d', '--name', containerName, '--restart=always', previousImage], undefined);
            return {
                status: runResult.status === 'success' ? 'success' : 'error',
                previousImage,
                output: runResult.stdout?.substring(0, 200)
            };
        } else if (method === 'script') {
            const scriptPath = data.rollbackScriptPath || nodeConfig.deploy?.rollbackScriptPath || '/opt/rollback.sh';
            const isWin = process.platform === 'win32';
            const isBat = /\.(bat|cmd|ps1)$/i.test(scriptPath);
            let cmd, args;
            if (isWin && isBat) { cmd = scriptPath; args = []; }
            else if (isWin) { cmd = 'sh'; args = [scriptPath]; }
            else { cmd = 'bash'; args = [scriptPath]; }
            const scriptResult = await this.executeCommandSync(cmd, args, undefined);
            return {
                status: scriptResult.status === 'success' ? 'success' : 'error',
                output: scriptResult.stdout?.substring(0, 500)
            };
        }
        return { status: 'error', message: `不支持的回滚方式: ${method}` };
    }

    /**
     * 同步命令执行（从 index.js 移入）
     */
    executeCommandSync(command, args, cwd) {
        return new Promise((resolve) => {
            const proc = spawn(command, args, {
                shell: true,
                cwd: cwd || this.currentCwd,
                env: { ...process.env }
            });
            let stdout = '';
            let stderr = '';
            proc.stdout.on('data', (data) => { stdout += this.decodeOutput(data); });
            proc.stderr.on('data', (data) => { stderr += this.decodeOutput(data); });
            const timeout = setTimeout(() => {
                proc.kill();
                resolve({ status: 'error', message: '命令超时', stdout, stderr });
            }, 60000);
            proc.on('close', (code) => {
                clearTimeout(timeout);
                resolve({
                    status: code === 0 ? 'success' : 'error',
                    exitCode: code,
                    stdout: stdout.substring(0, 10000),
                    stderr: stderr.substring(0, 5000)
                });
            });
            proc.on('error', (err) => {
                clearTimeout(timeout);
                resolve({ status: 'error', message: err.message, stdout, stderr });
            });
        });
    }

    // ==================== 本地脚本流式执行 ====================

    executeLocalScriptStream(scriptPath, args = [], options = {}) {
        return new Promise((resolve) => {
            const isWin = process.platform === 'win32';
            const isBat = /\.(bat|cmd|ps1)$/i.test(scriptPath);
            const taskId = options.taskId || 'LOCAL-' + Date.now();

            let cmd, cmdArgs;
            if (isWin && isBat) {
                cmd = 'cmd';
                cmdArgs = ['/c', scriptPath, ...args, '&', 'set', 'NO_PAUSE=1'];
            } else if (isWin) {
                cmd = 'sh';
                cmdArgs = [scriptPath, ...args];
            } else {
                cmd = 'bash';
                cmdArgs = [scriptPath, ...args];
            }

            log.info(`[LocalScript] 启动本地脚本: ${cmd} ${cmdArgs.join(' ')}`);

            const proc = spawn(cmd, cmdArgs, {
                shell: true,
                cwd: options.cwd || this.currentCwd,
                env: { ...process.env, NO_PAUSE: '1' },
                stdio: ['pipe', 'pipe', 'pipe']
            });

            const startTime = Date.now();
            const pid = proc.pid;
            let stdout = '';
            let stderr = '';

            this.runningProcesses.set(pid, { proc, type: 'local-script', startTime, taskId });

            if (this.mainWindow) {
                this.mainWindow.webContents.send('script-stream', {
                    type: 'start', taskId, scriptPath, pid, timestamp: Date.now()
                });
            }

            proc.stdout.on('data', (data) => {
                const text = this.decodeOutput(data);
                stdout += text;
                if (this.mainWindow) {
                    this.mainWindow.webContents.send('script-stream', {
                        type: 'stdout', taskId, text, pid, timestamp: Date.now()
                    });
                }
            });

            proc.stderr.on('data', (data) => {
                const text = this.decodeOutput(data);
                stderr += text;
                if (this.mainWindow) {
                    this.mainWindow.webContents.send('script-stream', {
                        type: 'stderr', taskId, text, pid, timestamp: Date.now()
                    });
                }
            });

            const timeout = setTimeout(() => {
                proc.kill();
                this.runningProcesses.delete(pid);
                if (this.mainWindow) {
                    this.mainWindow.webContents.send('script-stream', {
                        type: 'timeout', taskId, pid, timestamp: Date.now()
                    });
                }
                resolve({ status: 'error', message: '脚本执行超时 (5分钟)', exitCode: -1, stdout, stderr });
            }, options.timeout || 300000);

            proc.on('close', (code) => {
                clearTimeout(timeout);
                this.runningProcesses.delete(pid);
                const elapsed = Date.now() - startTime;
                log.info(`[LocalScript] 脚本执行完成: exitCode=${code}, 耗时=${elapsed}ms`);
                if (this.mainWindow) {
                    this.mainWindow.webContents.send('script-stream', {
                        type: 'close', taskId, exitCode: code, elapsed, pid, timestamp: Date.now()
                    });
                }
                if (code === 0 && options.autoPipeline) {
                    this.startAutoPipeline(taskId, options);
                }
                resolve({
                    status: code === 0 ? 'success' : 'error',
                    exitCode: code,
                    stdout: stdout.substring(0, 50000),
                    stderr: stderr.substring(0, 10000),
                    elapsed, taskId
                });
            });

            proc.on('error', (err) => {
                clearTimeout(timeout);
                this.runningProcesses.delete(pid);
                log.error(`[LocalScript] 脚本启动失败:`, err.message);
                resolve({ status: 'error', message: err.message, taskId });
            });
        });
    }

    // ==================== 自动化流水线 ====================

    startAutoPipeline(taskId, options) {
        const { env = 'dev', version = 'latest' } = options;
        log.info(`[Pipeline] 启动自动化流水线: taskId=${taskId}, env=${env}`);
        this.pipelineState = {
            active: true, taskId, stage: 'log_monitoring',
            logBuffer: [], logMonitorTimer: null, logMonitorProc: null,
            suggestions: [], env, version,
        };
        if (this.mainWindow) {
            this.mainWindow.webContents.send('pipeline-event', {
                type: 'started', taskId, stage: 'log_monitoring',
                message: '部署成功，启动日志监控...', timestamp: Date.now()
            });
        }
        setTimeout(() => {
            if (this.pipelineState.active && this.pipelineState.taskId === taskId) {
                this.startLogMonitoring(taskId);
            }
        }, 30000);
    }

    startLogMonitoring(taskId) {
        if (!this.pipelineState.active || this.pipelineState.taskId !== taskId) return;
        log.info(`[Pipeline] 开始日志监控: taskId=${taskId}`);
        this.pipelineState.stage = 'log_monitoring';

        const fs = require('fs');
        const logCandidates = [
            path.join(this.currentCwd, 'agent-app', 'logs', 'agent-app.log'),
            path.join(this.currentCwd, 'agent-app', 'target', 'agent-app.log'),
            path.join(this.currentCwd, 'logs', 'application.log'),
        ];
        let logPath = null;
        for (const candidate of logCandidates) {
            if (fs.existsSync(candidate)) { logPath = candidate; break; }
        }
        if (!logPath) {
            log.info('[Pipeline] 未找到本地日志文件，通过健康检查确认服务状态');
            this.checkServiceHealth(taskId);
            return;
        }
        log.info(`[Pipeline] 监控日志文件: ${logPath}`);

        if (process.platform === 'win32') {
            this.pipelineState.logMonitorProc = spawn('powershell', [
                '-NoProfile', '-Command', `Get-Content -Path "${logPath}" -Wait -Tail 100`
            ], { shell: true });
        } else {
            this.pipelineState.logMonitorProc = spawn('tail', ['-f', '-n', '100', logPath]);
        }

        let monitorDuration = 0;
        const MAX_MONITOR_MS = 120000;

        this.pipelineState.logMonitorProc.stdout?.on('data', (data) => {
            const text = this.decodeOutput(data);
            this.pipelineState.logBuffer.push(text);
            if (this.mainWindow) {
                this.mainWindow.webContents.send('pipeline-event', {
                    type: 'log', taskId, stage: 'log_monitoring', text, timestamp: Date.now()
                });
            }
            const errorKeywords = ['ERROR', 'Exception', 'FATAL', 'OOM', 'OutOfMemory', 'StackOverflow', 'Connection refused', 'Port already in use'];
            if (errorKeywords.some(kw => text.includes(kw))) {
                log.info(`[Pipeline] 检测到错误关键词，提前结束日志监控`);
                this.stopLogMonitoring();
                this.analyzeLogAndSuggest(taskId);
            }
        });

        this.pipelineState.logMonitorTimer = setInterval(() => {
            monitorDuration += 5000;
            if (monitorDuration >= MAX_MONITOR_MS || this.pipelineState.logBuffer.length >= 200) {
                this.stopLogMonitoring();
                this.analyzeLogAndSuggest(taskId);
            }
            if (monitorDuration % 30000 === 0 && this.mainWindow) {
                this.mainWindow.webContents.send('pipeline-event', {
                    type: 'progress', taskId, stage: 'log_monitoring',
                    message: `日志监控中... 已收集 ${this.pipelineState.logBuffer.length} 行`,
                    timestamp: Date.now()
                });
            }
        }, 5000);
    }

    stopLogMonitoring() {
        if (this.pipelineState.logMonitorTimer) {
            clearInterval(this.pipelineState.logMonitorTimer);
            this.pipelineState.logMonitorTimer = null;
        }
        if (this.pipelineState.logMonitorProc) {
            try { this.pipelineState.logMonitorProc.kill(); } catch (e) {}
            this.pipelineState.logMonitorProc = null;
        }
    }

    checkServiceHealth(taskId) {
        const http = require('http');
        const healthUrl = `${this.aiParseBaseUrl}/actuator/health`;
        let retries = 0;
        const maxRetries = 10;
        const check = () => {
            if (!this.pipelineState.active || this.pipelineState.taskId !== taskId) return;
            const req = http.get(healthUrl, { timeout: 5000 }, (res) => {
                let data = '';
                res.on('data', chunk => data += chunk);
                res.on('end', () => {
                    try {
                        const health = JSON.parse(data);
                        if (health.status === 'UP') {
                            if (this.mainWindow) {
                                this.mainWindow.webContents.send('pipeline-event', {
                                    type: 'progress', taskId, stage: 'log_monitoring',
                                    message: '服务健康检查通过，无错误日志', timestamp: Date.now()
                                });
                            }
                            this.completePipeline(taskId, 'healthy');
                        } else {
                            this.analyzeLogAndSuggest(taskId);
                        }
                    } catch (e) { this.analyzeLogAndSuggest(taskId); }
                });
            });
            req.on('error', () => { retries++; if (retries < maxRetries) setTimeout(check, 5000); else this.analyzeLogAndSuggest(taskId); });
            req.on('timeout', () => { req.destroy(); retries++; if (retries < maxRetries) setTimeout(check, 5000); else this.analyzeLogAndSuggest(taskId); });
        };
        check();
    }

    async analyzeLogAndSuggest(taskId) {
        if (!this.pipelineState.active || this.pipelineState.taskId !== taskId) return;
        this.pipelineState.stage = 'analyzing';
        log.info(`[Pipeline] 开始 AI 日志分析: taskId=${taskId}`);
        if (this.mainWindow) {
            this.mainWindow.webContents.send('pipeline-event', {
                type: 'progress', taskId, stage: 'analyzing',
                message: 'AI 正在分析日志...', timestamp: Date.now()
            });
        }
        const logText = this.pipelineState.logBuffer.join('\n').substring(0, 20000);
        try {
            const http = require('http');
            const requestBody = JSON.stringify({
                agentName: 'devops-analyzer',
                message: `分析以下应用启动日志，找出错误和异常，给出修复建议。\n\n要求：\n1. 列出所有 ERROR/FATAL 级别的异常\n2. 对每个异常给出：a) 错误原因 b) 涉及的文件/类 c) 修复建议 d) 修复代码片段（如有）\n3. 按严重程度排序（FATAL > ERROR > WARN）\n4. 如果日志正常（无错误），返回 "HEALTHY"\n\n应用日志：\n${logText || '（无日志可用，请通过健康检查确认服务状态）'}`,
                stream: false
            });
            const result = await this._callAI(requestBody);
            if (typeof result === 'string' && result.includes('HEALTHY')) {
                this.completePipeline(taskId, 'healthy');
                return;
            }
            this.pipelineState.suggestions = [{ id: 'FIX-' + Date.now(), analysis: result, timestamp: Date.now() }];
            this.pipelineState.stage = 'waiting_confirm';
            if (this.mainWindow) {
                this.mainWindow.webContents.send('pipeline-event', {
                    type: 'suggestions_ready', taskId, stage: 'waiting_confirm',
                    suggestions: this.pipelineState.suggestions,
                    message: 'AI 分析完成，发现可修复的问题。请确认是否自动修复。', timestamp: Date.now()
                });
            }
        } catch (err) {
            log.error('[Pipeline] AI 分析失败:', err.message);
            if (this.mainWindow) {
                this.mainWindow.webContents.send('pipeline-event', {
                    type: 'error', taskId, stage: 'analyzing',
                    message: 'AI 分析失败: ' + err.message, timestamp: Date.now()
                });
            }
            this.completePipeline(taskId, 'error');
        }
    }

    async executeAutoFix(taskId, suggestionId) {
        if (!this.pipelineState.active || this.pipelineState.taskId !== taskId) return;
        this.pipelineState.stage = 'fixing';
        const suggestion = this.pipelineState.suggestions.find(s => s.id === suggestionId);
        if (!suggestion) return;
        log.info(`[Pipeline] 开始自动修复: taskId=${taskId}`);
        if (this.mainWindow) {
            this.mainWindow.webContents.send('pipeline-event', {
                type: 'progress', taskId, stage: 'fixing',
                message: 'AI 正在自动修复代码...', timestamp: Date.now()
            });
        }
        try {
            const requestBody = JSON.stringify({
                agentName: 'devops-fixer',
                message: `基于以下分析结果，自动修复代码。\n\n修复规则：\n1. 只修改必要的文件\n2. 保持现有代码风格\n3. 添加必要的 import 语句\n4. 修复后确保编译通过\n\n分析结果：\n${suggestion.analysis}\n\n工作目录：${this.currentCwd}`,
                stream: false
            });
            const result = await this._callAI(requestBody, 120000);
            if (this.mainWindow) {
                this.mainWindow.webContents.send('pipeline-event', {
                    type: 'fix_result', taskId, stage: 'fixing', result,
                    message: '代码修复完成', timestamp: Date.now()
                });
            }
            this.pipelineState.stage = 'testing';
            await this.runAutoTest(taskId);
        } catch (err) {
            log.error('[Pipeline] 自动修复失败:', err.message);
            this.completePipeline(taskId, 'error');
        }
    }

    async runAutoTest(taskId) {
        if (!this.pipelineState.active || this.pipelineState.taskId !== taskId) return;
        log.info(`[Pipeline] 开始自动测试: taskId=${taskId}`);
        if (this.mainWindow) {
            this.mainWindow.webContents.send('pipeline-event', {
                type: 'progress', taskId, stage: 'testing',
                message: '正在运行单元测试...', timestamp: Date.now()
            });
        }
        const testResult = await this.executeLocalScriptStream(
            'mvn', ['test', '-f', path.join(this.currentCwd, 'pom.xml')],
            { taskId: taskId + '-TEST', timeout: 180000 }
        );
        if (testResult.status === 'success') {
            if (this.mainWindow) {
                this.mainWindow.webContents.send('pipeline-event', {
                    type: 'progress', taskId, stage: 'testing',
                    message: '测试通过! 准备重新部署...', timestamp: Date.now()
                });
            }
            this.pipelineState.stage = 'deploying';
            await this.redeploy(taskId);
        } else {
            if (this.mainWindow) {
                this.mainWindow.webContents.send('pipeline-event', {
                    type: 'test_failed', taskId, stage: 'testing',
                    testOutput: testResult.stdout?.substring(0, 5000),
                    message: '测试未通过，请手动检查', timestamp: Date.now()
                });
            }
            this.completePipeline(taskId, 'test_failed');
        }
    }

    async redeploy(taskId) {
        if (!this.pipelineState.active || this.pipelineState.taskId !== taskId) return;
        log.info(`[Pipeline] 重新构建部署: taskId=${taskId}`);
        if (this.mainWindow) {
            this.mainWindow.webContents.send('pipeline-event', {
                type: 'progress', taskId, stage: 'deploying',
                message: '正在重新打包并部署...', timestamp: Date.now()
            });
        }
        const buildResult = await this.executeLocalScriptStream(
            'mvn', ['clean', 'package', '-DskipTests', '-f', path.join(this.currentCwd, 'pom.xml')],
            { taskId: taskId + '-BUILD', timeout: 300000 }
        );
        if (buildResult.status !== 'success') {
            if (this.mainWindow) {
                this.mainWindow.webContents.send('pipeline-event', {
                    type: 'error', taskId, stage: 'deploying',
                    message: '构建失败: ' + (buildResult.stderr || '').substring(0, 500), timestamp: Date.now()
                });
            }
            this.completePipeline(taskId, 'build_failed');
            return;
        }
        const nodeConfig = this.loadNodeDeployConfig();
        const scriptPath = nodeConfig.deploy?.deployScriptPath || 'D:/work/code/yunxi-agent-platform/启动项目.bat';
        await this.executeLocalScriptStream(scriptPath, ['fast'], {
            taskId: taskId + '-REDEPLOY', timeout: 300000, cwd: this.currentCwd
        });
        this.completePipeline(taskId, 'redeployed');
    }

    completePipeline(taskId, finalStatus) {
        log.info(`[Pipeline] 流水线完成: taskId=${taskId}, status=${finalStatus}`);
        this.stopLogMonitoring();
        this.pipelineState.active = false;
        this.pipelineState.stage = 'completed';
        if (this.mainWindow) {
            this.mainWindow.webContents.send('pipeline-event', {
                type: 'completed', taskId, finalStatus,
                message: finalStatus === 'healthy' ? '部署成功，服务运行正常' :
                         finalStatus === 'redeployed' ? '自动修复并重新部署完成' :
                         finalStatus === 'test_failed' ? '自动修复后测试未通过' :
                         finalStatus === 'build_failed' ? '重新构建失败' : '流水线结束',
                timestamp: Date.now()
            });
        }
    }

    // AI 调用封装
    _callAI(requestBody, timeout = 60000) {
        const http = require('http');
        return new Promise((resolve) => {
            const url = new URL('/api/conversations/chat', this.aiParseBaseUrl);
            const req = http.request({
                hostname: url.hostname, port: url.port, path: url.pathname, method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(requestBody) },
                timeout
            }, (res) => {
                let data = '';
                res.on('data', chunk => data += chunk);
                res.on('end', () => {
                    try {
                        const json = JSON.parse(data);
                        resolve(json.content || json.message || json.data?.content || data);
                    } catch (e) { resolve(data); }
                });
            });
            req.on('error', (e) => resolve('AI 调用失败: ' + e.message));
            req.on('timeout', () => { req.destroy(); resolve('AI 调用超时'); });
            req.write(requestBody);
            req.end();
        });
    }
}

module.exports = DeployModule;
