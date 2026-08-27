package io.yunxi.platform.intent.util;

import java.util.List;

import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.classify.IntentTree;

/**
 * 意图关键词匹配公共工具。
 *
 * <p>统一 RuleIntentClassifier（matchNode / normalizedScore / bonusCount）与
 * LlmIntentClassifier（keywordScore）的关键词命中判定，消除重复。语义约定：
 * allKeywords 组内 AND、组间 OR；anyKeywords 任一命中；null/空集合视为"无约束"（恒通过）。
 * 抽取时逐行保持原行为，规则通道冻结线不变。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public final class IntentKeywordMatcher {

    private IntentKeywordMatcher() {
    }

    /** 查询归一化（null → ""），供各匹配方法共用 */
    public static String lower(String query) {
        return query == null ? "" : query.toLowerCase();
    }

    /**
     * 单组命中判定：组内所有关键词全部出现（组内 AND）。
     * 空组恒 true（与旧实现一致：无词可检查）。
     */
    public static boolean groupHit(List<String> group, String lower) {
        for (String w : group) {
            if (!lower.contains(w.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    /**
     * allKeywords 命中判定：组间 OR，任一组内全中即 true。
     * null/空返回 true（无约束）。
     */
    public static boolean allGroupHit(List<List<String>> allKeywords, String lower) {
        if (allKeywords == null || allKeywords.isEmpty()) {
            return true;
        }
        for (List<String> group : allKeywords) {
            if (groupHit(group, lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * anyKeywords 命中判定：任一命中即 true。
     * null/空返回 true（无约束）。
     */
    public static boolean anyKeywordHit(List<String> anyKeywords, String lower) {
        if (anyKeywords == null || anyKeywords.isEmpty()) {
            return true;
        }
        for (String w : anyKeywords) {
            if (lower.contains(w.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 命中的 allKeywords 组数（normalizedScore 降档用） */
    public static int hitAllGroupCount(List<List<String>> allKeywords, String lower) {
        if (allKeywords == null || allKeywords.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<String> group : allKeywords) {
            if (groupHit(group, lower)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 节点关键词粗筛分（LLM 白名单 Top-N 用）：
     * anyKeywords 命中数 + allKeywords 命中组关键词数。match 为 null 返回 0。
     */
    public static int keywordScore(IntentTree.Match match, String lower) {
        if (match == null) {
            return 0;
        }
        int score = 0;
        if (match.anyKeywords != null) {
            for (String w : match.anyKeywords) {
                if (lower.contains(w.toLowerCase())) {
                    score++;
                }
            }
        }
        if (match.allKeywords != null) {
            for (List<String> group : match.allKeywords) {
                if (groupHit(group, lower)) {
                    score += group.size();
                }
            }
        }
        return score;
    }

    /**
     * 实体加分计数：match.entities 中 type 与 NER 实体任一 type 相同则 +1
     * （规则模式纯加分语义）。
     */
    public static int bonusCount(IntentTree.Match match, List<Entity> entities) {
        if (match == null || match.entities == null || match.entities.isEmpty() || entities == null) {
            return 0;
        }
        int bonus = 0;
        for (IntentTree.EntityCond cond : match.entities) {
            if (cond.type == null) {
                continue;
            }
            for (Entity e : entities) {
                if (cond.type.equals(e.type())) {
                    bonus++;
                    break;
                }
            }
        }
        return bonus;
    }
}
