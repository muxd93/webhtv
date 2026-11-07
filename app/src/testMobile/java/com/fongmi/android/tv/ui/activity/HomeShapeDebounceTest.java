package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 分屏拖动会连续改变尺寸：去抖只保留最后一次尺寸，且只有真正的横竖翻转才刷新首页
 * （反向用例：尺寸无效或方向未变时绝不能触发 RefreshEvent.home）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class HomeShapeDebounceTest {

    @Test
    public void debounceKeepsLastSizeOnly() {
        HomeActivity.ShapeDebounce debounce = new HomeActivity.ShapeDebounce();
        debounce.request(1200, 800);
        debounce.request(800, 1200);
        debounce.request(1000, 700);
        assertEquals(1000, debounce.width());
        assertEquals(700, debounce.height());
    }

    @Test
    public void flipRequiresDirectionChange() {
        assertTrue(HomeActivity.shapeFlipChanged(false, 1200, 800));
        assertFalse("同方向尺寸变化不该刷新", HomeActivity.shapeFlipChanged(true, 1200, 800));
        assertFalse("拖动中反复翻转后回到原方向不刷新", HomeActivity.shapeFlipChanged(true, 1000, 700));
    }

    @Test
    public void invalidSizeIsIgnored() {
        assertFalse(HomeActivity.shapeFlipChanged(true, 0, 0));
        assertFalse(HomeActivity.shapeFlipChanged(false, -1, 900));
    }
}
