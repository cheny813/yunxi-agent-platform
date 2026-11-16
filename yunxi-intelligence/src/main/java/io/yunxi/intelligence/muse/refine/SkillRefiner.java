package io.yunxi.intelligence.muse.refine;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.intelligence.muse.model.EvalReport;
import io.yunxi.runtime.ModelFactory;
import io.yunxi.runtime.DistributedRuntimeConfig;
import io.yunxi.intelligence.muse.model.TestCaseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 技能精炼器：按评估报告的失败反馈，让 LLM 修补技能正文。
 *
 * <p>仅做「评估反馈 -> 修补技能正文」这一 AgentScope 不覆盖的环节；
 * 模型调用复用 AgentScope 的 {@link Model#stream}，技能持久化复用 AgentScope 的
 * {@link AgentSkillRepository}。修补后的正文始终写回磁盘（评估器读磁盘跑测试），
 * 若已配置技能仓库则同时入库，供运行时加载。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SkillRefiner {

    private final ModelFactory modelFactory;
    private final ObjectProvider<AgentSkillRepository> repoProvider;
    private final EvolutionConfig config;
    private final DistributedRuntimeConfig runtimeConfig;

    /** 技能演进写锁键前缀：按「技能名」粒度互斥，tenant 前缀位预留（默认 default）。 */
    private static final String SKILL_LOCK_PREFIX = "muse:skill:default:";

    /**
     * 按评估报告的失败反馈，让 LLM 修补技能正文。
     *
     * @return 修补后的技能正文（markdown）
     */
    public String refine(String skillName, String currentContent, EvalReport report, int iteration) {
        Model model = modelFactory.create(config.getModel());
        if (model == null) {
            log.warn("[muse] skill={} 模型 {} 解析失败，跳过本轮修补", skillName, config.getModel());
            return currentContent;
        }
        String prompt = buildRefinePrompt(skillName, currentContent, report, iteration);
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.USER).textContent(prompt).build());
        List<ToolSchema> noTools = List.of();
        StringBuilder sb = new StringBuilder();
        Flux<ChatResponse> flux = model.stream(messages, noTools, GenerateOptions.builder().build());
        flux.doOnNext(r -> sb.append(extractText(r))).blockLast();
        String refined = sb.toString().strip();
        if (refined.isEmpty()) {
            log.warn("[muse] skill={} 第{}轮修补返回为空，沿用上一版", skillName, iteration);
            return currentContent;
        }
        return refined;
    }

    /**
     * 把修补后的技能正文持久化：按技能名粒度进轮次闸门（多副本演进同一技能互斥，
     * 防止 last-write-wins 互相覆盖），闸门内写回磁盘并入库。
     *
     * @return true = 已保存；false = 闸门忙（其他副本正在演进该技能，等待下一轮重试）或写入失败
     */
    public boolean persist(String skillName, String content, String skillDir) {
        SessionTurnGate gate = runtimeConfig == null ? null : runtimeConfig.sessionTurnGate();
        if (gate == null) {
            log.warn("[muse] skill={} 运行时未提供轮次闸门（gate 缺失），退化为无锁直写（多副本部署可能互相覆盖）", skillName);
            writeAll(skillName, content, skillDir);
            return true;
        }
        try (TurnLease lease = gate.acquire(SKILL_LOCK_PREFIX + skillName)) {
            writeAll(skillName, content, skillDir);
            return true;
        } catch (TurnBusyException e) {
            log.warn("[muse] skill={} 演进锁被占用（其他副本正在演进），本轮跳过保存等待重试", skillName);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[muse] skill={} 等待演进锁时被中断", skillName);
            return false;
        } catch (RuntimeException e) {
            log.warn("[muse] skill={} 保存失败（锁已释放）: {}", skillName, e.getMessage());
            return false;
        }
    }

    private void writeAll(String skillName, String content, String skillDir) {
        if (skillDir != null && !skillDir.isBlank()) {
            try {
                Path dir = Path.of(skillDir);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("SKILL.md"), content);
            } catch (IOException e) {
                throw new UncheckedIOException("技能正文写回磁盘失败: " + skillName, e);
            }
        }
        AgentSkillRepository repo = repoProvider.getIfAvailable();
        if (repo != null) {
            AgentSkill skill = AgentSkill.builder()
                    .name(skillName)
                    .description(skillName + " (MUSE 自进化生成)")
                    .skillContent(content)
                    .source("muse")
                    .build();
            repo.save(List.of(skill), true);
        }
    }

    /**
     * 构造 LLM 修补提示词：注入当前技能正文 + 失败用例名与 stderr/stdout 明细。
     *
     * @param skillName     技能名
     * @param currentContent 当前技能 markdown 正文
     * @param report        评估报告（含失败用例明细）
     * @param iteration     当前迭代轮数
     * @return 可直接发送给 LLM 的修补提示词
     */
    private String buildRefinePrompt(String skillName, String currentContent,
                                     EvalReport report, int iteration) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是技能精炼器。下面是一个名为 \"").append(skillName)
          .append("\" 的技能正文（markdown）。它附属的自动化测试未全部通过，请根据【测试反馈】修补正文，")
          .append("使测试通过。只输出修补后的完整技能正文（保持 markdown 与 frontmatter 结构），不要解释。\n\n");
        sb.append("【当前技能正文】\n").append(currentContent).append("\n\n");
        sb.append("【测试反馈】第 ").append(iteration).append(" 轮评估：")
          .append(report.getSummary()).append("\n");
        List<TestCaseResult> fails = new ArrayList<>();
        for (TestCaseResult c : report.getCases()) {
            if (!c.isPassed()) fails.add(c);
        }
        if (!fails.isEmpty()) {
            sb.append("失败用例明细：\n");
            for (TestCaseResult c : fails) {
                sb.append("- ").append(c.getName()).append("\n");
                String detail = (c.getStderr() != null && !c.getStderr().isBlank())
                        ? c.getStderr() : (c.getStdout() != null ? c.getStdout() : "");
                if (detail != null && !detail.isBlank()) {
                    sb.append("  ").append(truncate(detail, 1500).replace("\n", "\n  ")).append("\n");
                }
            }
        }
        sb.append("\n请直接输出修补后的完整技能正文。");
        return sb.toString();
    }

    /**
     * 从 AgentScope {@link ChatResponse} 中提取文本聚合。
     */
    private String extractText(ChatResponse r) {
        if (r == null || r.getContent() == null) return "";
        StringBuilder s = new StringBuilder();
        for (ContentBlock b : r.getContent()) {
            if (b instanceof TextBlock tb) {
                s.append(tb.getText());
            }
        }
        return s.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
