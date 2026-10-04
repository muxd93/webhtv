package com.fongmi.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.server.process.ApkUrlPush;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.custom.CustomWallView;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import me.jessyan.autosize.AutoSizeCompat;

public abstract class BaseActivity extends AppCompatActivity {

    protected abstract ViewBinding getBinding();

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Setting.wrapLanguage(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getBinding().getRoot());
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        Util.hideSystemUI(this);
        setBackCallback();
        initEvent();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        if (!customWall()) return;
        addCustomWall();
    }

    private void addCustomWall() {
        ((ViewGroup) findViewById(android.R.id.content)).addView(new CustomWallView(this, null), 0, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    protected FragmentActivity getActivity() {
        return this;
    }

    protected boolean customWall() {
        return true;
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    protected void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        postRecyclerUpdate(view, adapter::notifyDataSetChanged);
    }

    protected void notifyItemsChanged(RecyclerView view, RecyclerView.Adapter<?> adapter, int... positions) {
        postRecyclerUpdate(view, () -> {
            for (int i = 0; i < positions.length; i++) {
                int position = positions[i];
                if (position < 0 || position >= adapter.getItemCount()) continue;
                boolean duplicate = false;
                for (int j = 0; j < i; j++) if (positions[j] == position) duplicate = true;
                if (!duplicate) adapter.notifyItemChanged(position);
            }
        });
    }

    private void postRecyclerUpdate(RecyclerView view, Runnable update) {
        view.post(new Runnable() {
            @Override
            public void run() {
                if (view.isComputingLayout()) {
                    view.postOnAnimation(this);
                    return;
                }
                update.run();
            }
        });
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    private static float loggedDensity = -1f;
    private static long loggedAt;

    private Resources hackResources(Resources resources) {
        try {
            AutoSizeCompat.autoConvertDensityOfGlobal(resources);
            logDensity(resources);
            return resources;
        } catch (Exception ignored) {
            return resources;
        }
    }

    private void logDensity(Resources resources) {
        if (!BuildConfig.DEBUG) return;
        DisplayMetrics metrics = resources.getDisplayMetrics();
        long now = SystemClock.elapsedRealtime();
        if (metrics.density == loggedDensity || now - loggedAt < 1000) return;
        loggedDensity = metrics.density;
        loggedAt = now;
        Configuration config = resources.getConfiguration();
        Log.i("AutoSizeDiag", "density=" + metrics.density + " widthPx=" + metrics.widthPixels + " heightPx=" + metrics.heightPixels + " swDp=" + config.screenWidthDp + " orient=" + config.orientation);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
        if (o instanceof RefreshEvent event && event.getType() == RefreshEvent.Type.LANGUAGE) recreate();
    }

    // AutoSizeCompat 用进程启动时捕获的静态屏宽（AutoSizeConfig.getScreenWidth）计算密度并缓存，
    // 平板冷启动时横竖屏时序不定导致初始比例每次启动随机，触屏设备直接走系统密度。
    @Override
    public Resources getResources() {
        Resources resources = super.getResources();
        if (Util.isTouchscreen(this)) return resources;
        return hackResources(resources);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    protected void onBackInvoked() {
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.create().resume(this);
        ApkUrlPush.get().resume(this);
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
