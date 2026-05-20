package io.yunxi.platform.infra.persistence;

import io.yunxi.platform.infra.config.DatabaseProperties;
import io.yunxi.platform.shared.entity.AgentEntity;
import io.yunxi.platform.shared.entity.ToolConfigEntity;
import io.yunxi.platform.shared.mapper.AgentMapper;
import io.yunxi.platform.shared.mapper.ConversationMapper;
import io.yunxi.platform.shared.mapper.ToolConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据持久化服务
 *
 * <p>
 * 本服务负责数据的持久化操作，包括 Agent、会话、工具配置等数据的保存和查询。
 * </p>
 *
 * <p>
 * <b>层级说明</b>：
 * <ul>
 * <li>所属层级：基础设施层</li>
 * <li>职责：数据持久化</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
public class PersistenceService {

    /** 数据库配置属性 */
    @Autowired
    private ObjectProvider<DatabaseProperties> databasePropertiesProvider;

    /** Agent Mapper */
    @Autowired
    private ObjectProvider<AgentMapper> agentMapperProvider;

    /** 工具配置 Mapper */
    @Autowired
    private ObjectProvider<ToolConfigMapper> toolConfigMapperProvider;

    /** 会话 Mapper */
    @Autowired
    private ConversationMapper conversationMapper;

    /**
     * 应用启动后初始化数据库
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeDatabase() {
        // 先创建表（如果不存在），确保数据库表结构就绪
        // 使用 CREATE TABLE IF NOT EXISTS，无论 database.enabled 为何值都安全执行
        createTablesIfNotExist();

        if (databasePropertiesProvider.getIfAvailable() == null
                || !databasePropertiesProvider.getIfAvailable().isEnabled()) {
            if (log.isInfoEnabled()) {
                log.info("数据库持久化未启用，跳过数据初始化");
            }
            return;
        }

        try {
            // 统计数据库中的数据
            long agentCount = agentMapperProvider.getIfAvailable().count();
            long toolConfigCount = toolConfigMapperProvider.getIfAvailable().count();

            if (log.isInfoEnabled()) {
                log.info("数据库初始化完成: {} 个 Agent, {} 个工具配置",
                        agentCount, toolConfigCount);
            }

        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("数据库初始化失败", e);
            }
        }
    }

    /**
     * 创建数据库表（如果不存在）
     */
    private void createTablesIfNotExist() {
        try {
            conversationMapper.createAgentsTableIfNotExists();
            if (log.isInfoEnabled()) {
                log.info("agents 表已就绪");
            }

            conversationMapper.createConversationsTableIfNotExists();
            if (log.isInfoEnabled()) {
                log.info("conversations 表已就绪");
            }

            conversationMapper.createToolConfigsTableIfNotExists();
            if (log.isInfoEnabled()) {
                log.info("tool_configs 表已就绪");
            }

            conversationMapper.createChatLogsTableIfNotExists();
            if (log.isInfoEnabled()) {
                log.info("chat_logs 表已就绪");
            }

        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("创建数据库表失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 保存或更新 Agent 到数据库
     *
     * <p>
     * 自动判断是新插入记录还是更新记录：
     * <ul>
     * <li>新记录：调用 entity.preInsert() 设置创建时间</li>
     * <li>已存在：调用 entity.preUpdate() 更新时间</li>
     * </ul>
     * </p>
     *
     * @param entity Agent 实体
     */
    @Transactional
    public void saveAgent(AgentEntity entity) {
        if (!databasePropertiesProvider.getIfAvailable().isEnabled()) {
            return;
        }

        try {
            // 使用 save 方法进行插入或更新
            entity.preInsert();
            agentMapperProvider.getIfAvailable().save(entity);
            if (log.isDebugEnabled()) {
                log.debug("保存 Agent: {}", entity.getName());
            }
        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("保存 Agent 失败: {}", entity.getName(), e);
            }
        }
    }

    /**
     * 保存工具配置
     *
     * @param entity 工具配置实体
     */
    @Transactional
    public void saveToolConfig(ToolConfigEntity entity) {
        if (!databasePropertiesProvider.getIfAvailable().isEnabled()) {
            return;
        }

        try {
            // 检查是否是新插入记录
            ToolConfigEntity existing = toolConfigMapperProvider.getIfAvailable().findByToolName(entity.getToolName());
            if (existing == null) {
                // 新插入
                entity.preInsert();
                toolConfigMapperProvider.getIfAvailable().insert(entity);
                if (log.isDebugEnabled()) {
                    log.debug("保存新工具配置: {}", entity.getToolName());
                }
            } else {
                // 更新
                entity.setId(existing.getId());
                entity.preUpdate();
                toolConfigMapperProvider.getIfAvailable().update(entity);
                if (log.isDebugEnabled()) {
                    log.debug("更新工具配置: {}", entity.getToolName());
                }
            }
        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("保存工具配置失败: {}", entity.getToolName(), e);
            }
        }
    }
}
