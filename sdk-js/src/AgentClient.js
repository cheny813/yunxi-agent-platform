/**
 * yunxi Agent Platform JavaScript/TypeScript Client
 * 
 * 提供简洁易用的API来调用Agent Platform服务
 * 支持Node.js和浏览器环境
 * 
 * @version 2.0.0
 * @author yunxi Agent Platform
 * 
 * @example
 * // 基础使用
 * const client = new AgentClient('http://localhost:8080');
 * const response = await client.chat('如何使用JavaScript?');
 * 
 * @example
 * // 流式输出
 * await client.chatStream('写个冒泡排序', (chunk) => {
 *     console.log(chunk);
 * });
 */

class AgentClient {
    /**
     * 创建AgentClient实例
     * @param {string} baseUrl - 服务基础URL (例如: "http://localhost:8080")
     * @param {Object} options - 可选配置
     * @param {string} options.defaultUserId - 默认用户ID
     * @param {string} options.defaultAgentName - 默认Agent名称
     * @param {number} options.timeout - 超时时间(毫秒)，默认300000(5分钟)
     * @param {Object} options.headers - 自定义请求头
     */
    constructor(baseUrl, options = {}) {
        if (!baseUrl || typeof baseUrl !== 'string' || baseUrl.trim() === '') {
            throw new Error('baseUrl 不能为空');
        }

        // 确保baseUrl不以斜杠结尾
        this.baseUrl = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
        this.defaultUserId = options.defaultUserId || null;
        this.defaultAgentName = options.defaultAgentName || null;
        this.timeout = options.timeout || 300000; // 5分钟
        this.defaultHeaders = options.headers || {};

        // 检测环境 (Node.js 或 浏览器)
        this.isNode = typeof process !== 'undefined' && process.versions && process.versions.node;
        
        // 选择合适的fetch实现
        if (this.isNode) {
            this.fetch = require('node-fetch');
        } else if (typeof window !== 'undefined' && window.fetch) {
            this.fetch = window.fetch.bind(window);
        } else {
            throw new Error('无法找到fetch实现，请安装node-fetch或在浏览器中使用');
        }

        console.log(`AgentClient初始化完成: baseUrl=${this.baseUrl}`);
    }

    // ==================== 私有方法 ====================

    /**
     * 构建请求URL
     * @private
     */
    _buildUrl(endpoint) {
        return `${this.baseUrl}${endpoint}`;
    }

