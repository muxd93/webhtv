package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.bean.Config;

/**
 * 多仓登记的行合并（纯逻辑，不依赖 Room/Android，便于 JVM 单测）。
 *
 * <p>约束来源：{@link ConfigRepository#upsert} 在唯一键冲突时会用传入对象整行覆盖已有行。
 * 多仓登记每次加载都会重跑，若用新建对象写库，子仓行的
 * json/time/active/home/parse/logo/kind/etag 会被清零——其中 time=0 会让 24 小时缓存永远判过期。
 * 因此已有行必须复用原对象，只更新名称与类型标记。
 */
final class DepotRow {

    private DepotRow() {
    }

    /**
     * 复用已落库的行：只更新名称（非空时）与 kind，其余字段保持原值。
     *
     * @return 可复用的行；不存在（null 或 id 无效）时返回 null，由调用方新建
     */
    static Config reuse(Config existing, String url, int type, String name, int kind) {
        if (existing == null || existing.getId() <= 0) return null;
        existing.setType(type);
        existing.setUrl(url);
        if (name != null && !name.isEmpty()) existing.setName(name);
        // kind 只升不降：目标为 DEPOT 必写；目标为 SINGLE 时保留已有的 DEPOT（嵌套多仓不被降级）
        if (kind == Config.DEPOT || existing.getKind() == Config.SINGLE) existing.setKind(kind);
        return existing;
    }

    /** 已有行优先复用，否则新建一行 */
    static Config resolve(Config existing, String url, int type, String name, int kind) {
        Config reused = reuse(existing, url, type, name, kind);
        return reused != null ? reused : new Config().type(type).kind(kind).url(url).name(name);
    }
}
