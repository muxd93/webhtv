package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.FeatureGate;

import java.util.ArrayList;
import java.util.List;

public final class PlayerKernelDialog {

    public interface Listener {
        void onSelected(int player);
    }

    private PlayerKernelDialog() {
    }

    public static void show(FragmentActivity activity, int selected, Listener listener) {
        show(activity, selected, activity.getResources().getStringArray(R.array.select_player_kernel), listener);
    }

    public static void show(Fragment fragment, int selected, Listener listener) {
        show(fragment.requireActivity(), selected, fragment.getResources().getStringArray(R.array.select_player_kernel), listener);
    }

    private static void show(FragmentActivity activity, int selected, String[] labels, Listener listener) {
        // 按 feature 开关过滤可用引擎：EXO 始终可用，IJK/MPV 受对应开关约束。
        List<Integer> available = new ArrayList<>();
        available.add(PlayerSetting.EXO);
        if (FeatureGate.ijk()) available.add(PlayerSetting.IJK);
        if (FeatureGate.mpv()) available.add(PlayerSetting.MPV);
        List<String> items = new ArrayList<>();
        for (int player : available) items.add(labels[player]);
        int current = PlayerSetting.sanitizePlayer(selected);
        int selIndex = available.indexOf(current);
        if (selIndex < 0) selIndex = 0;
        ChoiceDialog.showSingleNoCancel(activity, R.string.player_kernel, items.toArray(new String[0]), selIndex,
                which -> {
                    if (listener != null) listener.onSelected(available.get(which));
                });
    }
}
