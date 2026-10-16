package com.fongmi.android.tv.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

public class GithubProxyTest {

    private static final String ASSET = "https://github.com/muxd93/webhtv/releases/download/v1/mobile-arm64_v8a.apk";

    @Test
    public void rewritesKnownProxyModes() {
        assertEquals(
                "https://github.chenc.dev/github.com/muxd93/webhtv/releases/download/v1/mobile-arm64_v8a.apk",
                GithubProxy.resolve("github_chenc", "", "").rewrite(ASSET));
        assertEquals(
                "https://gh.acmsz.top/https://github.com/muxd93/webhtv/releases/download/v1/mobile-arm64_v8a.apk",
                GithubProxy.resolve("gh_acmsz", "", "").rewrite(ASSET));
    }

    @Test
    public void defaultPresetIsAccelerated() {
        assertEquals(
                "https://gh.ddlc.top/" + ASSET,
                GithubProxy.resolve(GithubProxy.DEFAULT, "", "").rewrite(ASSET));
    }

    @Test
    public void fallbacksCoverEveryOtherPresetThenDirect() {
        List<GithubProxy.Config> fallbacks = GithubProxy.fallbacks(GithubProxy.DEFAULT);
        assertEquals(GithubProxy.presets().length - 2, fallbacks.size());
        assertEquals(GithubProxy.DIRECT, fallbacks.get(fallbacks.size() - 1).id);
        for (GithubProxy.Config config : fallbacks) assertNotEquals(GithubProxy.DEFAULT, config.id);
    }

    @Test
    public void rejectsUnsafeCustomOrigins() {
        assertThrows(IllegalArgumentException.class, () -> GithubProxy.resolve(GithubProxy.CUSTOM, "http://proxy.example", GithubProxy.MODE_FULL_URL));
        assertThrows(IllegalArgumentException.class, () -> GithubProxy.resolve(GithubProxy.CUSTOM, "https://user:pass@proxy.example", GithubProxy.MODE_FULL_URL));
        assertThrows(IllegalArgumentException.class, () -> GithubProxy.resolve(GithubProxy.CUSTOM, "https://proxy.example/path", GithubProxy.MODE_FULL_URL));
    }
}
