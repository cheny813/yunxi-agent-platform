package io.yunxi.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.yunxi.definition.AgentSpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Agent 定义文件的唯一解析入口（严格模式）。
 *
 * <p>定义文件是<b>人写</b>的：拼错字段名、同一段写两遍，都不会在运行期表现出任何异常，只会让
 * 写下的那段配置<b>整体消失</b>。最危险的一例是 {@code permission} 写成 {@code permissions}——
 * 宽松解析会丢弃整段，权限策略只能由运行期代码猜测（全拒或全放行，都没有报错），而作者以为已经设了限制。
 * 这类错误无法从运行结果反推，只能靠解析期拒绝。
 *
 * <p>因此本入口只做两件事，且都是「宁可失败」：
 * <ul>
 *   <li>未知字段直接失败，并给出<b>最可能的正确字段名</b>（编辑距离最近者），把「配置没生效」
 *       变成一句可照做的修改提示；</li>
 *   <li>同名段重复声明直接失败，而不是静默取后者——否则前一份限制被无声覆盖。</li>
 * </ul>
 *
 * <p>严格性只适用于「人写的输入」这一侧（当前是随发布包交付的定义文件；若将来开放上传入口同样适用）。
 * 共享存储中已存定义的反序列化不走本入口：滚动升级期间新旧版本会互读对方写入的定义，那边必须保持宽松，
 * 否则新版本新增字段会让旧实例整个定义解析失败。
 */
public final class AgentSpecYamlReader {

    /**
     * 建议字段名的最大相对编辑距离：距离 / 较长字段名长度。超过即不像手误，不猜（乱猜比不猜更误导）。
     *
     * <p>用比例而非绝对距离，是因为手误的"绝对距离"随字段名变长而变大：{@code denyTools} →
     * {@code deniedTools} 相差 3 次编辑（短名里算很远，长名里就是手误），只有按长度归一才两头都对。
     */
    private static final double MAX_SUGGESTION_RATIO = 0.3;

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);

    private AgentSpecYamlReader() {
    }

    /**
     * 解析一份 Agent 定义。
     *
     * @throws IOException 字段名不被识别、同层字段重复、类型不匹配等任何结构问题都会抛出，
     *     异常消息面向定义作者（含行号与建议字段名），可直接回显到编辑界面。
     */
    public static AgentSpec read(InputStream in) throws IOException {
        try {
            return MAPPER.readValue(in, AgentSpec.class);
        } catch (UnrecognizedPropertyException e) {
            throw new IOException(describeUnknownField(e), e);
        }
    }

    /** 把 Jackson 的「不认识的字段」翻译成作者能直接照做的提示。 */
    private static String describeUnknownField(UnrecognizedPropertyException e) {
        String field = e.getPropertyName();
        List<String> known = e.getKnownPropertyIds().stream().map(String::valueOf).toList();
        String suggestion = nearestName(field, known);

        StringBuilder message = new StringBuilder();
        message.append("未知字段 '").append(field).append("'");
        if (e.getReferringClass() != null) {
            message.append("（").append(e.getReferringClass().getSimpleName()).append(" 中没有该字段）");
        }
        if (e.getLocation() != null) {
            message.append("，第 ").append(e.getLocation().getLineNr()).append(" 行");
        }
        message.append("。字段名必须精确匹配：拼错的整段配置会被视为不存在，"
                + "限制类字段（如权限、工具白名单）写错等于没有限制。");
        if (suggestion != null) {
            message.append("是否想写 '").append(suggestion).append("'？");
        }
        message.append("该层可用字段：").append(String.join(" / ", known));
        return message.toString();
    }

    /**
     * 在已知字段中找与输入最接近者；距离过远（不像手误）则不猜。
     *
     * <p>不区分大小写比较：{@code ToolGate} 与 {@code toolGate} 这类差异是常见手误，
     * 按原样比距离会被算成 1 而恰好可提示，但全大写写法会被算成很远，故先归一大小写再看距离。
     */
    private static String nearestName(String field, List<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestLength = 0;
        for (String candidate : candidates) {
            int distance = editDistance(field.toLowerCase(), candidate.toLowerCase());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
                bestLength = Math.max(field.length(), candidate.length());
            }
        }
        if (best == null || bestLength == 0) {
            return null;
        }
        return (double) bestDistance / bestLength <= MAX_SUGGESTION_RATIO ? best : null;
    }

    /** 标准编辑距离（插入 / 删除 / 替换各计 1）；字段名都很短，二次复杂度无实际影响。 */
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
