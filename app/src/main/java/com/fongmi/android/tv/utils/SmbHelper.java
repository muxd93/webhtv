package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.SmbServer;
import com.hierynomus.protocol.commons.EnumWithValue;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ImpersonationLevel;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.NamedPipe;
import com.hierynomus.smbj.share.PipeShare;
import com.hierynomus.smbj.share.Share;

import com.github.catvod.crawler.SpiderDebug;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class SmbHelper {

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 10000;
    private static final int PORT_PROBE_TIMEOUT = 400;
    private static final String[] VIDEO_EXTENSIONS = {"mp4", "mkv", "avi", "ts", "flv", "mov", "wmv", "rmvb", "3gp", "webm", "m4v", "mpeg", "mpg", "vob", "m2ts", "m3u8", "ogv", "f4v"};

    // ---- DCERPC / srvsvc 常量（用于共享枚举）----
    private static final int RPC_PT_BIND = 11;
    private static final int RPC_PT_BIND_ACK = 12;
    private static final int RPC_PT_REQUEST = 0;
    private static final int RPC_HEADER_SIZE = 24;
    private static final int SRVSVC_OP_NETSHAREENUMALL = 15;
    private static final int RPC_FLAG_LAST_FRAG = 0x02;
    private static final int MAX_FRAGMENT_SIZE = 8192;
    private static final int MAX_FRAGMENT_COUNT = 32;
    private static final int MAX_SHARE_COUNT = 256;
    private static final int SHARE_TYPE_MASK = 0x0F;
    private static final int SHARE_TYPE_DISK = 0x00;
    private static final int SHARE_TYPE_HIDDEN = 0x80000000;

    /** srvsvc 接口 UUID 4b324fc8-1670-01d3-1278-5a47bf6ee188 的小端字节序表示。 */
    private static final byte[] SRVSVC_UUID = {
            (byte) 0xC8, 0x4F, 0x32, 0x4B, 0x70, 0x16, (byte) 0xD3, 0x01,
            0x12, 0x78, 0x5A, 0x47, (byte) 0xBF, 0x6E, (byte) 0xE1, (byte) 0x88
    };

    /** NDR 传输语法 UUID 8a885d04-1ceb-11c9-9fe8-08002b104860 的小端字节序表示。 */
    private static final byte[] NDR_UUID = {
            0x04, 0x5D, (byte) 0x88, (byte) 0x8A, (byte) 0xEB, 0x1C, (byte) 0xC9, 0x11,
            (byte) 0x9F, (byte) 0xE8, 0x08, 0x00, 0x2B, 0x10, 0x48, 0x60
    };

    public static class SmbFileItem {
        private final String name;
        private final boolean directory;
        private final String path;

        public SmbFileItem(String name, boolean directory, String path) {
            this.name = name;
            this.directory = directory;
            this.path = path;
        }

        public String getName() { return name; }
        public boolean isDirectory() { return directory; }
        public String getPath() { return path; }
    }

    /**
     * 探测结果状态。用于向上层给出可区分的失败原因，而不是笼统的"连接失败"。
     */
    public enum ProbeStatus {
        OK,             // 已连通且鉴权成功
        AUTH_REQUIRED,  // 端口通、但匿名/所给凭据被拒，需要账号密码
        SHARE_DENIED,   // 鉴权成功但指定共享不可访问
        UNREACHABLE     // 主机不可达或端口不通
    }

    /**
     * 探测结果，携带状态、可用的鉴权上下文以及枚举到的共享列表。
     */
    public static class ProbeResult {
        private final ProbeStatus status;
        private final AuthenticationContext auth;
        private final List<String> shares;

        public ProbeResult(ProbeStatus status, AuthenticationContext auth, List<String> shares) {
            this.status = status;
            this.auth = auth;
            this.shares = shares == null ? new ArrayList<>() : shares;
        }

        public ProbeStatus getStatus() { return status; }
        public AuthenticationContext getAuth() { return auth; }
        public List<String> getShares() { return shares; }
        public boolean isOk() { return status == ProbeStatus.OK; }
        public boolean needAuth() { return status == ProbeStatus.AUTH_REQUIRED; }
    }

    public static boolean testConnection(String host, int port, String username, String password) {
        return testConnection(host, port, username, password, null);
    }

    /**
     * 连接测试。相比旧实现有两点关键修正：
     * 1. 未提供用户名时依次尝试 anonymous / guest / 空账号三种匿名形态，
     *    而不是硬编码 guest —— 这是此前"允许匿名访问的服务器连不上"的根因。
     * 2. 若指定了 shareName，会真正连接该共享做验证，避免"添加成功但浏览空白"。
     */
    public static boolean testConnection(String host, int port, String username, String password, String shareName) {
        try (SMBClient client = createClient(); Connection connection = client.connect(host, port)) {
            if (connection == null) return false;
            for (AuthenticationContext ac : buildAuthCandidates(username, password)) {
                Session session = null;
                try {
                    session = connection.authenticate(ac);
                    if (session == null) continue;
                    if (shareName == null || shareName.isEmpty()) return true;
                    try (Share share = session.connectShare(shareName)) {
                        if (share != null) return true;
                    } catch (Exception e) {
                        SpiderDebug.log("smb", "connectShare failed: %s", e.getMessage());
                    }
                } catch (Exception e) {
                    SpiderDebug.log("smb", "auth attempt failed: %s", e.getMessage());
                } finally {
                    closeQuietly(session);
                }
            }
            return false;
        } catch (Exception e) {
            SpiderDebug.log("smb", "testConnection failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * 探测一台主机：先确认 445 可达，再依次尝试匿名鉴权，成功则顺带枚举共享列表。
     * 匿名全部被拒时返回 AUTH_REQUIRED，由上层决定是否向用户索要账号密码。
     */
    public static ProbeResult probe(String host, int port, String username, String password) {
        if (!isPortOpen(host, port, PORT_PROBE_TIMEOUT)) {
            return new ProbeResult(ProbeStatus.UNREACHABLE, null, null);
        }
        try (SMBClient client = createClient(); Connection connection = client.connect(host, port)) {
            if (connection == null) return new ProbeResult(ProbeStatus.UNREACHABLE, null, null);
            for (AuthenticationContext ac : buildAuthCandidates(username, password)) {
                Session session = null;
                try {
                    session = connection.authenticate(ac);
                    if (session == null) continue;
                    List<String> shares = enumerateShares(session);
                    return new ProbeResult(ProbeStatus.OK, ac, shares);
                } catch (Exception e) {
                    SpiderDebug.log("smb", "probe auth failed for %s: %s", host, e.getMessage());
                } finally {
                    closeQuietly(session);
                }
            }
            return new ProbeResult(ProbeStatus.AUTH_REQUIRED, null, null);
        } catch (Exception e) {
            SpiderDebug.log("smb", "probe failed for %s: %s", host, e.getMessage());
            return new ProbeResult(ProbeStatus.UNREACHABLE, null, null);
        }
    }

    /**
     * 枚举服务器上的共享目录，自动过滤 IPC$ / ADMIN$ / C$ 等管理共享。
     */
    public static List<String> listShares(String host, int port, String username, String password) {
        try (SMBClient client = createClient(); Connection connection = client.connect(host, port)) {
            if (connection == null) return new ArrayList<>();
            for (AuthenticationContext ac : buildAuthCandidates(username, password)) {
                Session session = null;
                try {
                    session = connection.authenticate(ac);
                    if (session == null) continue;
                    List<String> shares = enumerateShares(session);
                    if (!shares.isEmpty()) return shares;
                } catch (Exception e) {
                    SpiderDebug.log("smb", "listShares auth failed: %s", e.getMessage());
                } finally {
                    closeQuietly(session);
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("smb", "listShares failed: %s", e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * 快速判断端口是否开放，避免对不存在的主机做完整 SMB 握手（扫描时性能关键）。
     */
    public static boolean isPortOpen(String host, int port, int timeout) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeout);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<SmbFileItem> listFiles(SmbServer server, String path) {
        List<SmbFileItem> items = new ArrayList<>();
        try (SMBClient client = createClient(); Connection connection = client.connect(server.getHost(), server.getPort())) {
            if (connection == null) return items;
            for (AuthenticationContext ac : buildAuthCandidates(server.getUsername(), server.getPassword())) {
                Session session = null;
                try {
                    session = connection.authenticate(ac);
                    if (session == null) continue;
                    try (Share raw = session.connectShare(server.getShareName())) {
                        if (!(raw instanceof DiskShare)) continue;
                        DiskShare share = (DiskShare) raw;
                        String dirPath = path.isEmpty() ? "\\" : path;
                        if (!dirPath.endsWith("\\")) dirPath += "\\";
                        List<FileIdBothDirectoryInformation> entries = share.list(dirPath);
                        for (FileIdBothDirectoryInformation entry : entries) {
                            String name = entry.getFileName();
                            if (name.equals(".") || name.equals("..")) continue;
                            boolean isDir = EnumWithValue.EnumUtils.isSet(entry.getFileAttributes(), FileAttributes.FILE_ATTRIBUTE_DIRECTORY);
                            String filePath = dirPath + name;
                            items.add(new SmbFileItem(name, isDir, filePath));
                        }
                        return items;
                    }
                } catch (Exception e) {
                    SpiderDebug.log("smb", "listFiles auth attempt failed: %s", e.getMessage());
                } finally {
                    closeQuietly(session);
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("smb", "listFiles failed: %s", e.getMessage());
        }
        return items;
    }

    public static List<SmbFileItem> listVideoFiles(SmbServer server, String dirPath) {
        return listVideoFiles(server, dirPath, false);
    }

    public static List<SmbFileItem> listVideoFiles(SmbServer server, String dirPath, boolean recursive) {
        List<SmbFileItem> videos = new ArrayList<>();
        collectVideoFiles(server, dirPath, recursive, videos);
        videos.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return videos;
    }

    private static void collectVideoFiles(SmbServer server, String dirPath, boolean recursive, List<SmbFileItem> out) {
        List<SmbFileItem> items = listFiles(server, dirPath);
        for (SmbFileItem item : items) {
            if (item.isDirectory()) {
                if (recursive) collectVideoFiles(server, item.getPath(), true, out);
            } else if (isVideoFile(item.getName())) {
                out.add(item);
            }
        }
    }

    // 输出可直接被播放器直连的 smb:// URL，并把凭证编码进 authority（user:pass@host），
    // 使内部资源链路自包含，无需再经 Sniffer 嗅探或 Parse 解析。
    // 匿名访问（无用户名）则不写入凭证。path 中的中文/空格等按 URI 规范编码，由 SmbDataSource 解码还原。
    public static String getSmbUrl(SmbServer server, String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        if (normalized.startsWith("/")) normalized = normalized.substring(1);

        // 凭证编码进 authority
        String authority;
        String username = server.getUsername();
        String password = server.getPassword();
        if (username != null && !username.isEmpty()) {
            authority = URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + ":" + URLEncoder.encode(password == null ? "" : password, StandardCharsets.UTF_8)
                    + "@" + server.getHost();
        } else {
            authority = server.getHost();
        }

        StringBuilder sb = new StringBuilder("smb://").append(authority);
        if (server.getPort() != 445) {
            sb.append(":").append(server.getPort());
        }
        // 逐段编码（保留 '/' 分隔符），避免中文/空格破坏 URI，同时保证 '/share/' 字面结构可被解析侧匹配
        sb.append("/").append(URLEncoder.encode(server.getShareName(), StandardCharsets.UTF_8));
        if (!normalized.isEmpty()) {
            for (String seg : normalized.split("/")) {
                if (!seg.isEmpty()) sb.append("/").append(URLEncoder.encode(seg, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private static AuthenticationContext createAuthContext(String username, String password) {
        return buildAuthCandidates(username, password).get(0);
    }

    /**
     * 构造鉴权候选列表。
     * <p>
     * 提供了用户名时只有一个候选；未提供用户名时返回三种匿名形态，按成功率排序依次尝试：
     * <ul>
     *   <li>{@code anonymous()} —— NULL session，Samba {@code map to guest = Bad User} 只认这种</li>
     *   <li>{@code guest()} —— Windows 来宾账户已启用的场景</li>
     *   <li>空用户名 + 空密码 —— 部分 NAS 固件的实现</li>
     * </ul>
     * 三者在协议层发出的 SessionSetup 报文并不相同，因此必须逐一尝试而不能只用其中一种。
     * 注意密码一律传 {@code char[0]} 而非 {@code null}，避免 smbj 内部空指针。
     */
    private static List<AuthenticationContext> buildAuthCandidates(String username, String password) {
        List<AuthenticationContext> candidates = new ArrayList<>();
        if (username == null || username.isEmpty()) {
            candidates.add(AuthenticationContext.anonymous());
            candidates.add(AuthenticationContext.guest());
            candidates.add(new AuthenticationContext("", new char[0], null));
        } else {
            candidates.add(new AuthenticationContext(username, password != null ? password.toCharArray() : new char[0], null));
        }
        return candidates;
    }

    /**
     * 通过 IPC$ 命名管道调用 srvsvc 的 NetShareEnumAll(opnum 15) 枚举共享。
     * smbj 0.14.0 未内置 DCERPC 栈，因此这里手工构造 bind 与 request PDU。
     */
    private static List<String> enumerateShares(Session session) {
        List<String> shares = new ArrayList<>();
        try (Share ipc = session.connectShare("IPC$")) {
            if (!(ipc instanceof PipeShare)) return shares;
            PipeShare pipeShare = (PipeShare) ipc;
            NamedPipe pipe = pipeShare.open("srvsvc", SMB2ImpersonationLevel.Impersonation,
                    EnumSet.of(AccessMask.MAXIMUM_ALLOWED), null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                    SMB2CreateDisposition.FILE_OPEN, null);
            if (pipe == null) return shares;
            try {
                byte[] bindAck = pipe.transact(buildRpcBind());
                if (bindAck == null || bindAck.length < 2 || (bindAck[2] & 0xFF) != RPC_PT_BIND_ACK) return shares;
                byte[] response = readFullResponse(pipe, buildNetShareEnumRequest(session.getConnection().getRemoteHostname()));
                shares.addAll(parseNetShareEnumResponse(response));
            } finally {
                try { pipe.close(); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            SpiderDebug.log("smb", "enumerateShares failed: %s", e.getMessage());
        }
        return shares;
    }

    /**
     * 发送请求并读取完整响应。
     * <p>
     * 共享较多时 srvsvc 会把响应拆成多个 PDU：首个 PDU 的 flags 若未置 PFC_LAST_FRAG(0x02)，
     * 需继续读取后续分片，并把各分片的 stub 数据拼接到首片之后，否则会漏掉后半部分共享。
     */
    private static byte[] readFullResponse(NamedPipe pipe, byte[] request) throws Exception {
        byte[] first = pipe.transact(request);
        if (first == null || first.length < RPC_HEADER_SIZE) return first;
        if ((first[3] & RPC_FLAG_LAST_FRAG) != 0) return first;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(first, 0, first.length);
        byte[] chunk = new byte[MAX_FRAGMENT_SIZE];
        for (int i = 0; i < MAX_FRAGMENT_COUNT; i++) {
            int read = pipe.read(chunk);
            if (read <= RPC_HEADER_SIZE) break;
            boolean last = (chunk[3] & RPC_FLAG_LAST_FRAG) != 0;
            // 后续分片只保留 stub 数据，丢弃各自的 24 字节 PDU 头
            out.write(chunk, RPC_HEADER_SIZE, read - RPC_HEADER_SIZE);
            if (last) break;
        }
        return out.toByteArray();
    }

    /** 构造 DCERPC bind PDU，绑定 srvsvc 接口。 */
    private static byte[] buildRpcBind() {
        ByteBuffer buf = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 5).put((byte) 0);            // version 5.0
        buf.put((byte) RPC_PT_BIND);                // packet type = bind
        buf.put((byte) 0x03);                       // flags: first + last fragment
        buf.putInt(0x00000010);                     // data representation (little endian, ASCII, IEEE)
        buf.putShort((short) 72);                   // frag length
        buf.putShort((short) 0);                    // auth length
        buf.putInt(1);                              // call id
        buf.putShort((short) 4280);                 // max xmit frag
        buf.putShort((short) 4280);                 // max recv frag
        buf.putInt(0);                              // assoc group
        buf.putInt(1);                              // num context items = 1
        buf.putShort((short) 0);                    // context id
        buf.putShort((short) 1);                    // num transfer syntaxes
        buf.put(SRVSVC_UUID);                       // abstract syntax: srvsvc
        buf.putInt(3);                              // interface version 3.0
        buf.put(NDR_UUID);                          // transfer syntax: NDR
        buf.putInt(2);                              // NDR version 2
        return buf.array();
    }

    /** 构造 NetShareEnumAll(level 1) 请求 PDU。 */
    private static byte[] buildNetShareEnumRequest(String serverName) {
        String server = "\\\\" + (serverName == null ? "" : serverName);
        byte[] stub = buildNetShareEnumStub(server);
        ByteBuffer buf = ByteBuffer.allocate(24 + stub.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 5).put((byte) 0);
        buf.put((byte) RPC_PT_REQUEST);
        buf.put((byte) 0x03);
        buf.putInt(0x00000010);
        buf.putShort((short) (24 + stub.length));
        buf.putShort((short) 0);
        buf.putInt(2);                              // call id
        buf.putInt(stub.length);                    // alloc hint
        buf.putShort((short) 0);                    // context id
        buf.putShort((short) SRVSVC_OP_NETSHAREENUMALL);
        buf.put(stub);
        return buf.array();
    }

    /** NDR 编码的 NetShareEnumAll 入参。 */
    private static byte[] buildNetShareEnumStub(String server) {
        ByteBuffer buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x00020000);                     // referent id of ServerName pointer
        putNdrString(buf, server);
        buf.putInt(1);                              // info level = 1
        buf.putInt(1);                              // switch value = 1
        buf.putInt(0x00020004);                     // referent id of ShareInfo pointer
        buf.putInt(0);                              // entries read = 0
        buf.putInt(0);                              // null array pointer
        buf.putInt(0xFFFFFFFF);                     // preferred max length
        buf.putInt(0);                              // null resume handle
        byte[] out = new byte[buf.position()];
        buf.rewind();
        buf.get(out);
        return out;
    }

    /** 写入 NDR conformant varying string（max count / offset / actual count + UTF-16LE + 4 字节对齐）。 */
    private static void putNdrString(ByteBuffer buf, String value) {
        int len = value.length() + 1;
        buf.putInt(len);                            // max count
        buf.putInt(0);                              // offset
        buf.putInt(len);                            // actual count
        for (int i = 0; i < value.length(); i++) buf.putShort((short) value.charAt(i));
        buf.putShort((short) 0);                    // null terminator
        while (buf.position() % 4 != 0) buf.put((byte) 0);
    }

    /**
     * 解析 NetShareEnumAll 响应，提取磁盘类共享名称。
     * 共享类型高位为 0 表示磁盘共享，0x80000000 位表示隐藏的管理共享。
     */
    private static List<String> parseNetShareEnumResponse(byte[] data) {
        List<String> shares = new ArrayList<>();
        if (data == null || data.length < RPC_HEADER_SIZE + 16) return shares;
        try {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            buf.position(RPC_HEADER_SIZE);
            buf.getInt();                           // info level
            buf.getInt();                           // switch value
            buf.getInt();                           // ctr referent id
            int count = buf.getInt();
            if (count <= 0 || count > MAX_SHARE_COUNT) return shares;
            buf.getInt();                           // array referent id
            int maxCount = buf.getInt();
            if (maxCount < count) return shares;
            // 第一遍读取条目头：名称/备注的 referent id 为 0 表示该字符串不会出现在后续数据区
            int[] types = new int[count];
            boolean[] hasName = new boolean[count];
            boolean[] hasComment = new boolean[count];
            for (int i = 0; i < count; i++) {
                if (buf.remaining() < 12) return shares;
                hasName[i] = buf.getInt() != 0;     // name referent id
                types[i] = buf.getInt();            // share type
                hasComment[i] = buf.getInt() != 0;  // comment referent id
            }
            // 第二遍读取实际的字符串内容，顺序与第一遍的指针出现顺序一致
            for (int i = 0; i < count; i++) {
                String name = hasName[i] ? readNdrString(buf) : null;
                if (hasComment[i]) readNdrString(buf); // 备注需读取以推进位置
                if (name == null || name.isEmpty()) continue;
                if ((types[i] & SHARE_TYPE_MASK) != SHARE_TYPE_DISK) continue;
                if ((types[i] & SHARE_TYPE_HIDDEN) != 0) continue;
                if (name.endsWith("$")) continue;
                shares.add(name);
            }
        } catch (Exception e) {
            SpiderDebug.log("smb", "parseNetShareEnumResponse failed: %s", e.getMessage());
        }
        return shares;
    }

    /** 读取 NDR conformant varying string 并跳过 4 字节对齐填充。 */
    private static String readNdrString(ByteBuffer buf) {
        if (buf.remaining() < 12) return null;
        buf.getInt();                               // max count
        buf.getInt();                               // offset
        int actual = buf.getInt();
        if (actual < 0 || actual * 2 > buf.remaining()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actual; i++) {
            char c = (char) buf.getShort();
            if (c != 0) sb.append(c);
        }
        while (buf.position() % 4 != 0 && buf.remaining() > 0) buf.get();
        return sb.toString();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static boolean isVideoFile(String name) {
        if (name == null || !name.contains(".")) return false;
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        for (String videoExt : VIDEO_EXTENSIONS) {
            if (videoExt.equals(ext)) return true;
        }
        return false;
    }

    private static SMBClient createClient() {
        SmbConfig config = SmbConfig.builder()
                .withSoTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
                .withTransactTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .build();
        return new SMBClient(config);
    }
}
