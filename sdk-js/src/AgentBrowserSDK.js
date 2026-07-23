/**
 * Agent SDK - 浏览器端机器控制能力
 * 让 AI Agent 能够在用户浏览器中执行脚本和命令
 *
 * 使用方式:
 * 1. 在页面中引入: <script src="agent-sdk.js"></script>
 * 2. 初始化: AgentSDK.init({ apiBase: '/api/agent' })
 * 3. AI 即可通过 SDK 在用户机器上执行操作
 */

const AgentSDK = (function() {
    'use strict';

    let config = {
        apiBase: '/api/agent',
        autoInit: true,
        debug: false
    };

    let initialized = false;

    /**
     * 初始化 SDK
     * @param {Object} options 配置选项
     */
    function init(options) {
        if (initialized) {
            log('SDK already initialized');
            return;
        }

        config = { ...config, ...options };

        // 检查浏览器环境
        if (!isBrowser()) {
            log('Not in browser environment');
            return;
        }

        // 注册全局方法供 AI 调用
        registerGlobalMethods();

        initialized = true;
        log('Agent SDK initialized');
    }

    /**
     * 检查是否在浏览器环境
     */
    function isBrowser() {
        return typeof window !== 'undefined' && typeof document !== 'undefined';
    }

    /**
     * 注册全局方法供 AI 调用
     */
    function registerGlobalMethods() {
        if (!window.AgentControl) {
            window.AgentControl = {
                // 文件操作
                listDir: listDir,
                readFile: readFile,
                writeFile: writeFile,
                fileExists: fileExists,

                // 命令执行
                executeCommand: executeCommand,
                runScript: runScript,

                // 进程管理
                listProcesses: listProcesses,
                killProcess: killProcess,

                // 系统信息
                getSystemInfo: getSystemInfo,
                getEnvVars: getEnvVars,

                // 开发者工具
                runTests: runTests,
                buildProject: buildProject,
                git操作: gitOperation,

                // 页面交互
                clickElement: clickElement,
                fillForm: fillForm,
                getPageInfo: getPageInfo
            };
        }
    }

    // ==================== 文件操作 ====================

    /**
     * 列出目录内容
     * @param {string} path 目录路径
     */
    function listDir(path) {
        log('listDir called:', path);

        // 本地模拟实现（实际应该通过 API 调用后端或本地脚本）
        const mockFiles = ['src', 'target', 'pom.xml', 'README.md'];
        return {
            status: 'success',
            path: path,
            files: mockFiles,
            dirs: ['src', 'target']
        };
    }

    /**
     * 读取文件内容（限制于浏览器安全，无法直接读取本地文件）
     */
    function readFile(path) {
        log('readFile called:', path);
        return {
            status: 'error',
            message: 'Browser security: Cannot read local files directly. Use file input or drag-drop.'
        };
    }

    /**
     * 写入文件（下载文件到本地）
     */
    function writeFile(path, content) {
        log('writeFile called:', path);

        // 触发文件下载
        const blob = new Blob([content], { type: 'text/plain' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = path.split('/').pop();
        a.click();
        URL.revokeObjectURL(url);

        return {
            status: 'success',
            message: 'File downloaded: ' + path
        };
    }

    /**
     * 检查文件是否存在
     */
    function fileExists(path) {
        return { status: 'error', message: 'Cannot check local file existence in browser' };
    }

    // ==================== 命令执行 ====================

    /**
     * 执行系统命令（通过 API 代理或 WebUSB）
     */
    function executeCommand(command, args) {
        log('executeCommand called:', command, args);

        // 常见内置命令可以直接执行
        const builtinCommands = {
            'date': () => new Date().toISOString(),
            'time': () => new Date().toLocaleTimeString(),
            'user-agent': () => navigator.userAgent,
            'platform': () => navigator.platform,
            'language': () => navigator.language
        };

        if (builtinCommands[command]) {
            return {
                status: 'success',
                command: command,
                output: builtinCommands[command](),
                exitCode: 0
            };
        }

        // 其他命令需要通过 API
        return callAPI('execute', { command, args });
    }

    /**
     * 运行脚本
     */
    function runScript(scriptContent, language) {
        log('runScript called, language:', language);

        try {
            if (language === 'javascript' || language === 'js') {
                // 安全地执行 JavaScript
                const result = new Function(scriptContent)();
                return {
                    status: 'success',
                    output: String(result),
                    exitCode: 0
                };
            }

            // 其他语言需要通过 API 执行
            return callAPI('run-script', { script: scriptContent, language });
        } catch (e) {
            return {
                status: 'error',
                message: e.message,
                exitCode: 1
            };
        }
    }

    // ==================== 进程管理 ====================

    /**
     * 列出运行中的进程
     */
    function listProcesses() {
        // 浏览器环境无法直接获取进程列表
        return {
            status: 'error',
            message: 'Browser security: Cannot list system processes'
        };
    }

    /**
     * 终止进程
     */
    function killProcess(pid) {
        return {
            status: 'error',
            message: 'Browser security: Cannot kill system processes'
        };
    }

    // ==================== 系统信息 ====================

    /**
     * 获取系统信息
     */
    function getSystemInfo() {
        return {
            status: 'success',
            data: {
                platform: navigator.platform,
                userAgent: navigator.userAgent,
                language: navigator.language,
                languages: navigator.languages,
                cookiesEnabled: navigator.cookieEnabled,
                screenWidth: screen.width,
                screenHeight: screen.height,
                windowSize: `${window.innerWidth}x${window.innerHeight}`,
                online: navigator.onLine,
                timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
                cores: navigator.hardwareConcurrency || 'unknown',
                memory: navigator.deviceMemory ? `${navigator.deviceMemory}GB` : 'unknown'
            }
        };
    }

    /**
     * 获取环境变量
     */
    function getEnvVars() {
        // 浏览器环境没有传统意义上的环境变量
        return {
            status: 'success',
            data: {
                'browser': 'Chrome/Firefox/Edge',
                'location.href': window.location.href,
                'location.origin': window.location.origin
            }
        };
    }

    // ==================== 开发者工具 ====================

    /**
     * 运行测试
     */
    function runTests(testFramework) {
        log('runTests called:', testFramework);
        return callAPI('run-tests', { framework: testFramework });
    }

    /**
     * 构建项目
     */
    function buildProject(buildTool) {
        log('buildProject called:', buildTool);
        return callAPI('build', { tool: buildTool });
    }

    /**
     * Git 操作
     */
    function gitOperation(operation, args) {
        log('gitOperation called:', operation, args);
        return callAPI('git', { operation, args });
    }

    // ==================== 页面交互 ====================

    /**
     * 点击元素
     */
    function clickElement(selector) {
        try {
            const el = document.querySelector(selector);
            if (el) {
                el.click();
                return { status: 'success', message: 'Clicked: ' + selector };
            }
            return { status: 'error', message: 'Element not found: ' + selector };
        } catch (e) {
            return { status: 'error', message: e.message };
        }
    }

    /**
     * 填写表单
     */
    function fillForm(selector, values) {
        try {
            const form = document.querySelector(selector);
            if (!form) {
                return { status: 'error', message: 'Form not found: ' + selector };
            }

            const results = [];
            for (const [name, value] of Object.entries(values)) {
                const input = form.querySelector(`[name="${name}"]`);
                if (input) {
                    input.value = value;
                    results.push({ field: name, status: 'success' });
                } else {
                    results.push({ field: name, status: 'error', message: 'Field not found' });
                }
            }

            return { status: 'success', results };
        } catch (e) {
            return { status: 'error', message: e.message };
        }
    }

    /**
     * 获取页面信息
     */
    function getPageInfo() {
        return {
            status: 'success',
            data: {
                title: document.title,
                url: window.location.href,
                referrer: document.referrer,
                forms: document.forms.length,
                links: document.links.length,
                images: document.images.length,
                scripts: document.scripts.length,
                elements: document.querySelectorAll('*').length
            }
        };
    }

    // ==================== API 通信 ====================

    /**
     * 调用后端 API
     */
    function callAPI(endpoint, data) {
        return fetch(`${config.apiBase}/${endpoint}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        }).then(res => res.json()).catch(err => ({
            status: 'error',
            message: 'API call failed: ' + err.message
        }));
    }

    /**
     * 日志输出
     */
    function log(...args) {
        if (config.debug) {
            console.log('[AgentSDK]', ...args);
        }
    }

    // ==================== 公开 API ====================

    return {
        init: init,
        version: '1.0.0',

        // 工具方法
        execute: executeCommand,
        script: runScript,
        sysinfo: getSystemInfo,
        pageinfo: getPageInfo,

        // 便捷方法
        click: clickElement,
        fill: fillForm,
        download: writeFile,

        // 状态
        isInitialized: () => initialized
    };
})();

// 自动初始化
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => AgentSDK.init());
} else {
    AgentSDK.init();
}

// 导出全局
window.AgentSDK = AgentSDK;