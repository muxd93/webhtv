package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.databinding.AdapterKeepMoreBinding;
import com.fongmi.android.tv.utils.ResUtil;

public class KeepMorePresenter extends Presenter {

    private final OnClickListener listener;
    private int width, height;

    public KeepMorePresenter(OnClickListener listener) {
        this.listener = listener;
        setLayoutSize();
    }

    /** 收藏行的"查看全部"尾卡标记，仅供行内选择器路由 Presenter */
    public static final class Marker {

        public static final Marker INSTANCE = new Marker();
    }

    public interface OnClickListener {

        void onMoreClick();
    }

    private void setLayoutSize() {
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * (Product.getColumn() - 1));
        int base = ResUtil.getScreenWidth() - space;
        width = base / Product.getColumn();
        height = (int) (width / 0.75f);
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        ViewHolder holder = new ViewHolder(AdapterKeepMoreBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.getRoot().getLayoutParams().height = height + ResUtil.dp2px(30);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        ViewHolder holder = (ViewHolder) viewHolder;
        holder.view.setOnClickListener(view -> listener.onMoreClick());
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterKeepMoreBinding binding;

        public ViewHolder(@NonNull AdapterKeepMoreBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
