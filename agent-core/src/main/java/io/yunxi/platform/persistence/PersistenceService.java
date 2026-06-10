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
