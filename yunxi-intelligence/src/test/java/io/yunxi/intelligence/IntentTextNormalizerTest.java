package io.yunxi.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 归一化：全角转半角、控制字符与连续空白折叠、大小写不改变语义（匹配层自行小写）。 */
class IntentTextNormalizerTest {

    private final IntentTextNormalizer normalizer = new IntentTextNormalizer();

    @Test
    void collapsesWhitespaceAndControlChars() {
        assertEquals("hello world foo", normalizer.normalize("hello\u0007  world \t\n foo"));
    }

    @Test
    void convertsFullWidthAsciiToHalfWidth() {
        assertEquals("ABC-123", normalizer.normalize("ＡＢＣ－１２３"));
    }

    @Test
    void convertsIdeographicSpaceToSingleSpace() {
        assertEquals("a b", normalizer.normalize("a\u3000b"));
    }

    @Test
    void keepsCjkPunctuationUnchanged() {
        // CJK 标点（、。等，不在全角 ASCII 区间）保留原样，不做半角化。
        assertEquals("帮我看看订单、谢谢。", normalizer.normalize("帮我看看订单、谢谢。"));
    }

    @Test
    void blankInputStaysBlank() {
        assertEquals("", normalizer.normalize("  \u000b "));
        assertEquals("", normalizer.normalize(null));
    }
}
