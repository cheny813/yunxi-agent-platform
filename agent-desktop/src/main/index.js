/**
 * yunxiClaw - 主进程
 * 
 * 让 AI 能够在用户电脑上执行命令、操作文件、管理 Git
 * 支持 WebSocket 接收 AI 指令
 * 
 * @author yunxiClaw
 * @version 1.0.0
 */

const { app, BrowserWindow, ipcMain, dialog, shell, Menu, Tray, globalShortcut, nativeImage } = require('electron');
const path = require('path');
const { spawn } = require('child_process');
const http = require('http');
const WebSocket = require('ws');
const log = require('electron-log');

// 配置日志
log.transports.file.level = 'info';
log.transports.console.level = 'debug';
log.transports.file.resolvePathFn = () => path.join(app.getPath('userData'), 'logs', 'main.log');

// 打印启动信息
log.info('='.repeat(50));
log.info('yunxiClaw 启动中...');
log.info('='.repeat(50));

// 全局变量
let mainWindow = null;
let tray = null;
let serverUrl = 'ws://localhost:40001/ws/desktop';
let isConnected = false;
let wsServer = null;
let wsClients = [];
let localServerPort = 9876; // 本地 WebSocket 服务端口
let currentCwd = 'd:/work/code/yunxi-agent-platform'; // 默认工作目录

// 远程连接客户端
let remoteWs = null;
let reconnectTimer = null;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 10;
const RECONNECT_INTERVAL = 5000;

// 获取本机局域网IP
function getLocalIp() {
    const os = require('os');
    const interfaces = os.networkInterfaces();
    for (const name of Object.keys(interfaces)) {
        for (const iface of interfaces[name]) {
            if (iface.family === 'IPv4' && !iface.internal) {
                return iface.address;
            }
        }
    }
    return '127.0.0.1';
}

// 创建主窗口
function createWindow() {
    log.info('创建主窗口...');

    mainWindow = new BrowserWindow({
        width: 420,
        height: 680,
        resizable: false,
        frame: true,
        minimizable: true,
        maximizable: false,
        alwaysOnTop: false,
        title: 'yunxiClaw',
        backgroundColor: '#ffffff',
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'preload.js')
        }
    });

    // 加载前端页面
    mainWindow.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));

    // 最小化到托盘
    mainWindow.on('minimize', (event) => {
        // 可以选择是否最小化到托盘
    });

    mainWindow.on('close', (event) => {
        if (!app.isQuitting) {
            event.preventDefault();
            mainWindow.hide();
            log.info('窗口隐藏到托盘');
        }
    });

    mainWindow.on('closed', () => {
        mainWindow = null;
    });

    // 创建应用菜单
    createMenu();

    log.info('主窗口创建完成');
}

// 创建系统托盘
function createTray() {
    const iconPath = path.join(__dirname, '..', 'assets', 'icon.png');

    // 检查 icon 是否存在
    if (!require('fs').existsSync(iconPath)) {
        log.info('托盘图标不存在，跳过托盘创建');
        return;
    }

    try {
        tray = new Tray(iconPath);
        
        const contextMenu = Menu.buildFromTemplate([
            { label: '显示窗口', click: () => mainWindow && mainWindow.show() },
            { type: 'separator' },
            { label: '启动本地服务器', click: () => startLocalServerMode() },
            { label: '连接远程服务器', click: () => connectToRemoteServer() },
            { type: 'separator' },
            { label: '断开连接', click: () => disconnectServer() },
            { type: 'separator' },
            { label: '退出', click: () => quitApp() }
        ]);

        tray.setToolTip('yunxiClaw 小龙虾 智能助手');
        tray.setContextMenu(contextMenu);

        tray.on('double-click', () => {
            mainWindow && mainWindow.show();
        });

        log.info('系统托盘创建完成');
    } catch (err) {
        log.warn('托盘图标不存在，跳过托盘创建:', err.message);
    }
}

