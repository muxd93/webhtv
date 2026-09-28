package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.SmbDevice;
import com.fongmi.android.tv.bean.SmbServer;
import com.fongmi.android.tv.databinding.ActivitySmbDiscoverBinding;
import com.fongmi.android.tv.databinding.DialogAddSmbServerBinding;
import com.fongmi.android.tv.databinding.DialogSmbLoginBinding;
import com.fongmi.android.tv.ui.adapter.SmbDeviceAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.SmbHelper;
import com.fongmi.android.tv.utils.SmbScanTask;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 老人模式下的局域网共享添加向导。
 * <p>
 * 把原先"手填 6 个字段"的表单改造成三步单选流程：
 * <pre>
 *   扫描设备 → 选择服务器 → 选择共享夹 → 完成
 * </pre>
 * 对匿名可访问的服务器，用户全程无需任何键盘输入；仅当匿名被拒时才索要账号密码。
 */
public class SmbDiscoverActivity extends BaseActivity implements SmbDeviceAdapter.OnClickListener, SmbScanTask.Listener {

    public static final String EXTRA_SERVER_ID = "server_id";

    private static final int STEP_DEVICE = 0;
    private static final int STEP_SHARE = 1;

    private ActivitySmbDiscoverBinding binding;
    private SmbDeviceAdapter adapter;
    private SmbScanTask scanTask;
    private SmbDevice selected;
    private List<SmbDevice> devices;
    private String username;
    private String password;
    private int step = STEP_DEVICE;
    private boolean scanning;

    public static void start(Activity activity, int requestCode) {
        activity.startActivityForResult(new Intent(activity, SmbDiscoverActivity.class), requestCode);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivitySmbDiscoverBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        devices = new ArrayList<>();
        setRecyclerView();
        startScan();
    }

    @Override
    protected void initEvent() {
        binding.rescan.setOnClickListener(v -> startScan());
        binding.manual.setOnClickListener(v -> showManualDialog());
    }

