package com.fongmi.android.tv.bean;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.utils.ResUtil;

public class Func implements Diffable<Func> {

    /** 功能主次层级：主组为高频内容入口，次组为工具栏/系统能力（视觉降权） */
    public enum Tier {
        PRIMARY,
        SECONDARY
    }

    private final int resId;
    private final Tier tier;
    private int drawable;

    public static Func create(int resId) {
        return new Func(resId, Tier.PRIMARY);
    }

    public static Func create(int resId, Tier tier) {
        return new Func(resId, tier);
    }

    public Func(int resId, Tier tier) {
        this.resId = resId;
        this.tier = tier == null ? Tier.PRIMARY : tier;
        this.setDrawable();
    }

    public int getResId() {
        return resId;
    }

    public int getDrawable() {
        return drawable;
    }

    public Tier getTier() {
        return tier;
    }

    public String getText() {
        return ResUtil.getString(resId);
    }

    public void setDrawable() {
        if (resId == R.string.home_vod) this.drawable = R.drawable.ic_home_vod;
        else if (resId == R.string.home_live) this.drawable = R.drawable.ic_home_live;
        else if (resId == R.string.home_keep) this.drawable = R.drawable.ic_home_keep;
        else if (resId == R.string.home_push) this.drawable = R.drawable.ic_home_push;
        else if (resId == R.string.home_search) this.drawable = R.drawable.ic_home_search;
        else if (resId == R.string.home_setting) this.drawable = R.drawable.ic_home_setting;
        else if (resId == R.string.home_smb) this.drawable = R.drawable.ic_home_smb;
        else if (resId == R.string.home_app) this.drawable = R.drawable.ic_home_app;
        else if (resId == R.string.home_file) this.drawable = R.drawable.ic_home_file;
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