// 创建应用菜单
function createMenu() {
    const template = [
        {
            label: '文件',
            submenu: [
                { label: '浏览文件...', click: () => browseFile() },
                { label: '选择工作目录...', click: () => selectWorkDir() },
                { type: 'separator' },
                { label: '退出', accelerator: 'CmdOrCtrl+Q', click: () => quitApp() }
            ]
        },
        {
            label: '操作',
            submenu: [
                { label: 'Git 状态', click: () => executeGitStatus() },
                { label: '运行测试', click: () => executeMvnTest() },
                { label: '构建项目', click: () => executeMvnBuild() },
                { type: 'separator' },
                { label: '刷新', accelerator: 'CmdOrCtrl+R', click: () => mainWindow && mainWindow.reload() }
            ]
        },
        {
            label: '视图',
            submenu: [
                { label: '开发者工具', accelerator: 'F12', click: () => mainWindow && mainWindow.webContents.toggleDevTools() },
                { type: 'separator' },
                { label: '始终置顶', type: 'checkbox', click: (item) => mainWindow && mainWindow.setAlwaysOnTop(item.checked) }
            ]
        },
        {
            label: '帮助',
            submenu: [
                { label: '关于 yunxiClaw', click: () => showAbout() },
                { label: '使用文档', click: () => shell.openExternal('https://docs.yunxi.com/claw') }
            ]
        }
    ];

    const menu = Menu.buildFromTemplate(template);
    Menu.setApplicationMenu(menu);
}

// 创建本地 WebSocket 服务器（用于接收 AI 命令）
function createLocalServer() {
    wsServer = new WebSocket.Server({ port: localServerPort });

    wsServer.on('connection', (ws) => {
        log.info('新的 WebSocket 客户端连接');
        wsClients.push(ws);

        // 发送欢迎消息
        ws.send(JSON.stringify({
            type: 'welcome',
            message: 'yunxiClaw 已连接',
            version: '1.0.0'
        }));

        // 通知 UI
        mainWindow && mainWindow.webContents.send('ai-connected', { count: wsClients.length });

        ws.on('message', async (message) => {
            try {
                const data = JSON.parse(message);
                log.info('收到 AI 命令:', data.type);
                await handleAIMessage(ws, data);
            } catch (err) {
                log.error('处理 AI 消息失败:', err);
                ws.send(JSON.stringify({ type: 'error', message: err.message }));
            }
        });

        ws.on('close', () => {
            log.info('WebSocket 客户端断开');
            wsClients = wsClients.filter(c => c !== ws);
            mainWindow && mainWindow.webContents.send('ai-disconnected', { count: wsClients.length });
        });

        ws.on('error', (err) => {
            log.error('WebSocket 错误:', err);
        });
    });

    wsServer.on('error', (err) => {
        log.error('WebSocket 服务器错误:', err);
    });

    log.info(`本地 WebSocket 服务器已启动: ws://localhost:${localServerPort}`);
}

// 处理 AI 消息
async function handleAIMessage(ws, data) {
    let result;

    try {
        switch (data.type) {
            case 'execute':
                // 执行命令
                result = await executeCommandSync(data.command, data.args || [], data.cwd);
                break;

            case 'git':
                // Git 操作
                result = await gitOperationSync(data.operation, data.args || []);
                break;

            case 'list-dir':
                // 列出目录
                result = await listDirSync(data.path);
                break;

            case 'read-file':
                // 读取文件
                result = await readFileSync(data.path);
                break;

            case 'write-file':
                // 写入文件
                result = await writeFileSync(data.path, data.content);
                break;

            case 'get-info':
                // 获取系统信息
                const info = await getSystemInfo();
                ws.send(JSON.stringify({ type: 'info', data: info }));
                return;

            case 'ping':
                ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
                return;

            case 'collect-profile':
                // 采集节点画像信息
                const profileData = await collectProfile();
                ws.send(JSON.stringify({ type: 'profile', requestId: data.requestId, data: profileData }));
                return;

            default:
                result = { status: 'error', message: `未知命令: ${data.type}` };
        }

        // 发送结果
        ws.send(JSON.stringify({
            type: 'result',
            requestId: data.requestId,
            ...result
        }));

        // 通知 UI
        if (mainWindow) {
            mainWindow.webContents.send('ai-command', {
                type: data.type,
                result: result
            });
        }

    } catch (err) {
        ws.send(JSON.stringify({
            type: 'error',
            requestId: data.requestId,
            message: err.message
        }));
    }
}

