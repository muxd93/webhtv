package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.viewbinding.ViewBinding;

import com.google.android.material.textview.MaterialTextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivitySettingBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.AboutDialog;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.DohDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.ui.dialog.BackupProgressDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.ServiceGate;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SettingActivity extends BaseActivity implements ConfigListener, SiteListener, LiveListener, DohDialog.Listener {

    private ActivitySettingBinding mBinding;
    private String[] size;
    private String[] language;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.vod.requestFocus();
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        setWallText();
        mBinding.versionText.setText(AppVersion.fullName());
        setCacheText();
        setOtherText();
        buildElderEntry();
    }

    private void setOtherText() {
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
        mBinding.elderGridColumnsText.setText(String.valueOf(Setting.getElderGridColumns()));
        mBinding.elderAutoNextText.setText(getSwitch(Setting.isAutoNextEps()));
        mBinding.elderRecursiveText.setText(getSwitch(Setting.isElderRecursive()));
        mBinding.languageText.setText((language = ResUtil.getStringArray(R.array.select_language))[Setting.getLanguageIndex()]);
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
    }

    private void setCacheText() {
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                mBinding.cacheText.setText(result);
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.vod.setOnClickListener(this::onVod);
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.wall.setOnClickListener(this::onWall);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.language.setOnClickListener(this::setLanguage);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.enhance.setOnClickListener(this::onEnhance);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.danmaku.setOnClickListener(this::onDanmaku);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.liveHome.setOnClickListener(this::onLiveHome);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.elderGridColumns.setOnClickListener(this::setElderGridColumns);
        mBinding.elderAutoNext.setOnClickListener(this::setElderAutoNext);
        mBinding.elderRecursive.setOnClickListener(this::setElderRecursive);
        mBinding.vodHistory.setOnClickListener(this::onVodHistory);
        mBinding.liveHistory.setOnClickListener(this::onLiveHistory);
        mBinding.wallDefault.setOnClickListener(this::setWallDefault);
        mBinding.wallRefresh.setOnClickListener(this::setWallRefresh);
        mBinding.wallRefresh.setOnLongClickListener(this::onWallHistory);
    }

    @Override
    public void setConfig(Config config) {
        if (config == null) return;
        String url = config.getUrl();
        if (!TextUtils.isEmpty(url) && url.startsWith("file")) {
            PermissionUtil.requestFile(this, allGranted -> load(config));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0:
                VodConfig.load(config, getCallback());
                break;
            case 1:
                LiveConfig.load(config, getCallback());
                break;
            case 2:
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
                break;
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(getActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
    }

    private void onVod(View view) {
        ConfigDialog.create().vod().show(this);
    }

    private void onLive(View view) {
        ConfigDialog.create().live().show(this);
    }

    private void onWall(View view) {
        ConfigDialog.create().wall().show(this);
    }

    private boolean onVodEdit(View view) {
        ConfigDialog.create().vod().edit().show(this);
        return true;
    }

    private boolean onLiveEdit(View view) {
        ConfigDialog.create().live().edit().show(this);
        return true;
    }

    private boolean onWallEdit(View view) {
        ConfigDialog.create().wall().edit().show(this);
        return true;
    }

    private void onVodHome(View view) {
        SiteDialog.create().action().show(this);
    }

    private void onLiveHome(View view) {
        LiveDialog.create().action().show(this);
    }

    private void onVodHistory(View view) {
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory(View view) {
        HistoryDialog.create().live().show(this);
    }

    private void onPlayer(View view) {
        SettingPlayerActivity.start(this);
    }

    private void onEnhance(View view) {
        SettingEnhanceActivity.start(this);
    }

    private void onDanmaku(View view) {
        SettingDanmakuActivity.start(this);
    }

    private void onVersion(View view) {
        AboutDialog.show(this, () -> Updater.create().force().start(this));
    }

    private void setWallDefault(View view) {
        Setting.putWall(Setting.nextDefaultWall());
        Setting.putWallType(0);
        setWallText();
        ConfigEvent.wall();
    }

    private void setWallRefresh(View view) {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private boolean onWallHistory(View view) {
        HistoryDialog.create().wall().show(this);
        return true;
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
    }

    private void setElderAutoNext(View view) {
        Setting.putAutoNextEps(!Setting.isAutoNextEps());
        mBinding.elderAutoNextText.setText(getSwitch(Setting.isAutoNextEps()));
    }

    private void setElderRecursive(View view) {
        Setting.putElderRecursive(!Setting.isElderRecursive());
        mBinding.elderRecursiveText.setText(getSwitch(Setting.isElderRecursive()));
    }

    private void setElderGridColumns(View view) {
        int current = Setting.getElderGridColumns();
        String[] items = {"3", "4", "5", "6"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.elder_grid_columns)
                .setSingleChoiceItems(items, current - 3, (dialog, which) -> {
                    int newColumns = which + 3;
                    if (newColumns != current) {
                        Setting.putElderGridColumns(newColumns);
                        mBinding.elderGridColumnsText.setText(String.valueOf(newColumns));
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 把随老人模式一起加进来、但散落在设置页各处的项收拢成一个「老人模式」分组，
     * 既有的三项（自动连播/列数/递归）从原位置搬进分组，再补上新增的开关。
     */
    private void buildElderEntry() {
        ViewGroup first = (ViewGroup) mBinding.elderAutoNext.getParent();
        ViewGroup root = first == null ? null : (ViewGroup) first.getParent();
        if (root == null || first == null) return;
        int index = root.indexOfChild(first);

        // 老人模式项已收进独立的下级页面，主设置页隐藏原平铺项，只留一个入口
        first.setVisibility(View.GONE);
        setOuterGone(mBinding.elderGridColumns);
        setOuterGone(mBinding.elderRecursive);

        LinearLayoutCompat item = new LinearLayoutCompat(this);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(16);
        item.setLayoutParams(params);
        item.setBackgroundResource(R.drawable.selector_item);
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setOrientation(LinearLayoutCompat.HORIZONTAL);

        MaterialTextView titleView = new MaterialTextView(this);
        titleView.setLayoutParams(new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        titleView.setText("老人模式设置");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);

        MaterialTextView arrowView = new MaterialTextView(this);
        arrowView.setLayoutParams(new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        arrowView.setText("进入");
        arrowView.setTextColor(Color.WHITE);
        arrowView.setTextSize(18);

        item.addView(titleView);
        item.addView(arrowView);
        item.setOnClickListener(v -> SettingElderActivity.start(this));
        root.addView(item, Math.min(index, root.getChildCount()));
    }

    /** 连带外层容器一起隐藏，避免只隐藏内层留下空白 */
    private void setOuterGone(View item) {
        View outer = (View) item.getParent();
        if (outer != null) outer.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 只有打开设置页才拉起远程托管与 mDNS，离开 30 秒后自动停止
        ServiceGate.get().acquire(ServiceGate.Gate.REMOTE_AGENT);
        ServiceGate.get().acquire(ServiceGate.Gate.NSD);
    }

    @Override
    protected void onPause() {
        ServiceGate.get().release(ServiceGate.Gate.REMOTE_AGENT);
        ServiceGate.get().release(ServiceGate.Gate.NSD);
        super.onPause();
    }

    private void setSize(View view) {
        int index = (PlayerSetting.getSize() + 1) % size.length;
        mBinding.sizeText.setText(size[index]);
        PlayerSetting.putSize(index);
        RefreshEvent.size();
    }

    private void setLanguage(View view) {
        int index = (Setting.getLanguageIndex() + 1) % language.length;
        Setting.putLanguageIndex(index);
        RefreshEvent.language();
    }

    private void setDoh(View view) {
        DohDialog.create().index(getDohIndex()).show(this);
    }

    @Override
    public void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void onCache(View view) {
        FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        });
    }

    private void onBackup(View view) {
        PermissionUtil.requestFile(this, allGranted -> {
            BackupProgressDialog progress = BackupProgressDialog.open(getSupportFragmentManager(), "备份应用数据");
            AppDatabase.backup(new Callback() {
            @Override
            public void success() {
                progress.finish();
                Notify.show(R.string.backup_success);
            }

            @Override
            public void error() {
                progress.finish();
                Notify.show(R.string.backup_fail);
            }
            }, progress::update);
        });
    }

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().callback(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                setOtherText();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }).show(this));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.WALL) {
            setWallText();
            return;
        }
        if (event.type() != ConfigEvent.Type.COMMON) return;
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        setWallText();
    }

    private void setWallText() {
        mBinding.wallUrl.setText(Setting.getWallDesc(WallConfig.getDesc()));
    }

}
