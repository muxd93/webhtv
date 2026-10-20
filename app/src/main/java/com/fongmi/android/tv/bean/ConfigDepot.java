package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.google.gson.annotations.SerializedName;

/**
 * 多仓源的子源关系：一个多仓配置（Config.kind=DEPOT）包含多个子源 URL。
 * 与 bean.Depot 区分：Depot 是配置 JSON 里 urls 数组的传输对象，本类是库内持久化关系。
 */
@Entity(indices = @Index(value = {"configId", "url"}, unique = true))
public class ConfigDepot {

    @PrimaryKey(autoGenerate = true)
    @SerializedName("id")
    private int id;
    @SerializedName("configId")
    private int configId;
    @SerializedName("url")
    private String url;
    @SerializedName("name")
    private String name;
    @SerializedName("ord")
    private int ord;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getConfigId() {
        return configId;
    }

    public void setConfigId(int configId) {
        this.configId = configId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getOrd() {
        return ord;
    }

    public void setOrd(int ord) {
        this.ord = ord;
    }

    public ConfigDepot configId(int configId) {
        setConfigId(configId);
        return this;
    }

    public ConfigDepot url(String url) {
        setUrl(url);
        return this;
    }

    public ConfigDepot name(String name) {
        setName(name);
        return this;
    }

    public ConfigDepot ord(int ord) {
        setOrd(ord);
        return this;
    }

    @NonNull
    @Override
    public String toString() {
        return "ConfigDepot{configId=" + configId + ", url=" + url + ", name=" + name + ", ord=" + ord + "}";
    }
}
