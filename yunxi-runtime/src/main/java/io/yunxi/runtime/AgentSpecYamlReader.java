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
        String suggestion = SpellingSuggestions.nearest(field, known);

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
}
