package io.yunxi.platform.persistence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.yunxi.platform.config.DatabaseProperties;
import io.yunxi.platform.shared.entity.AgentEntity;
import io.yunxi.platform.shared.entity.ToolConfigEntity;
import io.yunxi.platform.shared.mapper.AgentMapper;
import io.yunxi.platform.shared.mapper.ConversationMapper;
import io.yunxi.platform.shared.mapper.ToolConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据持久化服务
 * <p>
 * 负责 Agent、会话、工具配置等数据的持久化操作。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
public class PersistenceService {

    @Autowired
    private ObjectProvider<DatabaseProperties> databasePropertiesProvider;
    @Autowired
    private ObjectProvider<AgentMapper> agentMapperProvider;
    @Autowired
    private ObjectProvider<ToolConfigMapper> toolConfigMapperProvider;
    @Autowired
    private ConversationMapper conversationMapper;

    /**
     * 应用启动后初始化数据库：建表并统计已有数据。
     *
     * <p>若数据库未启用则直接跳过；否则创建所需表并输出 Agent、工具配置计数。</p>
     */
    @PostConstruct
    public void initializeDatabase() {
        createTablesIfNotExist();
        if (databasePropertiesProvider.getIfAvailable() == null
                || !databasePropertiesProvider.getIfAvailable().isEnabled()) {
            if (log.isInfoEnabled())
                log.info("数据库持久化未启用，跳过数据初始化");
            return;
        }
        try {
            long agentCount = agentMapperProvider.getIfAvailable().count();
            long toolConfigCount = toolConfigMapperProvider.getIfAvailable().count();
            if (log.isInfoEnabled())
                log.info("数据库初始化完成: {} 个 Agent, {} 个工具配置", agentCount, toolConfigCount);
        } catch (Exception e) {
            if (log.isErrorEnabled())
                log.error("数据库初始化失败", e);
        }
    }

    /**
     * 按需创建全部业务表（幂等）。
     *
     * <p>依次创建 agent_agents / agent_conversations / agent_tool_configs / agent_chat_logs 表，\n     * 失败仅记录日志不阻断应用启动。</p>
     */
    private void createTablesIfNotExist() {
        try {
            conversationMapper.createAgentsTableIfNotExists();
            conversationMapper.createConversationsTableIfNotExists();
            conversationMapper.createToolConfigsTableIfNotExists();
            conversationMapper.createChatLogsTableIfNotExists();
            if (log.isInfoEnabled())
                log.info("数据库表已就绪");
        } catch (Exception e) {
            if (log.isErrorEnabled())
                log.error("创建数据库表失败: {}", e.getMessage());
        }
    }

    /**
     * 保存或新增 Agent 实体（数据库启用时生效）。
     *
     * @param entity 待保存的 Agent 实体
     */
    @Transactional
    public void saveAgent(AgentEntity entity) {
        if (!databasePropertiesProvider.getIfAvailable().isEnabled())
            return;
        try {
            entity.preInsert();
            agentMapperProvider.getIfAvailable().save(entity);
            if (log.isDebugEnabled())
                log.debug("保存 Agent: {}", entity.getName());
        } catch (Exception e) {
            if (log.isErrorEnabled())
                log.error("保存 Agent 失败: {}", entity.getName(), e);
        }
    }

    /**
     * 保存工具配置，按工具名称幂等 upsert。
     *
     * <p>已存在同名工具配置则更新，否则新增。</p>
     *
     * @param entity 待保存的工具配置实体
     */
    @Transactional
    public void saveToolConfig(ToolConfigEntity entity) {
        if (!databasePropertiesProvider.getIfAvailable().isEnabled())
            return;
        try {
            ToolConfigEntity existing = toolConfigMapperProvider.getIfAvailable().findByToolName(entity.getToolName());
            if (existing == null) {
                entity.preInsert();
                toolConfigMapperProvider.getIfAvailable().insert(entity);
            } else {
                entity.setId(existing.getId());
                entity.preUpdate();
                toolConfigMapperProvider.getIfAvailable().update(entity);
            }
        } catch (Exception e) {
            if (log.isErrorEnabled())
                log.error("保存工具配置失败: {}", entity.getToolName(), e);
        }
    }
}
