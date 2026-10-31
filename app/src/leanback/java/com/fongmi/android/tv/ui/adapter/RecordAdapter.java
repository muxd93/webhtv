package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterSearchRecordBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

public class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<String> mHistory;  // 真实搜索历史（持久化于 Setting）
    private final List<String> mDisplay;  // 历史 + 预置默认词（去重后用于展示）

    public RecordAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mHistory = getHistory();
        this.mDisplay = buildDisplay();
        this.listener.onDataChanged(mDisplay.size());
    }

    public interface OnClickListener {

        void onItemClick(String text);

        void onDataChanged(int size);
    }

    private List<String> getHistory() {
        if (Setting.getKeyword().isEmpty()) return new ArrayList<>();
        return App.gson().fromJson(Setting.getKeyword(), TypeToken.getParameterized(List.class, String.class).getType());
    }

    /** 预置默认搜索词（如儿童节目），作为空历史的快捷入口，不参与真实历史存储、不可删除 */
    private List<String> getDefaults() {
        List<String> list = new ArrayList<>();
        for (String keyword : ResUtil.getStringArray(R.array.search_default_keywords)) {
            list.add(keyword);
        }
        return list;
    }

    private List<String> buildDisplay() {
        List<String> display = new ArrayList<>(mHistory);
        for (String keyword : getDefaults()) {
            if (!display.contains(keyword)) display.add(keyword);
        }
        return display;
    }

    private void saveHistory() {
        Setting.putKeyword(App.gson().toJson(mHistory));
    }

    private void rebuild() {
        mDisplay.clear();
        mDisplay.addAll(buildDisplay());
    }

    public void clearAll() {
        mHistory.clear();
        saveHistory();
        rebuild();
        notifyDataSetChanged();
        listener.onDataChanged(mDisplay.size());
    }

    public void add(String item) {
        mHistory.remove(item);
        mHistory.add(0, item);
        if (mHistory.size() > 9) mHistory.remove(9);
        saveHistory();
        rebuild();
        notifyDataSetChanged();
        listener.onDataChanged(getItemCount());
    }

    @Override
    public int getItemCount() {
        return mDisplay.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchRecordBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String text = mDisplay.get(position);
        holder.binding.text.setText(text);
        holder.binding.text.setOnClickListener(v -> listener.onItemClick(text));
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnLongClickListener {

        private final AdapterSearchRecordBinding binding;

        public ViewHolder(@NonNull AdapterSearchRecordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnLongClickListener(this);
        }

        @Override
        public boolean onLongClick(View v) {
            int position = getLayoutPosition();
            if (position < 0 || position >= mDisplay.size()) return false;
            String text = mDisplay.get(position);
            if (!mHistory.contains(text)) return false; // 默认词不可删除
            mHistory.remove(text);
            saveHistory();
            rebuild();
            notifyDataSetChanged();
            listener.onDataChanged(mDisplay.size());
            return true;
        }
    }
}
