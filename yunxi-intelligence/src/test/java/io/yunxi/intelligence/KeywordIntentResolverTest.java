package io.yunxi.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 关键词解析器迁移到新契约：命中返回显式目标 Agent（修正旧实现恒回落默认的缺陷），未命中返回 null。 */
class KeywordIntentResolverTest {

    private final IntentTextNormalizer normalizer = new IntentTextNormalizer();

    private KeywordIntentResolver resolver(Map<String, List<String>> rules, Map<String, String> targets) {
        return new KeywordIntentResolver(rules, targets, "default-agent", normalizer);
    }

    @Test
    void hitWithExplicitTargetReturnsThatAgent() {
        KeywordIntentResolver r = resolver(Map.of("sql", List.of("报表", "sql")), Map.of("sql", "text2sql-assistant"));
        IntentResolution got = r.resolve(new IntentRequest("帮我拉一下上个月报表", "t1", "s1", 0));
        assertEquals("sql", got.label());
        assertEquals("text2sql-assistant", got.targetAgentId());
        assertEquals(IntentResolution.Source.KEYWORD, got.source());
    }

    @Test
    void hitWithoutExplicitTargetFallsBackToDefaultAgent() {
        KeywordIntentResolver r = resolver(Map.of("general", List.of("你好")), Map.of());
        IntentResolution got = r.resolve(new IntentRequest("你好", "t1", "s1", 0));
        assertEquals("default-agent", got.targetAgentId());
    }

    @Test
    void missReturnsNull() {
        KeywordIntentResolver r = resolver(Map.of("sql", List.of("报表")), Map.of());
        assertNull(r.resolve(new IntentRequest("帮我查一下订单", "t1", "s1", 0)));
    }

    @Test
    void matchesCaseAndFullWidthInsensitively() {
        KeywordIntentResolver r = resolver(Map.of("sql", List.of("SQL")), Map.of("sql", "text2sql-assistant"));
        assertEquals("text2sql-assistant", r.resolve(new IntentRequest("写一段ＳＱＬ", "t1", "s1", 0)).targetAgentId());
    }
}
