package com.fongmi.android.tv.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

/**
 * Wraps {@link Context#bindService} so that unbinding is always safe and never
 * leaks a {@link ServiceConnection}. The bound state is tracked explicitly so a
 * release that happens before {@link #onServiceConnected} still unbinds, and a
 * connection that completes after release is dropped instead of leaking a
 * registry listener or keeping the binding alive for the process lifetime.
 */
public final class SafeServiceBinder implements ServiceConnection {

    private final Context appContext;
    private final ServiceConnection delegate;
    private volatile boolean bound;

    private SafeServiceBinder(Context context, ServiceConnection delegate) {
        this.appContext = context.getApplicationContext();
        this.delegate = delegate;
    }

    public static SafeServiceBinder create(Context context, ServiceConnection delegate) {
        return new SafeServiceBinder(context, delegate);
    }

    public void bind(Intent intent, int flags) {
        try {
            bound = appContext.bindService(intent, this, flags);
        } catch (Exception e) {
            bound = false;
        }
    }

    public void unbind() {
        if (bound) {
            try {
                appContext.unbindService(this);
            } catch (IllegalArgumentException ignored) {
            }
            bound = false;
        }
    }

    public boolean isBound() {
        return bound;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (!bound) return;
        delegate.onServiceConnected(name, binder);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        bound = false;
        delegate.onServiceDisconnected(name);
    }
}
