package com.fongmi.android.tv.api.config;

import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.function.Predicate;

import javax.net.ssl.SSLException;

/**
 * 多仓子配置的失败推进（纯逻辑，不依赖 Android/网络，便于单测）。
 *
 * <p>旧行为：选中即定（首个 active，否则第一个子仓），失败直接整仓报错、首屏空白。
 * 新行为：按 active → urls 顺序的候选队列，失败推进到下一个，全部失败才抛出最后一次错误。
 */
final class DepotFallback {

    /** 总尝试上限：当前子仓失败后最多再试 1 个，避免 N 个子仓串行放大等待 */
    static final int MAX_ATTEMPTS = 2;

    /** 顺延时间预算：首个候选已耗时超过该值就不再顺延（网络已不可信，翻倍等待不如直接报错） */
    static final long BUDGET_MS = 10000;

    private DepotFallback() {
    }

    /** 候选尝试：index 为子仓下标，抛出即视为该子仓失败 */
    interface Loader {
        void load(int index) throws Throwable;
    }

    /** 依次尝试候选；返回成功的下标，全部失败时抛出最后一次错误 */
    static int run(boolean[] active, Predicate<Throwable> canceled, Loader loader) throws Throwable {
        int[] order = order(active);
        if (order.length == 0) throw new IllegalStateException("Depot children is empty");
        long start = System.currentTimeMillis();
        Throwable last = null;
        for (int i = 0; i < order.length; i++) {
            if (i > 0 && !allowRetry(System.currentTimeMillis() - start)) break;
            try {
                loader.load(order[i]);
                return order[i];
            } catch (Throwable e) {
                if (canceled.test(e)) throw e;
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("Depot children is empty") : last;
    }

    /** 候选顺序：首个 active 优先（无 active 时退化为第一个），其余保持 urls 原始顺序；截断到 MAX_ATTEMPTS */
    static int[] order(boolean[] active) {
        int size = active == null ? 0 : active.length;
        if (size == 0) return new int[0];
        int first = 0;
        for (int i = 0; i < size; i++) {
            if (active[i]) {
                first = i;
                break;
            }
        }
        int limit = Math.min(MAX_ATTEMPTS, size);
        int[] result = new int[limit];
        result[0] = first;
        int count = 1;
        for (int i = 0; i < size && count < limit; i++) {
            if (i != first) result[count++] = i;
        }
        return result;
    }

    /** 顺延预算：首个候选耗时未超预算才继续 */
    static boolean allowRetry(long elapsedMs) {
        return elapsedMs < BUDGET_MS;
    }

    /** 失败层：DNS/TLS/HTTP/JSON（拿到了响应但不是配置）/OTHER；jar 层失败不抛异常，由 JarLoader 自己记录 */
    static String stage(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current instanceof UnknownHostException) return "DNS";
            if (current instanceof SSLException || current instanceof CertificateException) return "TLS";
            if (isContent(current)) return "JSON";
            if (current instanceof IOException) return "HTTP";
            current = current.getCause();
        }
        return "OTHER";
    }

    private static boolean isContent(Throwable error) {
        String name = error.getClass().getName();
        if (name.endsWith("JsonSyntaxException") || name.endsWith("MalformedJsonException")) return true;
        String message = error.getMessage();
        if (message == null) return false;
        return message.contains("Expected BEGIN") || message.contains("Not a JSON Object")
                || message.contains("base64") || message.contains("base-64");
    }
}
