package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.parser.EpgParser;
import com.fongmi.android.tv.utils.Formatters;
import com.github.catvod.utils.Json;
import com.google.gson.annotations.SerializedName;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public class Epg {

    @SerializedName("key")
    private String key;
    @SerializedName("date")
    private String date;
    @SerializedName("epg_data")
    private List<EpgData> list;
    // SWR 元数据（LIVE9）：fetchedAt = 数据抓取时间（0 = 旧数据/未知）；nextDayFirst =
    // 跨天"下一档"预取文本。两者不来自服务端格式，随本应用磁盘缓存 JSON 自洽往返
    private long fetchedAt;
    private String nextDayFirst;

    public static Epg objectFrom(String str, String key, ZoneId zoneId) {
        if (!Json.isObj(str)) return EpgParser.getEpg(str, key, zoneId);
        try {
            Epg item = App.gson().fromJson(str, Epg.class);
            item.setTime(zoneId);
            item.setKey(key);
            return item;
        } catch (Exception e) {
            return new Epg();
        }
    }

    public static Epg create(String key, String date) {
        Epg item = new Epg();
        item.setKey(key);
        item.setDate(date);
        item.setList(new ArrayList<>());
        return item;
    }

    public String getKey() {
        return TextUtils.isEmpty(key) ? "" : key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getDate() {
        return TextUtils.isEmpty(date) ? "" : date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public List<EpgData> getList() {
        return list == null ? Collections.emptyList() : list;
    }

    public void setList(List<EpgData> list) {
        this.list = list;
    }

    public boolean equal(String date) {
        return getDate().equals(date);
    }

    public long getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(long fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public String getNextDayFirst() {
        return nextDayFirst == null ? "" : nextDayFirst;
    }

    public void setNextDayFirst(String nextDayFirst) {
        this.nextDayFirst = nextDayFirst;
    }

    private void setTime(ZoneId zoneId) {
        setList(new ArrayList<>(new LinkedHashSet<>(getList())));
        for (EpgData item : getList()) {
            item.setStartTime(parseEpgTime(getDate().concat(item.getStart()), zoneId));
            item.setEndTime(parseEpgTime(getDate().concat(item.getEnd()), zoneId));
            if (item.getEndTime() < item.getStartTime()) item.checkDay(zoneId);
            item.trans();
        }
    }

    public EpgData getEpgData() {
        for (EpgData item : getList()) if (item.isSelected()) return item;
        return new EpgData();
    }

    public Epg selected() {
        for (EpgData item : getList()) item.setSelected(item.isInRange());
        return this;
    }

    public int getSelected() {
        for (int i = 0; i < getList().size(); i++) if (getList().get(i).isSelected()) return i;
        return -1;
    }

    public int getInRange() {
        for (int i = 0; i < getList().size(); i++) if (getList().get(i).isInRange()) return i;
        return -1;
    }

    /** OSD now/next 文本：真实在播节目 + 下一档（基于时间线，不受节目单浏览选择影响；无下一档/无 EPG 时退化）。 */
    public String nowNext() {
        int inRange = getInRange();
        if (inRange < 0) return getEpgData().format();
        EpgData data = getList().get(inRange);
        if (data.getTitle().isEmpty()) return "";
        int next = inRange + 1;
        if (next >= getList().size()) {
            // 当天最后一档：下一档取预取的次日首条（23:5x 跨天边界）
            String tomorrow = getNextDayFirst();
            return tomorrow.isEmpty() ? data.format() : data.format() + " → " + tomorrow;
        }
        EpgData nextData = getList().get(next);
        if (nextData.getTitle().isEmpty()) return data.format();
        String nextText = nextData.getStart().isEmpty() ? nextData.getTitle() : nextData.getStart() + " " + nextData.getTitle();
        return data.format() + " → " + nextText;
    }

    private long parseEpgTime(String source, ZoneId zoneId) {
        try {
            var fmt = source.length() > 16 ? Formatters.EPG_DT_LONG : Formatters.EPG_DT_SHORT;
            return LocalDateTime.parse(source, fmt).atZone(zoneId).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
