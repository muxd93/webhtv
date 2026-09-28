package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.fongmi.android.tv.db.AppDatabase;

import java.util.UUID;

@Entity
public class SmbServer {

    @NonNull
    @PrimaryKey
    private String id;
    private String name;
    private String host;
    private int port;
    private String shareName;
    private String username;
    private String password;
    private long createTime;

    public SmbServer() {
        this.id = UUID.randomUUID().toString();
        this.port = 445;
        this.createTime = System.currentTimeMillis();
    }

    @NonNull public String getId() { return id; }
    public void setId(@NonNull String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getShareName() { return shareName; }
    public void setShareName(String shareName) { this.shareName = shareName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public void save() {
        AppDatabase.get().getSmbServerDao().insertOrUpdate(this);
    }

    public void delete() {
        AppDatabase.get().getSmbServerDao().delete(id);
    }
}
