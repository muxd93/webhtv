package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.AppListUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

import androidx.appcompat.app.AlertDialog;

/** 正常模式的「应用」入口：列出可启动的电视应用，点击直接拉起 */
public class AppListDialog {

    public static void show(Activity activity) {
        List<ResolveInfo> apps = AppListUtil.query(activity);
        if (apps.isEmpty()) {
            Notify.show(R.string.home_app_none);
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.home_app)
                .setAdapter(new AppListAdapter(activity, apps), (dialog, which) -> AppListUtil.launch(activity, apps.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 带应用图标的选择列表，遥控器上下键可正常滚动 */
    private static class AppListAdapter extends ArrayAdapter<ResolveInfo> {

        private final android.content.pm.PackageManager pm;

        AppListAdapter(Activity activity, List<ResolveInfo> apps) {
            super(activity, android.R.layout.simple_list_item_1, apps);
            this.pm = activity.getPackageManager();
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            ResolveInfo info = getItem(position);
            if (info == null) return view;
            view.setText(info.loadLabel(pm));
            view.setTextSize(20);
            int pad = ResUtil.dp2px(16);
            view.setPadding(pad, pad, pad, pad);
            try {
                Drawable icon = pm.getApplicationIcon(info.activityInfo.packageName);
                int size = ResUtil.dp2px(32);
                icon.setBounds(0, 0, size, size);
                view.setCompoundDrawables(icon, null, null, null);
                view.setCompoundDrawablePadding(pad);
            } catch (Exception ignored) {
            }
            return view;
        }
    }
}
