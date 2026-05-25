package io.yunxi.platform.shared.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 推理审查配置
 *
 * <p>控制 LLM 推理结果是否需要人类审查后再执行。支持三种审查策略：
 * <ul>
 *   <li><b>all</b> — 每次推理都需要人类审查</li>
 *   <li><b>on-dangerous-tool</b> — 仅在 LLM 决定调用危险工具时需要审查</li>
 *   <li><b>keyword-match</b> — 推理内容包含敏感关键词时需要审查</li>
 * </ul>
 *
 * <pre>
 * hitl:
 *   reasoningReview:
 *     enabled: true
 *     strategy: "on-dangerous-tool"
 *     keywords: ["删除", "支付", "审批"]
 * </pre>
 *
 * @author yunxi-platform
 */
public class ReasoningReviewConfig {

    /** 是否启用推理审查 */
    private boolean enabled = false;

    /** 审查策略：all | on-dangerous-tool | keyword-match */
    private String strategy = "on-dangerous-tool";

    /** strategy=keyword-match 时的敏感关键词列表 */
    private List<String> keywords = new ArrayList<>();

    public ReasoningReviewConfig() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }
}