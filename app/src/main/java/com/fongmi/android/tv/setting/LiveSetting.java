package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

public class LiveSetting {

    private static final int LIST_STYLE_GLASS = 0;
    private static final int LIST_STYLE_CLASSIC = 1;

    public static boolean isBoot() {
        return Prefers.getBoolean("boot_live");
    }

    public static void putBoot(boolean boot) {
        Prefers.put("boot_live", boot);
    }

    public static boolean isAcross() {
        return Prefers.getBoolean("across", true);
    }

    public static void putAcross(boolean across) {
        Prefers.put("across", across);
    }

    public static boolean isChange() {
        return Prefers.getBoolean("change", true);
    }

    public static void putChange(boolean change) {
        Prefers.put("change", change);
    }

    public static boolean isInvert() {
        return Prefers.getBoolean("invert");
    }

    public static void putInvert(boolean invert) {
        Prefers.put("invert", invert);
    }

    public static boolean isAutoHop() {
        return Prefers.getBoolean("auto_hop", true);
    }

    public static void putAutoHop(boolean hop) {
        Prefers.put("auto_hop", hop);
    }

    /** 多仓聚合池开关（DEPOT3）：收集子仓直播条目与广告规则；默认开，关闭即回退既有行为。 */
    public static boolean isPool() {
        return Prefers.getBoolean("depot_pool", true);
    }

    public static void putPool(boolean pool) {
        Prefers.put("depot_pool", pool);
    }

    /** 用户起播超时档位(毫秒);0=跟随直播源声明。 */
    public static int getTimeout() {
        return Prefers.getInt("live_timeout", 0);
    }

    public static void putTimeout(int timeout) {
        Prefers.put("live_timeout", timeout);
    }

    public static int getScale() {
        return Prefers.getInt("scale_live", PlayerSetting.getScale());
    }

    public static void putScale(int scale) {
        Prefers.put("scale_live", scale);
    }

    public static int getListStyle() {
        int style = Prefers.getInt("live_list_style", LIST_STYLE_CLASSIC);
        return style == LIST_STYLE_CLASSIC ? LIST_STYLE_CLASSIC : LIST_STYLE_GLASS;
    }

    public static boolean isListStyleClassic() {
        return getListStyle() == LIST_STYLE_CLASSIC;
    }

    public static void putListStyle(int style) {
        Prefers.put("live_list_style", style == LIST_STYLE_CLASSIC ? LIST_STYLE_CLASSIC : LIST_STYLE_GLASS);
    }

    public static void putListStyleClassic(boolean classic) {
        putListStyle(classic ? LIST_STYLE_CLASSIC : LIST_STYLE_GLASS);
    }

    public static void toggleListStyle() {
        putListStyle(isListStyleClassic() ? LIST_STYLE_GLASS : LIST_STYLE_CLASSIC);
    }
}
