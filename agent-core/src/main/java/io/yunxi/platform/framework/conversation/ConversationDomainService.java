package io.yunxi.platform.framework.conversation;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.infra.cache.CacheNamespaces;
import io.yunxi.platform.infra.repository.ConversationRepository;
import io.yunxi.platform.shared.dto.ConversationInfoDto;
import io.yunxi.platform.shared.dto.CreateConversationRequest;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.exception.NotFoundException;
import io.yunxi.platform.shared.mapper.ConversationMapper;
import io.yunxi.platform.spi.cache.CacheProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * 会话领域服务
 *
 * <p>
 * 【领域层】负责会话的生命周期管理（单一职责）
 * </p>
 * <p>
 * 使用 {@link ConversationRepository} 抽象存储层，支持：
 * <ul>
 * <li>内存存储 - 适合开发测试</li>
 * <li>数据库存储 - 适合生产环境</li>
 * <li>组合存储 - 缓存+数据库降级</li>
 * </ul>
 * </p>
 * <p>
 * <b>集群部署支持</b>：使用 Redis 作为分布式缓存，多节点共享数据
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
public class ConversationDomainService {

    /** 会话存储仓库 */
    private final ConversationRepository conversationRepository;
    /** 缓存提供者 */
    private final CacheProvider cacheProvider;

    /** 本地缓存（用于快速访问，但数据已同步到 Redis） */
    private final Map<String, ConversationEntity> localCache = new ConcurrentHashMap<>();

    /**
     * 构造会话领域服务
     *
     * @param conversationMapper     会话 Mapper
     * @param conversationRepository 会话存储仓库
     * @param cacheProvider          缓存提供者
     * @param objectMapper           JSON 序列化工具
     */
    public ConversationDomainService(ConversationMapper conversationMapper,
            ConversationRepository conversationRepository,
            CacheProvider cacheProvider,
            ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.cacheProvider = cacheProvider;
        log.info("会话领域服务初始化完成，存储类型: {}", conversationRepository.getStorageType());
    }

