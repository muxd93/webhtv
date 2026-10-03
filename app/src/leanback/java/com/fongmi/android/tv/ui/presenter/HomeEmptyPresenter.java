package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.databinding.AdapterHomeEmptyBinding;

/** 首页推荐区无内容时的居中空态提示行，Marker 仅用于 ArrayObjectAdapter 内路由 */
public class HomeEmptyPresenter extends Presenter {

    public static final class Marker {

        public static final Marker INSTANCE = new Marker();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new ViewHolder(AdapterHomeEmptyBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterHomeEmptyBinding binding;

        public ViewHolder(@NonNull AdapterHomeEmptyBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
