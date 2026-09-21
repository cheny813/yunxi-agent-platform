package io.yunxi.platform.agent.middleware;

import java.util.stream.Collectors;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import io.yunxi.platform.profile.RedisUserProfileStore;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;

/**
 * 画像注入中间件：在系统提示词阶段（onSystemPrompt）把已存储的 UserProfile 追加到提示词。
 *
 * <p>画像注入属于运行时内的横切关切，因此复用 AgentScope-Java 原生 Middleware（onSystemPrompt 钩子）实现，
 * 避免在对话服务里直接拼接提示词。无画像或拿不到 userId 时原样返回，零副作用。</p>
 */
public class UserProfileMiddleware implements MiddlewareBase {

    private final RedisUserProfileStore store;

    public UserProfileMiddleware(RedisUserProfileStore store) {
        this.store = store;
    }

    @Override
    public int order() {
        return 120;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.fromCallable(() -> {
            String userId = ctx != null ? ctx.getUserId() : null;
            if (userId == null || userId.isBlank()) {
                return currentPrompt;
            }
            UserProfile profile = store.getProfile(userId);
            if (profile == null) {
                return currentPrompt;
            }
            String block = buildBlock(profile);
            if (block.isEmpty()) {
                return currentPrompt;
            }
            String base = currentPrompt != null ? currentPrompt : "";
            String sep = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
            return base + sep + block;
        });
    }

    private String buildBlock(UserProfile p) {
        StringBuilder sb = new StringBuilder();
        sb.append("## User Profile\n");
        if (p.getIdentities() != null && !p.getIdentities().isEmpty()) {
            sb.append("Known identities: ")
                    .append(p.getIdentities().stream()
                            .map(i -> i.getCategory() + "=" + i.getName())
                            .collect(Collectors.joining(", ")))
                    .append("\n");
        }
        if (p.getPersonalContext() != null && !p.getPersonalContext().isEmpty()) {
            sb.append("Preferences: ")
                    .append(p.getPersonalContext().entrySet().stream()
                            .map(e -> e.getKey() + ":" + e.getValue())
                            .collect(Collectors.joining(", ")))
                    .append("\n");
        }
        return sb.toString().strip();
    }
}
