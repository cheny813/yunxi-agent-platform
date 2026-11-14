package io.yunxi.intelligence;

/**
 * 智能平面。文本归一化：全角 ASCII 转半角、控制字符与连续空白折叠为单空格、去首尾空白。
 * 中文标点保留原样（避免破坏中文语义匹配）。归一化结果用于关键词匹配与分类输入。
 */
public final class IntentTextNormalizer {

    /** 归一化文本。null 视为空串。 */
    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean lastSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            char c = toHalfWidth(ch);
            if (Character.isISOControl(c)) {
                c = ' ';
            }
            if (Character.isWhitespace(c)) {
                if (lastSpace) {
                    continue;
                }
                c = ' ';
                lastSpace = true;
            } else {
                lastSpace = false;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 全角 ASCII（U+FF01–U+FF5E）与全角空格（U+3000）转半角；其余原样。 */
    private char toHalfWidth(char ch) {
        if (ch == '\u3000') {
            return ' ';
        }
        if (ch >= '\uFF01' && ch <= '\uFF5E') {
            return (char) (ch - 0xFEE0);
        }
        return ch;
    }
}
