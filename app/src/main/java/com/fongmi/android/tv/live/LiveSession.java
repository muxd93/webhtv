package com.fongmi.android.tv.live;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.LiveViewModel;
import com.fongmi.android.tv.setting.LiveSetting;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Shared live tuning state machine behind both the leanback and the mobile
 * LiveActivity. Owns group/channel selection, keep restore, the fetch /
 * reload / auto-line-switch flow, catchup tuning, keeps and hidden-group
 * unlocking. Flavor activities implement {@link Listener} for rendering and
 * forward user input; all decisions live here so a fix applies to both.
 *
 * <p>Object identity is the UI truth: the adapter lists hold the same
 * Channel/Group references as {@link #groups}, so "is this the tuned
 * channel/group" is a reference comparison, never {@code equals()} (those
 * remain reserved for parse-time merging of same-name channels).</p>
 */
public class LiveSession {

    /** 连续自动跳台上限:防死循环兜底(单频道组环绕自跳、全源死源等场景最多空转 3 次)。 */
    private static final int MAX_AUTO_HOP = 3;
    private static final long AUTO_HOP_DELAY_MS = 2000;

    public interface Listener {

        void onBusy(boolean busy);

        void onConfigError(String msg);

        void onGroupsChanged();

        void onGroupSelected(Group group);

        void onChannelTuned(Channel channel, boolean syncUi);

        void onChannelPosition(int position);

        void onLineChanged(boolean showInfo);

        void onCatchupLoading(EpgData data);

        /** 点击无回看能力的已播节目时回调(默认静默,flavor 侧决定是否提示)。 */
        default void onCatchupUnsupported() {
        }

        void onEpgUpdated(Epg epg);

        void onFetch(boolean catchup);

        void onPlaybackStart(Result result);

        void onPlaybackStopping();

        void onPlaybackFailed(String msg);

        void onLiveSwitched();

        void onUnlocked(Group group);

        void onSoftReloaded();
    }

    private final Listener listener;
    private final LiveViewModel viewModel;
    private final List<Group> groups = new ArrayList<>();
    private final List<Group> hides = new ArrayList<>();

    @Nullable
    private Group group;
    @Nullable
    private Channel channel;
    private String playbackKey;
    private String pendingReloadUrl;
    private String pendingReloadMsg;
    private boolean rendered;
    private boolean playbackCatchup;
    private int hopCount;
    private int hopGeneration;
    private long zapTune;
    private boolean zapLogged;
    /** 最近一次换台方向（+1 下一台 / -1 上一台），预热目标跟随（LIVE10）；restore/数字键等无方向入口保持缺省 +1。 */
    private int lastZapStep = 1;

    public LiveSession(LiveViewModel viewModel, Listener listener) {
        this.viewModel = viewModel;
        this.listener = listener;
    }

    public List<Group> groups() {
        return groups;
    }

    public List<Group> hides() {
        return hides;
    }

    @Nullable
    public Group currentGroup() {
        return group;
    }

    @Nullable
    public Channel currentChannel() {
        return channel;
    }

    public boolean isTuned(Channel item) {
        return channel != null && channel == item && group == channel.getGroup();
    }

    public boolean isCatchup() {
        return playbackCatchup;
    }

    @Nullable
    public String playbackKey() {
        return playbackKey;
    }

    public void start(boolean empty) {
        if (!empty) {
            LiveConfig.get().refreshIfStale();
            Task.submit(LineHealth::init);
            Task.submit(LineBlockStore::init);
            load();
            return;
        }
        LiveConfig.get().init().load(new Callback() {
            @Override
            public void success() {
                load();
            }

            @Override
            public void error(String msg) {
                listener.onConfigError(msg);
            }
        });
    }

