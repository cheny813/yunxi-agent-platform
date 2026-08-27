package io.yunxi.platform.intent.util;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.classify.IntentTree;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IntentKeywordMatcher} 单元测试。
 *
 * <p>覆盖 RuleIntentClassifier（allGroupHit / anyKeywordHit / hitAllGroupCount /
 * bonusCount）与 LlmIntentClassifier（keywordScore）抽取后的公共语义，确保
 * 重构前后行为等价：allKeywords 组内 AND、组间 OR，null/空集合视为无约束。</p>
 */
@DisplayName("IntentKeywordMatcher 公共匹配工具单元测试")
class IntentKeywordMatcherTest {

    private static final String LOWER = "我要查订单物流";

    @Test
    @DisplayName("allGroupHit：组间 OR、组内 AND、无约束恒通过")
    void allGroupHit() {
        // 组内 AND：["订单","物流"] 全部出现 → 命中
        assertThat(IntentKeywordMatcher.allGroupHit(List.of(List.of("订单", "物流")), LOWER)).isTrue();
        // 组内缺一词 → 不命中
        assertThat(IntentKeywordMatcher.allGroupHit(List.of(List.of("订单", "退款")), LOWER)).isFalse();
        // 组间 OR：第一组缺、第二组全中 → 命中
        assertThat(IntentKeywordMatcher.allGroupHit(
                List.of(List.of("退款", "发票"), List.of("订单", "物流")), LOWER)).isTrue();
        // 无约束恒通过
        assertThat(IntentKeywordMatcher.allGroupHit(null, LOWER)).isTrue();
        assertThat(IntentKeywordMatcher.allGroupHit(List.of(), LOWER)).isTrue();
    }

    @Test
    @DisplayName("anyKeywordHit：任一命中即通过、无约束恒通过")
    void anyKeywordHit() {
        assertThat(IntentKeywordMatcher.anyKeywordHit(List.of("物流", "退换"), LOWER)).isTrue();
        assertThat(IntentKeywordMatcher.anyKeywordHit(List.of("退款", "发票"), LOWER)).isFalse();
        assertThat(IntentKeywordMatcher.anyKeywordHit(null, LOWER)).isTrue();
        assertThat(IntentKeywordMatcher.anyKeywordHit(List.of(), LOWER)).isTrue();
    }

    @Test
    @DisplayName("hitAllGroupCount：命中的 allKeywords 组数")
    void hitAllGroupCount() {
        assertThat(IntentKeywordMatcher.hitAllGroupCount(
                List.of(List.of("订单", "物流"), List.of("订单", "退款")), LOWER)).isEqualTo(1);
        assertThat(IntentKeywordMatcher.hitAllGroupCount(null, LOWER)).isZero();
        assertThat(IntentKeywordMatcher.hitAllGroupCount(List.of(), LOWER)).isZero();
    }

    @Test
    @DisplayName("keywordScore：any 命中数 + all 命中组关键词数；null match 返回 0")
    void keywordScore() {
        IntentTree.Node n = node(List.of(List.of("订单", "物流")), List.of("物流", "退换"), null);
        String lower = IntentKeywordMatcher.lower(LOWER);
        // any 命中 "物流"（+1）+ all 组全中（+2）= 3
        assertThat(IntentKeywordMatcher.keywordScore(n.match, lower)).isEqualTo(3);
        assertThat(IntentKeywordMatcher.keywordScore(null, lower)).isZero();
    }

    @Test
    @DisplayName("bonusCount：实体类型命中数；null match 返回 0")
    void bonusCount() {
        IntentTree.Node n = node(null, null, List.of(cond("MEAL"), cond("CROWD")));
        List<Entity> entities = List.of(
                new Entity("MEAL", "午餐", "午餐", 0, 2, "DICT"));
        assertThat(IntentKeywordMatcher.bonusCount(n.match, entities)).isEqualTo(1);
        assertThat(IntentKeywordMatcher.bonusCount(null, entities)).isZero();
        assertThat(IntentKeywordMatcher.bonusCount(n.match, null)).isZero();
    }

    private static IntentTree.Node node(List<List<String>> allKeywords, List<String> anyKeywords,
            List<IntentTree.EntityCond> entities) {
        IntentTree.Node n = new IntentTree.Node();
        n.id = "n-" + System.nanoTime();
        n.label = "测试意图";
        IntentTree.Match m = new IntentTree.Match();
        m.allKeywords = allKeywords;
        m.anyKeywords = anyKeywords;
        m.entities = entities;
        n.match = m;
        return n;
    }

    private static IntentTree.EntityCond cond(String type) {
        IntentTree.EntityCond c = new IntentTree.EntityCond();
        c.type = type;
        return c;
    }
}
