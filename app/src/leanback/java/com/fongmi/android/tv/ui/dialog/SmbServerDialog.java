package com.fongmi.android.tv.ui.dialog;

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
import com.fongmi.android.tv.bean.SmbServer;
import com.fongmi.android.tv.db.AppDatabase;

import java.util.List;

/**
 * 已保存 SMB 服务器的管理弹窗。
 * 点击卡片进入浏览；长按可删除服务器；尾部固定「添加新服务器」卡片。
 */
public class SmbServerDialog extends DialogFragment {

    private Adapter adapter;
    private List<SmbServer> servers;
    private Callback callback;

    public interface Callback {

        void onServerClick(SmbServer server);

        void onAddServer();
    }

    public static SmbServerDialog create() {
        return new SmbServerDialog();
    }

    public SmbServerDialog callback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof SmbServerDialog) return;
        }
        show(activity.getSupportFragmentManager(), SmbServerDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public AlertDialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View root = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_smb_server, null);
        RecyclerView recycler = root.findViewById(R.id.recycler);
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        recycler.setAdapter(adapter = new Adapter());
        reload();
        return new AlertDialog.Builder(requireContext()).setView(root).create();
    }

    private void reload() {
        servers = AppDatabase.get().getSmbServerDao().findAll();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private static String displayName(SmbServer server) {
        return server.getName() != null && !server.getName().isEmpty() ? server.getName() : server.getHost();
    }

    private void confirmDelete(SmbServer server) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.smb_delete_confirm, displayName(server)))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    server.delete();
                    reload();
                    if (servers.isEmpty()) dismiss();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private class Adapter extends RecyclerView.Adapter<ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_smb_server, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            if (position == servers.size()) {
                holder.cover.setImageResource(R.drawable.ic_smb);
                holder.cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                holder.name.setText(R.string.home_smb_new_server);
                holder.itemView.setOnClickListener(v -> {
                    dismiss();
                    if (callback != null) callback.onAddServer();
                });
                holder.itemView.setOnLongClickListener(null);
                return;
            }
            SmbServer server = servers.get(position);
            holder.cover.setImageResource(R.drawable.ic_smb);
            holder.cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            holder.name.setText(displayName(server));
            holder.itemView.setOnClickListener(v -> {
                dismiss();
                if (callback != null) callback.onServerClick(server);
            });
            holder.itemView.setOnLongClickListener(v -> {
                confirmDelete(server);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return (servers == null ? 0 : servers.size()) + 1;
        }
    }

    private static class ViewHolder extends RecyclerView.ViewHolder {

        final ImageView cover;
        final TextView name;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.cover);
            name = itemView.findViewById(R.id.name);
        }
    }
}
