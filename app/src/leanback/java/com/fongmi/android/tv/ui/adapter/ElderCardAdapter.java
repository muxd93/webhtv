package com.fongmi.android.tv.ui.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.ElderCard;
import com.fongmi.android.tv.databinding.AdapterElderCardBinding;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.io.File;

public class ElderCardAdapter extends BaseDiffAdapter<ElderCard, ElderCardAdapter.ViewHolder> {

    private static final int COLOR_TILE = 0xFF2A2A2A;

    private final OnClickListener listener;

    public ElderCardAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onItemClick(ElderCard item);
        boolean onItemLongClick(ElderCard item);
        void onItemFocus(ElderCard item);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterElderCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ElderCard item = getItem(position);

        // 加号卡片：隐藏媒体区，显示虚线描边的添加提示
        if (item.getType() == ElderCard.Type.ADD) {
            holder.binding.mediaArea.setVisibility(View.GONE);
            holder.binding.addHint.setVisibility(View.VISIBLE);
            holder.binding.item.setBackgroundResource(R.drawable.bg_elder_card_add);
            return;
        }

        holder.binding.mediaArea.setVisibility(View.VISIBLE);
        holder.binding.addHint.setVisibility(View.GONE);
        holder.binding.item.setBackgroundResource(R.drawable.selector_vod);

        holder.binding.name.setText(item.getName());
        holder.binding.remark.setText(item.getVodRemarks());
        holder.binding.remark.setVisibility(item.getVodRemarks().isEmpty() ? View.GONE : View.VISIBLE);
        bindBadge(holder, item);

        holder.binding.image.setVisibility(View.VISIBLE);
        holder.binding.icon.setVisibility(View.GONE);
        resetCover(holder);
        if (item.getType() == ElderCard.Type.LIVE && !item.hasCustomCover()) {
            // 直播卡片没有海报：用内置电视图标，避免退化成"看"字色块；用户自定义封面优先
            holder.binding.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            holder.binding.image.setBackgroundColor(COLOR_TILE);
            holder.binding.image.setImageResource(R.drawable.ic_elder_video);
        } else if (item.getType() == ElderCard.Type.APP && !item.hasCustomCover()) {
            // 外部应用直接显示应用本身的图标，老人靠图标认 B站
            holder.binding.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            holder.binding.image.setBackgroundColor(COLOR_TILE);
            int pad = ResUtil.dp2px(24);
            holder.binding.image.setPadding(pad, pad, pad, pad);
            Drawable icon = appIcon(holder.binding.image.getContext(), item.getRefKey());
            if (icon != null) holder.binding.image.setImageDrawable(icon);
            else holder.binding.image.setImageResource(android.R.drawable.sym_def_app_icon);
        } else {
            ImgUtil.loadElderCover(item, holder.binding.image);
        }
    }

    /** 还原封面区状态，避免 View 复用把直播/应用卡片的图标与底色带给其它卡片 */
    private void resetCover(ViewHolder holder) {
        holder.binding.image.setPadding(0, 0, 0, 0);
        holder.binding.image.setBackgroundColor(Color.TRANSPARENT);
    }

    private Drawable appIcon(Context context, String pkg) {
        if (pkg == null) return null;
        try {
            return context.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private void bindBadge(ViewHolder holder, ElderCard item) {
        int labelRes;
        int colorRes;
        switch (item.getType() == null ? ElderCard.Type.ADD : item.getType()) {
            case KEEP: labelRes = R.string.elder_badge_keep; colorRes = R.color.elder_badge_keep; break;
            case HISTORY: labelRes = R.string.elder_badge_history; colorRes = R.color.elder_badge_history; break;
            case LOCAL_FILE: labelRes = R.string.elder_badge_local; colorRes = R.color.elder_badge_local; break;
            case SMB: labelRes = R.string.elder_badge_smb; colorRes = R.color.elder_badge_smb; break;
            default:
                holder.binding.badge.setVisibility(View.GONE);
                return;
        }
        holder.binding.badge.setVisibility(View.VISIBLE);
        holder.binding.badge.setText(labelRes);
        holder.binding.badge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(holder.binding.badge.getContext(), colorRes)));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterElderCardBinding binding;

        public ViewHolder(@NonNull AdapterElderCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            setFocusListener();
            setClickListeners();
        }

        private void setClickListeners() {
            itemView.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) listener.onItemClick(getItem(position));
            });
            itemView.setOnLongClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return false;
                return listener.onItemLongClick(getItem(position));
            });
        }

        private void setFocusListener() {
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start();
                    v.setTranslationZ(10f);
                    v.setSelected(true);
                    int position = getBindingAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) listener.onItemFocus(getItem(position));
                } else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    v.setTranslationZ(0f);
                    v.setSelected(false);
                }
            });
        }
    }
}
