/**
 * yunxiClaw - Preload Script
 * 
 * 在渲染进程和主进程之间建立安全的通信桥梁
 * 暴露有限的 API 给前端页面使用
 */

const { contextBridge, ipcRenderer } = require('electron');

// 暴露给前端的安全 API
contextBridge.exposeInMainWorld('agentAPI', {
    // ===== 系统信息 =====
    getSystemInfo: () => ipcRenderer.invoke('get-system-info'),
    getCwd: () => ipcRenderer.invoke('get-cwd'),
    setCwd: (path) => ipcRenderer.invoke('set-cwd', path),

    // ===== 连接状态 =====
    getConnectionStatus: () => ipcRenderer.invoke('get-connection-status'),
    setServerUrl: (url) => ipcRenderer.invoke('set-server-url', url),
    startLocalServer: () => ipcRenderer.invoke('start-local-server'),
    getLocalServerUrl: () => ipcRenderer.invoke('get-local-server-url'),
    connectRemoteServer: () => ipcRenderer.invoke('connect-remote-server'),
    disconnectRemoteServer: () => ipcRenderer.invoke('disconnect-remote-server'),

    // ===== 文件操作 =====
    listDir: (path) => ipcRenderer.invoke('list-dir', path),
    readFile: (path) => ipcRenderer.invoke('read-file', path),
    writeFile: (path, content) => ipcRenderer.invoke('write-file', { filePath: path, content }),
    selectFile: (options) => ipcRenderer.invoke('select-file', options),
    selectFolder: () => ipcRenderer.invoke('select-folder'),

    // ===== 命令执行 =====
    executeCommand: (command, args, cwd) => ipcRenderer.invoke('execute-command', { command, args, cwd }),

    // ===== Git 操作 =====
    gitOperation: (operation, args) => ipcRenderer.invoke('git-operation', { operation, args }),

    // ===== 工具方法 =====
    openExternal: (url) => ipcRenderer.invoke('open-external', url),
    showMessage: (options) => ipcRenderer.invoke('show-message', options),

    // ===== 事件监听 =====
    onCommandResult: (callback) => {
        ipcRenderer.on('command-result', (event, result) => callback(result));
    },
    onConnectionChanged: (callback) => {
        ipcRenderer.on('connection-changed', (event, data) => callback(data));
    },
    onFileSelected: (callback) => {
        ipcRenderer.on('file-selected', (event, data) => callback(data));
    },
    onDirSelected: (callback) => {
        ipcRenderer.on('dir-selected', (event, data) => callback(data));
    },
    onServerStarted: (callback) => {
        ipcRenderer.on('server-started', (event, data) => callback(data));
    },
    onAIConnected: (callback) => {
        ipcRenderer.on('ai-connected', (event, data) => callback(data));
    },
    onAIDisconnected: (callback) => {
        ipcRenderer.on('ai-disconnected', (event, data) => callback(data));
    },
    onAICommand: (callback) => {
        ipcRenderer.on('ai-command', (event, data) => callback(data));
    },

    // ===== 移除监听 =====
    removeAllListeners: () => {
        ipcRenderer.removeAllListeners('command-result');
        ipcRenderer.removeAllListeners('connection-changed');
        ipcRenderer.removeAllListeners('file-selected');
        ipcRenderer.removeAllListeners('dir-selected');
        ipcRenderer.removeAllListeners('server-started');
        ipcRenderer.removeAllListeners('ai-connected');
        ipcRenderer.removeAllListeners('ai-disconnected');
        ipcRenderer.removeAllListeners('ai-command');
    }
});

// 打印加载信息
console.log('yunxiClaw preload script loaded');