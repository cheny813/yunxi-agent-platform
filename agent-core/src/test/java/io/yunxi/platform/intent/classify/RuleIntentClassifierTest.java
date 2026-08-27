package io.yunxi.platform.intent.classify;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.yunxi.platform.agent.profile.ConceptRegistry;
import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.Intent;
import io.yunxi.platform.intent.config.IntentProperties;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.memory.MemorySceneRegistry;
import io.yunxi.platform.intent.snapshot.EntityDictSnapshot;
import io.yunxi.platform.intent.snapshot.MappingSnapshot;
import io.yunxi.platform.intent.snapshot.TermSnapshot;
import io.yunxi.platform.intent.snapshot.TreeSnapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * {@link RuleIntentClassifier} 置信语义单元测试。
 *
 * <p>覆盖 mode=rule 的基础置信（0.9 + 0.05×实体加分）与 mode=hybrid 的归一化置信
 * （allKeywords 全中 = 1.0、部分命中按组占比降档、anyKeywords 弱信号基线），
 * 以及 hybrid 模式下 EntityCond.optional 语义。</p>
 */
@DisplayName("RuleIntentClassifier 置信语义单元测试")
class RuleIntentClassifierTest {

    private IntentProperties properties;
    private MemorySceneRegistry memorySceneRegistry;
    private ObjectProvider<ConceptRegistry> conceptRegistryProvider;
    private RuleIntentClassifier classifier;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new IntentProperties();
        memorySceneRegistry = mock(MemorySceneRegistry.class);
        conceptRegistryProvider = mock(ObjectProvider.class);
        classifier = new RuleIntentClassifier(memorySceneRegistry, conceptRegistryProvider,
                properties);
    }

    private static IntentTree.Node node(List<List<String>> allKeywords, List<String> anyKeywords,
            List<IntentTree.EntityCond> entities, String id) {
        IntentTree.Node n = new IntentTree.Node();
        n.id = id == null ? "n-" + System.nanoTime() : id;
        n.label = "测试意图";
        IntentTree.Match m = new IntentTree.Match();
        m.allKeywords = allKeywords;
        m.anyKeywords = anyKeywords;
        m.entities = entities;
        n.match = m;
        return n;
    }

    private static DomainRuntime runtimeOf(IntentTree.Node... nodes) {
        List<IntentTree.Node> list = List.of(nodes);
        TreeSnapshot tree = new TreeSnapshot(list, Map.of(), Map.of(), list);
        return new DomainRuntime("base", 1L, Instant.now(), EntityDictSnapshot.empty(),
                TermSnapshot.empty(), tree, MappingSnapshot.empty());
    }

    private static IntentTree.EntityCond cond(String type, boolean optional) {
        IntentTree.EntityCond c = new IntentTree.EntityCond();
        c.type = type;
        c.optional = optional;
        return c;
    }

    private Intent classify(IntentTree.Node node, String query, List<Entity> entities) {
        return classifier.classify(query, entities, null, runtimeOf(node));
    }

    @Nested
    @DisplayName("mode=rule（默认）")
    class RuleMode {

        @Test
        @DisplayName("allKeywords 命中：基础置信 0.9")
        void allKeywordsHitBase() {
            IntentTree.Node n = node(List.of(List.of("订单")), null, null, "order");
            assertThat(classify(n, "我的订单到哪了", List.of()).confidence()).isEqualTo(0.9);
        }

        @Test
        @DisplayName("实体加分：0.9 + 0.05×1 = 0.95")
        void entityBonus() {
            IntentTree.Node n = node(List.of(List.of("订单")), null,
                    List.of(cond("ORDER_STATUS", false)), "order");
            Entity e = new Entity("ORDER_STATUS", "发货", "发货", 0, 2, "DICT");
            assertThat(classify(n, "订单发货了没", List.of(e)).confidence())
                    .isCloseTo(0.95, within(1e-9));
        }

        @Test
        @DisplayName("optional 恒忽略：实体缺失仍匹配")
        void optionalIgnoredInRuleMode() {
            IntentTree.Node n = node(List.of(List.of("订单")), null,
                    List.of(cond("ORDER_STATUS", false)), "order");
            assertThat(classify(n, "我的订单到哪了", List.of()).isUnknown()).isFalse();
        }
    }

    @Nested
    @DisplayName("mode=hybrid")
    class HybridMode {

        @BeforeEach
        void setHybrid() {
            properties.getClassification().setMode("hybrid");
            classifier = new RuleIntentClassifier(memorySceneRegistry, conceptRegistryProvider,
                    properties);
        }

        @Test
        @DisplayName("allKeywords 全中：归一化置信 1.0")
        void allGroupsHit() {
            IntentTree.Node n = node(List.of(List.of("手机"), List.of("订单")), null, null, "order");
            assertThat(classify(n, "查我的手机订单", List.of()).confidence()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("2 组中 1 组命中：按占比降档 0.5")
        void partialGroupsHit() {
            IntentTree.Node n = node(List.of(List.of("手机"), List.of("退款")), null, null, "order");
            assertThat(classify(n, "查我的手机订单", List.of()).confidence()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("部分命中 + 实体加分：0.5 + 0.05 = 0.55")
        void partialGroupsWithBonus() {
            IntentTree.Node n = node(List.of(List.of("手机"), List.of("退款")), null,
                    List.of(cond("ORDER_STATUS", false)), "order");
            Entity e = new Entity("ORDER_STATUS", "发货", "发货", 0, 2, "DICT");
            assertThat(classify(n, "查我的手机订单", List.of(e)).confidence())
                    .isCloseTo(0.55, within(1e-9));
        }

        @Test
        @DisplayName("仅 anyKeywords 命中（无实体）：弱信号基线 0.55")
        void anyKeywordsBaseline() {
            IntentTree.Node n = node(null, List.of("苹果", "香蕉"), null, "fruit");
            assertThat(classify(n, "我想买苹果", List.of()).confidence()).isEqualTo(0.55);
        }

        @Test
        @DisplayName("optional=false 实体缺失：节点不匹配 → unknown")
        void optionalFalseMissingRejects() {
            IntentTree.Node n = node(List.of(List.of("订单")), null,
                    List.of(cond("ORDER_STATUS", false)), "order");
            assertThat(classify(n, "我的订单到哪了", List.of()).isUnknown()).isTrue();
        }

        @Test
        @DisplayName("optional=true 实体缺失：不扣分仍匹配")
        void optionalTrueMissingOk() {
            IntentTree.Node n = node(List.of(List.of("订单")), null,
                    List.of(cond("DATE", true)), "order");
            assertThat(classify(n, "我的订单到哪了", List.of()).isUnknown()).isFalse();
        }

        @Test
        @DisplayName("optional=false 实体存在：正常匹配")
        void optionalFalsePresentMatches() {
            IntentTree.Node n = node(List.of(List.of("订单")), null,
                    List.of(cond("ORDER_STATUS", false)), "order");
            Entity e = new Entity("ORDER_STATUS", "发货", "发货", 0, 2, "DICT");
            assertThat(classify(n, "订单发货了没", List.of(e)).isUnknown()).isFalse();
        }
    }
}
