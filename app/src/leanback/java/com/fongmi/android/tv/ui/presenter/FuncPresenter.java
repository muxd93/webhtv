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

    /** 主组保持默认醒目样式；次组降权（缩小文字、降低不透明度）并在组前留出分隔间距 */
    private void applyTierStyle(ViewHolder holder, Func.Tier tier) {
        boolean secondary = tier == Func.Tier.SECONDARY;
        holder.binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, secondary ? 15 : 18);
        holder.binding.getRoot().setAlpha(secondary ? 0.68f : 1f);
        ViewGroup.LayoutParams lp = holder.binding.getRoot().getLayoutParams();
        if (lp instanceof MarginLayoutParams) {
            ((MarginLayoutParams) lp).leftMargin = secondary ? ResUtil.dp2px(18) : 0;
            holder.binding.getRoot().setLayoutParams(lp);
        }
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
