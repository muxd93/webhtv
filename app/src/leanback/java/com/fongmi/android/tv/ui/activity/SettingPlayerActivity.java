package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingPlayerBinding;
import com.fongmi.android.tv.impl.SpeedListener;
import com.fongmi.android.tv.impl.UaListener;
import com.fongmi.android.tv.player.lut.LutSetting;
import com.fongmi.android.tv.player.mpv.MpvConfigStore;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerButtonSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.LutDialog;
import com.fongmi.android.tv.ui.dialog.MpvConfigDialog;
import com.fongmi.android.tv.ui.dialog.PlaybackPerformanceDialog;
import com.fongmi.android.tv.ui.dialog.PlayerOsdDialog;
import com.fongmi.android.tv.ui.dialog.PlayerButtonConfigDialog;
import com.fongmi.android.tv.ui.dialog.PlayerKernelDialog;
import com.fongmi.android.tv.ui.dialog.SpeedDialog;
import com.fongmi.android.tv.ui.dialog.UaDialog;
import com.fongmi.android.tv.utils.ResUtil;

import java.text.DecimalFormat;

public class SettingPlayerActivity extends BaseActivity implements UaListener, SpeedListener {

    private ActivitySettingPlayerBinding mBinding;
    private DecimalFormat format;
    private String[] caption;
    private String[] kernel;
    private String[] scale;
    private String[] osd;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingPlayerActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingPlayerBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setVisible();
        format = new DecimalFormat("0.#");
        PlaybackPerformanceSetting.ensureInitialized();
        mBinding.exo4kCompat.requestFocus();
        mBinding.uaText.setText(Setting.getUa());
        setPerformanceText();
        setPlayerButtonsText();
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
        mBinding.speedText.setText(format.format(PlayerSetting.getSpeed()));
        mBinding.autoPlayText.setText(getSwitch(PlayerSetting.isAutoPlay()));
        mBinding.autoChangeText.setText(getSwitch(PlayerSetting.isAutoChange()));
        mBinding.backgroundText.setText(getSwitch(PlayerSetting.isBackgroundOn()));
        mBinding.osdText.setText(getOsdText(osd = ResUtil.getStringArray(R.array.select_player_osd)));
        mBinding.kernelText.setText((kernel = ResUtil.getStringArray(R.array.select_player_kernel))[PlayerSetting.getPlayer()]);
        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[PlayerSetting.getScale()]);
        mBinding.lutText.setText(LutSetting.getSummary());
        setMpvRows();
        mBinding.captionText.setText((caption = ResUtil.getStringArray(R.array.select_caption))[PlayerSetting.isCaption() ? 1 : 0]);
        hidePerformanceRows();
    }

    @Override
    protected void initEvent() {
        mBinding.ua.setOnClickListener(this::onUa);
        mBinding.kernel.setOnClickListener(this::setKernel);
        mBinding.scale.setOnClickListener(this::setScale);
        mBinding.lut.setOnClickListener(this::onLut);
        mBinding.mpvConfig.setOnClickListener(view -> MpvConfigDialog.show(this, () -> mBinding.mpvConfigText.setText(MpvConfigStore.summary())));
        mBinding.blurayMenu.setOnClickListener(view -> {
            PlayerSetting.putBlurayMenu(!PlayerSetting.isBlurayMenu());
            mBinding.blurayMenuText.setText(getSwitch(PlayerSetting.isBlurayMenu()));
        });
        mBinding.osd.setOnClickListener(this::onOsd);
        mBinding.playerButtons.setOnClickListener(view -> PlayerButtonConfigDialog.show(this, this::setPlayerButtonsText));
        mBinding.speed.setOnClickListener(this::onSpeed);
        mBinding.autoPlay.setOnClickListener(this::setAutoPlay);
        mBinding.autoChange.setOnClickListener(this::setAutoChange);
        mBinding.exo4kCompat.setOnClickListener(this::onPerformance);
        mBinding.caption.setOnClickListener(this::setCaption);
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.caption.setOnLongClickListener(this::onCaption);
        mBinding.background.setOnClickListener(this::onBackground);
    }

    private void setVisible() {
        boolean caption = PlayerSetting.hasCaption() && PlayerSetting.getPlayer() != PlayerSetting.IJK;
        mBinding.caption.setVisibility(caption ? View.VISIBLE : View.GONE);
    }

    private void onUa(View view) {
        UaDialog.show(this);
    }

    @Override
    public void setUa(String ua) {
        mBinding.uaText.setText(ua);
        Setting.putUa(ua);
    }

    private void setKernel(View view) {
        PlayerKernelDialog.show(this, PlayerSetting.getPlayer(), index -> {
            mBinding.kernelText.setText(kernel[index]);
            PlayerSetting.putPlayer(index);
            setVisible();
            setMpvRows();
            setPerformanceText();
        });
    }

    private void setScale(View view) {
        int index = (PlayerSetting.getScale() + 1) % scale.length;
        mBinding.scaleText.setText(scale[index]);
        PlayerSetting.putScale(index);
    }

    private void onLut(View view) {
        LutDialog.show(this, null, () -> mBinding.lutText.setText(LutSetting.getSummary()));
    }

    private void setMpvRows() {
        boolean visible = PlayerSetting.getPlayer() == PlayerSetting.MPV;
        mBinding.mpvConfig.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.blurayMenu.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.mpvConfigText.setText(MpvConfigStore.summary());
        mBinding.blurayMenuText.setText(getSwitch(PlayerSetting.isBlurayMenu()));
    }

    private void onOsd(View view) {
        PlayerOsdDialog.show(this, osd, getOsdChecked(), checked -> {
            setOsdChecked(checked);
            mBinding.osdText.setText(getOsdText(osd));
        });
    }

    private void setPlayerButtonsText() {
        mBinding.playerButtonsText.setText(getString(R.string.player_button_config_summary, PlayerButtonSetting.getVisibleCount(), PlayerButtonSetting.getTotalCount()));
    }

    private boolean[] getOsdChecked() {
        return new boolean[]{PlayerSetting.isOsdTitle(), PlayerSetting.isOsdResolution(), PlayerSetting.isOsdTime(), PlayerSetting.isOsdProgress(), PlayerSetting.isOsdTraffic(), PlayerSetting.isOsdMini(), PlayerSetting.isOsdDiagnostics()};
    }

    private void setOsdChecked(boolean[] checked) {
        PlayerSetting.putOsdTitle(checked[0]);
        PlayerSetting.putOsdResolution(checked[1]);
        PlayerSetting.putOsdTime(checked[2]);
        PlayerSetting.putOsdProgress(checked[3]);
        PlayerSetting.putOsdTraffic(checked[4]);
        PlayerSetting.putOsdMini(checked[5]);
        PlayerSetting.putOsdDiagnostics(checked[6]);
    }

    private String getOsdText(String[] items) {
        boolean[] checked = getOsdChecked();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < checked.length; i++) {
            if (!checked[i]) continue;
            if (builder.length() > 0) builder.append(" / ");
            builder.append(items[i]);
        }
        return builder.length() == 0 ? getString(R.string.setting_off) : builder.toString();
    }

    private void onSpeed(View view) {
        SpeedDialog.show(this);
    }

    @Override
    public void setSpeed(float speed) {
        mBinding.speedText.setText(format.format(speed));
        PlayerSetting.putSpeed(speed);
    }

    private void setAutoPlay(View view) {
        PlayerSetting.putAutoPlay(!PlayerSetting.isAutoPlay());
        mBinding.autoPlayText.setText(getSwitch(PlayerSetting.isAutoPlay()));
    }

    private void setAutoChange(View view) {
        PlayerSetting.putAutoChange(!PlayerSetting.isAutoChange());
        mBinding.autoChangeText.setText(getSwitch(PlayerSetting.isAutoChange()));
    }

    private void onPerformance(View view) {
        PlaybackPerformanceDialog.show(this, this::refreshPerformanceSettings);
    }

    private void refreshPerformanceSettings() {
        setPerformanceText();
        hidePerformanceRows();
    }

    private void setPerformanceText() {
        mBinding.exo4kCompatText.setText(PlaybackPerformanceSetting.getSummary());
    }

    private void hidePerformanceRows() {
        mBinding.render.setVisibility(View.GONE);
        mBinding.buffer.setVisibility(View.GONE);
        mBinding.bufferBytes.setVisibility(View.GONE);
        mBinding.backBuffer.setVisibility(View.GONE);
        mBinding.playCache.setVisibility(View.GONE);
        mBinding.preload.setVisibility(View.GONE);
        mBinding.preloadThread.setVisibility(View.GONE);
        mBinding.preloadSize.setVisibility(View.GONE);
        mBinding.preloadTime.setVisibility(View.GONE);
        mBinding.preloadAhead.setVisibility(View.GONE);
        mBinding.preloadPause.setVisibility(View.GONE);
        mBinding.tunnel.setVisibility(View.GONE);
        mBinding.audioDecode.setVisibility(View.GONE);
        mBinding.audioPassThrough.setVisibility(View.GONE);
        mBinding.videoDecode.setVisibility(View.GONE);
        mBinding.aac.setVisibility(View.GONE);
    }

    private void setCaption(View view) {
        PlayerSetting.putCaption(!PlayerSetting.isCaption());
        mBinding.captionText.setText(caption[PlayerSetting.isCaption() ? 1 : 0]);
    }

    private void setAdblock(View view) {
        Setting.putAdblock(!Setting.isAdblock());
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
    }

    private boolean onCaption(View view) {
        if (PlayerSetting.isCaption()) startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
        return PlayerSetting.isCaption();
    }

    private void onBackground(View view) {
        PlayerSetting.putBackground(PlayerSetting.isBackgroundOn() ? 0 : 1);
        mBinding.backgroundText.setText(getSwitch(PlayerSetting.isBackgroundOn()));
    }
}