    /**
     * 执行HTTP请求
     * @private
     */
    async _request(url, options = {}) {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), this.timeout);

        try {
            const headers = {
                'Content-Type': 'application/json',
                ...this.defaultHeaders,
                ...options.headers
            };

            const response = await this.fetch(url, {
                ...options,
                headers,
                signal: controller.signal
            });

            clearTimeout(timeoutId);

            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`HTTP ${response.status}: ${errorText}`);
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
     * 构建统一对话请求体
     * @private
     */
    _buildChatRequest(message, options = {}) {
        const request = {
            message: message,
            mode: options.mode || 'stream'
        };

        if (options.conversationId) {
            request.conversationId = options.conversationId;
        }

        if (options.agentName) {
            request.agentName = options.agentName;
        } else if (this.defaultAgentName) {
            request.agentName = this.defaultAgentName;
        }

        if (options.userId) {
            request.userId = options.userId;
        } else if (this.defaultUserId && !options.conversationId) {
            request.userId = this.defaultUserId;
            request.autoManageConversation = true;
        }

        return request;
    }

    /**
     * 处理响应错误
     * @private
     */
    _handleResponseError(response) {
        if (!response.success) {
            throw new Error(response.errorMessage || '请求失败');
        }
        return response;
    }

    // ==================== 同步对话 ====================

    /**
     * 同步对话（非流式）
     * @param {string} message - 用户消息
     * @param {Object} options - 可选参数
     * @param {string} options.conversationId - 会话ID
     * @param {string} options.agentName - Agent名称
     * @returns {Promise<string>} Agent的完整回复
     * 
     * @example
     * const response = await client.chatSync('1+1等于几?');
     * console.log(response); // 输出: 1+1等于2
     */
    async chatSync(message, options = {}) {
        try {
            const request = this._buildChatRequest(message, {
                mode: 'sync',
                ...options
            });

            const response = await this._request(this._buildUrl('/chat'), {
                method: 'POST',
                body: JSON.stringify(request)
            });

            const data = await response.json();
            this._handleResponseError(data);

            return data.response;
        } catch (error) {
            console.error('同步对话失败:', error);
            throw new Error(`同步对话失败: ${error.message}`);
        }
    }

    // ==================== 流式对话 ====================

    /**
     * 流式对话 - 使用回调函数接收数据块
     * @param {string} message - 用户消息
     * @param {function(string): void} onChunk - 数据块回调函数
     * @param {Object} options - 可选参数
     * @param {string} options.conversationId - 会话ID
     * @param {string} options.agentName - Agent名称
     * @returns {Promise<void>}
     * 
     * @example
     * await client.chatStream('写个冒泡排序', (chunk) => {
     *     console.log(chunk);
     * });
     */
    async chatStream(message, onChunk, options = {}) {
        try {
            const request = this._buildChatRequest(message, {
                mode: 'stream',
                ...options
            });

            const response = await this._request(this._buildUrl('/chat'), {
                method: 'POST',
                body: JSON.stringify(request)
            });

            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let fullResponse = '';

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                const chunk = decoder.decode(value, { stream: true });
                fullResponse += chunk;

                if (onChunk) {
                    onChunk(chunk);
                }
            }

            console.debug(`流式对话完成: message=${message}, totalLength=${fullResponse.length}`);
        } catch (error) {
            console.error('流式对话失败:', error);
            throw new Error(`流式对话失败: ${error.message}`);
        }
    }

    /**
     * 流式对话 - 返回AsyncGenerator
     * @param {string} message - 用户消息
     * @param {Object} options - 可选参数
     * @returns {AsyncGenerator<string>} 数据流生成器
     * 
     * @example
     * for await (const chunk of client.chatStreamIterator('写篇文章')) {
     *     console.log(chunk);
     * }
     */
    async *chatStreamIterator(message, options = {}) {
        try {
            const request = this._buildChatRequest(message, {
                mode: 'stream',
                ...options
            });

            const response = await this._request(this._buildUrl('/chat'), {
                method: 'POST',
                body: JSON.stringify(request)
            });

            const reader = response.body.getReader();
            const decoder = new TextDecoder();

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                const chunk = decoder.decode(value, { stream: true });
                yield chunk;
            }
        } catch (error) {
            console.error('流式对话失败:', error);
            throw new Error(`流式对话失败: ${error.message}`);
        }
    }

    // ==================== 简化方法 ====================

    /**
     * 简化调用 - 默认使用流式模式，返回完整响应
     * @param {string} message - 用户消息
     * @param {Object} options - 可选参数
     * @returns {Promise<string>} Agent的完整回复
     * 
     * @example
     * const response = await client.chat('如何使用Python?');
     * console.log(response);
     */
    async chat(message, options = {}) {
        let fullResponse = '';
        await this.chatStream(message, (chunk) => {
            fullResponse += chunk;
        }, options);
        return fullResponse;
    }

    /**
     * 简化调用 - 指定会话ID
     * @param {string} message - 用户消息
     * @param {string} conversationId - 会话ID
     * @returns {Promise<string>} Agent的完整回复
     */
    async chatWithConversation(message, conversationId) {
        return this.chat(message, { conversationId });
    }

    // ==================== 结构化输出 ====================

    /**
     * 结构化输出对话
     * @param {string} message - 用户消息
     * @param {Object} schema - JSON Schema
     * @param {Object} options - 可选参数
     * @returns {Promise<Object>} 结构化数据
     * 
     * @example
     * const schema = {
     *     type: 'object',
     *     properties: {
     *         name: { type: 'string' },
     *         age: { type: 'integer' }
     *     }
     * };
     * const result = await client.chatStructured('生成用户信息', schema);
     * console.log(result); // { name: '张三', age: 25 }
     */
    async chatStructured(message, schema, options = {}) {
        try {
            const request = this._buildChatRequest(message, {
                mode: 'structured',
                schema: schema,
                ...options
            });

            const response = await this._request(this._buildUrl('/chat'), {
                method: 'POST',
                body: JSON.stringify(request)
            });

            const data = await response.json();
            this._handleResponseError(data);

            return data.structuredResult;
        } catch (error) {
            console.error('结构化输出对话失败:', error);
            throw new Error(`结构化输出对话失败: ${error.message}`);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 健康检查
     * @returns {Promise<boolean>} 服务是否可用
     * 
     * @example
     * if (await client.isAvailable()) {
     *     console.log('服务正常');
     * } else {
     *     console.log('服务不可用');
     * }
     */
    async isAvailable() {
        try {
            await this._request(this._buildUrl('/actuator/health'), {
                method: 'GET',
                headers: {}
            });
            return true;
        } catch (error) {
            console.debug('服务健康检查失败', error);
            return false;
        }
    }

    /**
     * 获取服务信息
     * @returns {Promise<Object>} 服务信息
     */
    async getServiceInfo() {
        try {
            const response = await this._request(this._buildUrl('/actuator/info'), {
                method: 'GET',
                headers: {}
            });
            return await response.json();
        } catch (error) {
            console.error('获取服务信息失败:', error);
            throw new Error(`获取服务信息失败: ${error.message}`);
        }
    }

    // ==================== Getter/Setter ====================

    /**
     * 设置默认用户ID
     * @param {string} userId - 用户ID
     */
    setDefaultUserId(userId) {
        this.defaultUserId = userId;
    }

    /**
     * 获取默认用户ID
     * @returns {string|null} 用户ID
     */
    getDefaultUserId() {
        return this.defaultUserId;
    }

    /**
     * 设置默认Agent名称
     * @param {string} agentName - Agent名称
     */
    setDefaultAgentName(agentName) {
        this.defaultAgentName = agentName;
    }

    /**
     * 获取默认Agent名称
     * @returns {string|null} Agent名称
     */
    getDefaultAgentName() {
        return this.defaultAgentName;
    }

    /**
     * 设置超时时间
     * @param {number} timeout - 超时时间(毫秒)
     */
    setTimeout(timeout) {
        this.timeout = timeout;
    }

    /**
     * 获取超时时间
     * @returns {number} 超时时间(毫秒)
     */
    getTimeout() {
        return this.timeout;
    }
}

// ==================== 导出 ====================

// Node.js环境
if (typeof module !== 'undefined' && module.exports) {
    module.exports = AgentClient;
}

// ES6模块
if (typeof exports !== 'undefined') {
    exports.AgentClient = AgentClient;
}

// 浏览器环境 (添加到全局)
if (typeof window !== 'undefined') {
    window.AgentClient = AgentClient;
}
