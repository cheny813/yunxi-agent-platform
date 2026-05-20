package io.yunxi.platform.framework.structured;

import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.dto.StructuredOutputConfigDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schema 类注册中心
 * <p>
 * 管理结构化输出的 Schema 类，支持从 Agent 配置中动态加载。
 * 现在支持一个 Agent 配置多个 Schema 类，通过 schemaName 选择使用哪个 Schema。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 3.0.0
 */
@Slf4j
@Service
public class SchemaClassRegistry {

    /**
     * Agent 名称 -> Schema 信息映射
     * Value: SchemaInfo 包含多个 Schema 类和默认 Schema
     */
    private final Map<String, SchemaInfo> schemaInfoMap = new ConcurrentHashMap<>();

    /**
     * Schema 信息
     */
    public static class SchemaInfo {
        /**
         * 默认 Schema 类（兼容旧版）
         */
        private Class<?> defaultSchemaClass;

        /**
         * 命名 Schema 类映射（Key: schemaName, Value: Schema 类）
         */
        private final Map<String, Class<?>> namedSchemas = new ConcurrentHashMap<>();

        public Class<?> getDefaultSchemaClass() {
            return defaultSchemaClass;
        }

        public void setDefaultSchemaClass(Class<?> defaultSchemaClass) {
            this.defaultSchemaClass = defaultSchemaClass;
        }

        public Map<String, Class<?>> getNamedSchemas() {
            return namedSchemas;
        }

        /**
         * 根据名称获取 Schema 类
         */
        public Class<?> getSchemaByName(String schemaName) {
            if (schemaName == null || schemaName.isBlank()) {
                return defaultSchemaClass;
            }
            return namedSchemas.get(schemaName);
        }

        /**
         * 添加命名 Schema
         */
        public void addNamedSchema(String schemaName, Class<?> schemaClass) {
            namedSchemas.put(schemaName, schemaClass);
        }

        /**
         * 获取所有可用的 Schema 名称
         */
        public Set<String> getAvailableSchemaNames() {
            return namedSchemas.keySet();
        }
    }

    /**
     * 注册 Agent 的默认 Schema 类（兼容旧版）
     *
     * @param agentName  Agent 名称
     * @param schemaClass Schema 类
     */
    public void register(String agentName, Class<?> schemaClass) {
        if (agentName == null || agentName.isBlank()) {
            log.warn("Agent 名称为空，跳过注册");
            return;
        }
        if (schemaClass == null) {
            log.warn("Schema 类为空，跳过注册: {}", agentName);
            return;
        }

        SchemaInfo schemaInfo = schemaInfoMap.computeIfAbsent(agentName, k -> new SchemaInfo());
        schemaInfo.setDefaultSchemaClass(schemaClass);
        log.info("注册 Agent [{}] 的默认 Schema 类: {}", agentName, schemaClass.getName());
    }

    /**
     * 注册 Agent 的命名 Schema 类
     *
     * @param agentName  Agent 名称
     * @param schemaName Schema 名称
     * @param schemaClass Schema 类
     */
    public void registerSchema(String agentName, String schemaName, Class<?> schemaClass) {
        if (agentName == null || agentName.isBlank()) {
            log.warn("Agent 名称为空，跳过注册");
            return;
        }
        if (schemaName == null || schemaName.isBlank()) {
            log.warn("Schema 名称为空，跳过注册: {}", agentName);
            return;
        }
        if (schemaClass == null) {
            log.warn("Schema 类为空，跳过注册: {} - {}", agentName, schemaName);
            return;
        }

        SchemaInfo schemaInfo = schemaInfoMap.computeIfAbsent(agentName, k -> new SchemaInfo());
        schemaInfo.addNamedSchema(schemaName, schemaClass);
        log.info("注册 Agent [{}] 的命名 Schema [{}]: {}", agentName, schemaName, schemaClass.getName());
    }

    /**
     * 获取 Agent 的默认 Schema 类（兼容旧版）
     *
     * @param agentName Agent 名称
     * @return Schema 类，如果未注册则返回 null
     */
    public Class<?> get(String agentName) {
        return getSchema(agentName, null);
    }

    /**
     * 获取 Agent 的指定 Schema 类
     *
     * @param agentName  Agent 名称
     * @param schemaName Schema 名称，如果为 null 或空，返回默认 Schema
     * @return Schema 类，如果未注册则返回 null
     */
    public Class<?> getSchema(String agentName, String schemaName) {
        if (agentName == null || agentName.isBlank()) {
            return null;
        }

        SchemaInfo schemaInfo = schemaInfoMap.get(agentName);
        if (schemaInfo == null) {
            return null;
        }

        return schemaInfo.getSchemaByName(schemaName);
    }