// 同步版本的命令执行
function executeCommandSync(command, args, cwd) {
    return new Promise((resolve) => {
        const proc = spawn(command, args, {
            shell: true,
            cwd: cwd || currentCwd
        });

        let stdout = '';
        let stderr = '';

        proc.stdout.on('data', (data) => { stdout += data.toString(); });
        proc.stderr.on('data', (data) => { stderr += data.toString(); });

        const timeout = setTimeout(() => {
            proc.kill();
            resolve({ status: 'error', message: '命令执行超时 (60秒)' });
        }, 60000);

        proc.on('close', (code) => {
            clearTimeout(timeout);
            resolve({
                status: code === 0 ? 'success' : 'error',
                command: `${command} ${args.join(' ')}`,
                stdout: stdout.substring(0, 10000),
                stderr: stderr.substring(0, 5000),
                exitCode: code
            });
        });

        proc.on('error', (err) => {
            clearTimeout(timeout);
            resolve({ status: 'error', message: err.message });
        });
    });
}

// 同步版本的 Git 操作
function gitOperationSync(operation, args) {
    return new Promise((resolve) => {
        const proc = spawn('git', [operation, ...args], { shell: true });

        let stdout = '';
        let stderr = '';

        proc.stdout.on('data', (data) => { stdout += data.toString(); });
        proc.stderr.on('data', (data) => { stderr += data.toString(); });

        proc.on('close', (code) => {
            resolve({
                status: code === 0 ? 'success' : 'error',
                operation: operation,
                stdout: stdout.substring(0, 8000),
                stderr: stderr.substring(0, 2000),
                exitCode: code
            });
        });

        proc.on('error', (err) => {
            resolve({ status: 'error', message: err.message });
        });
    });
}

// 同步版本的目录列表
function listDirSync(dirPath) {
    const fs = require('fs');

    try {
        const items = fs.readdirSync(dirPath);
        const files = [];
        const dirs = [];

        items.forEach(item => {
            try {
                const fullPath = path.join(dirPath, item);
                const stat = fs.statSync(fullPath);
                if (stat.isDirectory()) {
                    dirs.push({ name: item, size: 0 });
                } else {
                    files.push({ name: item, size: stat.size });
                }
            } catch (e) { }
        });

        return {
            status: 'success',
            path: dirPath,
            files: files,
            dirs: dirs
        };
    } catch (err) {
        return { status: 'error', message: err.message };
    }
}

// 同步版本的文件读取
function readFileSync(filePath) {
    const fs = require('fs');

    try {
        const stat = fs.statSync(filePath);

        if (stat.size > 5 * 1024 * 1024) {
            return { status: 'error', message: '文件过大 (最大 5MB)' };
        }

        const content = fs.readFileSync(filePath, 'utf-8');
        return {
            status: 'success',
            path: filePath,
            name: path.basename(filePath),
            size: stat.size,
            content: content
        };
    } catch (err) {
        return { status: 'error', message: err.message };
    }
}

// 同步版本的文件写入
function writeFileSync(filePath, content) {
    const fs = require('fs');

    try {
        fs.writeFileSync(filePath, content, 'utf-8');
        return { status: 'success', path: filePath };
    } catch (err) {
        return { status: 'error', message: err.message };
    }
}

