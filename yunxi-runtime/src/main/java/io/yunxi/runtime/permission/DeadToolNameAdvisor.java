package io.yunxi.runtime.permission;

import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.SpellingSuggestions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 权限清单死名顾问：比对声明工具名与终态工具名单，找出「写了但永不生效」的名字。
 *
 * <p>权限规则按字面匹配工具名，写错名字不报错——那条规则静默地永不命中
 * （存量模板曾把 {@code memory} 写进白名单，框架记忆工具实名是
 * {@code memory_search} / {@code memory_get} / {@code memory_save}）。这类错误无法从
 * 运行结果反推，只能在装配期、终态工具名单就绪后比对暴露。
 *
 * <p>对每个死名附带编辑距离最近的实名候选（过远则不给，见 {@link SpellingSuggestions}）；
 * 候选是参考，死名报告本身才是主要价值。发现死名只告警不拒启：先声明后装载是合法演进
 * 路径（工具名单随 skill / MCP 变化），与 MCP 订阅缺名 WARN 同一取舍。
 */
public final class DeadToolNameAdvisor {

    /** 一个死名及其最接近的实名候选（无足够接近者时为 null）。 */
    public record DeadName(String declared, String suggestion) {
    }

    private DeadToolNameAdvisor() {
    }

    /**
     * 扫描权限配置中按<b>工具名</b>字面匹配的清单（allowedTools / deniedTools / toolGate.tools），
     * 返回其中不存在于 {@code knownNames} 的名字。
     *
     * @param permission 权限配置，可为 null（返回空）
     * @param knownNames 终态工具名单（构建完成的工具箱实名全集，含 MCP 与 skill 派生工具）
     */
    // 死名判定区分大小写：与工具实名语义一致（Memory_Search 不等于 memory_search，确实永不命中）；
    // 候选建议则大小写不敏感（手误常见形态就是整词大小写变化，归一后比距离才给得出候选）。
    public static List<DeadName> findDeadNames(AgentSpec.PermissionConfig permission,
            Collection<String> knownNames) {
        List<DeadName> dead = new ArrayList<>();
        if (permission == null || knownNames == null || knownNames.isEmpty()) {
            return dead;
        }
        scan(dead, permission.allowedTools(), knownNames);
        scan(dead, permission.deniedTools(), knownNames);
        if (permission.toolGate() != null) {
            scan(dead, permission.toolGate().tools(), knownNames);
        }
        // reasoningReview 只扫语义复核，不扫它：其清单是触发复核的关键词而非工具名，
        // 拿关键词与工具名比编辑距离只会产出误导性建议。
        return dead;
    }

    private static void scan(List<DeadName> dead, List<String> declared, Collection<String> knownNames) {
        if (declared == null) {
            return;
        }
        for (String name : declared) {
            if (!knownNames.contains(name)) {
                dead.add(new DeadName(name, SpellingSuggestions.nearest(name, knownNames)));
            }
        }
    }
}
