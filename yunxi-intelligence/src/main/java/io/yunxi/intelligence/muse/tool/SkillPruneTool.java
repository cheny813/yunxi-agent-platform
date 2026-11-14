package io.yunxi.intelligence.muse.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.intelligence.muse.engine.SelfEvolutionEngine;
import io.yunxi.intelligence.muse.prune.SkillPruner.PruneDecision;
import io.yunxi.intelligence.muse.prune.SkillPruner.SkillMeta;
import io.yunxi.runtime.Json;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MUSE 元工具：对技能库做剪枝/合并体检（低使用率归档 + 相似度合并）。
 *
 * <p>入参为 JSON 数组，每项含 name/usage_count/content。返回各项处置决策。</p>
 *
 * <p><b>画像维度说明</b>：原 agent-muse 版本会额外从「用户画像」只读聚合 {@code UsageProfile}
 * 升级「使用次数」判据为「使用者广度」判据。yunxi 产品平面当前尚无画像模块，
 * 该只读聚合器（UsageProfileAggregator）暂不接入，剪枝器退化为原有的「使用率 + 相似度」两条判据，
 * 行为与未引入画像特性时完全一致。画像维度待 yunxi 画像平面就绪后单独接线。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SkillPruneTool {

    private static final ObjectMapper MAPPER = Json.objectMapper();
    private final SelfEvolutionEngine engine;

    @Tool(name = "muse_skill_prune",
            description = "对技能库做剪枝体检：返回每个技能的处置（保留/归档/合并/需泛化）。"
                    + "skills_json 为 JSON 数组，每项 {name, usage_count, content}。")
    public String prune(
            @ToolParam(name = "skills_json", description = "JSON 数组，每项 {name, usage_count, content}") String json) {
        try {
            List<SkillMeta> metas = MAPPER.readValue(json, new TypeReference<List<SkillMeta>>() {});
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
}
