package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.ConfigRepository;
import com.fongmi.android.tv.api.config.ConfigService;
import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.utils.Prefers;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「一个源条目」：点播源(type=0)/直播源(type=1)/壁纸源(type=2)，一行一个 URL。
 * 注意与 api.config 包下的 VodConfig/LiveConfig/WallConfig（已加载的配置运行态）区分：
 * 本类只是源清单中的一行数据，加载后的 sites/lives/parses 等在那些类里。
 *
 * kind=SINGLE 为普通单源；kind=DEPOT 为多仓入口，其子源平铺为本类型的其他行，
 * 父子关系持久化在 ConfigDepot 表，可整组刷新/删除。
 * active 为显式激活标记（每 type 至多一个），替代早期依赖 time DESC 的隐式推断。
 */
@Entity(indices = @Index(value = {"url", "type"}, unique = true))
public class Config {

    public static final int VOD = 0;
    public static final int LIVE = 1;
    public static final int WALL = 2;

    public static final int SINGLE = 0;
    public static final int DEPOT = 1;

    /** 点播源预设：设置-点播源对话框中长按确定依次回填 */
    public static final String[] DEFAULT_VOD_URLS = {
            "http://z.qiqiv.cn/123.txt",
            "https://www.iyouhun.com/tv/dc"
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
    @SerializedName("kind")
    private int kind;
    @SerializedName("active")
    private boolean active;
    @SerializedName("etag")
    private String etag;
    @SerializedName("lastModified")
    private String lastModified;

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
        return new Config().type(type).url(url);
    }

    public static Config create(int type, String url, String name) {
        return new Config().type(type).url(url).name(name);
    }

    public static List<Config> getAll(int type) {
        return AppDatabase.get().getConfigDao().findByType(type);
    }

    public static List<Config> findUrls() {
        return AppDatabase.get().getConfigDao().findUrlByType(0);
    }

    public static Config vod() {
        return ConfigRepository.getActiveOrEmpty(VOD);
    }

    public static Config live() {
        return ConfigRepository.getActiveOrEmpty(LIVE);
    }

    public static Config wall() {
        return ConfigRepository.getActiveOrEmpty(WALL);
    }

    public static Config find(int id) {
        return AppDatabase.get().getConfigDao().findById(id);
    }

    /** 按规范化 URL 查找；不存在时返回未落库的空 id 对象（不再隐式 insert） */
    public static Config find(String url, int type) {
        return ConfigRepository.findByUrl(url, type);
    }

    /** 全部配置的 id→名称映射，供跨配置历史/收藏标注来源配置 */
    public static Map<Integer, String> nameMap() {
        Map<Integer, String> names = new HashMap<>();
        for (Config config : AppDatabase.get().getConfigDao().findAll()) names.put(config.getId(), config.getName());
        return names;
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

    public int getKind() {
        return kind;
    }

    public void setKind(int kind) {
        this.kind = kind;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isDepot() {
        return kind == DEPOT;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
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

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
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

    public Config kind(int kind) {
        setKind(kind);
        return this;
    }

    public Config depot() {
        return kind(DEPOT);
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

    public boolean isEmpty() {
        return TextUtils.isEmpty(getUrl());
    }

    public String getDesc() {
        if (!TextUtils.isEmpty(getName())) return getName();
        if (!TextUtils.isEmpty(getUrl())) return getUrl();
        return "";
    }

    /** 落库或更新（唯一键 url+type 冲突时回查补齐 id，杜绝 id=-1 静默丢失） */
    public Config save() {
        return ConfigRepository.upsert(this);
    }

    /** 激活并成为当前源（显式 active + Prefers 镜像） */
    public Config update() {
        return ConfigRepository.activate(this);
    }

    /** 删除配置：多仓级联删子配置与关系；历史/收藏保留，由 UI 标注来源已失效 */
    public void delete() {
        ConfigService.delete(this);
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