// 获取系统信息（同步版本）
// 采集节点画像数据
async function collectProfile() {
    const os = require('os');
    const profile = {
        hardware: {
            cpuModel: os.cpus()[0]?.model || 'unknown',
            cpuCores: os.cpus().length,
            totalMemoryGB: Math.round(os.totalmem() / 1024 / 1024 / 1024),
            freeMemoryGB: Math.round(os.freemem() / 1024 / 1024 / 1024)
        },
        network: {
            localIp: getLocalIp(),
            hostname: os.hostname()
        },
        software: [],
        commonPaths: {}
    };

    // 采集已安装软件
    try {
        const swList = [];
        if (process.platform === 'win32') {
            // Windows: 通过 where 查找常用软件
            const commands = [
                { name: 'git', cmd: 'where git' },
                { name: 'node', cmd: 'where node' },
                { name: 'java', cmd: 'where java' },
                { name: 'python', cmd: 'where python' },
                { name: 'docker', cmd: 'where docker' },
                { name: 'mysql', cmd: 'where mysql' },
                { name: 'redis-cli', cmd: 'where redis-cli' },
                { name: 'nginx', cmd: 'where nginx' },
                { name: 'mvn', cmd: 'where mvn' }
            ];
            for (const item of commands) {
                try {
                    const result = await executeCommandSync(item.cmd, [], undefined);
                    if (result.status === 'success') {
                        swList.push({ name: item.name, path: result.stdout?.trim()?.split('\n')[0] || '' });
                    }
                } catch (e) {}
            }
        } else {
            // Linux/Mac: 通过 which 查找
            const commands = [
                { name: 'git', cmd: 'which git' },
                { name: 'node', cmd: 'which node' },
                { name: 'java', cmd: 'which java' },
                { name: 'python3', cmd: 'which python3' },
                { name: 'docker', cmd: 'which docker' },
                { name: 'mysql', cmd: 'which mysql' },
                { name: 'redis-cli', cmd: 'which redis-cli' },
                { name: 'nginx', cmd: 'which nginx' },
                { name: 'mvn', cmd: 'which mvn' },
                { name: 'systemctl', cmd: 'which systemctl' }
            ];
            for (const item of commands) {
                try {
                    const result = await executeCommandSync(item.cmd, [], undefined);
                    if (result.status === 'success') {
                        swList.push({ name: item.name, path: result.stdout?.trim() || '' });
                    }
                } catch (e) {}
            }
        }
        profile.software = swList;
    } catch (e) {
        log.warn('采集软件信息失败:', e.message);
    }

    // 采集常用路径
    profile.commonPaths = {
        home: os.homedir(),
        tmp: os.tmpdir(),
        cwd: currentCwd,
        userData: app.getPath('userData')
    };

    return profile;
}

function getSystemInfo() {
    return {
        name: 'yunxiClaw',
        version: '1.0.0',
        platform: process.platform,
        arch: process.arch,
        nodeVersion: process.version,
        electronVersion: process.versions.electron,
        chromeVersion: process.versions.chrome,
        cwd: currentCwd,
        homeDir: app.getPath('home'),
        userDataDir: app.getPath('userData'),
        localServerPort: localServerPort
    };
}

// 连接到远程服务器（中继模式）- 桌面客户端作为客户端连接
function connectToRemoteServer() {
    // 关闭已有连接
    if (remoteWs) {
        try {
            remoteWs.close();
        } catch (e) {}
    }
    
    log.info('尝试连接到远程服务器:', serverUrl);
    
    remoteWs = new WebSocket(serverUrl);
    
    // 生成客户端ID
    const os = require('os');
    const clientId = 'yunxi-claw-' + os.hostname() + '-' + Date.now();

    // 采集节点信息
    const userInfo = os.userInfo();
    const nodeInfo = {
        userId: process.env.DESKTOP_USER_ID || userInfo.username,
        nodeType: 'desktop',
        tags: process.env.DESKTOP_TAGS ? process.env.DESKTOP_TAGS.split(',').map(t => t.trim()) : ['personal', 'desktop'],
        hostname: os.hostname(),
        os: os.type() + ' ' + os.release(),
        localIp: getLocalIp()
    };

    remoteWs.on('open', () => {
        log.info('已连接到远程服务器');
        isConnected = true;
        reconnectAttempts = 0;
        
        mainWindow && mainWindow.webContents.send('connection-changed', { 
            connected: true, 
            type: 'remote',
            serverUrl: serverUrl
        });
        
        // 发送注册消息（增强节点信息）
        remoteWs.send(JSON.stringify({
            type: 'register',
            clientId: clientId,
            capabilities: ['execute', 'git', 'file', 'list-dir', 'read-file', 'write-file'],
            userId: nodeInfo.userId,
            nodeType: nodeInfo.nodeType,
            tags: nodeInfo.tags,
            hostname: nodeInfo.hostname,
            os: nodeInfo.os,
            localIp: nodeInfo.localIp
        }));
    });

    remoteWs.on('message', async (message) => {
        try {
            const data = JSON.parse(message);
            log.info('收到远程服务器命令:', data.type);
            
            // 处理远程命令
            const result = await handleAIMessage(remoteWs, data);
            
            // 发送结果回服务器
            if (result && remoteWs.readyState === WebSocket.OPEN) {
                remoteWs.send(JSON.stringify({
                    type: 'result',
                    requestId: data.requestId,
                    ...result
                }));
            }
        } catch (err) {
            log.error('处理远程命令失败:', err);
            remoteWs.send(JSON.stringify({ 
                type: 'error', 
                message: err.message 
            }));
        }
    });

    remoteWs.on('close', () => {
        log.info('与远程服务器断开');
        isConnected = false;
        mainWindow && mainWindow.webContents.send('connection-changed', { connected: false });
        
        // 自动重连
        scheduleReconnect();
    });

    remoteWs.on('error', (err) => {
        log.error('远程连接错误:', err.message);
    });
}

