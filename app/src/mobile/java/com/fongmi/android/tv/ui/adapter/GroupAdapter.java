package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.databinding.AdapterGroupBinding;
import com.fongmi.android.tv.setting.LiveSetting;

import java.util.ArrayList;
import java.util.List;

public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.ViewHolder> {

    private static final Object PAYLOAD_SELECTED = new Object();

    private final OnClickListener listener;
    private final List<Group> mItems;
    private int selectedPosition;

    public GroupAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        this.selectedPosition = -1;
    }

    public interface OnClickListener {

        void setWidth(Group item);

        void onItemClick(Group item);
    }

    public void clear() {
        mItems.clear();
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    public void addAll(List<Group> items) {
        mItems.clear();
        mItems.addAll(items);
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    public void add(Group item) {
        mItems.add(item);
        notifyItemInserted(getItemCount() - 1);
    }

    public Group get(int position) {
        return mItems.get(position);
    }

    public int getPosition() {
        return selectedPosition;
    }

    public int indexOf(Group group) {
        return mItems.indexOf(group);
    }

    public void setSelected(Group group) {
        setSelected(indexOf(group));
    }

    public void setSelected(int position) {
        if (position < 0 || position >= mItems.size()) return;
        int old = selectedPosition;
        selectedPosition = position;
        if (old >= 0 && old < mItems.size() && old != position) notifyItemChanged(old, PAYLOAD_SELECTED);
        notifyItemChanged(position, PAYLOAD_SELECTED);
        listener.setWidth(mItems.get(position));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterGroupBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Group item = mItems.get(position);
        holder.binding.name.setText(item.getName());
        setListStyle(holder);
        holder.binding.getRoot().setSelected(position == selectedPosition);
        holder.binding.getRoot().setOnClickListener(view -> listener.onItemClick(item));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
        } else {
            holder.binding.getRoot().setSelected(position == selectedPosition);
        }
    }

    private void setListStyle(ViewHolder holder) {
        boolean classic = LiveSetting.isListStyleClassic();
        holder.binding.name.setBackgroundResource(classic ? R.drawable.shape_live_classic : R.drawable.shape_live);
        holder.binding.name.setTextColor(ContextCompat.getColorStateList(holder.binding.name.getContext(), classic ? R.color.selector_live_text_classic : R.color.selector_live_text));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterGroupBinding binding;

        ViewHolder(@NonNull AdapterGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
