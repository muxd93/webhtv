package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;

import androidx.appcompat.app.AlertDialog;
import androidx.room.Room;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.DialogConfigBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 首装无源时的添加链路：首页空态指引 → 真正的 ConfigListener 宿主（设置页）→ 提交。
 * 上一批只验了"指引会弹出来"，没验"点确定之后源真的被保存"：首页把配置文件对话框开在自己身上，
 * 而 ConfigDialog.onPositive 会强转宿主为 ConfigListener 再回调 setConfig，首页不是该接口实现，点确定即崩。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class HomeConfigEntryTest {

    private static final String URL = "https://example.test/vod.json";
    private static final String NAME = "测试源";

    private AppDatabase db;

    @Before
    public void setUp() throws Exception {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase.class).allowMainThreadQueries().build();
        Field field = AppDatabase.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, db);
    }

    @After
    public void tearDown() throws Exception {
        Field field = AppDatabase.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
        db.close();
    }

    /** 空态指引不得自己开源对话框（宿主必须是 ConfigListener），而是把用户带到设置页 */
    @Test
    public void sourceGuideHandsOffToConfigHost() throws Exception {
        HomeActivity home = Robolectric.buildActivity(HomeActivity.class).create().get();

        Method showSourceGuide = HomeActivity.class.getDeclaredMethod("showSourceGuide");
        showSourceGuide.setAccessible(true);
        showSourceGuide.invoke(home);
        ShadowLooper.shadowMainLooper().idle();

        AlertDialog guide = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("空态必须给出添加源指引", guide);
        guide.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // AlertDialog 的按钮回调经 Handler 投递，点完必须让主线程跑完这一轮消息
        ShadowLooper.shadowMainLooper().idle();

        Intent next = startedActivity(home);
        assertNotNull("指引必须跳到承接它的页面，而不是就地弹对话框", next);
        assertEquals(SettingActivity.class.getName(), next.getComponent().getClassName());
        assertTrue("首页不得自行打开要求 ConfigListener 宿主的对话框", home.getSupportFragmentManager().getFragments().isEmpty());
    }

    /** 在真正的宿主上提交：不崩溃、源落库、并交给加载器（宿主 setConfig → VodConfig.load） */
    @Test
    public void submitOnConfigHostPersistsSource() throws Exception {
        // 需要 STARTED 状态：DialogFragment 到 onStart 才会走 onCreateDialog 建出表单
        SettingActivity host = Robolectric.buildActivity(SettingActivity.class).create().start().get();
        ConfigDialog dialog = ConfigDialog.create().vod();
        dialog.show(host);
        ShadowLooper.shadowMainLooper().idle();

        DialogConfigBinding binding = binding(dialog);
        binding.text.setText(URL);
        binding.name.setText(NAME);
        try {
            binding.positive.performClick();
        } catch (UnsatisfiedLinkError e) {
            // 单测 JVM 没有 arm64 的 chaquopy 原生库，宿主的 setConfig → VodConfig.load 一开始就走 Python 加载器。
            // 能抛到这里说明回调已经到达真正的 ConfigListener 宿主；若宿主没实现该接口，更早的强转处会先抛 ClassCastException。
            assertTrue("只允许因缺少原生库而失败: " + e.getMessage(), e.getMessage().contains("crypto_chaquopy"));
        }

        com.fongmi.android.tv.bean.Config stored = com.fongmi.android.tv.bean.Config.find(URL, 0);
        assertFalse("点确定后源必须落库（崩溃发生在写入之后）", stored.isEmpty());
        assertEquals(NAME, stored.getName());
        assertEquals(URL, stored.getUrl());
    }

    private static DialogConfigBinding binding(ConfigDialog dialog) throws Exception {
        Field field = ConfigDialog.class.getDeclaredField("binding");
        field.setAccessible(true);
        return (DialogConfigBinding) field.get(dialog);
    }

    /** 反射取 ShadowActivity：直接引用该 shadow 会牵出编译期缺失的 android.hardware.fingerprint 类 */
    private static Intent startedActivity(Activity activity) throws Exception {
        Object shadow = Shadow.extract(activity);
        return (Intent) shadow.getClass().getMethod("getNextStartedActivity").invoke(shadow);
    }
}