// 调度重连
function scheduleReconnect() {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        log.warn('达到最大重连次数，停止重连');
        return;
    }
    
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
    }
    
    reconnectAttempts++;
    const delay = RECONNECT_INTERVAL * reconnectAttempts; // 指数退避
    
    log.info(`将在 ${delay/1000} 秒后尝试重连 (${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})`);
    
    reconnectTimer = setTimeout(() => {
        connectToRemoteServer();
    }, delay);
}

// 停止重连
function stopReconnect() {
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }
    reconnectAttempts = MAX_RECONNECT_ATTEMPTS; // 达到最大值，阻止重连
}

// 连接到服务器（菜单触发 - 启动本地服务器）
function startLocalServerMode() {
    log.info('启动本地 WebSocket 服务器...');
    
    if (!wsServer) {
        createLocalServer();
    }
    
    isConnected = true;
    mainWindow && mainWindow.webContents.send('connection-changed', { 
        connected: true, 
        type: 'local',
        port: localServerPort 
    });
    
    mainWindow && mainWindow.webContents.send('server-started', {
        port: localServerPort,
        url: `ws://localhost:${localServerPort}`
    });
}

// 保持向后兼容
function connectToServer() {
    startLocalServerMode();
}

// 断开服务器
function disconnectServer() {
    // 断开远程连接
    stopReconnect();
    if (remoteWs) {
        try {
            remoteWs.close();
        } catch (e) {}
        remoteWs = null;
    }
    
    // 关闭本地服务器
    if (wsServer) {
        wsServer.close();
        wsServer = null;
        wsClients = [];
    }
    
    isConnected = false;
    mainWindow && mainWindow.webContents.send('connection-changed', { connected: false });
}

// 退出应用
function quitApp() {
    app.isQuitting = true;
    app.quit();
}

// 快捷操作：浏览文件
async function browseFile() {
    const result = await dialog.showOpenDialog(mainWindow, {
        properties: ['openFile'],
        filters: [
            { name: '所有文件', extensions: ['*'] },
            { name: 'Java文件', extensions: ['java'] },
            { name: '前端文件', extensions: ['js', 'ts', 'vue', 'html', 'css'] }
        ]
    });

    if (!result.canceled && result.filePaths.length > 0) {
        mainWindow.webContents.send('file-selected', { path: result.filePaths[0] });
    }
}

// 快捷操作：选择工作目录
async function selectWorkDir() {
    const result = await dialog.showOpenDialog(mainWindow, {
        properties: ['openDirectory']
    });

    if (!result.canceled && result.filePaths.length > 0) {
        mainWindow.webContents.send('dir-selected', { path: result.filePaths[0] });
    }
}

// 快捷操作：Git 状态
function executeGitStatus() {
    executeCommand('git', ['status'], 'git-status');
}

// 快捷操作：Maven 测试
function executeMvnTest() {
    executeCommand('mvn', ['test'], 'mvn-test');
}

// 快捷操作：Maven 构建
function executeMvnBuild() {
    executeCommand('mvn', ['clean', 'package', '-DskipTests'], 'mvn-build');
}

// 执行命令并发送结果到渲染进程
function executeCommand(command, args, action) {
    log.info(`执行命令: ${command} ${args.join(' ')}`);

    const proc = spawn(command, args, {
        shell: true,
        cwd: currentCwd
    });

    let stdout = '';
    let stderr = '';

    proc.stdout.on('data', (data) => {
        stdout += data.toString();
    });

    proc.stderr.on('data', (data) => {
        stderr += data.toString();
    });

    proc.on('close', (code) => {
        const result = {
            action: action,
            command: `${command} ${args.join(' ')}`,
            status: code === 0 ? 'success' : 'error',
            stdout: stdout.substring(0, 5000), // 限制长度
            stderr: stderr.substring(0, 2000),
            exitCode: code
        };

        mainWindow && mainWindow.webContents.send('command-result', result);
        log.info(`命令执行完成, 退出码: ${code}`);
    });

    proc.on('error', (err) => {
        mainWindow && mainWindow.webContents.send('command-result', {
            action: action,
            status: 'error',
            stderr: err.message
        });
    });
}

