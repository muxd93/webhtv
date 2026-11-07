package com.fongmi.android.tv.api.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 用户手动重试的负缓存穿透：只清本次源相关的键，且不得顺手把自动路径的退避机制废掉。
 */
public class SourceRetryTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void keysCoverSourceAndDepotChildren() {
        List<String> keys = SourceRetry.keys("https://a.com/depot.json", Arrays.asList("https://a.com/c1.json", "https://a.com/c2.json"));
        assertEquals(3, keys.size());
        assertTrue(keys.contains("depot-retry-" + md5("https://a.com/depot.json")));
        assertTrue(keys.contains("depot-retry-" + md5("https://a.com/c1.json")));
        assertTrue(keys.contains("depot-retry-" + md5("https://a.com/c2.json")));
    }

    @Test
    public void keysIgnoreBlankAndDuplicates() {
        List<String> keys = SourceRetry.keys("https://a.com/x.json", Arrays.asList("https://a.com/x.json", "", null));
        assertEquals(Collections.singletonList("depot-retry-" + md5("https://a.com/x.json")), keys);
        assertEquals(0, SourceRetry.keys("", Collections.emptyList()).size());
    }

    /**
     * 反向用例：手动重试某个源时，同类型其它源的退避窗口必须原样保留，
     * 自动路径「窗口内拦截、窗口过后放行」的判定也不能被清理逻辑顺手废掉。
     */
    @Test
    public void refreshOfOneSourceKeepsOtherSourcesBackoff() {
        DepotBackoff.State other = DepotBackoff.fail(null, NOW, "dns");
        assertTrue("窗口内自动路径仍应拦截", DepotBackoff.blocked(DepotBackoff.parse(other.encode()), NOW));
        List<String> keys = SourceRetry.keys("https://a.com/mine.json", Arrays.asList("https://a.com/c1.json"));
        assertFalse("不得包含其它源的负缓存键", keys.contains("depot-retry-" + md5("https://b.com/other.json")));
        assertTrue(DepotBackoff.blocked(DepotBackoff.parse(other.encode()), NOW + DepotBackoff.window(1) - 1));
        assertFalse("窗口过后自动放行", DepotBackoff.blocked(DepotBackoff.parse(other.encode()), NOW + DepotBackoff.window(1) + 1));
    }

    private static String md5(String src) {
        try {
            byte[] bytes = MessageDigest.getInstance("MD5").digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            return builder.toString().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