    /**
     * 检查 Agent 是否配置了 Schema 类
     *
     * @param agentName Agent 名称
     * @return true 如果已配置 Schema 类
     */
    public boolean hasSchemaClass(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return false;
        }
        return schemaInfoMap.containsKey(agentName);
    }

    /**
     * 检查 Agent 是否配置了指定的 Schema 类
     *
     * @param agentName  Agent 名称
     * @param schemaName Schema 名称
     * @return true 如果已配置该 Schema 类
     */
    public boolean hasSchema(String agentName, String schemaName) {
        if (agentName == null || agentName.isBlank()) {
            return false;
        }

        SchemaInfo schemaInfo = schemaInfoMap.get(agentName);
        if (schemaInfo == null) {
            return false;
        }

        if (schemaName == null || schemaName.isBlank()) {
            return schemaInfo.getDefaultSchemaClass() != null;
        }

        return schemaInfo.getNamedSchemas().containsKey(schemaName);
    }

    /**
     * 获取 Agent 可用的 Schema 名称列表
     *
     * @param agentName Agent 名称
     * @return Schema 名称列表
     */
    public Set<String> getAvailableSchemaNames(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return Set.of();
        }

        SchemaInfo schemaInfo = schemaInfoMap.get(agentName);
        if (schemaInfo == null) {
            return Set.of();
        }

        return schemaInfo.getAvailableSchemaNames();
    }

    /**
     * 从 Agent 定义中加载 Schema 类
     *
     * @param definition Agent 定义
     */
    public void loadFromDefinition(AgentDefinition definition) {
        if (definition == null) {
            return;
        }

        String agentName = definition.getName();
        if (definition.getStructuredOutput() != null
                && definition.getStructuredOutput().isEnabled()) {

            StructuredOutputConfigDto config = definition.getStructuredOutput();

            // 加载默认 Schema 类
            String schemaClassName = config.getSchemaClass();
            if (schemaClassName != null && !schemaClassName.isBlank()) {
                try {
                    Class<?> schemaClass = Class.forName(schemaClassName);
                    register(agentName, schemaClass);
                } catch (ClassNotFoundException e) {
                    log.error("加载默认 Schema 类失败: {} - {}", schemaClassName, e.getMessage());
                }
            }

            // 加载多个命名 Schema 类
            Map<String, String> schemas = config.getSchemas();
            if (schemas != null && !schemas.isEmpty()) {
                for (Map.Entry<String, String> entry : schemas.entrySet()) {
                    String schemaName = entry.getKey();
                    String className = entry.getValue();
                    try {
                        Class<?> schemaClass = Class.forName(className);
                        registerSchema(agentName, schemaName, schemaClass);
                    } catch (ClassNotFoundException e) {
                        log.error("加载命名 Schema [{}] 失败: {} - {}", schemaName, className, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 获取已注册的 Agent 数量
     *
     * @return 已注册的 Agent 数量
     */
    public int size() {
        return schemaInfoMap.size();
    }

    /**
     * 获取所有已注册的 Agent 名称
     *
     * @return Agent 名称列表
     */
    public Set<String> getRegisteredAgentNames() {
        return schemaInfoMap.keySet();
    }

    /**
     * 移除 Agent 的所有 Schema 类
     *
     * @param agentName Agent 名称
     */
    public void unregister(String agentName) {
        SchemaInfo removed = schemaInfoMap.remove(agentName);
        if (removed != null) {
            int count = (removed.getDefaultSchemaClass() != null ? 1 : 0) 
                       + removed.getNamedSchemas().size();
            log.info("移除 Agent [{}] 的所有 Schema 类，共 {} 个", agentName, count);
        }
    }

    /**
     * 移除 Agent 的指定命名 Schema 类
     *
     * @param agentName  Agent 名称
     * @param schemaName Schema 名称
     */
    public void unregisterSchema(String agentName, String schemaName) {
        if (agentName == null || schemaName == null) {
            return;
        }

        SchemaInfo schemaInfo = schemaInfoMap.get(agentName);
        if (schemaInfo == null) {
            return;
        }

        Class<?> removed = schemaInfo.getNamedSchemas().remove(schemaName);
        if (removed != null) {
            log.info("移除 Agent [{}] 的命名 Schema [{}]: {}", agentName, schemaName, removed.getName());
        }
    }

    /**
     * 清空所有注册
     */
    public void clear() {
        int size = schemaInfoMap.size();
        schemaInfoMap.clear();
        log.info("清空所有 Schema 类注册，共移除 {} 个 Agent", size);
    }
}

