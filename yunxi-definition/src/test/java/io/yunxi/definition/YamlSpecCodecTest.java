package io.yunxi.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 定义层编解码验证：YAML 文本经 {@link YamlSpecCodec} 反序列化为 {@link AgentSpec} 记录时，
 * 字段映射必须与契约完全一致（磁盘形态 = 契约形态，不允许漂移）；encode → decode 往返必须保持相等。
 *
 * <p>这是所有平面的上游：定义加载写错，下游装配全会静默出错，故需精准单元锁定字段映射。</p>
 */
class YamlSpecCodecTest {

    private final YamlSpecCodec codec = new YamlSpecCodec();

    @Test
    void decode_parsesFullSpec_allFieldsMapped() {
        String yaml = """
                identity:
                  agentId: agent-demo
                  name: Demo Agent
                  description: demo description
                skill:
                  skills: [s1, s2]
                mcp:
                  servers: [mcp-a, mcp-b]
                state:
                  store: redis
                  persist: true
                permission:
                  toolGate:
                    enabled: true
                    tools: [shell]
                  reasoningReview:
                    enabled: false
                    strategy: strict
                    keywords: [delete, drop]
                  allowedTools: [Read, Grep]
                  deniedTools: [Write]
                """;

        AgentSpec spec = codec.decode(yaml);
        assertNotNull(spec);

        assertEquals("agent-demo", spec.identity().agentId());
        assertEquals("Demo Agent", spec.identity().name());
        assertEquals("demo description", spec.identity().description());

        assertEquals(List.of("s1", "s2"), spec.skill().skills());
        assertEquals(List.of("mcp-a", "mcp-b"), spec.mcp().servers());

        assertEquals("redis", spec.state().store());
        assertTrue(spec.state().persist());

        assertTrue(spec.permission().toolGate().enabled());
        assertEquals(List.of("shell"), spec.permission().toolGate().tools());

        assertEquals(List.of("Read", "Grep"), spec.permission().allowedTools());
        assertEquals(List.of("Write"), spec.permission().deniedTools());
    }

    @Test
    void roundTrip_encodeThenDecode_preservesSpec() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("a", "n", "d"),
                new AgentSpec.SkillConfig(List.of("s"), AgentSpec.SkillMode.BLACKLIST),
                new AgentSpec.McpConfig(List.of("mc")),
                new AgentSpec.StateConfig("redis", true),
                new AgentSpec.PermissionConfig(
                        new AgentSpec.PermissionConfig.ToolGate(true, List.of("shell")),
                        null, List.of("Read"), List.of("Write")),
                null, new AgentSpec.SubagentsConfig(List.of()), null,
                null, null,
                new AgentSpec.IntentConfig("饮食记录助手"));

        String yaml = codec.encode(spec);
        AgentSpec back = codec.decode(yaml);
        assertEquals(spec, back, "encode → decode 往返必须保持记录相等");
    }

    @Test
    void decode_parsesIntentHint() {
        String yaml = """
                identity:
                  agentId: agent-hint
                  name: Hint Agent
                  description: demo
                intent:
                  hint: 饮食记录助手：识别点餐与热量查询
                """;

        AgentSpec spec = codec.decode(yaml);
        assertEquals("饮食记录助手：识别点餐与热量查询", spec.intent().hint());
    }

    @Test
    void decode_legacyYamlWithoutIntent_intentIsNull() {
        String yaml = """
                identity:
                  agentId: agent-legacy
                  name: Legacy Agent
                  description: demo
                """;

        AgentSpec spec = codec.decode(yaml);
        assertNotNull(spec);
        assertNull(spec.intent(), "旧 YAML 不声明 intent 段时必须反序列化为 null 且不报错");
    }
}
