package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.ElderCard;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public class ElderCoverPickerDialog extends DialogFragment {

    private static final String ARG_CARD_ID = "card_id";

    private ElderCard card;
    private Callback callback;

    public interface Callback {
        void onCardChanged();
        void onPickCoverBuiltin(ElderCard card, String key);
        void onPickCoverLocal(ElderCard card);
        void onPickCoverUrl(ElderCard card);
    }

    public static ElderCoverPickerDialog create(ElderCard card) {
        ElderCoverPickerDialog dialog = new ElderCoverPickerDialog();
        Bundle args = new Bundle();
        args.putString(ARG_CARD_ID, card.getId());
        dialog.setArguments(args);
        dialog.card = card;
        return dialog;
    }

    public ElderCoverPickerDialog callback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof ElderCoverPickerDialog) return;
        }
        show(activity.getSupportFragmentManager(), ElderCoverPickerDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        if (callback == null && getActivity() instanceof Callback) {
            callback = (Callback) getActivity();
        }
        if (card == null) {
            String cardId = getArguments() != null ? getArguments().getString(ARG_CARD_ID) : null;
            if (cardId != null) card = com.fongmi.android.tv.db.AppDatabase.get().getElderCardDao().find(cardId);
        }

        View root = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_elder_cover_picker, null);
        RecyclerView grid = root.findViewById(R.id.coverGrid);
        grid.setLayoutManager(new GridLayoutManager(requireContext(), 4));
        grid.setAdapter(new CoverAdapter(buildItems()));

        return new AlertDialog.Builder(requireContext())
                .setView(root)
                .create();
    }

    private List<CoverItem> buildItems() {
        List<CoverItem> items = new ArrayList<>();
        // 内置精选图库（文字色块占位）
        for (String[] cover : ImgUtil.getBuiltinCovers()) {
            items.add(new CoverItem(CoverItem.KIND_BUILTIN, cover[0], cover[1]));
        }
        // 本地图片
        items.add(new CoverItem(CoverItem.KIND_LOCAL, null, getString(R.string.elder_cover_local)));
        // 网址（兜底高级入口）
        items.add(new CoverItem(CoverItem.KIND_URL, null, getString(R.string.elder_pic_from_url)));
        return items;
    }

    private static class CoverItem {
        static final int KIND_BUILTIN = 0;
        static final int KIND_LOCAL = 1;
        static final int KIND_URL = 2;

        final int kind;
        final String key;
        final String label;

        CoverItem(int kind, String key, String label) {
            this.kind = kind;
            this.key = key;
            this.label = label;
        }
    }

    private class CoverAdapter extends RecyclerView.Adapter<CoverAdapter.VH> {
        private final List<CoverItem> items;

        CoverAdapter(List<CoverItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_elder_cover_item, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            CoverItem item = items.get(position);
            holder.label.setText(item.label);
            if (item.kind == CoverItem.KIND_BUILTIN) {
                holder.cover.setImageDrawable(ImgUtil.builtinDrawable(item.key));
            } else {
                holder.cover.setImageResource(item.kind == CoverItem.KIND_LOCAL ? R.drawable.ic_file : R.drawable.ic_link);
                holder.cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            }
            holder.itemView.setOnClickListener(v -> {
                if (callback == null) return;
                switch (item.kind) {
                    case CoverItem.KIND_BUILTIN:
                        callback.onPickCoverBuiltin(card, item.key);
                        break;
                    case CoverItem.KIND_LOCAL:
                        callback.onPickCoverLocal(card);
                        break;
                    case CoverItem.KIND_URL:
                        callback.onPickCoverUrl(card);
                        break;
                }
                dismiss();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView label;

            VH(@NonNull View itemView) {
                super(itemView);
                cover = itemView.findViewById(R.id.cover);
                label = itemView.findViewById(R.id.label);
            }
        }
    }
}