    private void setRecyclerView() {
        adapter = new SmbDeviceAdapter(this);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter);
    }

    // ---- Step 1: 扫描 ----

    private void startScan() {
        stopScan();
        step = STEP_DEVICE;
        scanning = true;
        selected = null;
        devices.clear();
        adapter.clear();
        binding.title.setText(R.string.elder_smb_scanning);
        binding.subtitle.setText(R.string.elder_smb_scanning_tip);
        binding.empty.setVisibility(View.GONE);
        binding.progress.setVisibility(View.VISIBLE);
        binding.rescan.setVisibility(View.GONE);
        binding.manual.setVisibility(View.VISIBLE);
        scanTask = new SmbScanTask(this);
        scanTask.start();
    }

    private void stopScan() {
        if (scanTask != null) scanTask.stop();
        scanTask = null;
        scanning = false;
    }

    @Override
    public void onFind(SmbDevice device) {
        if (step != STEP_DEVICE) return;
        devices.add(device);
        adapter.addItem(device);
        binding.progress.setVisibility(View.GONE);
        binding.empty.setVisibility(View.GONE);
        binding.title.setText(R.string.elder_smb_select_device);
        binding.subtitle.setText(R.string.elder_smb_select_device_tip);
    }

    @Override
    public void onUpdate(SmbDevice device) {
        if (step != STEP_DEVICE) return;
        adapter.updateItem(device);
    }

    @Override
    public void onFinish() {
        scanning = false;
        if (step != STEP_DEVICE) return;
        binding.progress.setVisibility(View.GONE);
        binding.rescan.setVisibility(View.VISIBLE);
        if (adapter.getItemCount() > 0) {
            binding.title.setText(R.string.elder_smb_select_device);
            binding.subtitle.setText(R.string.elder_smb_select_device_tip);
        } else {
            binding.title.setText(R.string.elder_smb_not_found);
            binding.subtitle.setText(R.string.elder_smb_not_found_tip);
            binding.emptyText.setText(R.string.elder_smb_not_found_hint);
            binding.empty.setVisibility(View.VISIBLE);
        }
    }

    // ---- Step 2: 选服务器 ----

    @Override
    public void onDeviceClick(SmbDevice device) {
        if (step == STEP_SHARE) {
            confirmShare(device.getName());
            return;
        }
        selected = device;
        username = null;
        password = null;
        if (device.isNeedAuth()) {
            showLoginDialog(device);
        } else if (device.getShares().isEmpty()) {
            loadShares(device, null, null);
        } else {
            showShares(device.getShares());
        }
    }

    /** 匿名被拒时才弹出的账号密码框。 */
    private void showLoginDialog(SmbDevice device) {
        DialogSmbLoginBinding dialogBinding = DialogSmbLoginBinding.inflate(LayoutInflater.from(this));
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.elder_smb_login_title, device.getName()))
                .setView(dialogBinding.getRoot())
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String user = dialogBinding.username.getText() == null ? "" : dialogBinding.username.getText().toString().trim();
                    String pass = dialogBinding.password.getText() == null ? "" : dialogBinding.password.getText().toString();
                    loadShares(device, user, pass);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    /** 拉取共享列表；这一步把用户从"必须知道共享名"中解放出来。 */
    private void loadShares(SmbDevice device, String user, String pass) {
        binding.progress.setVisibility(View.VISIBLE);
        binding.subtitle.setText(R.string.elder_smb_connecting);
        Task.execute(() -> {
            List<String> shares = SmbHelper.listShares(device.getHost(), device.getPort(), user, pass);
            App.post(() -> {
                binding.progress.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;
                if (shares.isEmpty()) {
                    Toast.makeText(this, R.string.elder_smb_no_share, Toast.LENGTH_LONG).show();
                    binding.subtitle.setText(R.string.elder_smb_select_device_tip);
                    return;
                }
                username = user;
                password = pass;
                device.setShares(shares);
                showShares(shares);
            });
        });
    }

    // ---- Step 3: 选共享夹 ----

    private void showShares(List<String> shares) {
        step = STEP_SHARE;
        stopScan();
        List<SmbDevice> items = new ArrayList<>();
        for (String share : shares) {
            SmbDevice item = new SmbDevice(selected.getHost(), selected.getPort());
            item.setName(share);
            items.add(item);
        }
        adapter.setItems(items);
        binding.title.setText(R.string.elder_smb_select_share);
        binding.subtitle.setText(getString(R.string.elder_smb_select_share_tip, selected.getName()));
        binding.empty.setVisibility(View.GONE);
        binding.progress.setVisibility(View.GONE);
        binding.rescan.setVisibility(View.GONE);
        binding.manual.setVisibility(View.GONE);
        binding.recycler.scrollToPosition(0);
    }

    /** 共享选定后落库并回传结果。 */
    private void confirmShare(String shareName) {
        SmbServer server = new SmbServer();
        server.setHost(selected.getHost());
        server.setPort(selected.getPort());
        server.setShareName(shareName);
        server.setUsername(username);
        server.setPassword(password);
        server.setName(selected.hasFriendlyName() ? selected.getName() : shareName);
        server.save();
        Intent data = new Intent();
        data.putExtra(EXTRA_SERVER_ID, server.getId());
        setResult(RESULT_OK, data);
        finish();
    }

    // ---- 手动添加兜底 ----

    /**
     * 跨网段、VPN 等扫描覆盖不到的场景下的兜底入口。
     * 只要求填写 IP，共享名仍然通过枚举获得。
     */
    private void showManualDialog() {
        DialogAddSmbServerBinding dialogBinding = DialogAddSmbServerBinding.inflate(LayoutInflater.from(this));
        // 共享名与显示名改由枚举 / 选择得到，手动入口不再要求填写
        hideField(dialogBinding.shareName);
        hideField(dialogBinding.name);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.elder_smb_manual)
                .setView(dialogBinding.getRoot())
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String host = dialogBinding.host.getText() == null ? "" : dialogBinding.host.getText().toString().trim();
                    if (host.isEmpty()) {
                        Toast.makeText(this, R.string.elder_smb_host_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port = 445;
                    try {
                        String portText = dialogBinding.port.getText() == null ? "" : dialogBinding.port.getText().toString().trim();
                        if (!portText.isEmpty()) port = Integer.parseInt(portText);
                    } catch (NumberFormatException ignored) {
                    }
                    String user = dialogBinding.username.getText() == null ? "" : dialogBinding.username.getText().toString().trim();
                    String pass = dialogBinding.password.getText() == null ? "" : dialogBinding.password.getText().toString();
                    connectManual(host, port, user, pass);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    /** 隐藏输入框及其外层的 TextInputLayout，避免留下空白的标签行。 */
    private void hideField(View field) {
        if (field == null) return;
        View target = field.getParent() instanceof View ? (View) field.getParent() : field;
        if (target.getParent() instanceof View && target.getParent().getClass().getSimpleName().contains("TextInputLayout")) {
            target = (View) target.getParent();
        }
        target.setVisibility(View.GONE);
    }

    private void connectManual(String host, int port, String user, String pass) {
        stopScan();
        binding.progress.setVisibility(View.VISIBLE);
        binding.subtitle.setText(R.string.elder_smb_connecting);
        Task.execute(() -> {
            SmbHelper.ProbeResult result = SmbHelper.probe(host, port, user, pass);
            App.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                binding.progress.setVisibility(View.GONE);
                if (result.getStatus() == SmbHelper.ProbeStatus.UNREACHABLE) {
                    Toast.makeText(this, R.string.elder_smb_unreachable, Toast.LENGTH_LONG).show();
                    return;
                }
                if (result.needAuth()) {
                    Toast.makeText(this, R.string.elder_smb_auth_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                SmbDevice device = new SmbDevice(host, port);
                device.setShares(result.getShares());
                selected = device;
                username = user;
                password = pass;
                if (result.getShares().isEmpty()) {
                    Toast.makeText(this, R.string.elder_smb_no_share, Toast.LENGTH_LONG).show();
                    return;
                }
                showShares(result.getShares());
            });
        });
    }

    @Override
    protected void onBackInvoked() {
        if (step == STEP_SHARE) {
            step = STEP_DEVICE;
            adapter.setItems(devices);
            binding.title.setText(R.string.elder_smb_select_device);
            binding.subtitle.setText(R.string.elder_smb_select_device_tip);
            binding.rescan.setVisibility(View.VISIBLE);
            binding.manual.setVisibility(View.VISIBLE);
            if (devices.isEmpty()) startScan();
        } else {
            setResult(RESULT_CANCELED);
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
    }
}
