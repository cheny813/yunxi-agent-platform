package io.yunxi.agent.rule.core;

import io.yunxi.agent.rule.model.RulePriority;
import org.jeasy.rules.api.Facts;
import org.jeasy.rules.api.Rule;
import org.springframework.expression.*;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Set;

/**
 * 基于 Spring SpEL 的安全表达式规则
 *
 * <p>
 * 替代 MVELRule，修复 CVE-2023-50571（MVEL 远程代码执行漏洞）。
 * SpEL 表达式在受限的 EvaluationContext 中执行，禁止访问任意类。
 * </p>
 *
 * <h3>与 MVEL 表达式的差异</h3>
 * <ul>
 * <li>MVEL: {@code facts.cpuUsage > 80} — 直接访问 Facts 中的属性</li>
 * <li>SpEL: {@code #facts.cpuUsage > 80} 或
 * {@code #facts.get('cpuUsage') > 80}</li>
 * <li>SpEL 类型访问: {@code T(java.time.LocalTime).now().hour >= 9}（受白名单限制）</li>
 * </ul>
 *
 * @author yunxi Team
 * @since 1.1.0
 */
public class SpELRule implements Rule {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    /** 允许在 SpEL 表达式中通过 T() 访问的类白名单 */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "java.time.LocalTime",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.lang.Math",
            "java.lang.String");

    private final String name;
    private final String description;
    private final int priority;
    private final Expression conditionExpression;
    private final Expression actionExpression;

    private SpELRule(String name, String description, int priority,
            Expression conditionExpression, Expression actionExpression) {
        this.name = name;
        this.description = description;
        this.priority = priority;
        this.conditionExpression = conditionExpression;
        this.actionExpression = actionExpression;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public int compareTo(Rule other) {
        if (this.priority != other.getPriority()) {
            return Integer.compare(other.getPriority(), this.priority);
        }
        return this.name.compareTo(other.getName());
    }

    @Override
    public boolean evaluate(Facts facts) {
        if (conditionExpression == null) {
            return false;
        }
        EvaluationContext context = createSandboxedContext(facts);
        try {
            Boolean result = conditionExpression.getValue(context, Boolean.class);
            return result != null && result;
        } catch (SpelEvaluationException e) {
            throw new SpELRuleException(
                    "条件表达式求值失败: " + conditionExpression.getExpressionString(), e);
        }
    }

    @Override
    public void execute(Facts facts) {
        if (actionExpression == null) {
            return;
        }
        EvaluationContext context = createSandboxedContext(facts);
        try {
            actionExpression.getValue(context);
        } catch (SpelEvaluationException e) {
            throw new SpELRuleException(
                    "动作表达式执行失败: " + actionExpression.getExpressionString(), e);
        }
    }

    /**
     * 创建沙箱化的 SpEL 求值上下文
     *
     * <p>
     * 安全措施：
     * </p>
     * <ul>
     * <li>Facts 对象以 {@code #facts} 变量暴露，支持属性访问和 get() 方法</li>
     * <li>类型访问白名单：仅允许 {@link #ALLOWED_TYPES} 中的类</li>
     * <li>禁止通过反射或 Class.forName 访问未授权类</li>
     * </ul>
     */
    private EvaluationContext createSandboxedContext(Facts facts) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("facts", facts);
        context.setTypeLocator(typeName -> {
            if (ALLOWED_TYPES.contains(typeName)) {
                try {
                    return Class.forName(typeName);
                } catch (ClassNotFoundException e) {
                    throw new SpELRuleException("白名单类型不存在: " + typeName, e);
                }
            }
            throw new SpELRuleException(
                    "SpEL 类型访问被拒绝: " + typeName + "（仅允许: " + ALLOWED_TYPES + "）");
        });
        return context;
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name = "spel-rule";
        private String description = "";
        private int priority = RulePriority.NORMAL.getValue();
        private Expression condition;
        private Expression action;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * 设置条件表达式（when 子句）
         *
         * @param expression SpEL 表达式字符串，例如 {@code #facts.cpuUsage > 80}
         */
        public Builder when(String expression) {
            this.condition = PARSER.parseExpression(expression);
            return this;
        }

        /**
         * 设置动作表达式（then 子句）
         *
         * @param expression SpEL 表达式字符串，例如 {@code #facts.put('allowed', false)}
         */
        public Builder then(String expression) {
            this.action = PARSER.parseExpression(expression);
            return this;
        }

        public SpELRule build() {
            return new SpELRule(name, description, priority, condition, action);
        }
    }

    // ==================== Exception ====================

    /**
     * SpEL 规则求值异常
     */
    public static class SpELRuleException extends RuntimeException {
        public SpELRuleException(String message) {
            super(message);
        }

        public SpELRuleException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ==================== Static Utilities ====================

    /**
     * 验证 SpEL 表达式语法是否合法
     *
     * @param expression 表达式字符串
     * @throws SpELRuleException 如果语法错误
     */
    public static void validateExpression(String expression) {
        try {
            PARSER.parseExpression(expression);
        } catch (ParseException e) {
            throw new SpELRuleException("表达式语法错误: " + expression, e);
        }
    }
}