package com.fongmi.android.tv.bean;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.utils.ResUtil;

public class Func implements Diffable<Func> {

    private final int resId;
    private int drawable;
    private int color = R.color.white;

    public static Func create(int resId) {
        return new Func(resId);
    }

    public Func(int resId) {
        this.resId = resId;
        this.setDrawable();
    }

    public int getResId() {
        return resId;
    }

    public int getDrawable() {
        return drawable;
    }

    public int getColor() {
        return color;
    }

    public String getText() {
        return ResUtil.getString(resId);
    }

    public void setDrawable() {
        if (resId == R.string.home_vod) style(R.drawable.ic_home_vod, R.color.func_icon_vod);
        else if (resId == R.string.home_live) style(R.drawable.ic_home_live, R.color.func_icon_live);
        else if (resId == R.string.home_keep) style(R.drawable.ic_home_keep, R.color.func_icon_keep);
        else if (resId == R.string.home_push) style(R.drawable.ic_home_push, R.color.func_icon_push);
        else if (resId == R.string.home_search) style(R.drawable.ic_home_search, R.color.func_icon_search);
        else if (resId == R.string.home_setting) style(R.drawable.ic_home_setting, R.color.func_icon_setting);
        else if (resId == R.string.download_action) style(R.drawable.ic_detail_download, R.color.func_icon_download);
    }

    private void style(int drawable, int color) {
        this.drawable = drawable;
        this.color = color;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Func it)) return false;
        return getResId() == it.getResId();
    }

    @Override
    public boolean isSameItem(Func other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(Func other) {
        return equals(other);
    }
}
