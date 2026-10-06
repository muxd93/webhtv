package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.ConfigCache;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.source.SourceState;
import com.github.catvod.utils.Prefers;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

@Entity(indices = @Index(value = {"url", "type"}, unique = true))
public class Config {

    // 内置默认源列表：首次安装或点播源被清空时整体回填；第一条为默认生效源，展示顺序按声明顺序（time 递减）
    public static final String[] DEFAULT_VOD_URLS = {
            "http://z.qiqiv.cn/123.txt",
            "https://www.iyouhun.com/tv/dc",
            "https://github.catvod.com/https://raw.githubusercontent.com/tushen6/Tomorrow/master/lmw.json"
    };

    @PrimaryKey(autoGenerate = true)
    @SerializedName("id")
    private int id;
    @SerializedName("type")
    private int type;
    @SerializedName("time")
    private long time;
    @SerializedName("url")
    private String url;
    @SerializedName("json")
    private String json;
    @SerializedName("name")
    private String name;
    @SerializedName("logo")
    private String logo;
    @SerializedName("home")
    private String home;
    @SerializedName("parse")
    private String parse;
    // 多仓活仓（DEPOT1）：depot 标记本条为仓根；parentUrl 指向来源仓（普通配置为 NULL）
    @ColumnInfo(defaultValue = "0")
    @SerializedName("depot")
    private boolean depot;
    @SerializedName("parentUrl")
    private String parentUrl;

    @Ignore
    @SerializedName("notice")
    private String notice;
    @Ignore
    @SerializedName("danmaku")
    private String danmaku;

    public static List<Config> arrayFrom(String str) {
        Type listType = TypeToken.getParameterized(List.class, Config.class).getType();
        List<Config> items = App.gson().fromJson(str, listType);
        return items == null ? Collections.emptyList() : items;
    }

    public static Config objectFrom(String str) {
        return App.gson().fromJson(str, Config.class);
    }

    public static Config create(int type) {
        return new Config().type(type);
    }

    public static Config create(int type, String url) {
        return new Config().type(type).url(url).insert();
    }

    public static Config create(int type, String url, String name) {
        return new Config().type(type).url(url).name(name).insert();
    }

    public static List<Config> getAll(int type) {
        return AppDatabase.get().getConfigDao().findByType(type);
    }

    public static List<Config> findUrls() {
        return AppDatabase.get().getConfigDao().findUrlByType(0);
    }

    /** 指定仓的现存子源（DEPOT1 父子关联；用于展开时对账清理幽灵子源）。 */
    public static List<Config> getChildren(String parentUrl, int type) {
        return AppDatabase.get().getConfigDao().findChildren(parentUrl, type);
    }

    public static void delete(String url, int type) {
        AppDatabase.get().getConfigDao().delete(url, type);
        ConfigCache.delete(url);
    }

    public static Config vod() {
        Config item = AppDatabase.get().getConfigDao().findOne(0);
        Config root = depotRootOf(item, 0);
        return root != null ? root : (item == null ? seedDefaults() : item);
    }

    /** 点播源列表为空时回填内置默认源；第一条时间最大，成为默认生效源并保持列表首位。 */
    private static Config seedDefaults() {
        long time = System.currentTimeMillis();
        Config active = new Config().type(0).url(DEFAULT_VOD_URLS[0]).time(time).insert();
        for (int i = 1; i < DEFAULT_VOD_URLS.length; i++) {
            new Config().type(0).url(DEFAULT_VOD_URLS[i]).time(time - i).insert();
        }
        return active;
    }

    public static Config live() {
        Config item = AppDatabase.get().getConfigDao().findOne(1);
        Config root = depotRootOf(item, 1);
        return root != null ? root : (item == null ? create(1) : item);
    }

