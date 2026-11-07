package com.fongmi.android.tv.api.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 多仓接管直播源的判定：必须保留「当前没有直播源 / 手选源就是本多仓」仍自动接管的反向行为，
 * 只有「手选了不属于本多仓的独立直播源」才不覆盖。
 */
public class BaseConfigNeedSyncTest {

    private static final String DEPOT = "https://a.com/depot.json";

    private static final String OTHER = "https://b.com/live.json";

    @Test
    public void takesOverWhenNoLiveSource() {
        assertTrue(BaseConfig.needSync(false, null, DEPOT));
        assertTrue(BaseConfig.needSync(false, "", DEPOT));
    }

    @Test
    public void takesOverWhenSelectedLiveSourceIsThisDepot() {
        assertTrue(BaseConfig.needSync(false, DEPOT, DEPOT));
    }

    @Test
    public void takesOverWhenLiveFollowsVod() {
        assertTrue(BaseConfig.needSync(true, OTHER, DEPOT));
    }

    @Test
    public void keepsIndependentLiveSource() {
        assertFalse(BaseConfig.needSync(false, OTHER, DEPOT));
    }
}
