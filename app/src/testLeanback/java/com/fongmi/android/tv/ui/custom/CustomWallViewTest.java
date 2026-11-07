package com.fongmi.android.tv.ui.custom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.fongmi.android.tv.App;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class CustomWallViewTest {

    private File wall;

    @Before
    public void setUp() throws Exception {
        CustomWallView.resetForTest();
        wall = File.createTempFile("wall", ".png");
        Bitmap source = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        try (FileOutputStream out = new FileOutputStream(wall)) {
            source.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    @Test
    public void sameSizeReusesDecodedBitmap() {
        CustomWallView.WallSnapshot first = CustomWallView.loadWallSnapshot(wall, 320, 180);
        assertNotNull("首次解码失败", first);
        assertEquals(1, CustomWallView.decodeCount());

        CustomWallView.WallSnapshot second = CustomWallView.loadWallSnapshot(wall, 320, 180);
        assertNotNull(second);
        // 同一尺寸命中缓存：不再解码，且复用同一张位图
        assertEquals("同尺寸重复请求不应再解码", 1, CustomWallView.decodeCount());
        assertEquals(first.bitmap, second.bitmap);
    }

    @Test
    public void differentSizesDecodeSeparately() {
        CustomWallView.WallSnapshot small = CustomWallView.loadWallSnapshot(wall, 320, 180);
        assertNotNull(small);
        assertEquals(1, CustomWallView.decodeCount());

        // 更大尺寸不能命中 320x180 的缓存，必须按自己的采样率重新解码
        CustomWallView.WallSnapshot large = CustomWallView.loadWallSnapshot(wall, 1280, 720);
        assertNotNull(large);
        assertEquals(2, CustomWallView.decodeCount());
        assertNotSame("不同尺寸不能复用同一张位图", small.bitmap, large.bitmap);
        assertTrue("大尺寸位图应不小于小尺寸", large.bitmap.getWidth() >= small.bitmap.getWidth());
        assertTrue("大尺寸位图高度应不小于小尺寸", large.bitmap.getHeight() >= small.bitmap.getHeight());
    }

    @Test
    public void sampleSizeCapsToRequestedBounds() {
        assertEquals(1, CustomWallView.sampleSize(640, 360, 1280, 720));
        assertEquals(2, CustomWallView.sampleSize(640, 360, 320, 180));
        assertEquals(2, CustomWallView.sampleSize(640, 360, 400, 225));
        assertEquals(8, CustomWallView.sampleSize(640, 360, 100, 100));
    }

    @Test
    public void wallKeySeparatesSizeAndFileVersion() {
        String small = CustomWallView.wallKey(wall, 320, 180);
        String large = CustomWallView.wallKey(wall, 1280, 720);
        assertFalse("尺寸不同的 key 不能相同", small.equals(large));
        assertTrue("key 必须带上目标尺寸", large.endsWith("|1280x720"));
    }

    @Test
    public void detachedViewDropsLateResult() {
        androidx.fragment.app.FragmentActivity activity = Robolectric.buildActivity(androidx.fragment.app.FragmentActivity.class).setup().get();
        CustomWallView view = new CustomWallView(activity, null);
        activity.setContentView(view);
        int token = view.beginWallRequest();
        assertTrue("attach 期间请求应有效", view.isCurrentWallRequest(token));
        activity.setContentView(new android.widget.FrameLayout(activity));
        // detach 后迟到的结果必须被判为过期
        assertFalse("detach 后迟到结果必须丢弃", view.isCurrentWallRequest(token));
    }
}
