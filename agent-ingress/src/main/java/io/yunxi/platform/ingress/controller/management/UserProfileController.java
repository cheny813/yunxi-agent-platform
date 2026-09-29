package io.yunxi.platform.ingress.controller.management;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.profile.RedisUserProfileStore;
import io.yunxi.platform.spi.UserProfileEvolver;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;

/**
 * UserProfile 查询与演化端点。
 *
 * <p>路径刻意区别于 Agent 配置档（{@code /agents/{name}/profiles}），避免与控制面语义混淆。
 * 演化接受纯文本轮次（调用方自行决定何时触发），结果落 {@link RedisUserProfileStore}。</p>
 */
@RestController
@RequestMapping("/api/user-profiles")
public class UserProfileController {

    private final RedisUserProfileStore store;
    private final UserProfileEvolver evolver;

    public UserProfileController(RedisUserProfileStore store, UserProfileEvolver evolver) {
        this.store = store;
        this.evolver = evolver;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfile> get(@PathVariable String userId) {
        UserProfile p = store.getProfile(userId);
        return p != null ? ResponseEntity.ok(p) : ResponseEntity.notFound().build();
    }

    public static class EvolveRequest {
        public List<String> messages;
    }

    @PostMapping("/{userId}/evolve")
    public ResponseEntity<UserProfile> evolve(@PathVariable String userId,
            @RequestBody EvolveRequest req) {
        UserProfile current = store.getProfile(userId);
        if (current == null) {
            current = new UserProfile(userId, List.of(), Map.of(), Map.of(), System.currentTimeMillis());
        }
        List<Msg> turns = new java.util.ArrayList<>();
        if (req != null && req.messages != null) {
            for (String text : req.messages) {
                turns.add(Msg.builder().textContent(text).build());
            }
        }
        UserProfile updated = evolver.evolve(current, turns);
        store.saveProfile(userId, updated);
        return ResponseEntity.ok(updated);
    }
}
