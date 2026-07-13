package io.yunxi.platform.tool.impl;

import org.springframework.stereotype.Component;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;

/**
 * 计算器工具
 * <p>
 * 提供基本的数学计算功能。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class CalculatorTool {

    @Tool(name = "calculator", description = "执行数学计算，支持加减乘除、指数、平方根等运算")
    public String calculate(
            @ToolParam(name = "operation", description = "运算类型: add/subtract/multiply/divide/power/sqrt/abs/expression") String operation,
            @ToolParam(name = "a", description = "第一个操作数（双元运算需要）") Double a,
            @ToolParam(name = "b", description = "第二个操作数（双元运算需要）") Double b,
            @ToolParam(name = "expression", description = "自定义表达式（如 2+3*4）") String expression) {
        try {
            if ("expression".equals(operation)) {
                return evaluateExpression(expression);
            }

            if (a == null) {
                return "错误: 缺少操作数 a";
            }

            Double result = switch (operation) {
                case "add" -> a + b;
                case "subtract" -> a - b;
                case "multiply" -> a * b;
                case "divide" -> {
                    if (b == null)
                        throw new IllegalArgumentException("除法操作需要两个操作数");
                    if (b == 0)
                        throw new ArithmeticException("除数不能为零");
                    yield a / b;
                }
                case "power" -> Math.pow(a, b != null ? b : 2);
                case "sqrt" -> Math.sqrt(a);
                case "abs" -> Math.abs(a);
                default -> throw new IllegalArgumentException("不支持的运算类型: " + operation);
            };

            log.info("计算器执行: {} {} {} = {}", operation, a, b, result);
            return String.format("{\"operation\":\"%s\",\"result\":%s}", operation, result);
        } catch (Exception e) {
            log.error("计算器执行失败", e);
            return "计算失败: " + e.getMessage();
        }
    }

    /**
     * 计算简单的二元运算表达式（仅支持单个 + 或 * 运算符）。
     *
     * <p>先剥离所有非数字与运算符字符，再按运算符切分求值。不支持复杂表达式。</p>
     *
     * @param expression 用户提供的表达式字符串
     * @return 计算结果 JSON，或提示信息
     */
    private String evaluateExpression(String expression) {
        String cleanExpr = expression.replaceAll("[^0-9+\\-*/(). ]", "");
        if (cleanExpr.contains("+")) {
            String[] parts = cleanExpr.split("\\+");
            double a = Double.parseDouble(parts[0].trim());
            double b = Double.parseDouble(parts[1].trim());
            return String.format("{\"expression\":\"%s\",\"result\":%s}", expression, a + b);
        }
        if (cleanExpr.contains("*")) {
            String[] parts = cleanExpr.split("\\*");
            double a = Double.parseDouble(parts[0].trim());
            double b = Double.parseDouble(parts[1].trim());
            return String.format("{\"expression\":\"%s\",\"result\":%s}", expression, a * b);
        }
        return "表达式解析暂不支持复杂运算，请使用单个运算符";
    }
}
