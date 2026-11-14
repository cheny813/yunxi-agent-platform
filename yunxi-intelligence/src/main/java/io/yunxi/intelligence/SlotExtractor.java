package io.yunxi.intelligence;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能平面。槽位实体抽取：内置常见槽位（订单号 / 日期区间 / 金额 / 手机号 / 邮箱），
 * 配置槽位按同名覆盖。抽取值为规范化形式（相对日期对齐注入时钟，便于测试与下游复用）。
 *
 * <p>规则抽取可复现、可解释，值优先级高于模型输出的实体（合并规则由引擎执行）。</p>
 */
public class SlotExtractor {

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final Map<String, String> BUILTIN = Map.ofEntries(
            Map.entry("orderId", "(?:订单号|单号|订单|order)\\s*[:：#]?\\s*([A-Za-z]{0,4}\\d{5,})"),
            Map.entry("dateRange", "(上个月|上月|本月|这个月|今天|今日|昨天|昨日|近\\s*\\d+\\s*天|最近\\s*\\d+\\s*天|过去\\s*\\d+\\s*天)"),
            Map.entry("amount", "(?:金额|费用|共计)\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:元|块|¥|￥)"),
            Map.entry("phone", "(?<![0-9])1[3-9]\\d{9}(?![0-9])"),
            Map.entry("email", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));

    private final Map<String, Pattern> patterns;
    private final Clock clock;

    /** 以默认系统时钟构造。 */
    public SlotExtractor(Map<String, String> configuredSlots) {
        this(configuredSlots, Clock.systemDefaultZone());
    }

    /** 以注入时钟构造（测试可固定日期）。 */
    public SlotExtractor(Map<String, String> configuredSlots, Clock clock) {
        this.clock = clock;
        Map<String, Pattern> merged = new LinkedHashMap<>();
        BUILTIN.forEach((name, regex) -> merged.put(name, Pattern.compile(regex)));
        if (configuredSlots != null) {
            configuredSlots.forEach((name, regex) -> {
                if (name != null && regex != null && !regex.isBlank()) {
                    merged.put(name, Pattern.compile(regex));
                }
            });
        }
        this.patterns = Map.copyOf(merged);
    }

    /** 从归一化文本抽取实体；每个槽位取首个匹配，输出为规范化值。 */
    public Map<String, String> extract(String normalizedText) {
        Map<String, String> out = new LinkedHashMap<>();
        if (normalizedText == null || normalizedText.isBlank()) {
            return out;
        }
        for (Map.Entry<String, Pattern> e : patterns.entrySet()) {
            Matcher m = e.getValue().matcher(normalizedText);
            if (m.find()) {
                String value = canonical(e.getKey(), m.groupCount() >= 1 ? m.group(1) : m.group());
                if (value != null && !value.isBlank()) {
                    out.put(e.getKey(), value);
                }
            }
        }
        return out;
    }

    private String canonical(String slot, String value) {
        return switch (slot) {
            case "dateRange" -> canonicalDate(value.replaceAll("\\s+", ""));
            case "phone" -> value.replaceAll("\\D", "");
            default -> value.trim();
        };
    }

    /** 相对日期对齐注入时钟：上个月/本月 → yyyy-MM；今天/昨天 → ISO 日期；近 N 天 → since:yyyy-MM-dd。 */
    private String canonicalDate(String value) {
        LocalDate today = LocalDate.now(clock);
        if (value.startsWith("上个月") || value.startsWith("上月")) {
            return today.minusMonths(1).format(YEAR_MONTH);
        }
        if (value.startsWith("本月") || value.startsWith("这个月")) {
            return today.format(YEAR_MONTH);
        }
        if (value.startsWith("今天") || value.startsWith("今日")) {
            return today.toString();
        }
        if (value.startsWith("昨天") || value.startsWith("昨日")) {
            return today.minusDays(1).toString();
        }
        Matcher n = Pattern.compile("(\\d+)").matcher(value);
        if (n.find()) {
            int days = Integer.parseInt(n.group(1));
            return "since:" + today.minusDays(days).toString();
        }
        return value;
    }
}
