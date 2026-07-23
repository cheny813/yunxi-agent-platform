/**
 * yunxi Agent Platform TypeScript Type Definitions
 * 
 * @version 2.0.0
 */

/**
 * AgentClient配置选项
 */
export interface AgentClientOptions {
    /** 默认用户ID */
    defaultUserId?: string;
    /** 默认Agent名称 */
    defaultAgentName?: string;
    /** 超时时间(毫秒)，默认300000(5分钟) */
    timeout?: number;
    /** 自定义请求头 */
    headers?: Record<string, string>;
}

/**
 * 对话选项
 */
export interface ChatOptions {
    /** 会话ID */
    conversationId?: string;
    /** Agent名称 */
    agentName?: string;
    /** 用户ID */
    userId?: string;
    /** 对话模式 */
    mode?: 'sync' | 'stream' | 'structured';
    /** 流式事件回调（chatStream 可选）：每条解析后的 SSE 事件 */
    onEvent?: (event: SseEvent) => void;
}

/**
 * 结构化输出Schema
 */
export type StructuredSchema = Record<string, any>;

/**
 * 解析后的 SSE 事件对象
 */
export interface SseEvent {
    /** 事件类型（如 content / tool_call / tool_result_delta / TOOL_CALL_START 等） */
    type: string;
    /** 事件时间戳（ISO-8601） */
    timestamp?: string;
    /** 事件内容：友好消息为 JSON 字符串，原生事件为事件 JSON 字符串，content/thinking 为纯文本 */
    content: string;
}

/**
 * chatStreamEvents 的事件处理器集合（全部可选）
 */
export interface ChatEventHandlers {
    /** 每条解析后的事件，最后兜底分发 */
    onEvent?: (event: SseEvent) => void;
    /** 友好文本增量 (type=content) */
    onText?: (text: string) => void;
    /** 思考过程增量 (type=thinking) */
    onThinking?: (text: string) => void;
    /** 工具调用开始 (type=tool_call)，data: {toolCallId, toolCallName} */
    onToolCall?: (data: any) => void;
    /** 工具调用参数就绪 (type=tool_call_done)，data: {toolCallId, toolCallName} */
    onToolCallDone?: (data: any) => void;
    /** 工具结果 (type=tool_result) */
    onToolResult?: (data: any) => void;
    /** 工具流式输出开始 (type=tool_result_start) */
    onToolResultStart?: (data: any) => void;
    /** 工具流式输出增量 (type=tool_result_delta)，data: {toolCallId, toolCallName, delta} */
    onToolResultDelta?: (data: { toolCallId?: string; toolCallName?: string; delta: string }) => void;
    /** 状态提示 (type=agent_status) */
    onStatus?: (data: any) => void;
    /** 错误 (type=error) */
    onError?: (data: any) => void;
    /** 流结束 */
    onDone?: () => void;
}

/**
 * AgentClient类
 */
export class AgentClient {
    /**
     * 创建AgentClient实例
     * @param baseUrl - 服务基础URL (例如: "http://localhost:8080")
     * @param options - 可选配置
     */
    constructor(baseUrl: string, options?: AgentClientOptions);

    /**
     * 服务基础URL
     */
    readonly baseUrl: string;

    /**
     * 默认用户ID
     */
    defaultUserId: string | null;

    /**
     * 默认Agent名称
     */
    defaultAgentName: string | null;

    /**
     * 超时时间(毫秒)
     */
    timeout: number;

    /**
     * 同步对话（非流式）
     * @param message - 用户消息
     * @param options - 可选参数
     * @returns Agent的完整回复
     */
    chatSync(message: string, options?: ChatOptions): Promise<string>;

    /**
     * 流式对话 - 使用回调函数接收数据块
     * @param message - 用户消息
     * @param onChunk - 数据块回调函数
     * @param options - 可选参数
     */
    chatStream(message: string, onChunk: (chunk: string) => void, options?: ChatOptions): Promise<void>;

    /**
     * 流式对话 - 返回AsyncGenerator
     * @param message - 用户消息
     * @param options - 可选参数
     * @returns 数据流生成器
     */
    chatStreamIterator(message: string, options?: ChatOptions): AsyncGenerator<string, void, unknown>;

    /**
     * 流式对话 - 结构化事件回调，便于 UI 渲染工具调用卡片、工具流式进度等
     * @param message - 用户消息
     * @param handlers - 事件处理器集合（全部可选）
     * @param options - 可选参数
     * @returns 拼接后的完整文本回复
     */
    chatStreamEvents(message: string, handlers?: ChatEventHandlers, options?: ChatOptions): Promise<string>;

    /**
     * 简化调用 - 默认使用流式模式，返回完整响应
     * @param message - 用户消息
     * @param options - 可选参数
     * @returns Agent的完整回复
     */
    chat(message: string, options?: ChatOptions): Promise<string>;

    /**
     * 简化调用 - 指定会话ID
     * @param message - 用户消息
     * @param conversationId - 会话ID
     * @returns Agent的完整回复
     */
    chatWithConversation(message: string, conversationId: string): Promise<string>;

    /**
     * 结构化输出对话
     * @param message - 用户消息
     * @param schema - JSON Schema
     * @param options - 可选参数
     * @returns 结构化数据
     */
    chatStructured(message: string, schema: StructuredSchema, options?: ChatOptions): Promise<Record<string, any>>;

    /**
     * 健康检查
     * @returns 服务是否可用
     */
    isAvailable(): Promise<boolean>;

    /**
     * 获取服务信息
     * @returns 服务信息
     */
    getServiceInfo(): Promise<Record<string, any>>;

    /**
     * 设置默认用户ID
     * @param userId - 用户ID
     */
    setDefaultUserId(userId: string): void;

    /**
     * 获取默认用户ID
     * @returns 用户ID
     */
    getDefaultUserId(): string | null;

    /**
     * 设置默认Agent名称
     * @param agentName - Agent名称
     */
    setDefaultAgentName(agentName: string): void;

    /**
     * 获取默认Agent名称
     * @returns Agent名称
     */
    getDefaultAgentName(): string | null;

    /**
     * 设置超时时间
     * @param timeout - 超时时间(毫秒)
     */
    setTimeout(timeout: number): void;

    /**
     * 获取超时时间
     * @returns 超时时间(毫秒)
     */
    getTimeout(): number;
}

// ==================== 全局声明 (浏览器环境) ====================

declare global {
    interface Window {
        AgentClient: typeof AgentClient;
    }
}

export {};
