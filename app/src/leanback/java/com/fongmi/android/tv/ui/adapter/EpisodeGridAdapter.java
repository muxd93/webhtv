package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.ui.holder.EpisodeGridHolder;

import java.util.ArrayList;
import java.util.List;

public class EpisodeGridAdapter extends RecyclerView.Adapter<EpisodeGridHolder> {

    private final EpisodeAdapter.OnClickListener listener;
    private final List<Episode> mItems;

    public EpisodeGridAdapter(EpisodeAdapter.OnClickListener listener, ArrayList<Episode> items) {
        this.listener = listener;
        this.mItems = items;
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    @NonNull
    @Override
    public EpisodeGridHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new EpisodeGridHolder(AdapterEpisodeGridBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull EpisodeGridHolder holder, int position) {
        holder.initView(mItems.get(position));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }
}
