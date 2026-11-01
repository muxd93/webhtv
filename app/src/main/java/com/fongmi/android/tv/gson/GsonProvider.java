package com.fongmi.android.tv.gson;

import com.google.gson.Gson;

/**
 * 全局唯一 Gson 实例来源。
 *
 * <p>所有持久化与反序列化统一走这里，便于：
 * <ul>
 *   <li>在 R8 / release 下集中保留泛型签名所需的配置（配合 proguard -keepattributes Signature）；</li>
 *   <li>未来统一注册 TypeAdapter / 数字策略 / HTML 转义策略，避免散落各处行为不一致。</li>
 * </ul>
 *
 * <p>当前配置刻意与历史行为保持一致（默认 new Gson()），不引入任何行为回归。
 */
public final class GsonProvider {

    private static final Gson GSON = new Gson();

    private GsonProvider() {
    }

    public static Gson get() {
        return GSON;
    }
}
