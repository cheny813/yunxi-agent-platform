package io.yunxi.platform.shared.constants;

/**
 * 配置默认值常量类
 * 
 * <p>集中管理所有配置项的默认值，便于统一维护和修改。</p>
 * 
 * <h3>设计原则：</h3>
 * <ul>
 *   <li>环境特定配置（host、url等）：不设默认值，强制用户配置</li>
 *   <li>业务配置（模型名、超时等）：提供合理默认值</li>
 *   <li>开关类配置：默认关闭（安全优先）</li>
 * </ul>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public final class ConfigDefaults {

    private ConfigDefaults() {
        // 工具类，禁止实例化
    }

    // ==================== 模型配置 ====================
    
    /**
     * 默认模型提供商
     */
    public static final String DEFAULT_PROVIDER = "dashscope";
    
    /**
     * 默认模型名称
     */
    public static final String DEFAULT_MODEL_NAME = "qwen-plus";
    
    /**
     * 默认系统提示词
     */
    public static final String DEFAULT_SYSTEM_PROMPT = "你是一个专业的智能体助手";
    
    /**
     * 默认对话超时（秒）
     */
    public static final int DEFAULT_CHAT_TIMEOUT_SECONDS = 60;
    
    /**
     * 默认 API 超时（毫秒）
     */
    public static final int DEFAULT_API_TIMEOUT_MS = 60000;
    
    /**
     * 默认 MCP 超时（毫秒）
     */
    public static final int DEFAULT_MCP_TIMEOUT_MS = 30000;

    // ==================== A2A 配置 ====================
    
    /**
     * 默认注册中心类型
     */
    public static final String DEFAULT_REGISTRY_TYPE = "nacos";

    // ==================== 向量数据库配置 ====================
    
    /**
     * 默认向量维度
     */
    public static final int DEFAULT_EMBEDDING_DIMENSION = 1024;
    
    /**
     * 默认嵌入模型
     */
    public static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v3";
    
    /**
     * 默认索引类型
     */
    public static final String DEFAULT_INDEX_TYPE = "IVF_FLAT";
    
    /**
     * 默认度量类型
     */
    public static final String DEFAULT_METRIC_TYPE = "IP";
    
    /**
     * 默认 IVF 聚类数量
     */
    public static final int DEFAULT_NLIST = 1024;

    // ==================== 记忆配置 ====================
    
    /**
     * 默认记忆模式
     */
    public static final String DEFAULT_MEMORY_MODE = "full";
    
    /**
     * 默认记忆阈值
     */
    public static final int DEFAULT_MEMORY_THRESHOLD = 30;
    
    /**
     * 默认保留消息数
     */
    public static final int DEFAULT_LAST_KEEP_MESSAGES = 10;
    
    /**
     * 默认 Token 压缩比例
     */
    public static final double DEFAULT_TOKEN_RATIO = 0.3;
    
    /**
     * 默认最大历史消息数
     */
    public static final int DEFAULT_MAX_HISTORY = 10;
    
    /**
     * 默认最大上下文大小
     */
    public static final int DEFAULT_MAX_CONTEXT_SIZE = 20;

    // ==================== 知识库配置 ====================
    
    /**
     * 默认检索 TopK
     */
    public static final int DEFAULT_TOP_K = 5;
    
    /**
     * 默认相似度阈值
     */
    public static final double DEFAULT_SCORE_THRESHOLD = 0.5;

    // ==================== 备份配置 ====================
    
    /**
     * 默认备份路径
     */
    public static final String DEFAULT_BACKUP_PATH = "./backups";
    
    /**
     * 默认备份间隔（小时）
     */
    public static final int DEFAULT_BACKUP_INTERVAL_HOURS = 24;

    // ==================== 图像处理配置 ====================
    
    /**
     * 特征提取关键词（用于判断图像处理模式）
     */
    public static final String[] DEFAULT_FEATURE_KEYWORDS = {
        "face", "uniform", "工装", "人脸", "员工", "员工照片"
    };
}
