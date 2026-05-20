package io.yunxi.platform.agent.ruleengine;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则引擎测试抽象基类
 * 提供Spring上下文、事务管理和通用测试工具
 */
@SpringBootTest(classes = io.yunxi.agent.rule.RuleEngineApplication.class)
@ActiveProfiles("test")
@Transactional
public abstract class AbstractRuleEngineTest extends BaseTest {

    @BeforeEach
    void setupAbstractRuleEngineTest() {
        MockitoAnnotations.openMocks(this);
        // 测试环境的特殊初始化逻辑
        initializeTestEnvironment();
    }

    /**
     * 初始化测试环境
     */
    protected void initializeTestEnvironment() {
        // 可以在这里设置特定的测试环境配置
        // 例如：设置时钟、初始化缓存等
    }

    /**
     * 创建规则相关的测试数据
     */
    protected RuleTestDataBuilder createRuleTestBuilder() {
        return new RuleTestDataBuilder();
    }

    /**
     * 规则测试数据构建器
     */
    public static class RuleTestDataBuilder {

        private String ruleName = "test-rule";
        private String condition = "true";
        private String action = "System.out.println(\"test\")";
        private String phase = "RUNTIME";

        public RuleTestDataBuilder withName(String name) {
            this.ruleName = name;
            return this;
        }

        public RuleTestDataBuilder withCondition(String condition) {
            this.condition = condition;
            return this;
        }

        public RuleTestDataBuilder withAction(String action) {
            this.action = action;
            return this;
        }

        public RuleTestDataBuilder withPhase(String phase) {
            this.phase = phase;
            return this;
        }

        public TestRuleData build() {
            return new TestRuleData(ruleName, condition, action, phase);
        }
    }

    /**
     * 测试规则数据对象
     */
    public static class TestRuleData {
        public final String ruleName;
        public final String condition;
        public final String action;
        public final String phase;

        public TestRuleData(String ruleName, String condition, String action, String phase) {
            this.ruleName = ruleName;
            this.condition = condition;
            this.action = action;
            this.phase = phase;
        }
    }
}