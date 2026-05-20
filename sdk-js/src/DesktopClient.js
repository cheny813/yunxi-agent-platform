/**
 * yunxiClaw Desktop Client SDK
 * 
 * 用于在用户项目中集成桌面客户端功能
 * 支持执行命令、文件操作、Git 操作等
 * 
 * @version 1.0.0
 * @author YGC
 * 
 * @example
 * // 基础使用
 * import { DesktopClient } from 'yunxiclaw-sdk';
 * 
 * const client = new DesktopClient({
 *   serverUrl: 'http://localhost:40001'
 * });
 * 
 * // 连接到中继服务器
 * await client.connect();
 * 
 * // 执行命令
 * const result = await client.executeCommand('echo', ['Hello']);
 * console.log(result.stdout);
 * 
 * @example
 * // 浏览器环境
 * const client = new DesktopClient({
 *   serverUrl: 'http://localhost:40001'
 * });
 */

class DesktopClient {
  /**
   * 创建 DesktopClient 实例
   * @param {Object} options 配置选项
   * @param {string} options.serverUrl - 后端服务地址 (例如: "http://localhost:40001")
   * @param {string} options.clientId - 客户端ID（可选，默认自动生成）
   * @param {number} options.timeout - 超时时间(毫秒)，默认60000
   * @param {Object} options.headers - 自定义请求头
   */
  constructor(options = {}) {
    if (!options.serverUrl) {
      throw new Error('serverUrl 不能为空');
    }

    this.serverUrl = options.serverUrl.replace(/\/$/, '');
    this.clientId = options.clientId || this._generateClientId();
    this.timeout = options.timeout || 60000;
    this.headers = options.headers || {};
    
    // 内部状态
    this._ws = null;
    this._connected = false;
    this._wsConnected = false;
    this._pendingRequests = new Map();
    this._eventListeners = new Map();
    
    // 检测环境
    this._isBrowser = typeof window !== 'undefined' && typeof WebSocket !== 'undefined';
    this._fetch = this._isBrowser ? window.fetch : null;
    
    console.log(`[yunxiClaw] DesktopClient 初始化: serverUrl=${this.serverUrl}, clientId=${this.clientId}`);
  }

  // ==================== 连接管理 ====================