// 显示关于对话框
function showAbout() {
    dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: '关于 yunxiClaw',
        message: 'yunxiClaw',
        detail: `版本: 1.0.0
作者: yunxi
描述: 让 AI 能够在您的电脑上执行操作

功能:
• 连接 AI 助手
• 执行系统命令
• Git 操作
• 文件管理
• 项目构建`
    });
}

// IPC 通信处理
function setupIPC() {
    // 获取系统信息
    ipcMain.handle('get-system-info', async () => {
        return {
            name: 'yunxiClaw',
            version: '1.0.0',
            platform: process.platform,
            arch: process.arch,
            nodeVersion: process.version,
            electronVersion: process.versions.electron,
            chromeVersion: process.versions.chrome,
            homeDir: app.getPath('home'),
            userDataDir: app.getPath('userData'),
            tempDir: app.getPath('temp'),
            appPath: app.getAppPath()
        };
    });

    // 获取连接状态
    ipcMain.handle('get-connection-status', async () => {
        return { connected: isConnected, serverUrl };
    });

    // 设置服务器地址
    ipcMain.handle('set-server-url', async (event, url) => {
        serverUrl = url;
        log.info('服务器地址已设置为:', url);
        return { success: true };
    });

    // 执行系统命令
    ipcMain.handle('execute-command', async (event, { command, args = [], cwd }) => {
        log.info('执行命令:', command, args);

        return new Promise((resolve) => {
            const proc = spawn(command, args, {
                shell: true,
                cwd: cwd || currentCwd
            });

            let stdout = '';
            let stderr = '';

            proc.stdout.on('data', (data) => {
                stdout += data.toString();
            });

            proc.stderr.on('data', (data) => {
                stderr += data.toString();
            });

            // 超时 60 秒
            const timeout = setTimeout(() => {
                proc.kill();
                resolve({
                    status: 'error',
                    message: '命令执行超时 (60秒)',
                    command: command
                });
            }, 60000);

            proc.on('close', (code) => {
                clearTimeout(timeout);
                resolve({
                    status: code === 0 ? 'success' : 'error',
                    command: command,
                    stdout: stdout.substring(0, 10000),
                    stderr: stderr.substring(0, 5000),
                    exitCode: code
                });
            });

            proc.on('error', (err) => {
                clearTimeout(timeout);
                resolve({
                    status: 'error',
                    command: command,
                    message: err.message
                });
            });
        });
    });

    // 列出目录
    ipcMain.handle('list-dir', async (event, dirPath) => {
        const fs = require('fs');

        try {
            const items = fs.readdirSync(dirPath);
            const files = [];
            const dirs = [];

            items.forEach(item => {
                try {
                    const fullPath = path.join(dirPath, item);
                    const stat = fs.statSync(fullPath);
                    if (stat.isDirectory()) {
                        dirs.push({ name: item, size: 0 });
                    } else {
                        files.push({ name: item, size: stat.size });
                    }
                } catch (e) {
                    // 跳过无法访问的项目
                }
            });

            return {
                status: 'success',
                path: dirPath,
                files: files,
                dirs: dirs
            };
        } catch (err) {
            return {
                status: 'error',
                message: err.message
            };
        }
    });

    // 读取文件
    ipcMain.handle('read-file', async (event, filePath) => {
        const fs = require('fs');

        try {
            const stat = fs.statSync(filePath);

            if (stat.size > 5 * 1024 * 1024) {
                return { status: 'error', message: '文件过大 (最大 5MB)' };
            }

            const content = fs.readFileSync(filePath, 'utf-8');
            return {
                status: 'success',
                path: filePath,
                name: path.basename(filePath),
                size: stat.size,
                content: content
            };
        } catch (err) {
            return {
                status: 'error',
                message: err.message
            };
        }
    });

    // 写入文件
    ipcMain.handle('write-file', async (event, { filePath, content }) => {
        const fs = require('fs');

        try {
            fs.writeFileSync(filePath, content, 'utf-8');
            return {
                status: 'success',
                path: filePath
            };
        } catch (err) {
            return {
                status: 'error',
                message: err.message
            };
        }
    });

    // 选择文件
    ipcMain.handle('select-file', async (event, options = {}) => {
        const result = await dialog.showOpenDialog(mainWindow, {
            properties: ['openFile'],
            filters: options.filters || [{ name: '所有文件', extensions: ['*'] }]
        });

        if (result.canceled) {
            return { status: 'canceled' };
        }

        return {
            status: 'success',
            path: result.filePaths[0]
        };
    });

    // 选择目录
    ipcMain.handle('select-folder', async () => {
        const result = await dialog.showOpenDialog(mainWindow, {
            properties: ['openDirectory']
        });

        if (result.canceled) {
            return { status: 'canceled' };
        }

        return {
            status: 'success',
            path: result.filePaths[0]
        };
    });

    // 获取当前工作目录
    ipcMain.handle('get-cwd', async () => {
        return currentCwd;
    });

    // 设置工作目录
    ipcMain.handle('set-cwd', async (event, dirPath) => {
        try {
            currentCwd = dirPath;
            return { status: 'success', cwd: dirPath };
        } catch (err) {
            return { status: 'error', message: err.message };
        }
    });

    // Git 操作
    ipcMain.handle('git-operation', async (event, { operation, args = [] }) => {
        log.info('Git 操作:', operation, args);

        return new Promise((resolve) => {
            const proc = spawn('git', [operation, ...args], { shell: true });

            let stdout = '';
            let stderr = '';

            proc.stdout.on('data', (data) => { stdout += data.toString(); });
            proc.stderr.on('data', (data) => { stderr += data.toString(); });

            proc.on('close', (code) => {
                resolve({
                    status: code === 0 ? 'success' : 'error',
                    operation: operation,
                    stdout: stdout.substring(0, 8000),
                    stderr: stderr.substring(0, 2000),
                    exitCode: code
                });
            });

            proc.on('error', (err) => {
                resolve({ status: 'error', message: err.message });
            });
        });
    });

    // 打开外部链接
    ipcMain.handle('open-external', async (event, url) => {
        await shell.openExternal(url);
        return { success: true };
    });

    // 显示消息框
    ipcMain.handle('show-message', async (event, { type, title, message, detail }) => {
        return dialog.showMessageBox(mainWindow, {
            type: type || 'info',
            title: title || 'yunxiClaw',
            message: message,
            detail: detail
        });
    });

    // 启动本地服务器
    ipcMain.handle('start-local-server', async () => {
        if (wsServer) {
            return { 
                status: 'success', 
                port: localServerPort, 
                url: `ws://localhost:${localServerPort}` 
            };
        }
        
        createLocalServer();
        
        return { 
            status: 'success', 
            port: localServerPort, 
            url: `ws://localhost:${localServerPort}` 
        };
    });

    // 获取本地服务器 URL
    ipcMain.handle('get-local-server-url', async () => {
        return {
            url: wsServer ? `ws://localhost:${localServerPort}` : null,
            port: localServerPort,
            running: wsServer !== null
        };
    });

    // 连接远程服务器
    ipcMain.handle('connect-remote-server', async () => {
        connectToRemoteServer();
        return { success: true, serverUrl: serverUrl };
    });

    // 断开远程服务器
    ipcMain.handle('disconnect-remote-server', async () => {
        disconnectServer();
        return { success: true };
    });
}

// 应用就绪
app.whenReady().then(() => {
    log.info('应用准备就绪');
    createWindow();
    createTray();
    setupIPC();
    registerShortcuts();

    // 注册全局快捷键
    globalShortcut.register('CommandOrControl+Shift+C', () => {
        mainWindow && mainWindow.show();
    });

    log.info('yunxiClaw 启动完成');
});

// 窗口全部关闭
app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
    }
});

app.on('will-quit', () => {
    globalShortcut.unregisterAll();
});

// 注册快捷键
function registerShortcuts() {
    // 预留全局快捷键
}

// 全局异常处理
process.on('uncaughtException', (error) => {
    log.error('未捕获的异常:', error);
    dialog.showErrorBox('错误', `发生错误: ${error.message}`);
});

process.on('unhandledRejection', (reason, promise) => {
    log.error('未处理的 Promise 拒绝:', reason);
});