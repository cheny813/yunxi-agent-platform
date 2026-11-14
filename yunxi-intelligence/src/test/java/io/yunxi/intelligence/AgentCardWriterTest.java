package io.yunxi.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.definition.AgentSpec;
import org.junit.jupiter.api.Test;

/** 分类卡片派生：名称 + 首句，剥离角色指令前缀并截断；description（即系统提示词）全文绝不进入卡片。 */
class AgentCardWriterTest {

    private static AgentSpec spec(String agentId, String name, String description) {
        return new AgentSpec(new AgentSpec.Identity(agentId, name, description), null, null, null, null, null, null, null);
    }

    @Test
    void derivesNamePlusFirstSentenceWithoutRolePrefix() {
        String card = AgentCardWriter.derive(spec("a1", "数据助手", "你是一个专业的数据查询助手。你将帮助用户完成报表查询。"));
        assertEquals("数据助手：专业的数据查询助手。", card);
    }

    @Test
    void truncatesLongFirstSentence() {
        String longDesc = "你是一个非常非常非常长的介绍。第二句不会被纳入。";
        String card = AgentCardWriter.derive(spec("a2", "长描述", longDesc));
        assertTrue(card.length() <= "长描述：".length() + 120 + 1);
        assertFalse(card.contains("第二句"));
    }

    @Test
    void fallsBackToNameWhenDescriptionBlank() {
        assertEquals("空助手", AgentCardWriter.derive(spec("a3", "空助手", null)));
    }

    @Test
    void neverIncludesSystemPromptBody() {
        String prompt = "你是营养师。第一行指令。第二行 detailed instructions……";
        String card = AgentCardWriter.derive(spec("a4", "营养师", prompt));
        assertFalse(card.contains("第二行"));
    }

    @Test
    void prefersExplicitIntentHintOverDerivedCard() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("a5", "营养师", "你是营养师。第一句指令。"),
                null, null, null, null, null, null, null,
                null, null,
                new AgentSpec.IntentConfig("饮食记录助手：识别点餐、热量查询与饮食咨询"));
        assertEquals("饮食记录助手：识别点餐、热量查询与饮食咨询", AgentCardWriter.derive(spec));
    }

    @Test
    void fallsBackToDerivedCardWhenHintBlank() {
        AgentSpec spec = new AgentSpec(
                new AgentSpec.Identity("a6", "数据助手", "你是一个专业的数据查询助手。第二句。"),
                null, null, null, null, null, null, null,
                null, null,
                new AgentSpec.IntentConfig("   "));
        assertEquals("数据助手：专业的数据查询助手。", AgentCardWriter.derive(spec));
    }
}