  /**
   * 连接到中继服务器（WebSocket）
   * @returns {Promise<void>}
   */
  async connectWebSocket() {
    return new Promise((resolve, reject) => {
      const wsUrl = `${this.serverUrl.replace('http', 'ws')}/ws/desktop?clientId=${this.clientId}`;
      
      try {
        this._ws = new WebSocket(wsUrl);
      } catch (e) {
        reject(new Error(`WebSocket 连接失败: ${e.message}`));
        return;
      }

      const timeout = setTimeout(() => {
        this._ws.close();
        reject(new Error('WebSocket 连接超时'));
      }, 10000);

      this._ws.onopen = () => {
        clearTimeout(timeout);
        this._wsConnected = true;
        console.log('[yunxiClaw] WebSocket 已连接');
        
        // 注册客户端
        this._ws.send(JSON.stringify({
          type: 'register',
          clientId: this.clientId,
          capabilities: ['execute', 'git', 'file', 'list-dir', 'read-file', 'write-file']
        }));
        
        resolve();
      };

      this._ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          this._handleWsMessage(data);
        } catch (e) {
          console.error('[yunxiClaw] 解析消息失败:', e);
        }
      };

      this._ws.onerror = (error) => {
        clearTimeout(timeout);
        reject(new Error('WebSocket 错误'));
      };

      this._ws.onclose = () => {
        this._wsConnected = false;
        console.log('[yunxiClaw] WebSocket 已断开');
        this._emit('disconnected', {});
      };
    });
  }

  /**
   * 断开连接
   */
  disconnect() {
    if (this._ws) {
      this._ws.close();
      this._ws = null;
    }
    this._connected = false;
    this._wsConnected = false;
  }

  /**
   * 获取连接状态
   * @returns {Object} 连接状态
   */
  getConnectionStatus() {
    return {
      connected: this._connected,
      wsConnected: this._wsConnected,
      clientId: this.clientId
    };
  }

  // ==================== 命令执行 ====================

  /**
   * 执行系统命令
   * @param {string} command - 命令
   * @param {string[]} args - 参数
   * @param {string} cwd - 工作目录（可选）
   * @returns {Promise<Object>} 执行结果
   * 
   * @example
   * const result = await client.executeCommand('mvn', ['test'], 'D:\\project');
   * console.log(result.stdout);
   */
  async executeCommand(command, args = [], cwd = null) {
    return this._sendCommand({
      type: 'execute',
      command,
      args,
      cwd
    });
  }

  /**
   * 执行 Git 操作
   * @param {string} operation - 操作类型 (status, pull, push, add, commit, log, branch, diff, stash)
   * @param {string[]} args - 参数
   * @returns {Promise<Object>} 执行结果
   * 
   * @example
   * const result = await client.git('status');
   * const result = await client.git('pull', ['origin', 'main']);
   */
  async git(operation, args = []) {
    return this._sendCommand({
      type: 'git',
      operation,
      args
    });
  }

  /**
   * 列出目录内容
   * @param {string} path - 目录路径
   * @returns {Promise<Object>} 目录内容
   * 
   * @example
   * const result = await client.listDir('D:\\project');
   * console.log(result.files, result.dirs);
   */
  async listDir(path) {
    return this._sendCommand({
      type: 'list-dir',
      path
    });
  }

  /**
   * 读取文件
   * @param {string} path - 文件路径
   * @returns {Promise<Object>} 文件内容
   * 
   * @example
   * const result = await client.readFile('D:\\project\\pom.xml');
   * console.log(result.content);
   */
  async readFile(path) {
    return this._sendCommand({
      type: 'read-file',
      path
    });
  }

  /**
   * 写入文件
   * @param {string} path - 文件路径
   * @param {string} content - 文件内容
   * @returns {Promise<Object>} 执行结果
   * 
   * @example
   * await client.writeFile('D:\\project\\test.txt', 'Hello World');
   */
  async writeFile(path, content) {
    return this._sendCommand({
      type: 'write-file',
      path,
      content
    });
  }

  /**
   * 心跳检测
   * @returns {Promise<Object>}
   */
  async ping() {
    return this._sendCommand({ type: 'ping' });
  }

  // ==================== 批量操作 ====================

  /**
   * 获取所有在线客户端
   * @returns {Promise<Object[]>} 客户端列表
   */
  async getClients() {
    const response = await this._request('/api/desktop/clients');
    const data = await response.json();
    return data.clients || [];
  }

  /**
   * 发送命令到指定客户端（远程控制）
   * @param {string} targetClientId - 目标客户端ID
   * @param {Object} command - 命令对象
   * @returns {Promise<Object>} 执行结果
   */
  async sendToClient(targetClientId, command) {
    return this._request(`/api/desktop/command/${targetClientId}`, {
      method: 'POST',
      body: JSON.stringify(command)
    }).then(r => r.json());
  }

  /**
   * 广播命令到所有客户端
   * @param {Object} command - 命令对象
   * @returns {Promise<Object>} 执行结果
   */
  async broadcast(command) {
    return this._request('/api/desktop/broadcast', {
      method: 'POST',
      body: JSON.stringify(command)
    }).then(r => r.json());
  }

  /**
   * 获取中继服务器状态
   * @returns {Promise<Object>} 状态信息
   */
  async getStatus() {
    const response = await this._request('/api/desktop/status');
    return response.json();
  }

  // ==================== 事件处理 ====================

  /**
   * 监听事件
   * @param {string} event - 事件类型 (connected, disconnected, result, error)
   * @param {Function} callback - 回调函数
   */
  on(event, callback) {
    if (!this._eventListeners.has(event)) {
      this._eventListeners.set(event, []);
    }
    this._eventListeners.get(event).push(callback);
  }

  /**
   * 移除事件监听
   * @param {string} event - 事件类型
   * @param {Function} callback - 回调函数
   */
  off(event, callback) {
    const listeners = this._eventListeners.get(event);
    if (listeners) {
      const index = listeners.indexOf(callback);
      if (index > -1) {
        listeners.splice(index, 1);
      }
    }
  }

  // ==================== 私有方法 ====================

  /**
   * 发送命令（通过 API）
   * @private
   */
  async _sendCommand(command) {
    const requestId = this._generateRequestId();
    command.requestId = requestId;
    
    // 通过 API 发送（同步等待结果）
    const response = await this._request(`/api/desktop/command/${this.clientId}`, {
      method: 'POST',
      body: JSON.stringify(command)
    });

    const result = await response.json();
    
    if (!result.success && result.error) {
      throw new Error(result.message || result.error);
    }
    
    return result;
  }

  /**
   * 处理 WebSocket 消息
   * @private
   */
  _handleWsMessage(data) {
    const { type, requestId } = data;

    switch (type) {
      case 'welcome':
      case 'registered':
        this._connected = true;
        this._emit('connected', data);
        break;

      case 'result':
      case 'error':
        // 处理命令结果
        if (requestId && this._pendingRequests.has(requestId)) {
          const { resolve, reject } = this._pendingRequests.get(requestId);
          this._pendingRequests.delete(requestId);
          
          if (type === 'error') {
            reject(new Error(data.message || '命令执行失败'));
          } else {
            resolve(data);
          }
        }
        this._emit('result', data);
        break;

      case 'ping':
        // 心跳
        if (this._ws && this._ws.readyState === WebSocket.OPEN) {
          this._ws.send(JSON.stringify({ type: 'pong' }));
        }
        break;

      default:
        console.log('[yunxiClaw] 未知消息类型:', type);
    }
  }

  /**
   * 触发事件
   * @private
   */
  _emit(event, data) {
    const listeners = this._eventListeners.get(event);
    if (listeners) {
      listeners.forEach(callback => {
        try {
          callback(data);
        } catch (e) {
          console.error(`[yunxiClaw] 事件回调错误: ${e.message}`);
        }
      });
    }
  }

  /**
   * HTTP 请求
   * @private
   */
  async _request(endpoint, options = {}) {
    const url = `${this.serverUrl}${endpoint}`;
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), this.timeout);

    try {
      const response = await this._fetch(url, {
        ...options,
        headers: {
          'Content-Type': 'application/json',
          ...this.headers,
          ...options.headers
        },
        signal: controller.signal
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        const text = await response.text();
        throw new Error(`HTTP ${response.status}: ${text}`);
      }

      return response;
    } catch (error) {
      clearTimeout(timeoutId);
      if (error.name === 'AbortError') {
        throw new Error(`请求超时 (${this.timeout}ms)`);
      }
      throw error;
    }
  }

  /**
   * 生成客户端ID
   * @private
   */
  _generateClientId() {
    const hostname = typeof require !== 'undefined' ? 
      (require('os').hostname ? require('os').hostname() : 'unknown') : 
      'browser';
    return `yunxiclaw-${hostname}-${Date.now()}`;
  }

  /**
   * 生成请求ID
   * @private
   */
  _generateRequestId() {
    return `req-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }
}

// ==================== 导出 ====================

// Node.js / ES6 模块
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { DesktopClient };
}

// 浏览器全局
if (typeof window !== 'undefined') {
  window.DesktopClient = DesktopClient;
}