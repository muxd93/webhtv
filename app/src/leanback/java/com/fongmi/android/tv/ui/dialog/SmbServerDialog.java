package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.fongmi.android.tv.bean.SmbServer;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.db.dao.ElderCardDao;
import com.fongmi.android.tv.ui.activity.FileActivity;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 已保存 SMB 服务器的封面卡片选择弹窗。
 * <p>
 * 打开时按 SmbServer 列表同步 ElderCard(Type.SMB) 行（缺则建、孤儿删），零输入即可用：
 * 默认按名称首字生成色块封面。点击卡片进入浏览；长按可更换封面（内置图库/本地图片/网址）或删除服务器；
 * 尾部固定「添加新服务器」卡片。
 */
public class SmbServerDialog extends DialogFragment implements ElderCoverPickerDialog.Callback {

    private Adapter adapter;
    private List<SmbServer> servers;
    private List<ElderCard> cards;
    private Callback callback;
    private ElderCard pendingCoverCard;
    private ActivityResultLauncher<Intent> pickCoverLauncher;

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

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickCoverLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (pendingCoverCard == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            String path = FileChooser.getPathFromUri(result.getData().getData());
            if (TextUtils.isEmpty(path)) return;
            pendingCoverCard.applyCover(ElderCard.CoverType.LOCAL, path);
            pendingCoverCard.save();
            pendingCoverCard = null;
            reload();
        });
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

    /** 同步卡片与服务器：缺则建、孤儿删，然后按 createTime 顺序重新加载 */
    private void reload() {
        servers = AppDatabase.get().getSmbServerDao().findAll();
        ElderCardDao dao = AppDatabase.get().getElderCardDao();
        List<ElderCard> existing = dao.findByType(ElderCard.Type.SMB);
        Map<String, ElderCard> byRef = new HashMap<>();
        for (ElderCard card : existing) byRef.put(card.getRefKey(), card);
        for (SmbServer server : servers) {
            if (byRef.containsKey(server.getId())) continue;
            ElderCard.create(ElderCard.Type.SMB, server.getId(), displayName(server), "", 0, null).save();
        }
        for (ElderCard card : existing) {
            if (findServer(card.getRefKey()) == null) card.delete();
        }
        cards = dao.findByType(ElderCard.Type.SMB);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private SmbServer findServer(String refKey) {
        if (servers == null || refKey == null) return null;
        for (SmbServer server : servers) if (server.getId().equals(refKey)) return server;
        return null;
    }

    private static String displayName(SmbServer server) {
        return server.getName() != null && !server.getName().isEmpty() ? server.getName() : server.getHost();
    }

    private void showMenu(ElderCard card) {
        String[] items = {getString(R.string.smb_menu_cover), getString(R.string.smb_menu_delete)};
        new AlertDialog.Builder(requireContext())
                .setTitle(card.getName())
                .setItems(items, (dialog, which) -> {
                    if (which == 0) ElderCoverPickerDialog.create(card).callback(this).show(requireActivity());
                    else confirmDelete(card);
                })
                .show();
    }

    private void confirmDelete(ElderCard card) {
        SmbServer server = findServer(card.getRefKey());
        if (server == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.smb_delete_confirm, displayName(server)))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    server.delete();
                    card.delete();
                    reload();
                    if (cards.isEmpty()) dismiss();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void showUrlInput(ElderCard card) {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setHint(R.string.smb_url_hint);
        if (card.getCoverType() == ElderCard.CoverType.URL.value && card.getCoverValue() != null) input.setText(card.getCoverValue());
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.elder_pic_from_url)
                .setView(input)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    card.applyCover(ElderCard.CoverType.URL, url);
                    card.save();
                    reload();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    @Override
    public void onCardChanged() {
        reload();
    }

    @Override
    public void onPickCoverBuiltin(ElderCard card, String key) {
        card.applyCover(ElderCard.CoverType.BUILTIN, key);
        card.save();
        reload();
    }

    @Override
    public void onPickCoverLocal(ElderCard card) {
        pendingCoverCard = card;
        pickCoverLauncher.launch(new Intent(requireContext(), FileActivity.class));
    }

    @Override
    public void onPickCoverUrl(ElderCard card) {
        showUrlInput(card);
    }

    private class Adapter extends RecyclerView.Adapter<ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_smb_server, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            if (position == cards.size()) {
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
            ElderCard card = cards.get(position);
            ImgUtil.loadElderCover(card, holder.cover);
            holder.name.setText(card.getName());
            holder.itemView.setOnClickListener(v -> {
                SmbServer server = findServer(card.getRefKey());
                if (server == null) return;
                dismiss();
                if (callback != null) callback.onServerClick(server);
            });
            holder.itemView.setOnLongClickListener(v -> {
                showMenu(card);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return (cards == null ? 0 : cards.size()) + 1;
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
