package com.fongmi.android.tv.player.extractor;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.utils.SafeServiceBinder;
import com.fongmi.android.tv.utils.UrlUtil;
import com.forcetech.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;

public class Force implements Source.Extractor, ServiceConnection {

    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final Pattern PATTERN = Pattern.compile("(?i)(p[2-9]p|mitv)");
    private final HashSet<String> set = new HashSet<>();
    private final Object lock = new Object();
    private SafeServiceBinder binder;

    @Override
    public boolean match(Uri uri) {
        return PATTERN.matcher(UrlUtil.scheme(uri)).find();
    }

    private void init(String scheme) {
        if (binder == null) binder = SafeServiceBinder.create(App.get(), this);
        binder.bind(Util.intent(App.get(), scheme), Context.BIND_AUTO_CREATE);
    }

    @Override
    public String fetch(String url) throws Exception {
        String scheme = Util.scheme(url);
        synchronized (lock) {
            if (!set.contains(scheme)) {
                init(scheme);
                long deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
                while (!set.contains(scheme) && SystemClock.elapsedRealtime() < deadline) {
                    lock.wait(deadline - SystemClock.elapsedRealtime());
                }
            }
        }
        Uri uri = Uri.parse(url);
        int port = Util.port(scheme);
        String id = uri.getLastPathSegment();
        String cmd = "http://127.0.0.1:" + port + "/cmd.xml?cmd=switch_chan&server=" + uri.getHost() + ":" + uri.getPort() + "&id=" + id;
        OkHttp.string(cmd, Map.of(HttpHeaders.USER_AGENT, "MTV"));
        return "http://127.0.0.1:" + port + "/" + id;
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
        if (binder != null) binder.unbind();
        synchronized (lock) {
            set.clear();
            lock.notifyAll();
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (lock) {
            set.add(Util.trans(name));
            lock.notifyAll();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        synchronized (lock) {
            set.remove(Util.trans(name));
            lock.notifyAll();
        }
    }
}
