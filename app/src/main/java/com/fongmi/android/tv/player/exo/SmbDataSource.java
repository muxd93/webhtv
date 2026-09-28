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

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

/**
 * 让播放器透明直连 SMB 共享（smb://user:pass@host/share/path）。
 *
 * 设计原则：SMB / 本地文件等内部资源走直连，不经由 Sniffer 嗅探，也不触发 Parse 解析
 * （嗅探/解析仅用于网络爬虫视频源）。本 DataSource 包装 {@link DefaultDataSource}，对
 * smb scheme 用 smbj 流式读取，其余 scheme 委托给原始实现（http/https/file/content 等）。
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
    private long fileSize;

    private SMBClient client;
    private Connection connection;
    private Session session;
    private DiskShare share;
    private File file;

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
            client = new SMBClient();
            connection = client.connect(host, port);
            if (username == null || username.isEmpty()) {
                session = connection.authenticate(new AuthenticationContext("", new char[0], ""));
            } else {
                session = connection.authenticate(new AuthenticationContext(username, password.toCharArray(), ""));
            }
            share = (DiskShare) session.connectShare(shareName);
            file = share.openFile(filePath,
                    EnumSet.of(AccessMask.FILE_READ_DATA),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    null);
            fileSize = file.getFileInformation().getStandardInformation().getEndOfFile();
        } catch (Exception e) {
            closeSmb();
            throw new IOException("SMB open failed: " + e.getMessage(), e);
        }

        position = dataSpec.position;
        long available = Math.max(0, fileSize - position);
        return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : available;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int readLength) throws IOException {
        if (!smbMode) return delegate.read(buffer, offset, readLength);
        if (position >= fileSize) return C.RESULT_END_OF_INPUT;

        int toRead = (int) Math.min((long) readLength, fileSize - position);
        if (toRead <= 0) return C.RESULT_END_OF_INPUT;

        // smbj 的 File.read(byte[], offset) 写入整个 buffer（从下标 0），故用定长临时数组再拷贝。
        byte[] tmp = new byte[toRead];
        int n = file.read(tmp, position);
        if (n < 0) return C.RESULT_END_OF_INPUT;
        if (n == 0) return C.RESULT_END_OF_INPUT; // 网络未就绪视为结束，交由上层重试或结束
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
        if (file != null) {
            try { file.close(); } catch (Exception ignored) {}
            file = null;
        }
        if (share != null) {
            try { share.close(); } catch (Exception ignored) {}
            share = null;
        }
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
            session = null;
        }
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
            client = null;
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
