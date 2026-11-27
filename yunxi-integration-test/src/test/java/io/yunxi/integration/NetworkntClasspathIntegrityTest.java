package io.yunxi.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/**
 * classpath 完整性门禁：JSON Schema 实现版本冲突曾致框架 ToolValidator 静态初始化
 * 失败（模型返回 tool_calls 时 NoClassDefFoundError，所有带工具的 Agent 全链路中断）。
 * 根因是根 pom 曾将 com.networknt:json-schema-validator 钉在 1.x（缺 2.x 的
 * SpecificationVersion 类）。本测试保证运行时 classpath 恒为 networknt 2.x，
 * 且框架校验器可正常类初始化。
 */
class NetworkntClasspathIntegrityTest {

    @Test
    void specificationVersionPresent() throws Exception {
        // 2.x 独有类：1.x 无此类，存在性即证明版本未被降级覆盖
        Class.forName("com.networknt.schema.SpecificationVersion");
    }

    @Test
    void frameworkToolValidatorInitializes() {
        // 类初始化会构建默认 SchemaRegistry：classpath 缺 2.x API 时此处即炸
        assertDoesNotThrow(() -> Class.forName("io.agentscope.core.tool.ToolValidator"));
    }
}
