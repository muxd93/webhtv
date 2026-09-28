package com.fongmi.android.tv.utils;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.SmbDevice;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网 SMB 服务器扫描。
 * <p>
 * 三路并行取并集：
 * <ol>
 *   <li>对本机所在 /24 网段逐个探测 445 端口（主力手段，覆盖所有 SMB 服务器）</li>
 *   <li>mDNS 查询 {@code _smb._tcp}（用于拿到设备友好名，如"群晖NAS"）</li>
 *   <li>读取 {@code /proc/net/arp} 邻居表兜底（补扫已通信过但端口扫描可能漏掉的主机）</li>
 * </ol>
 * 发现一台即回调一台，不等全部完成，保证界面能边扫边显示。
 */
public class SmbScanTask {

    private static final int SMB_PORT = 445;
    private static final int PARALLELISM = 64;
    private static final int PORT_TIMEOUT = 300;
    private static final String MDNS_SERVICE_TYPE = "_smb._tcp.";

    private final Map<String, SmbDevice> found;
    private final CopyOnWriteArrayList<Future<?>> futures;
    private final ExecutorService executor;
    private final Map<String, String> mdnsNames;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private Listener listener;
    private volatile boolean stopped;

    public SmbScanTask(Listener listener) {
        this.listener = listener;
        this.found = new ConcurrentHashMap<>();
        this.mdnsNames = new ConcurrentHashMap<>();
        this.futures = new CopyOnWriteArrayList<>();
        this.executor = Executors.newFixedThreadPool(PARALLELISM);
    }

    public void start() {
        if (stopped) return;
        startMdns();
        Task.execute(() -> {
            if (stopped) return;
            LinkedHashSet<String> targets = new LinkedHashSet<>(getSubnetHosts());
            targets.addAll(getArpNeighbours());
            scan(new ArrayList<>(targets));
        });
    }

    public void stop() {
        stopped = true;
        listener = null;
        stopMdns();
        futures.forEach(f -> f.cancel(true));
        futures.clear();
        executor.shutdownNow();
    }

    private void scan(List<String> hosts) {
        if (hosts.isEmpty()) {
            finish();
            return;
        }
        AtomicInteger count = new AtomicInteger(hosts.size());
        for (String host : hosts) submit(host, count);
    }

    private void submit(String host, AtomicInteger count) {
        if (stopped || executor.isShutdown()) {
            if (count.decrementAndGet() == 0) finish();
            return;
        }
        try {
            futures.add(executor.submit(() -> {
                try {
                    if (!stopped) probe(host);
                } finally {
                    if (count.decrementAndGet() == 0) finish();
                }
            }));
        } catch (RejectedExecutionException ignored) {
            if (count.decrementAndGet() == 0) finish();
        }
    }

    /**
     * 探测单台主机：先做低成本的端口连通性判断，通了再做 SMB 鉴权与共享枚举。
     */
    private void probe(String host) {
        if (!SmbHelper.isPortOpen(host, SMB_PORT, PORT_TIMEOUT)) return;
        if (stopped || found.containsKey(host)) return;
        SmbDevice device = new SmbDevice(host, SMB_PORT);
        String friendly = mdnsNames.get(host);
        if (friendly != null) device.setName(friendly);
        SmbHelper.ProbeResult result = SmbHelper.probe(host, SMB_PORT, null, null);
        if (stopped) return;
        if (result.isOk()) {
            device.setAnonymous(true);
            device.setShares(result.getShares());
        } else if (result.needAuth()) {
            device.setNeedAuth(true);
        } else {
            // 端口开着但 SMB 握手失败，仍然展示出来让用户可以手动尝试
            device.setNeedAuth(true);
        }
        if (found.putIfAbsent(host, device) != null) return;
        App.post(() -> {
            if (listener != null) listener.onFind(device);
        });
    }

    // ---- mDNS ----

    private void startMdns() {
        try {
            Context context = App.get();
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;
            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    resolve(serviceInfo);
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                }
            };
            nsdManager.discoverServices(MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception ignored) {
        }
    }

    private void resolve(NsdServiceInfo serviceInfo) {
        if (stopped || nsdManager == null) return;
        try {
            nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    if (stopped || info.getHost() == null) return;
                    String host = info.getHost().getHostAddress();
                    String name = info.getServiceName();
                    if (host == null || name == null || name.isEmpty()) return;
                    mdnsNames.put(host, name);
                    // 若该主机已被端口扫描发现，补上友好名并通知界面刷新
                    SmbDevice exist = found.get(host);
                    if (exist != null && !exist.hasFriendlyName()) {
                        exist.setName(name);
                        App.post(() -> {
                            if (listener != null) listener.onUpdate(exist);
                        });
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void stopMdns() {
        try {
            if (nsdManager != null && discoveryListener != null) nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (Exception ignored) {
        } finally {
            nsdManager = null;
            discoveryListener = null;
        }
    }

    // ---- 目标地址收集 ----

    /** 本机所有 IPv4 地址所在 /24 网段的全部主机地址。 */
    private List<String> getSubnetHosts() {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        for (String ip : getLocalIps()) {
            int idx = ip.lastIndexOf('.');
            if (idx < 0) continue;
            String prefix = ip.substring(0, idx + 1);
            for (int i = 1; i < 255; i++) {
                String target = prefix + i;
                if (!target.equals(ip)) hosts.add(target);
            }
        }
        return new ArrayList<>(hosts);
    }

    private List<String> getLocalIps() {
        LinkedHashSet<String> ips = new LinkedHashSet<>();
        try {
            for (var en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface nif = en.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (var addresses = nif.getInetAddresses(); addresses.hasMoreElements(); ) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) ips.add(addr.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(ips);
    }

    /** 读取内核 ARP 邻居表，补充可能被跳过的主机。 */
    private List<String> getArpNeighbours() {
        List<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line = reader.readLine(); // 跳过表头
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 4) continue;
                String ip = parts[0];
                String mac = parts[3];
                if (mac.equals("00:00:00:00:00:00")) continue;
                if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) result.add(ip);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void finish() {
        App.post(() -> {
            if (listener != null) listener.onFinish();
        });
    }

    public interface Listener {

        void onFind(SmbDevice device);

        /** 已展示的条目信息有更新（例如 mDNS 补上了友好名）。 */
        default void onUpdate(SmbDevice device) {
        }

        default void onFinish() {
        }
    }
}
