package io.yunxi.platform.muse.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.platform.muse.engine.SelfEvolutionEngine;
import io.yunxi.platform.muse.prune.SkillPruner;
import io.yunxi.platform.muse.prune.SkillPruner.PruneDecision;
import io.yunxi.platform.muse.prune.SkillPruner.SkillMeta;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MUSE 元工具：对技能库做剪枝/合并体检（低使用率归档 + 相似度合并）。
 *
 * <p>入参为 JSON 数组，每项含 name/usage_count/content。返回各项处置决策。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SkillPruneTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SelfEvolutionEngine engine;

    @Tool(name = "muse_skill_prune",
            description = "对技能库做剪枝体检：返回每个技能的处置（保留/归档/合并）。"
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
