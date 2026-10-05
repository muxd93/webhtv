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

    public interface Listener {

        void onBusy(boolean busy);

        void onConfigError(String msg);

        void onGroupsChanged();

        void onGroupSelected(Group group);

        void onChannelTuned(Channel channel, boolean syncUi);

        void onChannelPosition(int position);

        void onLineChanged(boolean showInfo);

        void onCatchupLoading(EpgData data);

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
    private long zapTune;
    private boolean zapLogged;

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
        zapTune = 0;
        zapLogged = false;
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
        for (Group item : live.getGroups()) for (Channel channel : item.getChannel()) LineHealth.reorder(channel);
        groups.clear();
        hides.clear();
        for (Group item : live.getGroups()) (item.isHidden() ? hides : groups).add(item);
        listener.onGroupsChanged();
        restore(LiveConfig.get().findKeepPosition(groups));
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
        ZapPrewarm.schedule(this::nextPrewarmTarget);
        listener.onChannelTuned(channel, syncUi);
    }

    /** 预热目标：组内下一频道（组尾不跨组预热，方向未知避免无谓流量）。 */
    @Nullable
    private Channel nextPrewarmTarget() {
        if (group == null) return null;
        List<Channel> channels = group.getChannel();
        int next = group.getPosition() + 1;
        return next < channels.size() ? channels.get(next) : null;
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
        int count = group.getChannel().size();
        int position = group.getPosition() + step;
        boolean limit = step > 0 ? position > count - 1 : position < 0;
        if (LiveSetting.isAcross() && limit) {
            stepGroup(step, step > 0);
        } else {
            group.setPosition(limit ? (step > 0 ? 0 : count - 1) : position);
        }
        if (!group.isEmpty()) tune(group.current(), true);
    }

    private void stepGroup(int step, boolean first) {
        if (groups.isEmpty()) return;
        int position = groups.indexOf(group) + step;
        if (position > groups.size() - 1) position = 0;
        if (position < 0) position = groups.size() - 1;
        Group target = groups.get(position);
        if (target == group) return;
        group = target;
        listener.onGroupSelected(group);
        if (group.skip()) {
            stepGroup(step, first);
            return;
        }
        group.setPosition(first ? 0 : group.getChannel().size() - 1);
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

    public void onEpgDataClick(EpgData data) {
        if (channel == null || data == null) return;
        if (data.isSelected()) {
            fetchCatchup(data);
        } else if (channel.hasCatchup() || channel.isRtsp()) {
            listener.onCatchupLoading(data);
            fetchCatchup(data);
        }
    }

    public void fetchLive() {
        if (channel == null) return;
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
        if (!playbackCatchup) LineHealth.success(currentLine());
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
