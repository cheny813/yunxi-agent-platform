package io.yunxi.platform.muse.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.platform.muse.engine.SelfEvolutionEngine;
import io.yunxi.platform.muse.prune.SkillPruner.PruneDecision;
import io.yunxi.platform.muse.prune.SkillPruner.SkillMeta;
import io.yunxi.platform.muse.prune.UsageProfile;
import io.yunxi.platform.muse.prune.UsageProfileAggregator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MUSE 元工具：对技能库做剪枝/合并体检（低使用率归档 + 相似度合并 + 画像驱动判据）。
 *
 * <p>入参为 JSON 数组，每项含 name/usage_count/content。返回各项处置决策。</p>
 *
 * <p><b>画像维度自动补充</b>：入参不含使用者信息，本工具会以入参技能名为准，
 * 从轨迹与用户画像<b>只读</b>聚合出 {@link UsageProfile} 后交给剪枝器，
 * 使「使用次数」判据升级为「使用者广度」判据。聚合不可用时保持原有两条判据不变。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SkillPruneTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SelfEvolutionEngine engine;
    /** 使用者画像聚合器；MUSE 关闭或组件缺失时为空，此时跳过画像补充。 */
    private final ObjectProvider<UsageProfileAggregator> aggregatorProvider;

    @Tool(name = "muse_skill_prune",
            description = "对技能库做剪枝体检：返回每个技能的处置（保留/归档/合并/需泛化）。"
                    + "skills_json 为 JSON 数组，每项 {name, usage_count, content}。"
                    + "系统会自动参考各技能的使用者广度（只读用户画像）修正判定。")
    public String prune(
            @ToolParam(name = "skills_json", description = "JSON 数组，每项 {name, usage_count, content}") String json) {
        try {
            List<SkillMeta> metas = MAPPER.readValue(json, new TypeReference<List<SkillMeta>>() {});
            enrichWithUsageProfile(metas);
            List<PruneDecision> decisions = engine.getPruner().review(metas);
            StringBuilder sb = new StringBuilder("剪枝体检结果:\n");
            for (PruneDecision d : decisions) {
                sb.append("- ").append(d.getName()).append(" => ").append(d.getAction());
                if (d.getMergeWith() != null) sb.append(" -> ").append(d.getMergeWith());
                sb.append(" (").append(d.getReason()).append(")\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "解析 skills_json 失败: " + e.getMessage();
        }
    }

    /**
     * 为入参技能补充使用者画像特征（只读）：
     * 未自带 {@code usageProfile} 的技能，按技能名从轨迹与画像聚合。
     * 聚合失败或组件缺失时静默跳过——剪枝退化为原有两条判据。
     */
    private void enrichWithUsageProfile(List<SkillMeta> metas) {
        UsageProfileAggregator aggregator = aggregatorProvider.getIfAvailable();
        log.info("[muse] 画像补充入口: skills={}, aggregatorPresent={}", metas.size(), aggregator != null);
        if (aggregator == null) {
            return;
        }
        for (SkillMeta meta : metas) {
            if (meta.getUsageProfile() != null || meta.getName() == null) {
                continue;
            }
            try {
                UsageProfile up = aggregator.aggregate(meta.getName());
                log.info("[muse] 技能 {} 画像聚合结果: {}", meta.getName(),
                        up == null ? "null(降级)" : ("distinctUsers=" + up.getDistinctUsers()
                                + ", successCalls=" + up.getSuccessCalls()
                                + ", categories=" + up.getCategoryDistribution()));
                if (up != null) {
                    meta.setUsageProfile(up);
                }
            } catch (Exception e) {
                log.warn("[muse] 技能 {} 画像聚合异常: {}", meta.getName(), e.getMessage());
            }
        }
    }
}
