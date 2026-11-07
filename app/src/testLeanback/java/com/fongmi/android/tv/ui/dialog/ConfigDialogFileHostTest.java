package com.fongmi.android.tv.ui.dialog;

import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResult;
import androidx.room.Room;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.DialogConfigBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.ui.activity.HomeActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 配置文件对话框的两条提交路径都假定宿主实现了 ConfigListener：点「确定」的 onPositive，
 * 以及延迟投递的文件选择回调。宿主换成不实现该接口的页面就崩在 main thread / main looper 上，
 * 而源其实已经由 saveConfig 落库——崩溃只是"忘了通知宿主刷新"的副作用。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class ConfigDialogFileHostTest {

    private static final String URL = "https://example.test/vod.json";
    private static final String NAME = "测试源";

    private AppDatabase db;

    @Before
    public void setUp() throws Exception {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase.class).allowMainThreadQueries().build();
        setDatabase(db);
    }

    @After
    public void tearDown() throws Exception {
        setDatabase(null);
        db.close();
    }

    /** 首页就是当初的受害者：它不实现 ConfigListener，这里用它模拟"非 ConfigListener 宿主" */
    @Test
    public void fileSubmitOnNonConfigListenerHostDoesNotCrash() throws Exception {
        HomeActivity host = Robolectric.buildActivity(HomeActivity.class).create().start().get();
        ConfigDialog dialog = ConfigDialog.create().vod();
        dialog.show(host);
        ShadowLooper.shadowMainLooper().idle();

        // 直接投递真实的 ActivityResult 给回调本身（不经 SAF 系统选择器，其余逻辑全是生产代码）
        deliverFileResult(dialog, Uri.fromFile(new File("/sdcard/webhtv/vod.json")));

        // 外层 post 100ms 后 dismiss，内层再 100ms 后回调宿主；旧实现在这一步抛 ClassCastException
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        host.getSupportFragmentManager().executePendingTransactions();

        // 走到这里说明内层回调没有抛异常；同时确认它确实执行了（外层 post 已把对话框关掉）
        assertFalse("回调必须真的跑到关闭对话框那一步", dialog.isAdded());
    }

    /** 点「确定」时旧实现先落库、再裸强转宿主，非 ConfigListener 宿主直接抛 ClassCastException */
    @Test
    public void positiveSubmitOnNonConfigListenerHostDoesNotCrash() throws Exception {
        HomeActivity host = Robolectric.buildActivity(HomeActivity.class).create().start().get();
        ConfigDialog dialog = ConfigDialog.create().vod();
        dialog.show(host);
        ShadowLooper.shadowMainLooper().idle();

        DialogConfigBinding binding = binding(dialog);
        binding.text.setText(URL);
        binding.name.setText(NAME);
        binding.positive.performClick();

        host.getSupportFragmentManager().executePendingTransactions();
        assertFalse("点确定后对话框必须关掉", dialog.isAdded());
        com.fongmi.android.tv.bean.Config stored = com.fongmi.android.tv.bean.Config.find(URL, 0);
        assertFalse("源必须已经落库：跳过宿主回调只影响列表刷新，不丢数据", stored.isEmpty());
    }

    private static DialogConfigBinding binding(ConfigDialog dialog) throws Exception {
        Field field = ConfigDialog.class.getDeclaredField("binding");
        field.setAccessible(true);
        return (DialogConfigBinding) field.get(dialog);
    }

    private static void setDatabase(AppDatabase database) throws Exception {
        Field field = AppDatabase.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, database);
    }

    private static void deliverFileResult(ConfigDialog dialog, Uri uri) throws Exception {
        Method callback = fileResultCallback();
        ActivityResult result = new ActivityResult(Activity.RESULT_OK, new Intent().setData(uri));
        try {
            callback.invoke(dialog, result);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** 注册文件选择器时生成的那个唯一回调：只有一个 ActivityResult 参数 */
    private static Method fileResultCallback() {
        for (Method method : ConfigDialog.class.getDeclaredMethods()) {
            if (method.getParameterCount() != 1) continue;
            if (method.getParameterTypes()[0] != ActivityResult.class) continue;
            method.setAccessible(true);
            return method;
        }
        throw new IllegalStateException("ConfigDialog 里找不到文件选择回调，测试已失效");
    }
}
