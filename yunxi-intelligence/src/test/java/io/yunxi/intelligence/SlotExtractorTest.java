package io.yunxi.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 槽位抽取：内置槽位 + 配置覆盖 + 日期规范化（时钟注入可测）。 */
class SlotExtractorTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-11-13T08:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final SlotExtractor extractor = new SlotExtractor(Map.of(), FIXED);

    @Test
    void extractsOrderIdPhoneEmail() {
        Map<String, String> got =
                extractor.extract("帮我看看订单 A123456 到哪了，手机 13812345678，邮箱 a.b@test.com");
        assertEquals("A123456", got.get("orderId"));
        assertEquals("13812345678", got.get("phone"));
        assertEquals("a.b@test.com", got.get("email"));
    }

    @Test
    void canonicalizesRelativeDateAgainstClock() {
        Map<String, String> got = extractor.extract("上个月的账单");
        assertEquals(LocalDate.of(2026, 10, 1).toString().substring(0, 7), got.get("dateRange"));

        Map<String, String> today = extractor.extract("今天有什么安排");
        assertEquals(LocalDate.of(2026, 11, 13).toString(), today.get("dateRange"));
    }

    @Test
    void lastNDaysBecomesSinceDate() {
        Map<String, String> got = extractor.extract("近 3 天的支出");
        assertEquals("since:2026-11-10", got.get("dateRange"));
    }

    @Test
    void missingSlotsAreAbsent() {
        Map<String, String> got = extractor.extract("你好");
        assertTrue(got.isEmpty());
    }

    @Test
    void configuredSlotOverridesBuiltin() {
        SlotExtractor custom = new SlotExtractor(Map.of("sku", "SKU[-:]\\s*([A-Za-z0-9]+)"), FIXED);
        Map<String, String> got = custom.extract("查一下 SKU-XY99");
        assertEquals("XY99", got.get("sku"));
        assertFalse(got.containsKey("orderId"));
    }
}
