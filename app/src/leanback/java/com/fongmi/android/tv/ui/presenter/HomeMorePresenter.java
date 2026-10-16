package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.HomeMore;
import com.fongmi.android.tv.databinding.AdapterHomeMoreBinding;
import com.fongmi.android.tv.ui.HomeRows;
import com.fongmi.android.tv.utils.ResUtil;

public class HomeMorePresenter extends Presenter {

    private final HomeRows.OnMoreClickListener mListener;
    private final int width;
    private final int height;

    public HomeMorePresenter(HomeRows.OnMoreClickListener listener) {
        mListener = listener;
        int column = Math.max(1, Product.getColumn());
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * (column - 1));
        int base = ResUtil.getScreenWidth() - space;
        width = base / column;
        height = (int) (width / 0.75f);
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        ViewHolder holder = new ViewHolder(AdapterHomeMoreBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.getRoot().getLayoutParams().height = height;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        HomeMore item = (HomeMore) object;
        ViewHolder holder = (ViewHolder) viewHolder;
        holder.binding.text.setText(holder.view.getContext().getString(R.string.home_view_all, item.getCount()));
        holder.view.setOnClickListener(view -> mListener.onMoreClick(item.getKind()));
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        viewHolder.view.setOnClickListener(null);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterHomeMoreBinding binding;

        public ViewHolder(@NonNull AdapterHomeMoreBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
