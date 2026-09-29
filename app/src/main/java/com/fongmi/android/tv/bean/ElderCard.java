package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;
import androidx.room.ColumnInfo;

import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Diffable;

import java.util.Objects;
import java.util.UUID;

@Entity
@TypeConverters(ElderCard.TypeConverter.class)
public class ElderCard implements Diffable<ElderCard> {

    public enum Type {
        KEEP, HISTORY, LOCAL_FILE, SMB, LIVE, APP, ADD
    }

    public static class TypeConverter {
        @androidx.room.TypeConverter
        public Type toType(String value) {
            return value == null ? null : Type.valueOf(value);
        }

        @androidx.room.TypeConverter
        public String fromType(Type type) {
            return type == null ? null : type.name();
        }
    }

    public enum CoverType {
        DEFAULT(0),     // 未自定义：优先 vodPic，否则按类型自动生成（如本地视频首帧/文字色块）
        URL(1),         // 用户输入的图片网址（pic 字段即 URL）
        LOCAL(2),       // 本地图片文件（coverValue 存文件路径，pic 同步存路径兼容旧逻辑）
        BUILTIN(3),     // 内置精选图库（coverValue 存图库 key，如 "tv"/"movie"）
        FIRST_FRAME(4); // 本地视频自动提取的首帧（pic 存生成的帧文件路径）

        public final int value;

        CoverType(int value) {
            this.value = value;
        }

        public static CoverType from(int value) {
            for (CoverType t : values()) if (t.value == value) return t;
            return DEFAULT;
        }
    }

    @NonNull
    @PrimaryKey
    private String id;
    private Type type;
    private String name;
    private String pic;
    private int coverType;
    private String coverValue;
    private int sortOrder;
    private String refKey;
    private int cid;
    private String siteKey;
    private long createTime;
    @ColumnInfo(name = "is_dir")
    private boolean dir;

    @Ignore
    private String vodRemarks;
    @Ignore
    private String vodPic;

    public ElderCard() {
        this.id = UUID.randomUUID().toString();
        this.createTime = System.currentTimeMillis();
    }

    public static ElderCard create(Type type) {
        ElderCard card = new ElderCard();
        card.type = type;
        return card;
    }

    public static ElderCard create(Type type, String refKey, String name, String pic, int cid, String siteKey) {
        ElderCard card = new ElderCard();
        card.type = type;
        card.refKey = refKey;
        card.name = name;
        card.pic = pic;
        card.cid = cid;
        card.siteKey = siteKey;
        return card;
    }

    public void save() {
        AppDatabase.get().getElderCardDao().insertOrUpdate(this);
    }

    public void delete() {
        AppDatabase.get().getElderCardDao().delete(id);
    }

    @NonNull
    public String getId() { return id; }
    public void setId(@NonNull String id) { this.id = id; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPic() { return pic; }
    public void setPic(String pic) { this.pic = pic; }
    public int getCoverType() { return coverType; }
    public void setCoverType(int coverType) { this.coverType = coverType; }
    public void setCoverType(CoverType coverType) { this.coverType = coverType.value; }
    public String getCoverValue() { return coverValue; }
    public void setCoverValue(String coverValue) { this.coverValue = coverValue; }

    /** 统一落封面：URL/LOCAL 时 pic 与 coverValue 同值（loadElderCover 从 pic 取图），BUILTIN 只写 key */
    public void applyCover(CoverType type, String value) {
        this.coverType = type.value;
        this.coverValue = value;
        if (type == CoverType.URL || type == CoverType.LOCAL) this.pic = value;
    }

    /** 是否为用户自定义封面（区别于系统自动生成/默认） */
    public boolean hasCustomCover() {
        CoverType t = CoverType.from(coverType);
        return t == CoverType.URL || t == CoverType.LOCAL || t == CoverType.BUILTIN;
    }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getRefKey() { return refKey; }
    public void setRefKey(String refKey) { this.refKey = refKey; }
    public int getCid() { return cid; }
    public void setCid(int cid) { this.cid = cid; }
    public String getSiteKey() { return siteKey; }
    public void setSiteKey(String siteKey) { this.siteKey = siteKey; }
    public boolean isDir() { return dir; }
    public void setDir(boolean dir) { this.dir = dir; }
    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
    public String getVodRemarks() { return vodRemarks == null ? "" : vodRemarks; }
    public void setVodRemarks(String vodRemarks) { this.vodRemarks = vodRemarks; }
    public String getVodPic() { return vodPic == null ? "" : vodPic; }
    public void setVodPic(String vodPic) { this.vodPic = vodPic; }

    public String getSiteKeyFromRef() {
        if (refKey == null) return "";
        String[] parts = refKey.split(AppDatabase.SYMBOL);
        return parts.length > 0 ? parts[0] : "";
    }

    public String getVodIdFromRef() {
        if (refKey == null) return "";
        String[] parts = refKey.split(AppDatabase.SYMBOL);
        return parts.length > 1 ? parts[1] : "";
    }

    public boolean isAddType() { return type == Type.ADD; }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ElderCard it)) return false;
        return Objects.equals(getId(), it.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId());
    }

    @Override
    public boolean isSameItem(ElderCard other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(ElderCard other) {
        return Objects.equals(getName(), other.getName()) && Objects.equals(getPic(), other.getPic()) && Objects.equals(getType(), other.getType()) && getSortOrder() == other.getSortOrder();
    }
}