    /**
     * 创建会话
     *
     * @param request 创建会话请求
     * @return 会话信息
     */
    public ConversationInfoDto createConversation(CreateConversationRequest request) {
        String id = UUID.randomUUID().toString();
        String agentName = request.getAgentName() != null ? request.getAgentName() : "default";
        String userId = request.getUserId() != null ? request.getUserId() : "default-user";
        String title = request.getTitle() != null ? request.getTitle() : "新对话";

        ConversationEntity entity = new ConversationEntity();
        entity.setId(id);
        entity.setAgentName(agentName);
        entity.setUserId(userId);
        entity.setTitle(title);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setLastUpdatedAt(LocalDateTime.now());

        // 使用 Repository 保存
        boolean saved = conversationRepository.save(entity);
        if (!saved) {
            log.warn("Repository 保存失败，使用本地缓存: id={}", id);
            localCache.put(id, entity);
        } else {
            log.info("Repository 保存成功: id={}, storageType={}", id, conversationRepository.getStorageType());
        }

        // 同时缓存到 Redis（集群共享，失败则忽略）
        try {
            cacheProvider.put(CacheNamespaces.CONVERSATION, id, entity,
                    Duration.ofHours(CacheNamespaces.DEFAULT_TTL_HOURS));
        } catch (Exception e) {
            log.warn("Redis 缓存写入失败: {}", e.getMessage());
        }

        log.info("创建会话: id={}, agentName={}, userId={}, title={}", id, agentName, userId, title);

        ConversationInfoDto info = new ConversationInfoDto();
        info.setId(id);
        info.setAgentName(agentName);
        info.setUserId(userId);
        info.setTitle(title);
        info.setCreatedAt(entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
        return info;
    }

    /**
     * 查找或创建会话：优先查找用户与 Agent 的最近活跃会话，不存在则创建新会话
     *
     * <p>
     * 核心逻辑：同一用户使用同一 Agent 时，复用已有的未过期会话，
     * 避免在会话列表中产生多条重复条目。
     * </p>
     *
     * @param request 创建会话请求
     * @return 会话信息（可能是已有的，也可能是新建的）
     */
    public ConversationInfoDto findOrCreateConversation(CreateConversationRequest request) {
        String userId = request.getUserId();
        String agentName = request.getAgentName();

        // 只有 userId 和 agentName 都不为空时才能查找已有会话
        if (userId != null && !userId.isBlank() && agentName != null && !agentName.isBlank()) {
            Optional<ConversationEntity> existing = conversationRepository.findByUserIdAndAgentName(userId, agentName);
            if (existing.isPresent()) {
                ConversationEntity entity = existing.get();
                log.info("复用已有会话: id={}, userId={}, agentName={}", entity.getId(), userId, agentName);
                ConversationInfoDto info = new ConversationInfoDto();
                info.setId(entity.getId());
                info.setAgentName(entity.getAgentName());
                info.setUserId(entity.getUserId());
                info.setTitle(entity.getTitle());
                info.setCreatedAt(entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
                return info;
            }
        }

        // 没有找到活跃会话，创建新会话
        return createConversation(request);
    }

    /**
     * 获取会话实体
     *
     * <p>
     * 查找顺序：本地缓存 -> Redis -> 数据库
     * </p>
     *
     * @param conversationId 会话 ID
     * @return 会话实体
     */
    public ConversationEntity getConversation(String conversationId) {
        // 1. 先查本地缓存（最快）
        ConversationEntity entity = localCache.get(conversationId);
        if (entity != null) {
            log.debug("从本地缓存命中: conversationId={}", conversationId);
            return entity;
        }

        // 2. 再查 Redis 缓存（集群共享，失败则忽略）
        try {
            Optional<ConversationEntity> fromRedis = cacheProvider.get(
                    CacheNamespaces.CONVERSATION, conversationId, ConversationEntity.class);
            if (fromRedis.isPresent()) {
                log.debug("从 Redis 缓存命中: conversationId={}", conversationId);
                // 回填本地缓存
                localCache.put(conversationId, fromRedis.get());
                return fromRedis.get();
            }
        } catch (Exception e) {
            log.warn("Redis 缓存查询失败，降级到数据库: {}", e.getMessage());
        }

        // 3. 最后查数据库
        entity = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new NotFoundException("会话不存在: " + conversationId));

        // 回填缓存
        localCache.put(conversationId, entity);
        try {
            cacheProvider.put(CacheNamespaces.CONVERSATION, conversationId, entity,
                    Duration.ofHours(CacheNamespaces.DEFAULT_TTL_HOURS));
        } catch (Exception e) {
            log.warn("Redis 缓存写入失败: {}", e.getMessage());
        }

        log.debug("从数据库命中并缓存: conversationId={}", conversationId);
        return entity;
    }

    /**
     * 获取会话信息
     *
     * @param conversationId 会话 ID
     * @return 会话信息 DTO
     */
    public ConversationInfoDto getConversationInfo(String conversationId) {
        ConversationEntity entity = getConversation(conversationId);
        ConversationInfoDto info = new ConversationInfoDto();
        info.setId(entity.getId());
        info.setAgentName(entity.getAgentName());
        info.setUserId(entity.getUserId());
        info.setTitle(entity.getTitle());
        info.setCreatedAt(entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
        return info;
    }

    /**
     * 获取会话消息列表
     *
     * @param conversationId 会话 ID
     * @return 消息列表
     */
    public List<Object> getConversationMessages(String conversationId) {
        log.info("获取会话消息: conversationId={}", conversationId);

        try {
            ConversationEntity entity = getConversation(conversationId);
            List<Object> messages = new ArrayList<>();

            if (entity.getMessages() != null) {
                log.info("会话有 {} 条消息", entity.getMessages().size());

                for (int i = 0; i < entity.getMessages().size(); i++) {
                    io.agentscope.core.message.Msg msg = entity.getMessages().get(i);
                    try {
                        Map<String, Object> messageMap = new HashMap<>();

                        // 尝试获取消息的实际文本内容
                        String content = "";
                        try {
                            content = msg.getTextContent();
                        } catch (Exception e) {
                            log.warn("无法获取消息文本内容: {}", e.getMessage());
                            // 降级：使用 toString()
                            String msgString = msg.toString();
                            content = msgString;
                        }

                        // 判断消息角色：优先使用 Msg 的实际角色，降级用索引奇偶判断
                        String role;
                        try {
                            io.agentscope.core.message.MsgRole msgRole = msg.getRole();
                            if (msgRole != null) {
                                role = msgRole.name().toLowerCase();
                            } else {
                                role = (i % 2 == 0) ? "user" : "assistant";
                            }
                        } catch (Exception e) {
                            role = (i % 2 == 0) ? "user" : "assistant";
                        }

                        messageMap.put("role", role);
                        messageMap.put("content", content);

                        // 提取推理内容（存储在 Msg.metadata 的 "thinking" 字段中）
                        String thinking = "";
                        try {
                            Map<String, Object> metadata = msg.getMetadata();
                            if (metadata != null && metadata.get("thinking") instanceof String) {
                                thinking = (String) metadata.get("thinking");
                            }
                        } catch (Exception e) {
                            log.debug("提取推理内容失败: {}", e.getMessage());
                        }
                        messageMap.put("thinking", thinking);

                        messages.add(messageMap);
                        log.debug("处理消息: index={}, role={}, contentLength={}",
                                i, role, content != null ? content.length() : 0);
                    } catch (Exception e) {
                        log.error("处理消息失败: {}", e.getMessage(), e);
                    }
                }
            } else {
                log.info("会话没有消息");
            }

            log.info("返回 {} 条消息", messages.size());
            return messages;
        } catch (Exception e) {
            log.error("获取会话消息失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 删除会话
     *
     * @param conversationId 会话 ID
     */
    public void deleteConversation(String conversationId) {
        // 先获取会话信息（用于清理用户列表缓存）
        String userId = null;
        try {
            var entity = conversationRepository.findById(conversationId).orElse(null);
            if (entity != null) {
                userId = entity.getUserId();
            }
        } catch (Exception e) {
            log.warn("获取会话信息失败: {}", e.getMessage());
        }

        // 清理本地缓存
        localCache.remove(conversationId);
        // 清理 Redis 缓存（失败则忽略）
        try {
            cacheProvider.delete(CacheNamespaces.CONVERSATION, conversationId);
            // 清理记忆缓存
            cacheProvider.delete(CacheNamespaces.MEMORY, conversationId);
            cacheProvider.delete(CacheNamespaces.MEMORY_CONFIG, conversationId);
            // 清理用户会话列表缓存（通过用户ID）
            if (userId != null) {
                cacheProvider.delete(CacheNamespaces.USER_CONVERSATIONS, userId);
                log.debug("清理用户会话列表缓存: userId={}", userId);
            }
        } catch (Exception e) {
            log.warn("Redis 缓存删除失败: {}", e.getMessage());
        }
        // 删除数据库记录
        boolean deleted = conversationRepository.deleteById(conversationId);
        log.info("删除会话: id={}, success={}", conversationId, deleted);
    }

    /**
     * 检查会话是否存在
     *
     * @param conversationId 会话 ID
     * @return 是否存在
     */
    public boolean exists(String conversationId) {
        if (localCache.containsKey(conversationId)) {
            return true;
        }
        try {
            if (cacheProvider.exists(CacheNamespaces.CONVERSATION, conversationId)) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Redis 缓存查询失败: {}", e.getMessage());
        }
        return conversationRepository.existsById(conversationId);
    }

    /**
     * 获取用户会话列表
     *
     * @param userId 用户ID
     * @return 会话信息列表
     */
    public List<ConversationInfoDto> listConversationsByUserId(String userId) {
        log.info("查询用户会话列表: userId={}", userId);

        // 尝试从 Redis 缓存获取（失败则忽略）
        try {
            Optional<List<ConversationInfoDto>> cachedList = cacheProvider.get(
                    CacheNamespaces.USER_CONVERSATIONS, userId,
                    new com.fasterxml.jackson.core.type.TypeReference<List<ConversationInfoDto>>() {
                    });
            if (cachedList.isPresent()) {
                log.debug("从 Redis 缓存获取用户会话列表: userId={}, count={}", userId, cachedList.get().size());
                return cachedList.get();
            }
        } catch (Exception e) {
            log.warn("Redis 缓存查询失败，降级到数据库查询: {}", e.getMessage());
        }

        List<ConversationEntity> entities;
        try {
            // 使用 Repository 查询
            entities = conversationRepository.findByUserId(userId);
            log.info("从 Repository 查询到 {} 条会话记录, storageType={}",
                    entities.size(), conversationRepository.getStorageType());
        } catch (Exception ex) {
            log.error("Repository 查询失败，从本地缓存查询: {}", ex.getMessage());
            // 从本地缓存中过滤
            entities = localCache.values().stream()
                    .filter(cacheEntity -> userId.equals(cacheEntity.getUserId()))
                    .toList();
            log.info("从本地缓存查询到 {} 条会话记录", entities.size());
        }

        List<ConversationInfoDto> result = entities.stream().map(entity -> {
            ConversationInfoDto info = new ConversationInfoDto();
            info.setId(entity.getId());
            info.setAgentName(entity.getAgentName());
            info.setUserId(entity.getUserId());
            info.setTitle(entity.getTitle());
            info.setCreatedAt(entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
            return info;
        }).toList();

        // 缓存到 Redis（失败则忽略）
        try {
            cacheProvider.put(CacheNamespaces.USER_CONVERSATIONS, userId, result,
                    Duration.ofMinutes(5)); // 用户会话列表缓存5分钟
        } catch (Exception e) {
            log.warn("Redis 缓存写入失败: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 定期清理过期缓存
     */
    @Scheduled(fixedRate = 3600000) // 每小时
    public void cleanupExpiredCache() {
        log.debug("清理过期会话缓存");
        // 清理本地缓存（Redis 有自己的过期机制）
        localCache.clear();
    }

    /**
     * 更新会话缓存（对话后调用）
     *
     * @param entity 会话实体
     */
    public void updateCache(ConversationEntity entity) {
        if (entity == null || entity.getId() == null) {
            return;
        }
        // 更新本地缓存
        localCache.put(entity.getId(), entity);
        // 更新 Redis 缓存（失败则忽略）
        try {
            cacheProvider.put(CacheNamespaces.CONVERSATION, entity.getId(), entity,
                    Duration.ofHours(CacheNamespaces.DEFAULT_TTL_HOURS));
        } catch (Exception e) {
            log.warn("Redis 缓存更新失败: {}", e.getMessage());
        }
    }

    /**
     * 保存会话到数据库（含消息内容）
     *
     * @param entity 会话实体
     */
    public void saveConversation(ConversationEntity entity) {
        if (entity == null || entity.getId() == null) {
            return;
        }
        try {
            conversationRepository.save(entity);
            // 同步更新缓存
            updateCache(entity);
            log.debug("会话已保存到数据库: id={}, messages={}", entity.getId(),
                    entity.getMessages() != null ? entity.getMessages().size() : 0);
        } catch (Exception e) {
            log.error("保存会话到数据库失败: id={}", entity.getId(), e);
        }
    }
}
