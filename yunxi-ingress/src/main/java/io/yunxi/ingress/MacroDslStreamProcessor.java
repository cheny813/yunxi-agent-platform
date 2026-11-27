package io.yunxi.ingress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * @macroui/a2ui DSL 流末分流器（传输层）：从模型正文提取 &lt;macro-a2ui&gt; 哨兵块，
 * 组装 SSE 命名事件 macro-dsl 的载荷。
 *
 * <p>职责边界：DSL 解析、组件渲染、资源保护全部由前端 @macroui/a2ui 承担（其 parser
 * 内建 maxBuffer=1MB / maxDepth=100），本类只做纯文本提取与载荷组装，不复制任何 DSL
 * 语义。与 {@link A2uiStreamProcessor}（Google A2UI 1.0）并存，互不干扰。</p>
 *
 * <p>由应用装配点（RuntimeConfiguration）声明为 Bean。</p>
 */
public class MacroDslStreamProcessor {

    private static final String OPEN = "<macro-a2ui>";
    private static final String CLOSE = "</macro-a2ui>";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 提取正文中所有宏 DSL 块并按出现顺序拼接；无完整块返回 null。 */
    public JsonNode split(String finalText) {
        if (finalText == null) {
            return null;
        }
        StringBuilder dsl = new StringBuilder();
        String rest = finalText;
        while (true) {
            int open = rest.indexOf(OPEN);
            if (open < 0) {
                break;
            }
            int close = rest.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                // 流式截断：未闭合块不完整，忽略（不缓存半个块）
                break;
            }
            dsl.append(rest, open + OPEN.length(), close);
            rest = rest.substring(close + CLOSE.length());
        }
        if (dsl.length() == 0) {
            return null;
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("dsl", dsl.toString());
        return payload;
    }
}
