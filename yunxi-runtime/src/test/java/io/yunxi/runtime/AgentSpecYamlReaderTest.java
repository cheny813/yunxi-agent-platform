package io.yunxi.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.definition.AgentSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 定义文件解析：人写的 YAML 必须严格解析，字段名写错要当场失败并给出可操作提示。
 *
 * <p>被测的风险场景不是「写错一个无害字段」，而是<b>写错后限制整段失效</b>：把 {@code permission}
 * 拼成 {@code permissions} 时，宽松解析会丢弃整段、权限退回缺省的最大放行，用户以为设了限制。
 * 这类错误无法从运行结果反推，只能在解析期拦下。
 */
class AgentSpecYamlReaderTest {

    private static final String MINIMAL = """
            identity:
              agentId: demo-agent
              name: 演示助手
              description: 你是演示助手。
            """;

    @Test
    void parsesKnownFields() throws IOException {
        AgentSpec spec = read(MINIMAL);

        assertEquals("demo-agent", spec.identity().agentId());
    }

    @Test
    void misspelledSectionFailsAndPointsAtTheLikelyTarget() {
        IOException error = assertThrows(IOException.class, () -> read("""
                identity:
                  agentId: demo-agent
                  name: 演示助手
                permissions:
                  allowedTools:
                    - memory
                """));

        String message = error.getMessage();
        assertTrue(message.contains("permissions"), "须点名写错的字段：" + message);
        assertTrue(message.contains("是否想写 'permission'"), "须给出应改成的字段名：" + message);
    }

    @Test
    void misspelledNestedFieldFailsAndPointsAtTheLikelyTarget() {
        // deniedTools 拼错是最危险的一类：意图是收紧工具白名单，实际整段被丢弃。
        IOException error = assertThrows(IOException.class, () -> read("""
                identity:
                  agentId: demo-agent
                  name: 演示助手
                permission:
                  denyTools:
                    - execute
                """));

        String message = error.getMessage();
        assertTrue(message.contains("denyTools"), "须点名写错的字段：" + message);
        assertTrue(message.contains("是否想写 'deniedTools'"), "须给出应改成的字段名：" + message);
    }

    @Test
    void unknownFieldWithoutCloseMatchStillFailsAndListsUsableFields() {
        IOException error = assertThrows(IOException.class, () -> read("""
                identity:
                  agentId: demo-agent
                  name: 演示助手
                completelyMadeUpSection:
                  foo: bar
                """));

        String message = error.getMessage();
        assertTrue(message.contains("completelyMadeUpSection"), message);
        assertTrue(message.contains("identity"), "须列出该层可用字段，便于对照修正：" + message);
    }

    @Test
    void duplicateSectionFailsInsteadOfSilentlyTakingTheLastOne() {
        // 同一段声明两次时若静默取后者，前一份限制会被无声覆盖——与拼错字段属同一类静默丢弃。
        assertThrows(IOException.class, () -> read("""
                identity:
                  agentId: first-agent
                  name: 第一份
                identity:
                  agentId: second-agent
                  name: 第二份
                """));
    }

    private static AgentSpec read(String yaml) throws IOException {
        try (InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            return AgentSpecYamlReader.read(in);
        }
    }
}
