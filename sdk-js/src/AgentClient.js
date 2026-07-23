/**
 * yunxi Agent Platform JavaScript/TypeScript Client
 * 
 * 提供简洁易用的API来调用Agent Platform服务
 * 支持Node.js和浏览器环境
 * 
 * @version 2.1.0
 * @author yunxi Agent Platform
 * 
 * @example
 * // 基础使用
 * const client = new AgentClient('http://localhost:40001');
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
     * @param {string} baseUrl - 服务基础URL (例如: "http://localhost:40001")
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
        // 兼容历史调用：/chat 映射到统一流式入口 /api/conversations/chat/stream
        if (endpoint === '/chat') endpoint = '/api/conversations/chat/stream';
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

    /**
     * 解析单个 SSE 数据块（data: 行）为事件对象
     * @private
     * @returns {{type:string, timestamp?:string, content:string}|null}
     */
    _parseSseBlock(block) {
        const line = block.split('\n').find((l) => l.startsWith('data:'));
        if (!line) return null;
        let json = line.slice(5).trim();
        // 防御：个别网关会在 data 内容里再嵌套一层 data: 前缀，递归剥离直到真正 JSON
        while (json.startsWith('data:')) {
            json = json.slice(5).trim();
        }
        if (!json) return null;
        try {
            return JSON.parse(json);
        } catch (e) {
            console.warn('SSE 事件解析失败，已跳过:', json);
            return null;
        }
    }

    /**
     * 流式对话底层生成器：逐条吐出解析后的 SSE 事件对象。
     * 事件对象形如 { type, timestamp, content }，
     * content 为后端下发的内容（友好消息为 JSON 字符串，原生事件为事件 JSON 字符串）。
     * @private
     */
    async *_streamChatEvents(message, options = {}) {
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
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });

            let idx;
            while ((idx = buffer.indexOf('\n\n')) !== -1) {
                const block = buffer.slice(0, idx);
                buffer = buffer.slice(idx + 2);
                const evt = this._parseSseBlock(block);
                if (evt) yield evt;
            }
        }

        // 冲刷缓冲区中最后一个（可能未以 \n\n 结尾的）事件
        if (buffer.trim()) {
            const evt = this._parseSseBlock(buffer);
            if (evt) yield evt;
        }
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
            let buffer = '';
            const onEvent = options && options.onEvent;

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                const chunk = decoder.decode(value, { stream: true });
                fullResponse += chunk;

                // 向后兼容：仍回传原始文本块
                if (onChunk) {
                    onChunk(chunk);
                }

                // 可选：解析并分发结构化 SSE 事件
                if (onEvent) {
                    buffer += chunk;
                    let idx;
                    while ((idx = buffer.indexOf('\n\n')) !== -1) {
                        const block = buffer.slice(0, idx);
                        buffer = buffer.slice(idx + 2);
                        const evt = this._parseSseBlock(block);
                        if (evt) onEvent(evt);
                    }
                }
            }

            // 冲刷缓冲区中残留的最后一个事件
            if (onEvent && buffer.trim()) {
                const evt = this._parseSseBlock(buffer);
                if (evt) onEvent(evt);
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

    /**
     * 流式对话 - 结构化事件回调
     * <p>
     * 相比 {@link chatStream}（仅回传原始文本块），本方法会把每条 SSE 事件解析为
     * 结构化对象并分派到对应的 handler，便于最终 UI 渲染工具调用卡片、工具流式进度等。
     * 后端对 content/thinking 下发纯文本，对其余友好消息（tool_call / tool_result_* 等）
     * 下发 JSON 字符串，本方法会自动 JSON.parse 后传入对应 handler。
     * </p>
     * @param {string} message - 用户消息
     * @param {Object} handlers - 事件处理器集合（均可选）
     * @param {function(Object):void} [handlers.onEvent] 每条解析后的事件（{type, timestamp, content}），最后兜底分发
     * @param {function(string):void} [handlers.onText] 友好文本增量（type=content）
     * @param {function(string):void} [handlers.onThinking] 思考过程增量（type=thinking）
     * @param {function(Object):void} [handlers.onToolCall] 工具调用开始（type=tool_call）
     * @param {function(Object):void} [handlers.onToolCallDone] 工具调用参数就绪（type=tool_call_done）
     * @param {function(Object):void} [handlers.onToolResult] 工具结果（type=tool_result）
     * @param {function(Object):void} [handlers.onToolResultStart] 工具流式输出开始（type=tool_result_start）
     * @param {function(string):void} [handlers.onToolResultDelta] 工具流式输出增量（type=tool_result_delta）
     * @param {function(Object):void} [handlers.onStatus] 状态提示（type=agent_status）
     * @param {function(Object):void} [handlers.onError] 错误（type=error）
     * @param {function():void} [handlers.onDone] 流结束
     * @param {Object} options - 可选参数（同 chatStream）
     * @returns {Promise<string>} 拼接后的完整文本回复
     *
     * @example
     * await client.chatStreamEvents('查一下天气', {
     *     onToolCall: (t) => console.log('调用工具', t.toolCallName),
     *     onToolResultStart: (t) => console.log('工具开始输出', t.toolCallName),
     *     onToolResultDelta: (d) => process.stdout.write(d.delta),
     *     onText: (s) => process.stdout.write(s),
     * });
     */
    async chatStreamEvents(message, handlers = {}, options = {}) {
        try {
            let fullText = '';

            for await (const evt of this._streamChatEvents(message, options)) {
                const { type, content } = evt;

                // content 可能是 JSON 字符串（友好消息/原生事件）或纯文本（content/thinking）
                let data = content;
                if (typeof content === 'string') {
                    try {
                        data = JSON.parse(content);
                    } catch (e) {
                        // 纯文本，保持原样
                    }
                }

                if (handlers.onEvent) {
                    handlers.onEvent(evt);
                }

                switch (type) {
                    case 'content':
                        if (typeof data === 'string') {
                            fullText += data;
                            if (handlers.onText) handlers.onText(data);
                        }
                        break;
                    case 'thinking':
                        if (typeof data === 'string' && handlers.onThinking) handlers.onThinking(data);
                        break;
                    case 'tool_call':
                        if (handlers.onToolCall) handlers.onToolCall(data);
                        break;
                    case 'tool_call_done':
                        if (handlers.onToolCallDone) handlers.onToolCallDone(data);
                        break;
                    case 'tool_result':
                        if (handlers.onToolResult) handlers.onToolResult(data);
                        break;
                    case 'tool_result_start':
                        if (handlers.onToolResultStart) handlers.onToolResultStart(data);
                        break;
                    case 'tool_result_delta': {
                        // 后端 content 为 JSON 对象 {"toolCallId","toolCallName","delta"}，
                        // 完整透传给 handler（若解析失败则包装为 {delta} 以兼容纯文本）
                        const payload = (data && typeof data === 'object')
                            ? data
                            : { delta: typeof data === 'string' ? data : '' };
                        if (handlers.onToolResultDelta) handlers.onToolResultDelta(payload);
                        break;
                    }
                    case 'agent_status':
                        if (handlers.onStatus) handlers.onStatus(data);
                        break;
                    case 'error':
                        if (handlers.onError) handlers.onError(data);
                        break;
                    default:
                        break;
                }
            }

            if (handlers.onDone) handlers.onDone();
            return fullText;
        } catch (error) {
            console.error('流式事件对话失败:', error);
            throw new Error(`流式事件对话失败: ${error.message}`);
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