    public void setConfig(Config config) {
        Config current = LiveConfig.get().getConfig();
        LiveConfig.load(config, new Callback() {
            @Override
            public void start() {
                listener.onBusy(true);
            }

            @Override
            public void success() {
                switchLive(LiveConfig.get().getHome());
            }

            @Override
            public void error(String msg) {
                LiveConfig.load(current, new Callback());
                listener.onConfigError(msg);
                listener.onBusy(false);
            }
        });
    }

    public void switchLive(Live item) {
        // 点当前已渲染的源不再清树重载打断播放
        if (item.isSelected() && rendered) return;
        if (item.isSelected()) item.getGroups().clear();
        LiveConfig.get().setHome(item);
        listener.onLiveSwitched();
        reset();
        load();
    }

    public void reset() {
        rendered = false;
        groups.clear();
        hides.clear();
        group = null;
        channel = null;
        playbackKey = null;
        clearPendingReload();
        playbackCatchup = false;
        hopCount = 0;
        cancelAutoHop();
        zapTune = 0;
        zapLogged = false;
        lastZapStep = 1;
        ZapPrewarm.cancel();
        listener.onGroupsChanged();
    }

    private void load() {
        listener.onBusy(true);
        viewModel.parse(LiveConfig.get().getHome());
    }

    /**
     * Mobile-only: renders the menu from the already-parsed groups before the
     * re-parse finishes. No-op once this session has rendered.
     */
    public void preview() {
        Live home = LiveConfig.get().getHome();
        // 后台解析进行中的树尚未发布完整分组，避免主线程遍历到半成品（CME）
        if (viewModel.isParsing(home)) return;
        render(home);
    }

    public void onLiveParsed(Live live) {
        // 已渲染会话收到重解析结果（如聚合更新后的重入）：走软重绑而非丢弃
        if (rendered) {
            softReload();
            return;
        }
        render(live);
    }

