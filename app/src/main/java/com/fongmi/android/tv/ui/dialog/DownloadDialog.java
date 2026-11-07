package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DownloadTask;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.databinding.DialogDownloadBinding;
import com.fongmi.android.tv.databinding.DialogDownloadEpisodeBinding;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.download.DownloadStore;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class DownloadDialog extends DialogFragment {

    private DialogDownloadBinding binding;
    private EpisodeAdapter adapter;
    private String siteKey;
    private String vodId;
    private String vodName;
    private String vodPic;
    private List<Flag> flags;

    public static DownloadDialog create() {
        return new DownloadDialog();
    }

    public DownloadDialog siteKey(String siteKey) {
        this.siteKey = siteKey;
        return this;
    }

    public DownloadDialog vodId(String vodId) {
        this.vodId = vodId;
        return this;
    }

    public DownloadDialog vodName(String vodName) {
        this.vodName = vodName;
        return this;
    }

    public DownloadDialog vodPic(String vodPic) {
        this.vodPic = vodPic;
        return this;
    }

    public DownloadDialog flags(List<Flag> flags) {
        this.flags = flags;
        return this;
    }

    private Flag getFlag() {
        if (flags == null || flags.isEmpty()) return null;
        for (Flag item : flags) if (item.isSelected()) return item;
        return flags.get(0);
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof DownloadDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogDownloadBinding.inflate(getLayoutInflater());
        initView();
        initEvent();
        // 标题/正文/操作行放在同一张浅色卡片里：系统对话框底色来自深色电视主题，只给自定义视图加白底会形成
        // 「深色外框 + 白卡 + 深色标题 + 深色按钮区」的夹层，且底部按钮在矮屏上会被推出屏幕
        return LightDialog.create(requireActivity(), vodName, binding.getRoot(),
                getString(R.string.download_start), view -> confirm(),
                getString(R.string.dialog_negative), null);
    }

    private void initView() {
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(requireContext()));
        binding.recycler.getLayoutParams().height = listHeight();
        binding.recycler.setAdapter(adapter = new EpisodeAdapter());
        binding.recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
    }

    /** 列表高度跟随可用高度：固定 300dp 在 480~540dp 高的电视上会把底部操作行挤出屏幕 */
    private int listHeight() {
        int screen = ResUtil.getScreenHeight(requireContext());
        return Math.min(ResUtil.dp2px(300), Math.max(ResUtil.dp2px(120), screen * 2 / 5));
    }

    private void initEvent() {
        binding.selectAll.setOnClickListener(view -> {
            adapter.setAllChecked(true);
            updateCount();
        });
        binding.invert.setOnClickListener(view -> {
            adapter.invert();
            updateCount();
        });
        updateCount();
    }

    private void updateCount() {
        if (adapter == null) return;
        binding.count.setText(getString(R.string.download_selected, adapter.checkedCount(), adapter.getItemCount()));
    }

    private void confirm() {
        Flag flag = getFlag();
        if (flag == null || adapter == null) return;
        List<Episode> episodes = adapter.getChecked();
        if (episodes.isEmpty()) return;
        int added = DownloadManager.add(siteKey, vodId, vodName, vodPic, flag.getFlag(), episodes);
        if (added > 0) DownloadManager.toastAdded(added);
        else Notify.show(R.string.download_already);
        dismiss();
    }

    private class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {

        private final List<Item> items = new ArrayList<>();

        private EpisodeAdapter() {
            Flag flag = getFlag();
            if (flag == null) return;
            for (Episode episode : flag.getEpisodes()) {
                boolean cached = false;
                for (DownloadTask task : DownloadStore.findByEpisodeUrl(siteKey, vodId, episode.getUrl())) {
                    if (task.isCompleted()) {
                        cached = true;
                        break;
                    }
                }
                items.add(new Item(episode, cached));
            }
        }

        private void setAllChecked(boolean checked) {
            for (Item item : items) if (!item.cached) item.checked = checked;
            notifyDataSetChanged();
        }

        private void invert() {
            for (Item item : items) if (!item.cached) item.checked = !item.checked;
            notifyDataSetChanged();
        }

        private int checkedCount() {
            int count = 0;
            for (Item item : items) if (item.checked) count++;
            return count;
        }

        private List<Episode> getChecked() {
            List<Episode> episodes = new ArrayList<>();
            for (Item item : items) if (item.checked) episodes.add(item.episode);
            return episodes;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(DialogDownloadEpisodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Item item = items.get(position);
            holder.binding.name.setText(item.episode.getName());
            holder.binding.check.setChecked(item.checked);
            holder.binding.tag.setVisibility(item.cached ? View.VISIBLE : View.GONE);
            holder.binding.getRoot().setOnClickListener(view -> {
                if (item.cached) return;
                item.checked = !item.checked;
                holder.binding.check.setChecked(item.checked);
                updateCount();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private class Item {

            private final Episode episode;
            private final boolean cached;
            private boolean checked = true;

            private Item(Episode episode, boolean cached) {
                this.episode = episode;
                this.cached = cached;
                this.checked = !cached;
            }
        }

        private class ViewHolder extends RecyclerView.ViewHolder {

            private final DialogDownloadEpisodeBinding binding;

            private ViewHolder(DialogDownloadEpisodeBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
