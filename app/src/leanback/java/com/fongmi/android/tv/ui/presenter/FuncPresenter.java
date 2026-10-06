package com.fongmi.android.tv.ui.presenter;

import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.databinding.AdapterFuncBinding;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;

public class FuncPresenter extends Presenter {

    private final OnClickListener listener;

    public FuncPresenter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onItemClick(Func item);

        void onItemFocus(Func item);

        boolean onLongClick(Func item);
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new ViewHolder(AdapterFuncBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        Func item = (Func) object;
        ViewHolder holder = (ViewHolder) viewHolder;
        holder.binding.text.setText(item.getText());
        holder.binding.icon.setImageResource(item.getDrawable());
        applyTierStyle(holder, item.getTier());
        applyIconTint(holder, item);
        setOnClickListener(holder, view -> listener.onItemClick(item));
        holder.view.setOnLongClickListener(view -> listener.onLongClick(item));
        holder.view.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) listener.onItemFocus(item);
        });
        holder.view.setOnKeyListener((view, keyCode, event) -> onKeyDown(view, event));
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    /** 主次组统一醒目度（QA2 反馈：降权样式被误读为禁用）；次组仅保留组前分隔间距 */
    private void applyTierStyle(ViewHolder holder, Func.Tier tier) {
        holder.binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        holder.binding.getRoot().setAlpha(1f);
        ViewGroup.LayoutParams lp = holder.binding.getRoot().getLayoutParams();
        if (lp instanceof MarginLayoutParams) {
            ((MarginLayoutParams) lp).leftMargin = tier == Func.Tier.SECONDARY ? ResUtil.dp2px(18) : 0;
            holder.binding.getRoot().setLayoutParams(lp);
        }
    }

    /** 全部功能项彩色图标；无色项（0）保持原样 */
    private void applyIconTint(ViewHolder holder, Func item) {
        int tint = item.getTint();
        if (tint != 0) holder.binding.icon.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
    }

    private boolean onKeyDown(android.view.View view, KeyEvent event) {
        if (!KeyUtil.isActionDown(event) || (!KeyUtil.isLeftKey(event) && !KeyUtil.isRightKey(event))) return false;
        if (!(view.getParent() instanceof RecyclerView recyclerView) || recyclerView.getAdapter() == null) return false;
        int count = recyclerView.getAdapter().getItemCount();
        if (count <= 1) return false;
        int position = recyclerView.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION) return false;
        if (KeyUtil.isRightKey(event) && position == count - 1) return requestFocus(recyclerView, 0);
        if (KeyUtil.isLeftKey(event) && position == 0) return requestFocus(recyclerView, count - 1);
        return false;
    }

    private boolean requestFocus(RecyclerView recyclerView, int position) {
        RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView.requestFocus()) return true;
        recyclerView.scrollToPosition(position);
        recyclerView.post(() -> {
            RecyclerView.ViewHolder next = recyclerView.findViewHolderForAdapterPosition(position);
            if (next != null) next.itemView.requestFocus();
        });
        return true;
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterFuncBinding binding;

        public ViewHolder(@NonNull AdapterFuncBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
