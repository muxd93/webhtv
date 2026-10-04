package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.LivePreset;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.setting.LiveEpgSetting;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;
import java.util.Objects;

/**
 * 点播/直播/墙纸配置对话框的公共基类：type/edit 状态、URL 快捷补全、
 * 保存（含自动命名与直播聚合池删除联动）、推荐源导入、文件选择、推送回填。
 * 子类只负责布局装配（initView/initEvent）与配置交付（onConfigSaved）。
 */
public abstract class BaseConfigDialog extends BaseAlertDialog {

    protected boolean append = true;
    protected boolean edit;
    protected String origin;
    protected int type;

    public BaseConfigDialog vod() {
        type = 0;
        return this;
    }

    public BaseConfigDialog live() {
        type = 1;
        return this;
    }

    public BaseConfigDialog wall() {
        type = 2;
        return this;
    }

    public BaseConfigDialog edit() {
        edit = true;
        return this;
    }

    protected abstract TextView nameView();

    protected abstract EditText urlView();

    /** 配置落库后的交付（切换/刷新由宿主决定）。 */
    protected abstract void onConfigSaved(Config config);

    /** 预设等列表对话框的构建钩子，mobile 覆写以携带主题样式。 */
    protected MaterialAlertDialogBuilder listDialog() {
        return builder();
    }

    protected int getPositiveText() {
        return edit ? R.string.dialog_edit : R.string.dialog_positive;
    }

    protected Config getConfig() {
        return switch (type) {
            case 0 -> VodConfig.get().getConfig();
            case 1 -> LiveConfig.get().getConfig();
            case 2 -> WallConfig.get().getConfig();
            default -> Config.create(type);
        };
    }

    private Config getStoredConfig() {
        return switch (type) {
            case 0 -> Config.vod();
            case 1 -> Config.live();
            case 2 -> Config.wall();
            default -> Config.create(type);
        };
    }

    private int getTypeName() {
        return switch (type) {
            case 0 -> R.string.setting_vod;
            case 1 -> R.string.setting_live;
            case 2 -> R.string.setting_wall;
            default -> R.string.remote_trust_config_type;
        };
    }

    protected String getDialogTitle() {
        int action = edit ? R.string.remote_trust_config_edit : R.string.remote_trust_config_add;
        return getString(R.string.setting_config_dialog_title, getString(action), getString(getTypeName()));
    }

    /** 装配名称/地址两个输入框的公共初值与行为；子类在 initView/initEvent 中调用。 */
    protected void initConfigViews() {
        Config config = getConfig();
        nameView().setText(edit ? config.getName() : "");
        urlView().setText(origin = config.getUrl());
        urlView().setSelection(TextUtils.isEmpty(origin) ? 0 : origin.length());
    }

    protected void initConfigEvents() {
        urlView().addTextChangedListener(new CustomTextListener() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                detect(s.toString());
            }
        });
        urlView().setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onPositive();
            return true;
        });
    }

    protected void onChoose(View view) {
        FileChooser.from(launcher).show();
    }

    protected void onPreset(View view) {
        List<LivePreset> presets = LivePreset.get();
        if (presets.isEmpty()) return;
        LivePreset.refresh();
        String[] names = presets.stream().map(LivePreset::getTitle).toArray(String[]::new);
        listDialog().setTitle(R.string.live_preset).setItems(names, (dialog, which) -> importPreset(presets.get(which))).show();
    }

    protected void importPreset(LivePreset item) {
        // 仅在用户未设全局 EPG 时随源挂载，避免覆盖已有配置
        if (!item.getEpg().isEmpty() && LiveEpgSetting.getUrl().isEmpty()) LiveEpgSetting.putUrl(item.getEpg());
        nameView().setText(item.getName());
        urlView().setText(item.getUrl());
        urlView().setSelection(item.getUrl().length());
        onPositive();
    }

    private void detect(String s) {
        if (append && "h".equalsIgnoreCase(s)) {
            append = false;
            urlView().append("ttp://");
        } else if (append && "f".equalsIgnoreCase(s)) {
            append = false;
            urlView().append("ile://");
        } else if (append && "a".equalsIgnoreCase(s)) {
            append = false;
            urlView().append("ssets://");
        } else if (s.length() > 1) {
            append = false;
        } else if (s.isEmpty()) {
            append = true;
        }
    }

    protected void onPositive() {
        String name = nameView().getText().toString().trim();
        String text = urlView().getText().toString().trim();
        Config config = saveConfig(text, name);
        if (config == null) {
            Notify.show(R.string.remote_trust_config_url_required);
            urlView().requestFocus();
            return;
        }
        onConfigSaved(config);
        dismiss();
    }

    private Config saveConfig(String text, String name) {
        if (text.isEmpty()) {
            if (!edit) return null;
            if (!TextUtils.isEmpty(origin)) {
                Config.delete(origin, type);
            }
            return getStoredConfig();
        } else if (edit) {
            return Config.find(origin, type).url(text).name(name).update();
        } else {
            Config exists = AppDatabase.get().getConfigDao().find(text, type);
            if (exists != null) return exists;
            // 未命名时按 URL 推导默认名（取 path 或 host）
            return Config.create(type).url(text).name(TextUtils.isEmpty(name) ? UrlUtil.getName(text) : name).update();
        }
    }

    /** 桌面推送（Server.setConfig）在对话框打开时回填输入框。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.SETTING) return;
        nameView().setText(event.name());
        urlView().setText(event.text());
        urlView().setSelection(urlView().getText().length());
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    protected final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        String name = nameView().getText().toString().trim();
        String path = Objects.toString(FileChooser.getPathFromUri(result.getData().getData()), "");
        if (TextUtils.isEmpty(path)) return;
        onConfigSaved(saveConfig("file:/" + path.replace(Path.rootPath(), ""), name));
        dismiss();
    });
}