    /**
     * 后台重解析结果（重进直播页/换配置后的再解析）到达时的会话软刷新：换到新树并尽量
     * 对位当前台，不触碰播放器。找不到原台时保留旧引用继续播，列表反映新树。
     */
    public void softReload() {
        if (!rendered) return;
        Live home = LiveConfig.get().getHome();
        if (home == null || home.getGroups().isEmpty()) return;
        // 后台解析进行中的树尚未发布完整分组，避免主线程遍历到半成品（CME）；解析完成会经 onLiveParsed 软重绑补上
        if (viewModel.isParsing(home)) return;
        String groupName = group == null ? "" : group.getName();
        String channelName = channel == null ? "" : channel.getName();
        groups.clear();
        hides.clear();
        for (Group item : home.getGroups()) (item.isHidden() ? hides : groups).add(item);
        // LIVE10:软重载拿到全新 Channel 对象,渲染期的三桶重排必须补做,否则沉底/屏蔽顺序回退解析原序
        reorderAll(home.getGroups());
        Group target = groupName.isEmpty() ? null : findGroupByName(groupName);
        if (target != null) group = target;
        Channel found = target == null || channel == null ? null : findChannelByName(target, channelName);
        if (found != null) {
            channel = found.group(target);
            List<Channel> channels = target.getChannel();
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i) == found) {
                    target.setPosition(i);
                    break;
                }
            }
        } else if (target != null && channel != null) {
            // 原台已不在新树（被隔离/改名）：保留引用继续播，但重绑到新树同名组，
            // 否则 isTuned/syncToTuned 会拿到脱离树的旧组导致列表选中态错位
            channel.group(target);
        }
        listener.onSoftReloaded();
    }

    @Nullable
    private Group findGroupByName(String name) {
        for (Group item : groups) if (item.getName().equals(name)) return item;
        return null;
    }

    @Nullable
    private Channel findChannelByName(Group target, String name) {
        for (Channel item : target.getChannel()) if (item.getName().equals(name)) return item;
        return null;
    }

    private void render(Live live) {
        if (live == null || rendered || live.getGroups().isEmpty()) return;
        rendered = true;
        viewModel.parseXml(live);
        // 被动健康沉底：渲染前对全部频道重排（线路与名称同步、选中线路保位），只沉底不删除
        reorderAll(live.getGroups());
        groups.clear();
        hides.clear();
        for (Group item : live.getGroups()) (item.isHidden() ? hides : groups).add(item);
        listener.onGroupsChanged();
        restore(LiveConfig.get().findKeepPosition(groups));
    }

    /** 三桶重排整棵分组树（健康→沉底→屏蔽，各桶内保持解析原序）：render 与 softReload 共用，供 JVM 单测。 */
    static void reorderAll(List<Group> allGroups) {
        for (Group item : allGroups) for (Channel channel : item.getChannel()) LineHealth.reorder(channel);
    }

    public boolean isRendered() {
        return rendered;
    }

    /** 收藏恢复：keep 位置失效时兜底到第一个有频道的真实分组，保证进页自动播（单分组/收藏首组等场景）。 */
    private void restore(int[] position) {
        int[] target = valid(position) ? position : fallbackPosition();
        if (target == null) return;
        group = groups.get(target[0]);
        group.setPosition(target[1]);
        listener.onGroupSelected(group);
        tune(group.current(), true);
    }

    public void restoreByNumber(String number) {
        int[] position = LiveConfig.get().findByChannelNumber(number, groups);
        if (!valid(position)) return;
        group = groups.get(position[0]);
        group.setPosition(position[1]);
        listener.onGroupSelected(group);
        tune(group.current(), true);
    }

    private boolean valid(int[] position) {
        if (position == null || position[0] < 0 || position[0] >= groups.size()) return false;
        Group target = groups.get(position[0]);
        return position[1] >= 0 && position[1] < target.getChannel().size();
    }

    private int[] fallbackPosition() {
        for (int i = 0; i < groups.size(); i++) {
            Group item = groups.get(i);
            if (item.isKeep() || item.getChannel().isEmpty()) continue;
            return new int[]{i, 0};
        }
        return null;
    }

    public void selectGroup(Group item) {
        group = item;
        listener.onGroupSelected(item);
    }

    public void tune(Channel item, boolean syncUi) {
        if (item == null || group == null) return;
        cancelAutoHop();
        List<Channel> channels = group.getChannel();
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i) == item) {
                group.setPosition(i);
                break;
            }
        }
        channel = item.group(group);
        viewModel.getEpg(channel);
        zapTune = System.currentTimeMillis();
        zapLogged = false;
        ZapMetric.tune(channel.getName(), channel.getUrls().size());
        // LIVE10:主线程快照预热目标(方向跟随最近换台方向),scheduler 线程不再读会话状态
        ZapPrewarm.schedule(prewarmTarget());
        listener.onChannelTuned(channel, syncUi);
    }

    /** 预热目标（LIVE10）：最近换台方向上的邻台目的地，与 stepChannel 共用同一邻台语义；命中当前台或空位则不预热。 */
    @Nullable
    private Channel prewarmTarget() {
        if (group == null || group.isEmpty()) return null;
        int[] target = neighborDestination(group, groups, lastZapStep, LiveSetting.isAcross());
        if (target == null) return null;
        Group destination = groups.get(target[0]);
        List<Channel> channels = destination.getChannel();
        if (target[1] < 0 || target[1] >= channels.size()) return null;
        Channel candidate = channels.get(target[1]);
        return destination == group && candidate == group.current() ? null : candidate;
    }

    public void syncToTuned() {
        if (channel == null) return;
        Group target = channel.getGroup();
        if (group != target) selectGroup(target);
        listener.onChannelPosition(target.getPosition());
    }

    public void nextChannel() {
        stepChannel(1);
    }

    public void prevChannel() {
        stepChannel(-1);
    }

    private void stepChannel(int step) {
        if (group == null) return;
        lastZapStep = step;
        int[] target = neighborDestination(group, groups, step, LiveSetting.isAcross());
        if (target == null) return;
        Group destination = groups.get(target[0]);
        if (destination != group) {
            group = destination;
            listener.onGroupSelected(group);
        }
        group.setPosition(target[1]);
        if (!group.isEmpty()) tune(group.current(), true);
    }

    /**
     * 邻台目的地（LIVE10 纯函数，stepChannel 与预热目标共用，消除两处邻台逻辑漂移）：
     * 返回 {组索引, 组内位置}。组内步进；越界时 across 则跨组（skip 组跳过、按方向落首/尾位、
     * 环绕；其余组全 skip 时回原组按方向落首/尾位），否则组内环绕；单组越界原位重进（与原
     * stepGroup 提前 return 一致）。仅无组时返回 null；空组按方向落 0/-1，由调用方 isEmpty 兜底不 tune。
     */
    static int[] neighborDestination(Group from, List<Group> allGroups, int step, boolean across) {
        return neighborDestination(from, allGroups, step, across, Group::skip);
    }

    /** 谓词参数化版（JVM 单测注入 skip 语义；生产即 Group::skip=收藏组）。 */
    static int[] neighborDestination(Group from, List<Group> allGroups, int step, boolean across, Predicate<Group> skipTest) {
        if (from == null || allGroups.isEmpty()) return null;
        int index = allGroups.indexOf(from);
        if (index < 0) return null;
        int count = from.getChannel().size();
        int position = from.getPosition() + step;
        boolean limit = step > 0 ? position > count - 1 : position < 0;
        if (!across || !limit) return new int[]{index, limit ? (step > 0 ? 0 : count - 1) : position};
        for (int i = 1; i < allGroups.size(); i++) {
            int p = Math.floorMod(index + step * i, allGroups.size());
            Group candidate = allGroups.get(p);
            if (!skipTest.test(candidate)) return new int[]{p, step > 0 ? 0 : candidate.getChannel().size() - 1};
        }
        // 其余组全部 skip：单组时原 stepGroup 环回自身提前 return（原位重进）；多组时环绕回原组按方向落首/尾位
        return allGroups.size() > 1 ? new int[]{index, step > 0 ? 0 : count - 1} : new int[]{index, from.getPosition()};
    }

    public void nextLine(boolean showInfo) {
        if (channel == null || channel.isOnly()) return;
        // 自动换线路径：跳过用户屏蔽的线路；全部被屏蔽时不动，避免错误循环
        if (!stepLine(1)) return;
        viewModel.getEpg(channel);
        listener.onLineChanged(showInfo);
        fetchLive();
    }

    public void prevLine() {
        if (channel == null || channel.isOnly()) return;
        if (!stepLine(-1)) return;
        viewModel.getEpg(channel);
        listener.onLineChanged(true);
        fetchLive();
    }

    /** 环绕步进换线并跳过屏蔽线路；返回是否落到了与原先不同的线路（全部被屏蔽时环绕回原位 = false）。 */
    private boolean stepLine(int step) {
        int origin = channel.getIndex();
        int size = channel.getUrls().size();
        for (int i = 0; i < size; i++) {
            channel.switchLine(step > 0);
            if (!LineBlockStore.isBlocked(channel.getUrls().get(channel.getIndex()))) break;
        }
        return channel.getIndex() != origin;
    }

    public void setLine(int position) {
        if (channel == null || position < 0 || position >= channel.getUrls().size()) return;
        if (channel.getIndex() == position) return;
        channel.setIndex(position);
        viewModel.getEpg(channel);
        listener.onLineChanged(false);
        fetchLive();
    }

    /**
     * 弹窗长按：屏蔽/取消屏蔽指定线路（显式动作，不受 catchup 豁免影响）。
     * 与沉底同一契约——先重排（选中线路按 URL 保位）；屏蔽当前在播线路时自动
     * 跳到下一条未屏蔽线路（其余全部被屏蔽则留在原线继续播）。
     */
    public void toggleBlock(int position) {
        if (channel == null || position < 0 || position >= channel.getUrls().size()) return;
        String url = channel.getUrls().get(position);
        if (LineBlockStore.isBlocked(url)) LineBlockStore.unblock(url);
        else LineBlockStore.block(url, channel.lineName(position));
        LineHealth.reorder(channel);
        if (LineBlockStore.isBlocked(url) && url.equals(currentLine()) && stepLine(1)) {
            viewModel.getEpg(channel);
            listener.onLineChanged(false);
            fetchLive();
        }
    }

    /**
     * 频道当前线路能否回看该节目:能力(catchup 声明 / PLTV 特征自动预设 / RTSP 时移)
     * + days 窗口,供节目单状态标签与点击决策共用,保证"显示可回看"与"点击可回看"不漂移。
     */
    public static boolean isCatchupable(Channel channel, EpgData data) {
        if (channel == null || data == null || data.isFuture() || !data.isPast()) return false;
        if (channel.isRtsp()) return true;
        if (!channel.hasCatchup()) return false;
        return channel.getCatchup().withinDays(data);
    }

    public void onEpgDataClick(EpgData data) {
        if (channel == null || data == null || data.isFuture()) return;
        // 回看中点正在播的这档 = 回直播;直播中点当前档仍走下方选中分支(从头看本档)
        if (data.isInRange() && isCatchup()) {
            getEpg();
            fetchLive();
            return;
        }
        if (data.isSelected() || isCatchupable(channel, data)) {
            if (!data.isSelected()) listener.onCatchupLoading(data);
            fetchCatchup(data);
            return;
        }
        listener.onCatchupUnsupported();
    }

    public void fetchLive() {
        if (channel == null) return;
        cancelAutoHop();
        playbackCatchup = false;
        LiveConfig.get().setKeep(channel);
        listener.onFetch(false);
        viewModel.getUrl(channel);
    }

    public void fetchCatchup(EpgData data) {
        if (channel == null) return;
        playbackCatchup = true;
        listener.onFetch(true);
        viewModel.getUrl(channel, data);
    }

    public void getEpg() {
        if (channel != null) viewModel.getEpg(channel);
    }

    public void onEpgResult(Epg epg) {
        listener.onEpgUpdated(epg);
    }

    public void onXmlResult(boolean success) {
        if (success && channel != null) viewModel.getEpg(channel);
    }

    public void play(Result result) {
        String realUrl = result.getRealUrl();
        if (isSameReload(realUrl)) {
            String msg = pendingReloadMsg;
            clearPendingReload();
            handleSameReload(msg);
            return;
        }
        clearPendingReload();
        playbackKey = realUrl;
        hopCount = 0;
        if (!playbackCatchup) {
            LineHealth.success(currentLine());
            // LIVE10 起播复用提示（推断级）：本次起播端点是否在预热池存活窗口内
            ZapMetric.reuse(ZapPrewarm.isWarmed(realUrl), channel == null ? "" : channel.getName());
        }
        if (zapTune > 0 && !zapLogged) {
            zapLogged = true;
            ZapMetric.resolved(System.currentTimeMillis() - zapTune, channel == null ? "" : channel.getName());
        }
        listener.onPlaybackStart(result);
    }

    /** 当前选中线路的原始 URL（健康记分键，与渲染重排使用同一形态）。 */
    @Nullable
    private String currentLine() {
        if (channel == null) return null;
        List<String> urls = channel.getUrls();
        int index = channel.getIndex();
        return index >= 0 && index < urls.size() ? urls.get(index) : null;
    }

    private boolean isSameReload(String realUrl) {
        return pendingReloadUrl != null && !pendingReloadUrl.isEmpty() && pendingReloadUrl.equals(realUrl);
    }

    private void clearPendingReload() {
        pendingReloadUrl = null;
        pendingReloadMsg = null;
    }

    private void handleSameReload(String msg) {
        if (channel != null && !channel.isOnly()) {
            boolean sunk = !playbackCatchup && LineHealth.failure(currentLine());
            nextLine(true);
            if (sunk) LineHealth.reorder(channel);
        } else {
            onPlaybackError(msg);
        }
    }

    public void onPlaybackReload(String msg, String fallbackUrl) {
        if (channel == null) {
            onPlaybackError(msg);
            return;
        }
        if (!playbackCatchup) LineHealth.failure(currentLine());
        pendingReloadUrl = playbackKey != null ? playbackKey : fallbackUrl;
        pendingReloadMsg = msg;
        listener.onPlaybackStopping();
        fetchLive();
    }

    public void onPlaybackError(String msg) {
        boolean sunk = !playbackCatchup && LineHealth.failure(currentLine());
        listener.onPlaybackFailed(msg);
        startFlow();
        // 沉底即时生效：换线落点已定，立刻重排本频道（后续换台/线路列表直接反映）
        if (sunk && channel != null) LineHealth.reorder(channel);
        scheduleAutoHopIfNeeded();
    }

    /**
     * 全线路失败兜底:换线不可用(自动换线关闭或已是最后一线)时延时跳下一台。
     * 代数校验防串台——期间任何 tune/fetchLive(用户手动动作、重载链)都会使挂起跳台作废;
     * 播放成功清零计数,连续 MAX_AUTO_HOP 次失败后停回错误视图。
     */
    private void scheduleAutoHopIfNeeded() {
        if (!LiveSetting.isAutoHop() || playbackCatchup || channel == null) return;
        if (LiveSetting.isChange() && !channel.isLast()) return;
        if (hopCount >= MAX_AUTO_HOP) return;
        int generation = ++hopGeneration;
        Task.schedule(() -> {
            if (generation != hopGeneration || channel == null) return;
            hopCount++;
            nextChannel();
        }, AUTO_HOP_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelAutoHop() {
        hopGeneration++;
    }

    /** 页面销毁:作废挂起的自动跳台与在途预热,防止死后回调触碰已释放的播放器(同 mKeyDown.release 语义)。 */
    public void release() {
        cancelAutoHop();
        ZapPrewarm.cancel();
    }

    private void startFlow() {
        if (channel == null || !LiveSetting.isChange()) return;
        if (!channel.isLast()) nextLine(true);
    }

    public void onEnded(boolean live) {
        if (live) {
            checkNext();
        } else {
            nextChannel();
        }
    }

    private void checkNext() {
        if (channel == null) return;
        Epg epg = channel.getData(viewModel.getZoneId());
        int current = epg.getInRange();
        int position = epg.getSelected() + 1;
        boolean hasNext = position <= current && position > 0;
        if (hasNext) {
            onEpgDataClick(epg.getList().get(position));
        } else {
            fetchLive();
        }
    }

    @Nullable
    public Group keepGroup() {
        return groups.isEmpty() ? null : groups.get(0).isKeep() ? groups.get(0) : null;
    }

    public void addKeep(Channel item) {
        Group keep = keepGroup();
        if (keep == null || item == null) return;
        keep.add(item);
        Keep keepItem = new Keep();
        keepItem.setKey(item.getName());
        keepItem.setType(1);
        keepItem.save();
    }

    public void delKeep(Channel item) {
        Group keep = keepGroup();
        if (keep != null && item != null) keep.getChannel().remove(item);
        Keep.delete(item.getName());
    }

    /** 解锁匹配 pass 的隐藏组（pass=null 时解锁全部）；无匹配返回 false，调用方应给反馈避免静默。 */
    public boolean unlock(String pass) {
        Group first = null;
        Iterator<Group> iterator = hides.iterator();
        while (iterator.hasNext()) {
            Group item = iterator.next();
            if (pass != null && !pass.equals(item.getPass())) continue;
            groups.add(item);
            if (first == null) first = item;
            iterator.remove();
        }
        if (first == null) return false;
        listener.onGroupsChanged();
        listener.onUnlocked(first);
        return true;
    }
}
