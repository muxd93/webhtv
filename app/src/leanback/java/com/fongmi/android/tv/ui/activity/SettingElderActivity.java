package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingElderBinding;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.ServiceGate;
import com.google.android.material.textview.MaterialTextView;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 老人模式的独立设置页：项比较多，平铺在主设置页会淹没其它设置，
 * 因此与「播放设置」一样收进下级页面，主设置页只留一个入口。
 */
public class SettingElderActivity extends BaseActivity {

    private ActivitySettingElderBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingElderActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingElderBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        addSwitch("自动连播", Setting::isAutoNextEps, Setting::putAutoNextEps);
        addColumns();
        addSwitch("递归播放", Setting::isElderRecursive, Setting::putElderRecursive);
        addSwitch("语音播报（不识字必备）", Setting::isElderTts, Setting::putElderTts);
        addSwitch("省电模式（不常驻后台）", Setting::isElderPowerSave, Setting::putElderPowerSave);
        addSwitch("每天自动更新站源", Setting::isElderAutoUpdate, Setting::putElderAutoUpdate);
        addSwitch("时钟显示秒", Setting::isElderClockSecond, Setting::putElderClockSecond);
        addSwitch("DLNA 接收（投屏）", Setting::isElderDlna, value -> {
            Setting.putElderDlna(value);
            if (value) DLNARendererService.start(this);
            else DLNARendererService.stop(this);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 与上级设置页一致：停留期间维持远程托管与 mDNS，离开 30 秒后停止
        ServiceGate.get().acquire(ServiceGate.Gate.REMOTE_AGENT);
        ServiceGate.get().acquire(ServiceGate.Gate.NSD);
    }

    @Override
    protected void onPause() {
        ServiceGate.get().release(ServiceGate.Gate.REMOTE_AGENT);
        ServiceGate.get().release(ServiceGate.Gate.NSD);
        super.onPause();
    }

    private void addColumns() {
        LinearLayoutCompat item = newItem();
        item.addView(newTitle("首页列数"));
        MaterialTextView value = newValue();
        value.setText(String.valueOf(Setting.getElderGridColumns()));
        item.addView(value);
        item.setOnClickListener(v -> {
            int current = Setting.getElderGridColumns();
            String[] items = {"3", "4", "5", "6"};
            new AlertDialog.Builder(this)
                    .setTitle("首页列数")
                    .setSingleChoiceItems(items, current - 3, (dialog, which) -> {
                        int next = which + 3;
                        if (next != current) {
                            Setting.putElderGridColumns(next);
                            value.setText(String.valueOf(next));
                        }
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        mBinding.container.addView(item);
    }

    private void addSwitch(String title, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        LinearLayoutCompat item = newItem();
        item.addView(newTitle(title));
        MaterialTextView value = newValue();
        value.setText(getSwitch(getter.get()));
        item.addView(value);
        item.setOnClickListener(v -> {
            setter.accept(!getter.get());
            value.setText(getSwitch(getter.get()));
        });
        mBinding.container.addView(item);
    }

    private LinearLayoutCompat newItem() {
        LinearLayoutCompat item = new LinearLayoutCompat(this);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(16);
        item.setLayoutParams(params);
        item.setBackgroundResource(R.drawable.selector_item);
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setOrientation(LinearLayoutCompat.HORIZONTAL);
        return item;
    }

    private MaterialTextView newTitle(String title) {
        MaterialTextView view = new MaterialTextView(this);
        view.setLayoutParams(new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        view.setText(title);
        view.setTextColor(Color.WHITE);
        view.setTextSize(18);
        return view;
    }

    private MaterialTextView newValue() {
        MaterialTextView view = new MaterialTextView(this);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(ResUtil.dp2px(16));
        view.setLayoutParams(params);
        view.setGravity(Gravity.END);
        view.setTextColor(Color.WHITE);
        view.setTextSize(18);
        return view;
    }
}
