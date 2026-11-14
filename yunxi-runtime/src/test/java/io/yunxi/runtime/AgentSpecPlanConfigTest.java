package io.yunxi.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.yunxi.definition.AgentSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反序列化路径测试：验证 AgentSpec 的 per-Agent {@code plan.taskList} 字段经
 * {@code AgentDefinitionLoader} 同款 yamlMapper 正确解析，且既有不含 plan 段的定义零回归
 * （{@code FAIL_ON_UNKNOWN_PROPERTIES=false} 容忍缺省，解析为 null）。
 */
class AgentSpecPlanConfigTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void parsesPlanTaskListTrue() throws Exception {
        String yaml = """
                identity:
                  agentId: a
                  name: a
                  description: a
                plan:
                  taskList: true
                """;
        AgentSpec spec = YAML.readValue(yaml, AgentSpec.class);
        assertNotNull(spec.plan(), "含 plan 段应解析为非 null");
        assertTrue(spec.plan().taskList(), "plan.taskList 应为 true");
    }

    @Test
    void omitsPlanWhenAbsent() throws Exception {
        String yaml = """
                identity:
                  agentId: a
                  name: a
                  description: a
                """;
        AgentSpec spec = YAML.readValue(yaml, AgentSpec.class);
        assertNull(spec.plan(), "不含 plan 段的既有定义应解析为 null（零回归）");
    }

    @Test
    void planDefaultsToFalseWhenDeclaredEmpty() throws Exception {
        String yaml = """
                identity:
                  agentId: a
                  name: a
                  description: a
                plan:
                  taskList: false
                """;
        AgentSpec spec = YAML.readValue(yaml, AgentSpec.class);
        assertNotNull(spec.plan());
        assertFalse(spec.plan().taskList(), "plan.taskList 显式 false 应保留");
    }
}
