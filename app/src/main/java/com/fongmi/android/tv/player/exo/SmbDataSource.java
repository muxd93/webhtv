package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;

import com.fongmi.android.tv.utils.PushId;
import com.fongmi.android.tv.utils.SmbHelper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 让播放器透明直连 SMB 共享（smb://[user:pass@]host/share/path）。
 *
 * 设计原则：SMB / 本地文件等内部资源走直连，不经由 Sniffer 嗅探，也不触发 Parse 解析
 * （嗅探/解析仅用于网络爬虫视频源）。本 DataSource 包装 {@link DefaultDataSource}，对
 * smb scheme 用 SmbHelper 的 SMB 会话流式读取（鉴权候选/超时与浏览侧一致），
 * 其余 scheme 委托给原始实现（http/https/file/content 等）。
 *
 * 接入 {@link MediaSourceFactory} 后即被 CacheDataSource / 预加载链路自动包裹，
 * 因此大视频会边播边写入本地缓存，不会重复从网络拉取。
 */
@UnstableApi
public class SmbDataSource implements DataSource {

    private final DataSource delegate;

    private Uri uri;
    private boolean smbMode;
    private long position;

    private SmbHelper.ReadHandle handle;

    public SmbDataSource(@NonNull DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
        delegate.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        this.uri = dataSpec.uri;
        if (!"smb".equals(uri.getScheme())) {
            smbMode = false;
            return delegate.open(dataSpec);
        }
        smbMode = true;
        closeSmb();

        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 445 : uri.getPort();
        // Uri.getPath() 已解码，得到 "/share/子目录/文件.mp4"
        String path = uri.getPath();
        if (path != null) path = decode(path); // 兜底解码（如 %2F → /），与 getSmbUrl 逐段编码对应
        String rel = (path != null && path.startsWith("/")) ? path.substring(1) : (path == null ? "" : path);
        int slash = rel.indexOf('/');
        String shareName = slash < 0 ? rel : rel.substring(0, slash);
        String filePath = slash < 0 ? "" : rel.substring(slash + 1);

        String username = null;
        String password = "";
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            String[] up = userInfo.split(":", 2);
            username = decode(up[0]);
            password = up.length > 1 ? decode(up[1]) : "";
        }

        try {
            handle = SmbHelper.openForRead(host, port, shareName, filePath, username, password);
        } catch (IOException e) {
            closeSmb();
            throw e;
        }

        position = dataSpec.position;
        long available = Math.max(0, handle.getSize() - position);
        return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : available;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int readLength) throws IOException {
        if (!smbMode) return delegate.read(buffer, offset, readLength);
        if (position >= handle.getSize()) return C.RESULT_END_OF_INPUT;

        int toRead = (int) Math.min((long) readLength, handle.getSize() - position);
        if (toRead <= 0) return C.RESULT_END_OF_INPUT;

        // smbj 的 File.read(byte[], offset) 写入整个 buffer（从下标 0），故用定长临时数组再拷贝。
        byte[] tmp = new byte[toRead];
        int n = handle.read(tmp, position);
        if (n <= 0) return C.RESULT_END_OF_INPUT; // 网络未就绪视为结束，交由上层重试或结束
        System.arraycopy(tmp, 0, buffer, offset, n);
        position += n;
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return smbMode ? uri : delegate.getUri();
    }

    @Override
    public void close() throws IOException {
        if (!smbMode) {
            delegate.close();
            return;
        }
        closeSmb();
    }

    private void closeSmb() {
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception ignored) {
            }
            handle = null;
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    /** 包装 {@link DefaultDataSource.Factory}：smb scheme 走 {@link SmbDataSource}，其余委托原始实现。 */
    @UnstableApi
    public static final class Factory implements DataSource.Factory {
        private final DefaultDataSource.Factory defaultFactory;

        public Factory(@NonNull Context context, @NonNull DataSource.Factory httpDataSourceFactory) {
            this.defaultFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new SmbDataSource(defaultFactory.createDataSource());
        }
    }
}
