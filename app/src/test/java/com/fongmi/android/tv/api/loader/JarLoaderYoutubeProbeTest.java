package com.fongmi.android.tv.api.loader;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 youtube iframe 探测的准入判断：开关关闭时不发请求，打开时每个 jar 只探一次。
 */
public class JarLoaderYoutubeProbeTest {

    @Test
    public void featureOffNeverProbes() {
        Set<String> probed = new HashSet<>();
        assertFalse(JarLoader.claimProbe(false, probed, "jar-a"));
        assertFalse(JarLoader.claimProbe(false, probed, "jar-b"));
        assertTrue("开关关闭时不应消耗探测配额", probed.isEmpty());
    }

    @Test
    public void featureOnProbesOncePerJar() {
        Set<String> probed = new HashSet<>();
        assertTrue(JarLoader.claimProbe(true, probed, "jar-a"));
        assertFalse("同一 jar 第二次不再探测", JarLoader.claimProbe(true, probed, "jar-a"));
        assertTrue("不同 jar 各自探测一次", JarLoader.claimProbe(true, probed, "jar-b"));
    }
}
