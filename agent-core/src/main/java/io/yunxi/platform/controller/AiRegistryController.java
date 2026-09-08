package io.yunxi.platform.controller;

import java.util.List;
import java.util.Map;

import io.yunxi.platform.agent.mcp.AiRegistryStore;
import io.yunxi.platform.agent.mcp.AiRegistryStore.AiType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nacos 3.2 AI Registry 接入控制器（复用 MCP 协调底座同一 Nacos 实例发布 Skill/Agent/Prompt/AgentSpec）。
 * <p>提供 Skill / Agent / Prompt / AgentSpec 的发布/查询/删除/列出，统一治理并复用 MCP 协调底座
 * 同一 Nacos 实例（同集群同控制台同命名空间）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-registry")
@PreAuthorize("hasRole('ADMIN') or @permissionService.isOperator(authentication)")
public class AiRegistryController {

    private final AiRegistryStore aiRegistryStore;

    public AiRegistryController(AiRegistryStore aiRegistryStore) {
        this.aiRegistryStore = aiRegistryStore;
    }

    /** 列出全部已发布的 AI 资源（type+name）。 */
    @GetMapping
    public List<Map<String, String>> list() {
        return aiRegistryStore.list();
    }

    /** 发布/更新一个 AI 资源。type ∈ skill|agent|prompt|agentspec；body 为资源内容（JSON/YAML/文本）。 */
    @PostMapping("/{type}/{name}")
    public ResponseEntity<Map<String, Object>> publish(
            @PathVariable String type,
            @PathVariable String name,
            @RequestBody String content) {
        AiType t = parseType(type);
        aiRegistryStore.publish(t, name, content);
        return ResponseEntity.ok(Map.of("type", t.name(), "name", name, "status", "published"));
    }

    /** 查询一个 AI 资源内容。 */
    @GetMapping("/{type}/{name}")
    public ResponseEntity<?> get(@PathVariable String type, @PathVariable String name) {
        String content = aiRegistryStore.get(parseType(type), name);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("type", type, "name", name, "content", content));
    }

    /** 删除一个 AI 资源。 */
    @DeleteMapping("/{type}/{name}")
    public ResponseEntity<Map<String, Object>> remove(
            @PathVariable String type, @PathVariable String name) {
        aiRegistryStore.remove(parseType(type), name);
        return ResponseEntity.ok(Map.of("type", type, "name", name, "status", "removed"));
    }

    private AiType parseType(String type) {
        try {
            return AiType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "不支持的 AI 资源类型: " + type + "，应为 skill|agent|prompt|agentspec");
        }
    }
}
