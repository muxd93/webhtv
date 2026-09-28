package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.SmbDevice;
import com.fongmi.android.tv.databinding.AdapterSmbDeviceBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫描到的局域网 SMB 服务器列表。也复用于"选择共享夹"这一步（此时每项代表一个共享）。
 */
public class SmbDeviceAdapter extends RecyclerView.Adapter<SmbDeviceAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<SmbDevice> items;

    public interface OnClickListener {
        void onDeviceClick(SmbDevice device);
    }

    public SmbDeviceAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
    }

    public void addItem(SmbDevice device) {
        if (items.contains(device)) return;
        items.add(device);
        notifyItemInserted(items.size() - 1);
    }

    public void updateItem(SmbDevice device) {
        int index = items.indexOf(device);
        if (index < 0) return;
        items.set(index, device);
        notifyItemChanged(index);
    }

    public void setItems(List<SmbDevice> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSmbDeviceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SmbDevice item = items.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.desc.setText(item.getSubtitle());
        holder.binding.image.setImageResource(R.drawable.ic_elder_smb);
        boolean needAuth = item.isNeedAuth();
        holder.binding.tag.setVisibility(needAuth ? View.VISIBLE : View.GONE);
        if (needAuth) holder.binding.tag.setText(R.string.elder_smb_need_password);
        holder.binding.getRoot().setOnClickListener(v -> listener.onDeviceClick(item));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSmbDeviceBinding binding;

        ViewHolder(@NonNull AdapterSmbDeviceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
