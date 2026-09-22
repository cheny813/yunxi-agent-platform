package io.yunxi.platform.muse.prune;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.agentscope.core.message.ToolResultState;

import io.yunxi.platform.profile.RedisUserProfileStore;
import io.yunxi.platform.spi.profile.UserProfileProvider.Identity;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;
import io.yunxi.platform.trace.ReasoningSpan;
import io.yunxi.platform.trace.SpanKind;
import io.yunxi.platform.trace.TraceStore;
import io.yunxi.platform.muse.config.EvolutionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 使用者画像聚合器：把轨迹与用户画像<b>只读</b>聚合成 {@link UsageProfile}，供剪枝器参考。
 *
 * <p>这是「用户画像作为 MUSE 只读输入特征」的唯一落点。方向严格单向：本类读画像，
 * 绝不写画像；画像抽取链路也绝不读剪枝结果。</p>
 *
 * <p><b>为什么需要它</b>：{@code SkillMeta.usageCount} 是全局累计计数，无法区分
 * 「10 个用户各用 1 次」（通用技能）与「1 个用户用了 10 次」（单一用户过拟合）。
 * 本类补上「使用者广度」这一维度。</p>
 *
 * <p><b>数据来源</b>：
 * <ul>
 *   <li>轨迹从 {@link TraceStore} 读取。轨迹标识（traceId）编码了使用者身份，格式为
 *       {@code userId:sessionId}，故使用者可由轨迹标识解析，无需修改轨迹结构。</li>
 *   <li>画像从 {@link RedisUserProfileStore} 读取，只取身份类别（category）作分布统计。</li>
 * </ul>
 *
 * <p><b>降级</b>：任何一步失败（存储不可用、轨迹为空、画像缺失）都返回 {@code null} 或
 * 跳过该条轨迹，使剪枝退化为原有两条规则，行为与未引入本特性时一致。</p>
 *
 * <p><b>已知边界</b>：轨迹存储只提供「枚举」而非「按技能反查」，故本类需枚举近期轨迹后
 * 逐个读取过滤，扫描量受配置上限约束，超长历史下聚合是近似的。</p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
public class UsageProfileAggregator {

    private final TraceStore traceStore;
    private final RedisUserProfileStore profileStore;
    private final EvolutionConfig config;

    public UsageProfileAggregator(TraceStore traceStore,
                                  RedisUserProfileStore profileStore,
                                  EvolutionConfig config) {
        this.traceStore = traceStore;
        this.profileStore = profileStore;
        this.config = config;
    }

    /**
     * 聚合指定技能的使用者画像特征。
     *
     * @param skillName 技能名，与轨迹中工具调用的名称对应
     * @return 聚合结果；无可用数据时返回 {@code null}（调用方应退化为原有判据）
     */
    public UsageProfile aggregate(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }
        EvolutionConfig.ProfileAware cfg = config.getPruner().getProfileAware();
        if (!cfg.isEnabled()) {
            return null;
        }
        try {
            List<String> traceIds = traceStore.recentTraceIds(cfg.getScanLimit());
            if (traceIds.isEmpty()) {
                return null;
            }
            Set<String> users = new HashSet<>();
            int[] successCalls = {0};
            boolean[] matched = {false};

            for (String traceId : traceIds) {
                String userId = userIdOf(traceId);
                if (userId == null) {
                    continue;
                }
                List<ReasoningSpan> spans = traceStore.read(traceId);
                if (spans.isEmpty()) {
                    continue;
                }
                boolean hitInTrace = false;
                for (ReasoningSpan span : spans) {
                    if (!isToolCallOf(span, skillName)) {
                        continue;
                    }
                    hitInTrace = true;
                    if (span.status() == ToolResultState.SUCCESS) {
                        successCalls[0]++;
                    }
                }
                if (!hitInTrace) {
                    continue;
                }
                matched[0] = true;
                users.add(userId);
            }

            if (!matched[0]) {
                return null;
            }

            // 类别分布按「有多少个不同用户具有该类别」统计，避免同一用户多次调用被重复放大。
            Map<String, Integer> distinctCategoryUsers = distinctCategoryUsers(users);
            return new UsageProfile(users.size(), distinctCategoryUsers, successCalls[0]);
        } catch (Exception e) {
            log.warn("[muse] 画像聚合失败: skill={}: {}", skillName, e.getMessage());
            return null;
        }
    }

    /** 统计命中用户集合中，每个画像类别分别覆盖多少个不同用户。 */
    private Map<String, Integer> distinctCategoryUsers(Set<String> users) {
        Map<String, Set<String>> categoryToUsers = new LinkedHashMap<>();
        for (String userId : users) {
            UserProfile profile = profileStore.getProfile(userId);
            if (profile == null || profile.getIdentities() == null) {
                continue;
            }
            for (Identity id : profile.getIdentities()) {
                if (id == null || id.getCategory() == null || id.getCategory().isBlank()) {
                    continue;
                }
                categoryToUsers.computeIfAbsent(id.getCategory(), k -> new HashSet<>()).add(userId);
            }
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : categoryToUsers.entrySet()) {
            result.put(e.getKey(), e.getValue().size());
        }
        return result;
    }

    /**
     * 判断快照是否为指定技能的工具调用节点。
     *
     * <p>技能以工具形式被模型调用，在轨迹里表现为 {@link SpanKind#TOOL_CALL} 节点，
     * 其负载携带工具名。</p>
     */
    private static boolean isToolCallOf(ReasoningSpan span, String skillName) {
        if (span == null || span.kind() != SpanKind.TOOL_CALL || span.payload() == null) {
            return false;
        }
        Object toolName = span.payload().get("toolName");
        return skillName.equals(toolName);
    }

    /**
     * 从轨迹标识解析使用者标识。
     *
     * <p>轨迹标识格式为 {@code userId:sessionId}；无使用者身份时退化为纯会话标识，
     * 此时无法参与使用者维度统计，返回 {@code null} 跳过。</p>
     */
    private static String userIdOf(String traceId) {
        if (traceId == null) {
            return null;
        }
        int sep = traceId.indexOf(':');
        if (sep <= 0) {
            return null;
        }
        String userId = traceId.substring(0, sep);
        return userId.isBlank() ? null : userId;
    }
}
