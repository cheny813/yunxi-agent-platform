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
}

/**
 * 结构化输出Schema
 */
export type StructuredSchema = Record<string, any>;

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
