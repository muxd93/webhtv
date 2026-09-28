package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 局域网扫描发现的一台 SMB 服务器。
 * <p>
 * 与 {@link SmbServer} 的区别：{@code SmbServer} 是用户已添加、会持久化的条目，
 * 而本类只描述一次扫描过程中的临时发现结果。
 */
public class SmbDevice {

    private final String host;
    private final int port;
    private String name;
    private boolean anonymous;
    private boolean needAuth;
    private List<String> shares;

    public SmbDevice(String host, int port) {
        this.host = host;
        this.port = port;
        this.name = host;
        this.shares = new ArrayList<>();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getName() {
        return name == null || name.isEmpty() ? host : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** 是否无需账号密码即可访问。 */
    public boolean isAnonymous() {
        return anonymous;
    }

    public void setAnonymous(boolean anonymous) {
        this.anonymous = anonymous;
    }

    /** 是否需要用户提供账号密码。 */
    public boolean isNeedAuth() {
        return needAuth;
    }

    public void setNeedAuth(boolean needAuth) {
        this.needAuth = needAuth;
    }

    public List<String> getShares() {
        return shares == null ? new ArrayList<>() : shares;
    }

    public void setShares(List<String> shares) {
        this.shares = shares;
    }

    /** 显示用副标题：优先展示共享数量，其次展示是否需要密码。 */
    public String getSubtitle() {
        if (needAuth) return host;
        int size = getShares().size();
        return size > 0 ? host + " · " + size : host;
    }

    /** 名称是否是通过 mDNS 等途径拿到的友好名，而非裸 IP。 */
    public boolean hasFriendlyName() {
        return name != null && !name.isEmpty() && !name.equals(host);
    }

    @NonNull
    @Override
    public String toString() {
        return getName() + "(" + host + ":" + port + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SmbDevice)) return false;
        SmbDevice other = (SmbDevice) obj;
        return port == other.port && Objects.equals(host, other.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }
}
