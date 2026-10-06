package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.LiveApi;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.databinding.DialogLiveProgramBinding;
import com.fongmi.android.tv.ui.adapter.LiveProgramAdapter;
import com.fongmi.android.tv.ui.adapter.LiveProgramDateAdapter;
import com.fongmi.android.tv.utils.Formatters;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class LiveProgramDialog extends BaseBottomSheetDialog implements LiveProgramDateAdapter.OnClickListener, LiveProgramAdapter.OnClickListener {

    /** 单日懒加载回调(实现方注入 viewModel;null = 不支持懒加载,空日期仅提示)。 */
    public interface DayFetcher {

        void fetch(Channel channel, int offset, Consumer<Epg> callback);
    }

    private DialogLiveProgramBinding binding;
    private LiveProgramDateAdapter dateAdapter;
    private LiveProgramAdapter programAdapter;
    private Consumer<EpgData> listener;
    private DayFetcher fetcher;
    private Channel channel;
    private ZoneId zoneId;

    public static LiveProgramDialog create() {
        return new LiveProgramDialog();
    }

    public LiveProgramDialog channel(Channel channel) {
        this.channel = channel;
        return this;
    }

    public LiveProgramDialog zoneId(ZoneId zoneId) {
        this.zoneId = zoneId;
        return this;
    }

    public LiveProgramDialog fetcher(DayFetcher fetcher) {
        this.fetcher = fetcher;
        return this;
    }

    public LiveProgramDialog listener(Consumer<EpgData> listener) {
        this.listener = listener;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof LiveProgramDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        configureWindow(dialog);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow(getDialog());
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogLiveProgramBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        if (channel == null) return;
        if (zoneId == null) zoneId = ZoneId.systemDefault();
        binding.title.setText(channel.getShow());
        binding.date.setHasFixedSize(false);
        binding.date.setItemAnimator(null);
        binding.program.setHasFixedSize(false);
        binding.program.setItemAnimator(null);
        binding.date.setAdapter(dateAdapter = new LiveProgramDateAdapter(this));
        binding.program.setAdapter(programAdapter = new LiveProgramAdapter(this).channel(channel));
        String today = LocalDate.now(zoneId).format(Formatters.DATE);
        dateAdapter.setItems(buildSlots(), today);
        showProgram(dateAdapter.getSelected());
        // 今天无数据(模板 EPG 起播抓取失败等)时进入弹窗即自动懒取一次
        Epg selected = dateAdapter.getSelected();
        if (selected.getList().isEmpty() && fetcher != null && LiveApi.isDateRequestable(channel)) fetchDay(selected);
    }

    @Override
    public void onDateClick(Epg epg) {
        boolean requestable = fetcher != null && LiveApi.isDateRequestable(channel);
        if (epg.getList().isEmpty() && requestable) {
            showProgram(epg);
            fetchDay(epg);
            return;
        }
        showProgram(epg);
        if (epg.getList().isEmpty()) Notify.show(R.string.live_epg_no_data);
    }

    /** 固定 -6~+2 九天槽位:有数据用数据,无数据用同日期占位空槽(模板 {date} 源可点击懒取)。 */
    private List<Epg> buildSlots() {
        List<Epg> slots = new ArrayList<>();
        LocalDate today = LocalDate.now(zoneId);
        for (int offset = -6; offset <= 2; offset++) {
            String date = today.plusDays(offset).format(Formatters.DATE);
            Epg data = channel.getDataList().stream().filter(epg -> epg.equal(date)).findFirst().orElse(null);
            slots.add(data != null ? data : Epg.create(channel.getTvgId(), date));
        }
        return slots;
    }

    /** 懒加载单日:结果回填槽位;停留该日期时刷新节目列表,空数据/失败提示(不清屏)。 */
    private void fetchDay(Epg slot) {
        int offset = (int) ChronoUnit.DAYS.between(LocalDate.now(zoneId), LocalDate.parse(slot.getDate(), Formatters.DATE));
        fetcher.fetch(channel, offset, result -> binding.program.post(() -> {
            if (!isAdded()) return;
            if (result == null || result.getList().isEmpty()) {
                Notify.show(R.string.live_epg_no_data);
                return;
            }
            dateAdapter.update(result);
            if (dateAdapter.getSelected().getDate().equals(result.getDate())) showProgram(result);
        }));
    }

    @Override
    public void onProgramClick(EpgData item) {
        if (listener != null) listener.accept(item);
        dismiss();
    }

    private void showProgram(Epg epg) {
        epg.selected();
        programAdapter.setEpg(epg);
        binding.program.post(() -> scrollToPosition(binding.program, Math.max(programAdapter.getSelected(), 0)));
    }

    private void scrollToPosition(RecyclerView view, int position) {
        if (view.getLayoutManager() != null) view.getLayoutManager().scrollToPosition(position);
    }

    private void configureWindow(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setDimAmount(0f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        WindowCompat.setDecorFitsSystemWindows(window, true);
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected boolean stableOverlay() {
        return true;
    }

    @Override
    protected void setBehavior(BottomSheetDialog dialog) {
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        sheet.setBackgroundColor(ResUtil.getColor(com.fongmi.android.tv.R.color.transparent));
        int height = getPanelHeight();
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        params.height = height;
        sheet.setLayoutParams(params);
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        behavior.setPeekHeight(height);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
        behavior.setDraggable(false);
    }

    private int getPanelHeight() {
        int screen = ResUtil.getScreenHeight(requireContext());
        if (ResUtil.isLand(requireContext())) return Math.max(ResUtil.dp2px(260), Math.min(ResUtil.dp2px(430), Math.round(screen * 0.76f)));
        return Math.max(ResUtil.dp2px(360), Math.min(ResUtil.dp2px(620), Math.round(screen * 0.62f)));
    }
}
