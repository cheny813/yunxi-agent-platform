/**
 * yunxiClaw DesktopClient 类型定义
 * 
 * @version 1.0.0
 */

export interface DesktopClientOptions {
  /** 后端服务地址 */
  serverUrl: string;
  /** 客户端ID（可选） */
  clientId?: string;
  /** 超时时间(毫秒)，默认60000 */
  timeout?: number;
  /** 自定义请求头 */
  headers?: Record<string, string>;
}

export interface ConnectionStatus {
  /** 是否已连接 */
  connected: boolean;
  /** WebSocket 是否已连接 */
  wsConnected: boolean;
  /** 客户端ID */
  clientId: string;
}

export interface CommandResult {
  /** 请求ID */
  requestId: string;
  /** 状态: success | error */
  status: 'success' | 'error';
  /** 标准输出 */
  stdout?: string;
  /** 标准错误 */
  stderr?: string;
  /** 退出码 */
  exitCode?: number;
  /** 错误消息 */
  message?: string;
}

export interface DirEntry {
  /** 文件/目录名 */
  name: string;
  /** 大小（文件） */
  size: number;
}

export interface ListDirResult extends CommandResult {
  /** 文件列表 */
  files: DirEntry[];
  /** 目录列表 */
  dirs: DirEntry[];
}

export interface ReadFileResult extends CommandResult {
  /** 文件名 */
  name?: string;
  /** 文件大小 */
  size?: number;
  /** 文件内容 */
  content?: string;
}

export interface WriteFileResult extends CommandResult {
  /** 文件路径 */
  path?: string;
}

export interface ClientInfo {
  /** 客户端ID */
  clientId: string;
  /** 支持的能力 */
  capabilities: string;
  /** 连接时间 */
  connectedAt: number;
  /** 最后心跳时间 */
  lastHeartbeat?: number;
}

export interface DesktopStatus {
  /** 是否成功 */
  success: boolean;
  /** 客户端数量 */
  clientCount: number;
  /** 在线客户端ID列表 */
  onlineClients: string[];
}

/**
 * yunxiClaw DesktopClient
 * 
 * 用于在用户项目中集成桌面客户端功能
 */
export declare class DesktopClient {
  constructor(options: DesktopClientOptions);

  // 连接管理
  /**
   * 连接到中继服务器（WebSocket）
   */
  connectWebSocket(): Promise<void>;
  
  /**
   * 断开连接
   */
  disconnect(): void;
  
  /**
   * 获取连接状态
   */
  getConnectionStatus(): ConnectionStatus;

  // 命令执行
  /**
   * 执行系统命令
   * @param command - 命令
   * @param args - 参数
   * @param cwd - 工作目录（可选）
   */
  executeCommand(command: string, args?: string[], cwd?: string): Promise<CommandResult>;
  
  /**
   * 执行 Git 操作
   * @param operation - 操作类型
   * @param args - 参数
   */
  git(operation: string, args?: string[]): Promise<CommandResult>;
  
  /**
   * 列出目录内容
   * @param path - 目录路径
   */
  listDir(path: string): Promise<ListDirResult>;
  
  /**
   * 读取文件
   * @param path - 文件路径
   */
  readFile(path: string): Promise<ReadFileResult>;
  
  /**
   * 写入文件
   * @param path - 文件路径
   * @param content - 文件内容
   */
  writeFile(path: string, content: string): Promise<WriteFileResult>;
  
  /**
   * 心跳检测
   */
  ping(): Promise<CommandResult>;

  // 批量操作
  /**
   * 获取所有在线客户端
   */
  getClients(): Promise<ClientInfo[]>;
  
  /**
   * 发送命令到指定客户端
   * @param targetClientId - 目标客户端ID
   * @param command - 命令对象
   */
  sendToClient(targetClientId: string, command: object): Promise<CommandResult>;
  
  /**
   * 广播命令到所有客户端
   * @param command - 命令对象
   */
  broadcast(command: object): Promise<CommandResult>;
  
  /**
   * 获取中继服务器状态
   */
  getStatus(): Promise<DesktopStatus>;

  // 事件处理
  /**
   * 监听事件
   * @param event - 事件类型
   * @param callback - 回调函数
   */
  on(event: string, callback: (data: any) => void): void;
  
  /**
   * 移除事件监听
   * @param event - 事件类型
   * @param callback - 回调函数
   */
  off(event: string, callback: (data: any) => void): void;
}

export default DesktopClient;