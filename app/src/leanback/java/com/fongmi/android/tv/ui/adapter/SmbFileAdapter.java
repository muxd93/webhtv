package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterSmbFileBinding;
import com.fongmi.android.tv.utils.SmbHelper;

import java.util.ArrayList;
import java.util.List;

public class SmbFileAdapter extends RecyclerView.Adapter<SmbFileAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<SmbHelper.SmbFileItem> items;

    public interface OnClickListener {
        void onItemClick(SmbHelper.SmbFileItem item);
        boolean onItemLongClick(SmbHelper.SmbFileItem item);
    }

    public SmbFileAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    public void setItems(List<SmbHelper.SmbFileItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSmbFileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SmbHelper.SmbFileItem item = items.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.image.setImageResource(item.isDirectory() ? R.drawable.ic_smb_folder : R.drawable.ic_smb_video);
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        holder.binding.getRoot().setOnLongClickListener(v -> listener.onItemLongClick(item));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSmbFileBinding binding;

        ViewHolder(@NonNull AdapterSmbFileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
