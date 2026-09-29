package io.yunxi.platform.ingress.controller.management;

import java.util.List;
import java.util.Map;

import io.yunxi.platform.agent.mcp.AiRegistryStore;
import io.yunxi.platform.agent.mcp.AiRegistryStore.AiType;
import io.yunxi.platform.security.auth.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Nacos 3.2 AI Registry 接入控制器（复用 MCP 协调底座同一 Nacos 实例发布 Skill/Agent/Prompt/AgentSpec）。
 * <p>提供 Skill / Agent / Prompt / AgentSpec 的发布/查询/删除/列出，统一治理并复用 MCP 协调底座
 * 同一 Nacos 实例（同集群同控制台同命名空间）。</p>
 * <p>管理端点需 ADMIN 角色（取自请求头 X-User-Roles，经 SecurityContext 解析）。平台采用网关注入身份头的
 * 头信任模型：角色守卫在受信任内网边界内有效；对外暴露时须由网关/边缘层做真实身份认证（JWT 或 Spring Security），
 * 否则调用方可伪造 X-User-Roles 头绕过本守卫。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-registry")
public class AiRegistryController {

    /** 允许调用管理端点的角色。 */
    private static final String REQUIRED_ROLE = "ADMIN";

    private final AiRegistryStore aiRegistryStore;
    private final SecurityContext securityContext;

    public AiRegistryController(AiRegistryStore aiRegistryStore, SecurityContext securityContext) {
        this.aiRegistryStore = aiRegistryStore;
        this.securityContext = securityContext;
    }

    /** 角色守卫：当前用户不具备 REQUIRED_ROLE 时返回 403。真实执行原 @PreAuthorize 守卫的语义。 */
    private void assertAdmin() {
        for (String role : securityContext.getCurrentUserRoles()) {
            if (REQUIRED_ROLE.equalsIgnoreCase(role.trim())) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "管理端点需要 " + REQUIRED_ROLE + " 角色，当前请求未携带有效角色");
    }

    /** 列出全部已发布的 AI 资源（type+name）。 */
    @GetMapping
    public List<Map<String, String>> list() {
        assertAdmin();
        return aiRegistryStore.list();
    }

    /** 发布/更新一个 AI 资源。type ∈ skill|agent|prompt|agentspec；body 为资源内容（JSON/YAML/文本）。 */
    @PostMapping("/{type}/{name}")
    public ResponseEntity<Map<String, Object>> publish(
            @PathVariable String type,
            @PathVariable String name,
            @RequestBody String content) {
        assertAdmin();
        AiType t = parseType(type);
        aiRegistryStore.publish(t, name, content);
        return ResponseEntity.ok(Map.of("type", t.name(), "name", name, "status", "published"));
    }

    /** 查询一个 AI 资源内容。 */
    @GetMapping("/{type}/{name}")
    public ResponseEntity<?> get(@PathVariable String type, @PathVariable String name) {
        assertAdmin();
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
        assertAdmin();
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
