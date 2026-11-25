package io.yunxi.runtime;

import java.util.Collection;

/**
 * 手误候选名建议的唯一实现：在候选名单里找与输入最接近者，距离过远则不猜。
 *
 * <p>同一算法服务两个消费方，本类是它们的单一真相源：
 * <ul>
 *   <li>定义期：{@code AgentSpecYamlReader} 对未知<b>字段名</b>给出建议；</li>
 *   <li>装配期：{@code DeadToolNameAdvisor} 对权限清单里不存在的<b>工具名</b>给出建议。</li>
 * </ul>
 *
 * <p>两处场景的风险相同——人写的名字拼错后不报错、对应配置整体失效——因此「像不像手误」
 * 的判定也必须一致，任何一方调阈值都应看到另一方的行为同步变化。
 */
public final class SpellingSuggestions {

    /**
     * 建议的最大相对编辑距离：距离 / 较长名字长度。超过即不像手误，不猜（乱猜比不猜更误导）。
     *
     * <p>用比例而非绝对距离，是因为手误的"绝对距离"随名字变长而变大：{@code memory_serch} →
     * {@code memory_search} 只差 1 次编辑（一眼手误），而 {@code memory} → {@code memory_search}
     * 相差 7 次（更像是记错了名字而不是拼错了字），只有按长度归一才两头都对。
     */
    public static final double MAX_SUGGESTION_RATIO = 0.3;

    private SpellingSuggestions() {
    }

    /**
     * 在候选名单中找与输入最接近者；距离过远（不像手误）则返回 null。
     *
     * <p>不区分大小写比较：工具名与字段名这类标识符的常见手误是整词小写变化，
     * 按原样比距离会高估差异，故先归一大小写再看距离。
     */
    public static String nearest(String input, Collection<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestLength = 0;
        for (String candidate : candidates) {
            int distance = editDistance(input.toLowerCase(), candidate.toLowerCase());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
                bestLength = Math.max(input.length(), candidate.length());
            }
        }
        if (best == null || bestLength == 0) {
            return null;
        }
        return (double) bestDistance / bestLength <= MAX_SUGGESTION_RATIO ? best : null;
    }

    /** 标准编辑距离（插入 / 删除 / 替换各计 1）；标识符都很短，二次复杂度无实际影响。 */
    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
