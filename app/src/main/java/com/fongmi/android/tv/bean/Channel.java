package com.fongmi.android.tv.bean;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.gson.HeaderAdapter;
import com.fongmi.android.tv.setting.LiveEpgSetting;
import com.fongmi.android.tv.utils.Formatters;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Trans;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class Channel {

    @SerializedName("urls")
    private List<String> urls;
    @SerializedName("lineNames")
    private List<String> lineNames;
    @SerializedName("number")
    private String number;
    @SerializedName("logo")
    private String logo;
    @SerializedName("epg")
    private String epg;
    @SerializedName("name")
    private String name;
    @SerializedName("ua")
    private String ua;
    @SerializedName("click")
    private String click;
    @SerializedName("format")
    private String format;
    @SerializedName("origin")
    private String origin;
    @SerializedName("referer")
    private String referer;
    @SerializedName("tvgId")
    private String tvgId;
    @SerializedName("tvgName")
    private String tvgName;
    @SerializedName("catchup")
    private Catchup catchup;
    @SerializedName("header")
    @JsonAdapter(HeaderAdapter.class)
    private Map<String, String> header;
    @SerializedName("parse")
    private Integer parse;
    @SerializedName("drm")
    private Drm drm;

    private Group group;
    private String show;
    private int index;
    private List<Epg> dataList;

    public Channel() {
    }

    public Channel(String name) {
        this.name = name;
    }

    public static Channel objectFrom(JsonElement element) {
        Channel channel = App.gson().fromJson(element, Channel.class);
        if (channel != null) channel.normalizeLines();
        return channel;
    }

    public static Channel create(int number) {
        return new Channel().setNumber(number);
    }

    public static Channel create(String name) {
        return new Channel(name);
    }

    public static Channel create(Channel channel) {
        return new Channel().copy(channel);
    }

    public List<String> getUrls() {
        return urls = urls == null ? new ArrayList<>() : urls;
    }

    public void setUrls(List<String> urls) {
        this.urls = urls;
    }

    public List<String> getLineNames() {
        return lineNames = lineNames == null ? new ArrayList<>() : lineNames;
    }

    public void setLineNames(List<String> lineNames) {
        this.lineNames = lineNames;
    }

    public String lineName(int index) {
        return lineNames != null && index >= 0 && index < lineNames.size() ? lineNames.get(index) : "";
    }

    /** [$名称] 后缀拆分：$ 后含 "://" 视为 URL 本体的一部分，不拆；$ 起始视为 URL，不拆。 */
    public static String[] splitLine(String url) {
        if (url == null) return new String[]{"", ""};
        int index = url.indexOf('$');
        if (index < 1) return new String[]{url, ""};
        String tail = url.substring(index + 1);
        if (tail.contains("://")) return new String[]{url, ""};
        return new String[]{url.substring(0, index), tail};
    }

    /** 追加原始线路（可含 $名称 后缀），拆分后保持 urls 与 lineNames 等长。 */
    public void addLine(String url) {
        String[] parts = splitLine(url);
        addLine(parts[0], parts[1]);
    }

    public void addLine(String url, String name) {
        getUrls().add(url);
        getLineNames().add(name == null ? "" : name);
    }

    /** 把另一频道的线路并入本频道：URL 按整洁值去重，名称随行。 */
    public void mergeLines(Channel other) {
        List<String> theirs = other.getUrls();
        for (int i = 0; i < theirs.size(); i++) {
            if (getUrls().contains(theirs.get(i))) continue;
            addLine(theirs.get(i), other.lineName(i));
        }
    }

    /** 按给定次序（order[i] = 原索引）同步重排线路与名称。 */
    public void orderLines(int[] order) {
        List<String> orderedUrls = new ArrayList<>(order.length);
        List<String> orderedNames = new ArrayList<>(order.length);
        for (int index : order) {
            orderedUrls.add(getUrls().get(index));
            orderedNames.add(lineName(index));
        }
        this.urls = orderedUrls;
        this.lineNames = orderedNames;
    }

    /** 反序列化/旧数据归一：URL 内联 $名称 剥离进 lineNames，并补齐等长。 */
    public void normalizeLines() {
        List<String> names = getLineNames();
        while (names.size() < getUrls().size()) names.add("");
        for (int i = 0; i < getUrls().size(); i++) {
            String[] parts = splitLine(getUrls().get(i));
            urls.set(i, parts[0]);
            if (names.get(i).isEmpty()) names.set(i, parts[1]);
        }
    }

    public String getNumber() {
        return TextUtils.isEmpty(number) ? "" : number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public Channel setNumber(int number) {
        setNumber(String.format(Locale.getDefault(), "%03d", number));
        return this;
    }

    public String getLogo() {
        return TextUtils.isEmpty(logo) ? "" : logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getEpg() {
        return TextUtils.isEmpty(epg) ? "" : epg;
    }

    public void setEpg(String epg) {
        this.epg = epg;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShow() {
        return TextUtils.isEmpty(show) ? getName() : show;
    }

    public void setShow(String show) {
        this.show = show;
    }

    public String getUa() {
        return TextUtils.isEmpty(ua) ? "" : ua;
    }

    public void setUa(String ua) {
        this.ua = ua;
    }

    public String getClick() {
        return TextUtils.isEmpty(click) ? "" : click;
    }

    public void setClick(String click) {
        this.click = click;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getOrigin() {
        return TextUtils.isEmpty(origin) ? "" : origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getReferer() {
        return TextUtils.isEmpty(referer) ? "" : referer;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    public String getTvgId() {
        return TextUtils.isEmpty(tvgId) ? getTvgName() : tvgId;
    }

    public void setTvgId(String tvgId) {
        this.tvgId = tvgId;
    }

    public String getTvgName() {
        return TextUtils.isEmpty(tvgName) ? getName() : tvgName;
    }

    public void setTvgName(String tvgName) {
        this.tvgName = tvgName;
    }

    public Catchup getCatchup() {
        return catchup == null ? new Catchup() : catchup;
    }

    public void setCatchup(Catchup catchup) {
        this.catchup = catchup;
    }

    public Map<String, String> getHeader() {
        return header == null ? new HashMap<>() : header;
    }

    public void setHeader(Map<String, String> header) {
        this.header = header;
    }

    public Integer getParse() {
        return parse == null ? 0 : parse;
    }

    public void setParse(Integer parse) {
        this.parse = parse;
    }

    public Drm getDrm() {
        return drm;
    }

    public void setDrm(Drm drm) {
        this.drm = drm;
    }

    public Group getGroup() {
        return group;
    }

    public void setGroup(Group group) {
        this.group = group;
    }

    public Epg getData() {
        return getData(ZoneId.systemDefault());
    }

    // EPG data is written by background parse/EPG tasks and read on the main
    // thread, so structural access stays inside this monitor. getDataList
    // hands out a snapshot; callers treat it as read-only.
    public synchronized Epg getData(ZoneId zoneId) {
        String today = LocalDate.now(zoneId).format(Formatters.DATE);
        if (dataList == null) return new Epg();
        return dataList.stream().filter(e -> e.equal(today)).findFirst().orElse(new Epg());
    }

    public synchronized List<Epg> getDataList() {
        return dataList == null ? Collections.emptyList() : new ArrayList<>(dataList);
    }

    public synchronized void setDataList(List<Epg> list) {
        this.dataList = list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    public synchronized void setData(Epg data) {
        if (dataList == null) dataList = new ArrayList<>();
        dataList.removeIf(e -> e.equal(data.getDate()));
        dataList.add(data);
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = Math.max(index, 0);
    }

    public void setIndex(String line) {
        for (int i = 0; i < getUrls().size(); i++) {
            String url = getUrls().get(i);
            if (url.equals(line) || (url.contains("$") && line.equals(url.split("\\$")[0]))) {
                setIndex(i);
                break;
            }
        }
    }

    public int getLineVisible() {
        return isOnly() ? View.GONE : View.VISIBLE;
    }

    public void loadLogo(ImageView view) {
        int width = 0, height = 0;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.width > 0 && params.height > 0) {
            width = params.width;
            height = params.height;
        }
        ImgUtil.load(getName(), getLogo(), view, false, width, height);
    }

    public void switchLine(boolean next) {
        List<?> urls = getUrls();
        if (urls.isEmpty()) return;
        int size = urls.size();
        int step = next ? 1 : -1;
        setIndex((getIndex() + step + size) % size);
    }

    /** urls 已在解析期归一（内联 $名称 剥离进 lineNames），含 $ 的 URL（splitLine 守卫保留）必须原样返回。 */
    public String getCurrent() {
        if (getUrls().isEmpty()) return "";
        return getUrls().get(getIndex());
    }

    public boolean isOnly() {
        return getUrls().size() == 1;
    }

    public boolean isLast() {
        return getUrls().isEmpty() || getIndex() == getUrls().size() - 1;
    }

    public boolean isRtsp() {
        return getCurrent().startsWith("rtsp");
    }

    public boolean hasCatchup() {
        if (getCatchup().isEmpty() && getCurrent().contains("/PLTV/")) setCatchup(Catchup.PLTV());
        if (!getCatchup().getRegex().isEmpty()) return getCatchup().match(getCurrent());
        return !getCatchup().isEmpty();
    }

    public String getLine() {
        if (getUrls().size() <= 1) return "";
        String name = lineName(getIndex());
        if (!name.isEmpty()) return name;
        return ResUtil.getString(R.string.live_line, getIndex() + 1);
    }

    public Channel group(Group group) {
        setGroup(group);
        return this;
    }

    public void live(Live live) {
        if (!live.getUa().isEmpty() && getUa().isEmpty()) setUa(live.getUa());
        if (!live.getClick().isEmpty() && getClick().isEmpty()) setClick(live.getClick());
        if (!live.getHeader().isEmpty() && getHeader().isEmpty()) setHeader(live.getHeader());
        if (!live.getOrigin().isEmpty() && getOrigin().isEmpty()) setOrigin(live.getOrigin());
        if (!live.getCatchup().isEmpty() && getCatchup().isEmpty()) setCatchup(live.getCatchup());
        if (!live.getReferer().isEmpty() && getReferer().isEmpty()) setReferer(live.getReferer());
        if (!LiveEpgSetting.getUrl().isEmpty() || (!LiveEpgSetting.getEffectiveUrl(live).isEmpty() && !getEpg().startsWith("http"))) LiveEpgSetting.apply(live, this);
        if (live.getLogo().contains("{") && !getLogo().startsWith("http")) setLogo(live.getLogo().replace("{id}", getTvgId()).replace("{name}", getTvgName()).replace("{logo}", getLogo()));
    }

    public Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>(getHeader());
        if (!getUa().isEmpty()) headers.put(HttpHeaders.USER_AGENT, getUa());
        if (!getOrigin().isEmpty()) headers.put(HttpHeaders.ORIGIN, getOrigin());
        if (!getReferer().isEmpty()) headers.put(HttpHeaders.REFERER, getReferer());
        return headers;
    }

    public Channel copy(Channel item) {
        setCatchup(item.getCatchup());
        setReferer(item.getReferer());
        setTvgName(item.getTvgName());
        setHeader(item.getHeader());
        setNumber(item.getNumber());
        setOrigin(item.getOrigin());
        setFormat(item.getFormat());
        setParse(item.getParse());
        setClick(item.getClick());
        setTvgId(item.getTvgId());
        setLogo(item.getLogo());
        setName(item.getName());
        setShow(item.getShow());
        setUrls(item.getUrls());
        setDataList(item.getDataList());
        setDrm(item.getDrm());
        setEpg(item.getEpg());
        setUa(item.getUa());
        setLineNames(item.lineNames == null ? new ArrayList<>() : new ArrayList<>(item.getLineNames()));
        normalizeLines();
        return this;
    }

    public Result result() {
        Result result = new Result();
        result.setDrm(getDrm());
        result.setUrl(getCurrent());
        result.setClick(getClick());
        result.setParse(getParse());
        result.setFormat(getFormat());
        result.setHeader(getHeaders());
        return result;
    }

    public Channel trans() {
        if (Trans.pass()) return this;
        this.show = Trans.s2t(name);
        return this;
    }

    // Name/number equality is the parse-time merge key (Group.find/add dedup
    // and channel-number lookup). UI code must compare Channel references
    // instead of relying on equals().
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Channel it)) return false;
        String name1 = getName(), name2 = it.getName();
        String number1 = getNumber(), number2 = it.getNumber();
        if (!name1.isEmpty() && !name2.isEmpty()) return Objects.equals(name1, name2);
        if (!number1.isEmpty() && !number2.isEmpty()) return Objects.equals(number1, number2);
        return false;
    }

    @Override
    public int hashCode() {
        String name = getName(), number = getNumber();
        if (!name.isEmpty()) return Objects.hash(name);
        if (!number.isEmpty()) return Objects.hash(number);
        return 0;
    }
}