    /** 若最近生效源是某仓的子源，则重启后回到仓根（重新展开/探测/选健康子源），避免仓"消失"（B）。 */
    private static Config depotRootOf(Config item, int type) {
        if (item == null || TextUtils.isEmpty(item.getParentUrl())) return null;
        Config root = AppDatabase.get().getConfigDao().find(item.getParentUrl(), type);
        return (root != null && root.isDepot()) ? root : null;
    }

    public static Config wall() {
        Config item = AppDatabase.get().getConfigDao().findOne(2);
        return item == null ? create(2) : item;
    }

    public static Config find(int id) {
        return AppDatabase.get().getConfigDao().findById(id);
    }

    public static Config find(String url, int type) {
        Config item = AppDatabase.get().getConfigDao().find(url, type);
        return item == null ? create(type, url) : item.type(type);
    }

    public static Config find(String url, String name, int type) {
        Config item = AppDatabase.get().getConfigDao().find(url, type);
        return item == null ? create(type, url, name) : item.type(type).name(name);
    }

    public static Config find(Config config) {
        return find(config, config.getType());
    }

    public static Config find(Config config, int type) {
        Config item = AppDatabase.get().getConfigDao().find(config.getUrl(), type);
        return item == null ? create(type, config.getUrl(), config.getName()) : item.type(type).name(config.getName());
    }

    public static Config find(Depot depot, int type) {
        Config item = AppDatabase.get().getConfigDao().find(depot.getUrl(), type);
        return item == null ? create(type, depot.getUrl(), depot.getName()) : item.type(type).name(depot.getName());
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getJson() {
        return json;
    }

    public void setJson(String json) {
        this.json = json;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getHome() {
        return home;
    }

    public void setHome(String home) {
        this.home = home;
    }

    public String getParse() {
        return parse;
    }

    public void setParse(String parse) {
        this.parse = parse;
    }

    public boolean isDepot() {
        return depot;
    }

    public void setDepot(boolean depot) {
        this.depot = depot;
    }

    public String getParentUrl() {
        return parentUrl;
    }

    public void setParentUrl(String parentUrl) {
        this.parentUrl = parentUrl;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public String getNotice() {
        return notice;
    }

    public void setNotice(String notice) {
        this.notice = notice;
    }

    public String getDanmaku() {
        return danmaku;
    }

    public void setDanmaku(String danmaku) {
        this.danmaku = danmaku;
    }

    public Config type(int type) {
        setType(type);
        return this;
    }

    public Config url(String url) {
        setUrl(url);
        return this;
    }

    public Config json(String json) {
        setJson(json);
        return this;
    }

    public Config name(String name) {
        setName(name);
        return this;
    }

    public Config time(long time) {
        setTime(time);
        return this;
    }

    public Config depot(boolean depot) {
        setDepot(depot);
        return this;
    }

    public Config parentUrl(String parentUrl) {
        setParentUrl(parentUrl);
        return this;
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(getUrl());
    }

    public String getDesc() {
        if (!TextUtils.isEmpty(getName())) return getName();
        if (!TextUtils.isEmpty(getUrl())) return getUrl();
        return "";
    }

    public Config insert() {
        if (isEmpty()) return this;
        setId(Math.toIntExact(AppDatabase.get().getConfigDao().insert(this)));
        return this;
    }

    public Config save() {
        if (isEmpty()) return this;
        AppDatabase.get().getConfigDao().insertOrUpdate(this);
        return this;
    }

    public Config update() {
        if (isEmpty()) return this;
        setTime(System.currentTimeMillis());
        Prefers.put("config_" + getType(), getUrl());
        return save();
    }

    public void delete() {
        // 删仓根时级联清理其子源，避免遗留孤儿子源（A：删除仓根不级联）
        if (isDepot()) {
            for (Config child : getChildren(getUrl(), getType())) child.delete();
        }
        AppDatabase.get().getConfigDao().delete(getUrl(), getType());
        History.delete(getId());
        Keep.delete(getId());
        ConfigCache.delete(getUrl());
        SourceState.clear(getType(), getUrl());
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Config it)) return false;
        return getId() == it.getId();
    }
}
