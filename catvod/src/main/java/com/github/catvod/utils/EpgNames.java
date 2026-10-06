package com.github.catvod.utils;

import java.util.Locale;

/**
 * 频道名归一化,仅用于 EPG 匹配的兜底索引(精确命中优先,归一化只兜底,不覆盖精确语义)。
 * 折叠规则:小写 → 简繁归一(强制转简,与设备语言无关)→ 全角转半角 → 去空白与常见分隔符。
 * 纯 Java,无 Android 依赖,JVM 可测。
 */
public class EpgNames {

    public static String normalize(String name) {
        if (name == null) return "";
        String text = Trans.t2s(false, name.trim().toLowerCase(Locale.ROOT));
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '！' && c <= '～') c = (char) (c - 0xFEE0);
            if (!isSeparator(c)) sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isSeparator(char c) {
        if (Character.isWhitespace(c) || c == 0x3000) return true;
        return c == '-' || c == '_' || c == '.' || c == '(' || c == ')' || c == '[' || c == ']' || c == '~'
                || c == '（' || c == '）' || c == '【' || c == '】' || c == '《' || c == '》' || c == '「' || c == '」'
                || c == '·' || c == '・' || c == '—' || c == '–' || c == '、' || c == '　';
    }
}
