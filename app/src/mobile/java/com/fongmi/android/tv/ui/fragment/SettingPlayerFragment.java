package com.fongmi.android.tv.ui.fragment;

import android.content.Intent;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingPlayerBinding;
import com.fongmi.android.tv.impl.SpeedListener;
import com.fongmi.android.tv.impl.UaListener;
import com.fongmi.android.tv.player.lut.LutSetting;
import com.fongmi.android.tv.player.mpv.MpvConfigStore;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerButtonSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.ChoiceDialog;
import com.fongmi.android.tv.ui.dialog.LutDialog;
import com.fongmi.android.tv.ui.dialog.MpvConfigDialog;
import com.fongmi.android.tv.ui.dialog.PlaybackPerformanceDialog;
import com.fongmi.android.tv.ui.dialog.PlayerButtonConfigDialog;
import com.fongmi.android.tv.ui.dialog.PlayerKernelDialog;
import com.fongmi.android.tv.ui.dialog.PlayerOsdDialog;
import com.fongmi.android.tv.ui.dialog.SpeedDialog;
import com.fongmi.android.tv.ui.dialog.UaDialog;
import com.fongmi.android.tv.utils.ResUtil;

import java.text.DecimalFormat;

public class SettingPlayerFragment extends BaseFragment implements UaListener, SpeedListener {

    private FragmentSettingPlayerBinding mBinding;
    private DecimalFormat format;
    private String[] background;
    private String[] caption;
    private String[] kernel;
    private String[] padLiveMode;
    private String[] scale;
    private String[] osd;

    public static SettingPlayerFragment newInstance() {
        return new SettingPlayerFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingPlayerBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        format = new DecimalFormat("0.#");
        PlaybackPerformanceSetting.ensureInitialized();
        mBinding.uaText.setText(Setting.getUa());
        setPerformanceText();
        setPadLiveModeText();
        setPlayerButtonsText();
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
        mBinding.speedText.setText(format.format(PlayerSetting.getSpeed()));
        mBinding.autoPlayText.setText(getSwitch(PlayerSetting.isAutoPlay()));
        mBinding.autoChangeText.setText(getSwitch(PlayerSetting.isAutoChange()));
        setVisible();
        mBinding.osdText.setText(getOsdText(osd = ResUtil.getStringArray(R.array.select_player_osd)));
        mBinding.kernelText.setText((kernel = ResUtil.getStringArray(R.array.select_player_kernel))[PlayerSetting.getPlayer()]);
        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[PlayerSetting.getScale()]);
        mBinding.lutText.setText(LutSetting.getSummary());
        setMpvRows();
        mBinding.captionText.setText((caption = ResUtil.getStringArray(R.array.select_caption))[PlayerSetting.isCaption() ? 1 : 0]);
        mBinding.backgroundText.setText((background = ResUtil.getStringArray(R.array.select_background))[PlayerSetting.getBackground()]);
        hidePerformanceRows();
    }

    @Override
    protected void initEvent() {
        mBinding.ua.setOnClickListener(this::onUa);
        mBinding.kernel.setOnClickListener(this::onKernel);
        mBinding.scale.setOnClickListener(this::onScale);
        mBinding.lut.setOnClickListener(this::onLut);
        mBinding.mpvConfig.setOnClickListener(view -> MpvConfigDialog.show(this, () -> mBinding.mpvConfigText.setText(MpvConfigStore.summary())));
        mBinding.blurayMenu.setOnClickListener(view -> {
            PlayerSetting.putBlurayMenu(!PlayerSetting.isBlurayMenu());
            mBinding.blurayMenuText.setText(getSwitch(PlayerSetting.isBlurayMenu()));
        });
        mBinding.osd.setOnClickListener(this::onOsd);
        mBinding.playerButtons.setOnClickListener(view -> PlayerButtonConfigDialog.show(this, this::setPlayerButtonsText));
        mBinding.padLive.setOnClickListener(this::setPadLiveMode);
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

    private void onKernel(View view) {
        PlayerKernelDialog.show(this, PlayerSetting.getPlayer(), which -> {
            mBinding.kernelText.setText(kernel[which]);
            PlayerSetting.putPlayer(which);
            setVisible();
            setMpvRows();
            setPerformanceText();
        });
    }

    private void onScale(View view) {
        ChoiceDialog.showSingle(this, R.string.player_scale, scale, PlayerSetting.getScale(), which -> {
            mBinding.scaleText.setText(scale[which]);
            PlayerSetting.putScale(which);
        });
    }

    private void onLut(View view) {
        LutDialog.show(this, () -> mBinding.lutText.setText(LutSetting.getSummary()));
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

    private void setPadLiveModeText() {
        mBinding.padLive.setVisibility(ResUtil.isPad() ? View.VISIBLE : View.GONE);
        mBinding.padLiveText.setText((padLiveMode = ResUtil.getStringArray(R.array.select_pad_live_mode))[PlayerSetting.getPadLiveMode()]);
    }

    private void setPadLiveMode(View view) {
        int index = (PlayerSetting.getPadLiveMode() + 1) % padLiveMode.length;
        PlayerSetting.putPadLiveMode(index);
        mBinding.padLiveText.setText(padLiveMode[index]);
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

    private boolean onCaption(View view) {
        if (PlayerSetting.isCaption()) startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
        return PlayerSetting.isCaption();
    }

    private void setAdblock(View view) {
        Setting.putAdblock(!Setting.isAdblock());
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
    }

    private void onBackground(View view) {
        ChoiceDialog.showSingle(this, R.string.player_background, background, PlayerSetting.getBackground(), which -> {
            mBinding.backgroundText.setText(background[which]);
            PlayerSetting.putBackground(which);
        });
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) initView();
    }
}
