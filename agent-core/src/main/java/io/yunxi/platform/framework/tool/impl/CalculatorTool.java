package io.yunxi.platform.framework.tool.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolExecutionException;
import io.yunxi.platform.framework.tool.ToolInput;
import io.yunxi.platform.framework.tool.ToolResult;

import java.util.HashMap;
import java.util.Map;

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
public class CalculatorTool implements Tool {

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "执行数学计算，支持加减乘除、指数、平方根等运算";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "operation": {
                            "type": "string",
                            "enum": ["add", "subtract", "multiply", "divide", "power", "sqrt", "abs"],
                            "description": "运算类型"
                        },
                        "a": {
                            "type": "number",
                            "description": "第一个操作数"
                        },
                        "b": {
                            "type": "number",
                            "description": "第二个操作数（双元运算需要）"
                        },
                        "expression": {
                            "type": "string",
                            "description": "自定义表达式（如 2+3*4）"
                        }
                    },
                    "required": ["operation"]
                }
                """;
    }

    @Override
    public ToolResult execute(ToolInput input) throws ToolExecutionException {
        try {
            String operation = input.getString("operation");

            // 处理自定义表达式
            if ("expression".equals(operation)) {
                String expression = input.getString("expression");
                return evaluateExpression(expression);
            }

            // 获取操作数
            Double a = input.hasParameter("a") ? input.getParameter("a", Double.class) : null;
            Double b = input.hasParameter("b") ? input.getParameter("b", Double.class) : null;

            if (a == null) {
                return ToolResult.error("缺少操作数 a");
            }

            // 执行运算
            Double result = switch (operation) {
                case "add" -> a + b;
                case "subtract" -> a - b;
                case "multiply" -> a * b;
                case "divide" -> {
                    if (b == null) {
                        throw new IllegalArgumentException("除法操作需要两个操作数");
                    }
                    if (b == 0) {
                        throw new ArithmeticException("除数不能为 0");
                    }
                    yield a / b;
                }
                case "power" -> Math.pow(a, b != null ? b : 2);
                case "sqrt" -> Math.sqrt(a);
                case "abs" -> Math.abs(a);
                default -> throw new IllegalArgumentException("不支持的运算类型: " + operation);
            };

            Map<String, Object> output = new HashMap<>();
            output.put("operation", operation);
            output.put("a", a);
            if (b != null) {
                output.put("b", b);
            }
            output.put("result", result);

            log.info("计算器执行: {} {} {} = {}", operation, a, b, result);

            return ToolResult.success(output, "Number");

        } catch (Exception e) {
            log.error("计算器执行失败", e);
            throw new ToolExecutionException(getName(), "计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算表达式
     * <p>
     * 简单实现，仅支持基本运算符。
     * </p>
     */
    private ToolResult evaluateExpression(String expression) {
        try {
            // 使用 ScriptEngine 计算表达式（需要额外依赖）
            // 这里使用简单的解析器作为示例
            String cleanExpr = expression.replaceAll("[^0-9+\\-*/(). ]", "");

            // 简单的双运算符解析（完整实现需要表达式解析器）
            if (cleanExpr.contains("+")) {
                String[] parts = cleanExpr.split("\\+");
                double a = Double.parseDouble(parts[0].trim());
                double b = Double.parseDouble(parts[1].trim());
                double result = a + b;
                return ToolResult.success(Map.of("expression", expression, "result", result), "Number");
            }
            if (cleanExpr.contains("*")) {
                String[] parts = cleanExpr.split("\\*");
                double a = Double.parseDouble(parts[0].trim());
                double b = Double.parseDouble(parts[1].trim());
                double result = a * b;
                return ToolResult.success(Map.of("expression", expression, "result", result), "Number");
            }

            throw new UnsupportedOperationException("表达式解析暂不支持复杂运算，请使用单个运算符");

        } catch (Exception e) {
            log.error("表达式计算失败: {}", expression, e);
            return ToolResult.error("表达式计算失败: " + e.getMessage());
        }
    }
}
