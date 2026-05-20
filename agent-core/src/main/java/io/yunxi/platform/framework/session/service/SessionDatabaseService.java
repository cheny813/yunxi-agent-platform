package io.yunxi.platform.framework.session.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.framework.session.entity.SessionSummaryEntity;
import io.yunxi.platform.framework.session.entity.SessionTagEntity;
import io.yunxi.platform.framework.session.mapper.SessionSummaryMapper;
import io.yunxi.platform.framework.session.mapper.SessionTagMapper;
import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话数据库服务
 * <p>
 * 负责会话摘要、标签的存储、检索和全文搜索
 * 基于MySQL实现，支持FULLTEXT全文索引
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class SessionDatabaseService {

    /** 会话摘要 Mapper */
    private final SessionSummaryMapper sessionSummaryMapper;
    /** 会话标签 Mapper */
    private final SessionTagMapper sessionTagMapper;
    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper;

    /**
     * 构造会话数据库服务
     *
     * @param sessionSummaryMapper 会话摘要 Mapper
     * @param sessionTagMapper     会话标签 Mapper
     * @param objectMapper         JSON 对象映射器
     */
    public SessionDatabaseService(SessionSummaryMapper sessionSummaryMapper,
                                  SessionTagMapper sessionTagMapper,
                                  ObjectMapper objectMapper) {
        this.sessionSummaryMapper = sessionSummaryMapper;
        this.sessionTagMapper = sessionTagMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 初始化数据库表
     */
    @PostConstruct
    public void init() {
        try {
            // 初始化数据库表
            sessionSummaryMapper.createSessionSummariesTableIfNotExists();
            sessionTagMapper.createSessionTagsTableIfNotExists();
            sessionTagMapper.createFullTextIndexIfNotExists();
            log.info("SessionDatabaseService initialized: database tables created");
        } catch (Exception e) {
            log.error("Failed to initialize session database tables", e);
        }
    }

    /**
     * 保存或更新会话摘要
     *
     * @param conversationId 会话ID
     * @param agentName     Agent名称
     * @param userId        用户ID
     * @param summary       摘要内容
     * @param summaryType   摘要类型
     * @param keywords      关键词列表
     * @param messages      消息列表（用于计算统计信息）
     */
    @Transactional
    public void saveSessionSummary(String conversationId, String agentName, String userId,
                                   String summary, String summaryType, List<String> keywords,
                                   List<Msg> messages) {
        try {
            SessionSummaryEntity entity = new SessionSummaryEntity();
            entity.setConversationId(conversationId);
            entity.setAgentName(agentName);
            entity.setUserId(userId);
            entity.setSummary(summary);
            entity.setSummaryType(summaryType);
            entity.setKeywords(keywords != null ? objectMapper.writeValueAsString(keywords) : null);
            entity.setMessageCount(messages != null ? messages.size() : 0);
            entity.setTokenCount(estimateTokenCount(messages));
            entity.setGeneratedAt(LocalDateTime.now());

            sessionSummaryMapper.save(entity);
            log.debug("Saved session summary for conversation: {}, type: {}", conversationId, summaryType);
        } catch (Exception e) {
            log.error("Failed to save session summary for conversation: {}", conversationId, e);
            throw new RuntimeException("Failed to save session summary", e);
        }
    }

    /**
     * 批量保存会话标签
     *
     * @param tags 标签列表
     */
    @Transactional
    public void saveSessionTags(List<SessionTagEntity> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }

        try {
            sessionTagMapper.insertBatch(tags);
            log.debug("Saved {} tags for conversation: {}", tags.size(),
                    tags.get(0).getConversationId());
        } catch (Exception e) {
            log.error("Failed to save session tags", e);
            throw new RuntimeException("Failed to save session tags", e);
        }
    }

    /**
     * 保存单个会话标签
     *
     * @param tag 标签实体
     */
    @Transactional
    public void saveSessionTag(SessionTagEntity tag) {
        try {
            sessionTagMapper.save(tag);
            log.debug("Saved tag: {} for conversation: {}", tag.getTagName(), tag.getConversationId());
        } catch (Exception e) {
            log.error("Failed to save session tag: {}", tag.getTagName(), e);
            throw new RuntimeException("Failed to save session tag", e);
        }
    }

    /**
     * 查询会话摘要
     *
     * @param conversationId 会话ID
     * @return 摘要实体
     */
    public SessionSummaryEntity getSessionSummary(String conversationId) {
        return sessionSummaryMapper.findByConversationId(conversationId);
    }

    /**
     * 查询会话摘要（指定类型）
     *
     * @param conversationId 会话ID
     * @param summaryType   摘要类型
     * @return 摘要实体
     */
    public SessionSummaryEntity getSessionSummaryByType(String conversationId, String summaryType) {
        return sessionSummaryMapper.findByConversationIdAndType(conversationId, summaryType);
    }

    /**
     * 查询会话的所有标签
     *
     * @param conversationId 会话ID
     * @return 标签列表
     */
    public List<SessionTagEntity> getSessionTags(String conversationId) {
        return sessionTagMapper.findByConversationId(conversationId);
    }

    /**
     * 查询用户的所有会话摘要
     *
     * @param userId 用户ID
     * @return 摘要列表
     */
    public List<SessionSummaryEntity> getUserSummaries(String userId) {
        return sessionSummaryMapper.findByUserId(userId);
    }

    /**
     * 查询用户的所有会话标签
     *
     * @param userId 用户ID
     * @return 标签列表
     */
    public List<SessionTagEntity> getUserTags(String userId) {
        return sessionTagMapper.findByUserId(userId);
    }

    /**
     * 全文搜索会话标签
     *
     * @param query 搜索查询
     * @param limit 结果限制
     * @return 匹配的标签列表
     */
    public List<SessionTagEntity> searchTagsFullText(String query, int limit) {
        try {
            List<SessionTagEntity> results = sessionTagMapper.fullTextSearch(query, limit);
            log.debug("Full-text search for '{}' returned {} results", query, results.size());
            return results;
        } catch (Exception e) {
            log.error("Full-text search failed for query: {}", query, e);
            return Collections.emptyList();
        }
    }

    /**
     * 删除会话摘要和标签
     *
     * @param conversationId 会话ID
     */
    @Transactional
    public void deleteSessionData(String conversationId) {
        try {
            sessionSummaryMapper.deleteByConversationId(conversationId);
            sessionTagMapper.deleteByConversationId(conversationId);
            log.debug("Deleted session data for conversation: {}", conversationId);
        } catch (Exception e) {
            log.error("Failed to delete session data for conversation: {}", conversationId, e);
            throw new RuntimeException("Failed to delete session data", e);
        }
    }

    /**
     * 批量删除会话数据
     *
     * @param conversationIds 会话ID列表
     */
    @Transactional
    public void deleteSessionDataBatch(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return;
        }

        try {
            sessionSummaryMapper.deleteByConversationIdIn(conversationIds);
            sessionTagMapper.deleteByConversationIdIn(conversationIds);
            log.debug("Batch deleted session data for {} conversations", conversationIds.size());
        } catch (Exception e) {
            log.error("Failed to batch delete session data", e);
            throw new RuntimeException("Failed to batch delete session data", e);
        }
    }

    /**
     * 从会话实体提取关键词
     *
     * @param conversation 会话实体
     * @return 关键词列表
     */
    public List<String> extractKeywords(ConversationEntity conversation) {
        List<String> keywords = new ArrayList<>();

        // 从标题提取关键词
        if (conversation.getTitle() != null && !conversation.getTitle().isBlank()) {
            keywords.addAll(Arrays.asList(conversation.getTitle().split("\\s+")));
        }

        // 从消息中提取高频词汇（简单实现）
        if (conversation.getMessages() != null && !conversation.getMessages().isEmpty()) {
            Map<String, Integer> wordFrequency = new HashMap<>();
            for (Msg msg : conversation.getMessages()) {
                String content = msg.toString();
                if (content != null && !content.isBlank()) {
                    String[] words = content.split("\\s+");
                    for (String word : words) {
                        if (word.length() > 2) { // 忽略短词
                            wordFrequency.merge(word, 1, Integer::sum);
                        }
                    }
                }
            }

            // 取前10个高频词
            keywords.addAll(wordFrequency.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList()));
        }

        // 去重并限制数量
        return keywords.stream()
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
    }

    /**
     * 估算Token数量
     *
     * @param messages 消息列表
     * @return Token数量估算
     */
    private int estimateTokenCount(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int totalChars = messages.stream()
                .mapToInt(msg -> msg.toString().length())
                .sum();

        // 简单估算：1 token ≈ 4 个字符（中文）或 4 个单词（英文）
        return totalChars / 4;
    }

    /**
     * 解析关键词JSON
     *
     * @param keywordsJson 关键词JSON字符串
     * @return 关键词列表
     */
    public List<String> parseKeywords(String keywordsJson) {
        if (keywordsJson == null || keywordsJson.isBlank()) {
            return Collections.emptyList();
        }

        try {
            return objectMapper.readValue(keywordsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse keywords JSON: {}", keywordsJson, e);
            return Collections.emptyList();
        }
    }

    /**
     * 创建标签实体
     *
     * @param conversationId 会话ID
     * @param agentName     Agent名称
     * @param userId        用户ID
     * @param tagType       标签类型
     * @param tagName       标签名称
     * @param tagValue      标签值
     * @param confidence    置信度
     * @param weight        权重
     * @return 标签实体
     */
    public SessionTagEntity createTagEntity(String conversationId, String agentName, String userId,
                                             String tagType, String tagName, String tagValue,
                                             double confidence, int weight) {
        SessionTagEntity tag = new SessionTagEntity();
        tag.setConversationId(conversationId);
        tag.setAgentName(agentName);
        tag.setUserId(userId);
        tag.setTagType(tagType);
        tag.setTagName(tagName);
        tag.setTagValue(tagValue);
        tag.setConfidence(confidence);
        tag.setWeight(weight);
        return tag;
    }

    /**
     * 健康检查
     *
     * @return 是否健康
     */
    public boolean healthCheck() {
        try {
            sessionSummaryMapper.findByCreatedAtBefore(LocalDateTime.now());
            return true;
        } catch (Exception e) {
            log.error("Session database health check failed", e);
            return false;
        }
    }
}
