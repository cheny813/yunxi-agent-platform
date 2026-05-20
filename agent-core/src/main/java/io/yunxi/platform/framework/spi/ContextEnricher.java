package io.yunxi.platform.framework.spi;

import java.util.Map;

/**
 * 上下文增强器 SPI
 *
 * <p>业务层实现此接口，为特定场景的上下文数据提供搜索增强、格式化和提示追加能力。</p>
 * <p>框架层 ChatAppService 通过遍历所有实现来注入业务上下文，无需硬编码业务逻辑。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface ContextEnricher {

    /**
     * 是否能处理该上下文
     *
     * @param contextData 前端传入的上下文数据
     * @return true 表示此增强器可以处理
     */
    boolean supports(Map<String, Object> contextData);

    /**
     * 从上下文数据中搜索/补充额外信息
     *
     * @param contextData 前端传入的上下文数据
     * @param userMessage 用户消息（用于提取搜索关键词等）
     * @return 格式化的增强文本，可为空字符串
     */
    String enrich(Map<String, Object> contextData, String userMessage);

    /**
     * 格式化特定 key 的上下文数据为可读文本
     *
     * @param key   上下文数据的 key
     * @param value 上下文数据的 value
     * @return 格式化后的文本，返回 null 表示此增强器不处理该 key
     */
    String formatKey(String key, Object value);

    /**
     * 场景追加提示
     *
     * @param contextData 前端传入的上下文数据
     * @return 追加到上下文末尾的提示文本，返回 null 表示无追加
     */
    String appendPrompt(Map<String, Object> contextData);
}
