/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yunxi.platform.framework.hook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * 文本工具调用解析 Hook
 *
 * <p>当模型不支持原生 Function Calling API 时，从文本内容中解析工具调用。
 * 适用于 qwen-plus 等可能将工具调用以 JSON 文本形式输出而非通过 API 的 tool_calls 字段返回的模型。
 *
 * <p>支持的格式：
 * <ul>
 *   <li>OpenAI 风格 JSON 数组：[{"id": "call_xxx", "function": {"name": "tool_name", "arguments": "{...}"}}]
 *   <li>单个工具调用 JSON：{"name": "tool_name", "arguments": {...}}
 *   <li>代码块中的工具调用：```json\n[{...}]\n```
 * </ul>
 */
public class TextToolCallParserHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(TextToolCallParserHook.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** OpenAI 风格工具调用 JSON 数组匹配模式 */
    private static final Pattern TOOL_CALL_ARRAY_PATTERN = Pattern.compile(
            "\\[\\s*\\{\\s*\"id\"\\s*:\\s*\"[^\"]+\"[^]]*\\}\\s*\\]",
            Pattern.DOTALL);

    /** 工具调用 JSON 对象匹配模式 */
    private static final Pattern TOOL_CALL_OBJECT_PATTERN = Pattern.compile(
            "\\{\\s*\"function\"\\s*:\\s*\\{\\s*\"name\"\\s*:\\s*\"[^\"]+\"[^}]*\\}[^}]*\\}",
            Pattern.DOTALL);

    /** 简单工具调用格式匹配模式 */
    private static final Pattern SIMPLE_TOOL_CALL_PATTERN = Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]*\\})\\s*\\}",
            Pattern.DOTALL);

    /** JSON 代码块匹配模式 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```",
            Pattern.MULTILINE);

    private final Toolkit toolkit;
    private final boolean strictMode;

    /**
     * 创建 TextToolCallParserHook。
     *
     * @param toolkit 工具包，用于验证工具名称
     * @param strictMode 严格模式：true 表示仅当不存在 ToolUseBlock 时才解析；false 表示始终尝试解析
     */
    public TextToolCallParserHook(Toolkit toolkit, boolean strictMode) {
        this.toolkit = toolkit;
        this.strictMode = strictMode;
    }

    /**
     * 创建 TextToolCallParserHook（启用严格模式）。
     *
     * @param toolkit 工具包，用于验证工具名称
     */
    public TextToolCallParserHook(Toolkit toolkit) {
        this(toolkit, true);
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent e) {
            return handlePostReasoning(e).thenReturn(event);
        }
        return Mono.just(event);
    }

    private Mono<Void> handlePostReasoning(PostReasoningEvent event) {
        Msg reasoningMsg = event.getReasoningMessage();
        if (reasoningMsg == null) {
            return Mono.empty();
        }

        // 严格模式下，如果已存在 ToolUseBlock 则跳过
        if (strictMode && !reasoningMsg.getContentBlocks(ToolUseBlock.class).isEmpty()) {
            log.debug("ToolUseBlock 已存在，跳过文本解析");
            return Mono.empty();
        }

        // 获取文本内容
        String textContent = reasoningMsg.getTextContent();
        if (!StringUtils.hasText(textContent)) {
            return Mono.empty();
        }

        // 尝试从文本中解析工具调用
        List<ToolUseBlock> parsedToolCalls = parseToolCallsFromText(textContent);
        if (parsedToolCalls.isEmpty()) {
            return Mono.empty();
        }

        log.info("从文本内容中解析到 {} 个工具调用", parsedToolCalls.size());

        // 构建新的内容块列表
        List<ContentBlock> newContent = new ArrayList<>();

        // 先添加思考块（非 TextBlock 和 ToolUseBlock）
        reasoningMsg.getContent().stream()
                .filter(b -> !(b instanceof TextBlock) && !(b instanceof ToolUseBlock))
                .forEach(newContent::add);

        // 添加移除工具调用 JSON 后的文本块（可选）
        String cleanedText = removeToolCallJson(textContent);
        if (StringUtils.hasText(cleanedText.trim())) {
            newContent.add(TextBlock.builder().text(cleanedText.trim()).build());
        }

        // 添加解析出的工具使用块
        newContent.addAll(parsedToolCalls);

        // 更新推理消息
        Msg updatedMsg = Msg.builder()
                .id(reasoningMsg.getId())
                .name(reasoningMsg.getName())
                .role(reasoningMsg.getRole())
                .content(newContent)
                .metadata(reasoningMsg.getMetadata())
                .timestamp(reasoningMsg.getTimestamp())
                .generateReason(reasoningMsg.getGenerateReason())
                .build();

        event.setReasoningMessage(updatedMsg);
        log.debug("已更新推理消息，包含 {} 个解析的工具调用", parsedToolCalls.size());

        return Mono.empty();
    }

    /**
     * 从文本内容中解析工具调用。
     */
    private List<ToolUseBlock> parseToolCallsFromText(String text) {
        List<ToolUseBlock> result = new ArrayList<>();

        // 首先尝试从代码块中提取
        List<String> codeBlockContents = extractCodeBlockContents(text);
        for (String content : codeBlockContents) {
            result.addAll(parseJsonToolCalls(content));
        }

        // 如果代码块中没有找到工具调用，尝试直接解析
        if (result.isEmpty()) {
            result.addAll(parseJsonToolCalls(text));
        }

        // 验证工具名称是否在已注册的工具中
        return validateToolCalls(result);
    }

    /**
     * 从代码块中提取 JSON 内容。
     */
    private List<String> extractCodeBlockContents(String text) {
        List<String> contents = new ArrayList<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(text);
        while (matcher.find()) {
            String content = matcher.group(1).trim();
            if (content.startsWith("[") || content.startsWith("{")) {
                contents.add(content);
            }
        }
        return contents;
    }

    /**
     * 从 JSON 字符串解析工具调用。
     */
    private List<ToolUseBlock> parseJsonToolCalls(String json) {
        List<ToolUseBlock> result = new ArrayList<>();

        try {
            // 尝试 OpenAI 风格的数组格式
            List<Map<String, Object>> toolCalls = parseOpenAIToolCalls(json);
            if (!toolCalls.isEmpty()) {
                for (Map<String, Object> tc : toolCalls) {
                    ToolUseBlock block = convertToToolUseBlock(tc);
                    if (block != null) {
                        result.add(block);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("解析 OpenAI 风格工具调用失败: {}", e.getMessage());
        }

        try {
            // 尝试简单格式
            Matcher matcher = SIMPLE_TOOL_CALL_PATTERN.matcher(json);
            while (matcher.find()) {
                String name = matcher.group(1);
                String argsJson = matcher.group(2);
                ToolUseBlock block = ToolUseBlock.builder()
                        .id("call_" + System.currentTimeMillis())
                        .name(name)
                        .content(argsJson)
                        .input(parseArgs(argsJson))
                        .build();
                result.add(block);
            }
        } catch (Exception e) {
            log.debug("解析简单格式工具调用失败: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 解析 OpenAI 风格的工具调用。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseOpenAIToolCalls(String json) {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            // 查找 JSON 数组模式
            Matcher matcher = TOOL_CALL_ARRAY_PATTERN.matcher(json);
            if (matcher.find()) {
                String jsonArray = matcher.group();
                List<Object> items = objectMapper.readValue(jsonArray, new TypeReference<List<Object>>() {});
                for (Object item : items) {
                    if (item instanceof Map) {
                        result.add((Map<String, Object>) item);
                    }
                }
            }

            // 如果数组模式未匹配，尝试对象模式（单个工具调用）
            if (result.isEmpty()) {
                matcher = TOOL_CALL_OBJECT_PATTERN.matcher(json);
                if (matcher.find()) {
                    String jsonObj = matcher.group();
                    Map<String, Object> item = objectMapper.readValue(jsonObj, new TypeReference<Map<String, Object>>() {});
                    result.add(item);
                }
            }
        } catch (Exception e) {
            log.debug("解析 OpenAI 风格工具调用 JSON 失败: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 将解析的工具调用 Map 转换为 ToolUseBlock。
     */
    @SuppressWarnings("unchecked")
    private ToolUseBlock convertToToolUseBlock(Map<String, Object> tc) {
        try {
            String id = (String) tc.get("id");
            if (id == null) {
                id = "call_" + System.currentTimeMillis();
            }

            Map<String, Object> function = (Map<String, Object>) tc.get("function");
            if (function == null) {
                return null;
            }

            String name = (String) function.get("name");
            if (name == null || name.isEmpty()) {
                return null;
            }

            Object argsObj = function.get("arguments");
            String argsJson;
            Map<String, Object> args;

            if (argsObj instanceof String) {
                argsJson = (String) argsObj;
                args = parseArgs(argsJson);
            } else if (argsObj instanceof Map) {
                args = (Map<String, Object>) argsObj;
                argsJson = objectMapper.writeValueAsString(args);
            } else {
                argsJson = "{}";
                args = new HashMap<>();
            }

            return ToolUseBlock.builder()
                    .id(id)
                    .name(name)
                    .content(argsJson)
                    .input(args)
                    .build();
        } catch (Exception e) {
            log.debug("转换工具调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将参数 JSON 字符串解析为 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String argsJson) {
        if (!StringUtils.hasText(argsJson)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("解析参数 JSON 失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 验证工具调用是否在已注册的工具中。
     */
    private List<ToolUseBlock> validateToolCalls(List<ToolUseBlock> toolCalls) {
        if (toolkit == null) {
            return toolCalls;
        }

        List<ToolSchema> registeredTools = toolkit.getToolSchemas();
        List<String> registeredNames = registeredTools.stream()
                .map(ToolSchema::getName)
                .toList();

        List<ToolUseBlock> validCalls = new ArrayList<>();
        for (ToolUseBlock block : toolCalls) {
            if (registeredNames.contains(block.getName())) {
                validCalls.add(block);
                log.debug("有效的工具调用: {}", block.getName());
            } else {
                log.warn("未知的工具名称: {}，已忽略", block.getName());
            }
        }

        return validCalls;
    }

    /**
     * 从文本内容中移除工具调用 JSON。
     */
    private String removeToolCallJson(String text) {
        String result = text;

        // 移除包含工具调用的代码块
        result = CODE_BLOCK_PATTERN.matcher(result).replaceAll("");

        // 移除 OpenAI 风格的工具调用数组
        result = TOOL_CALL_ARRAY_PATTERN.matcher(result).replaceAll("");

        // 移除剩余的工具调用对象
        result = TOOL_CALL_OBJECT_PATTERN.matcher(result).replaceAll("");

        return result;
    }

    @Override
    public int priority() {
        // 在其他 Hook 之前执行（优先级越低越早执行）
        return 45;
    }
}
