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
        render(LiveConfig.get().getHome());
    }

    public void onLiveParsed(Live live) {
        render(live);
    }

    private void render(Live live) {
        if (live == null || rendered || live.getGroups().isEmpty()) return;
        rendered = true;
        viewModel.parseXml(live);
        groups.clear();
        hides.clear();
        for (Group item : live.getGroups()) (item.isHidden() ? hides : groups).add(item);
        listener.onGroupsChanged();
        restore(LiveConfig.get().findKeepPosition(groups));
    }

    private void restore(int[] position) {
        if (position[0] == -1) return;
        if (groups.size() == 1 || position[0] >= groups.size()) return;
        group = groups.get(position[0]);
        group.setPosition(position[1]);
        listener.onGroupSelected(group);
        tune(group.current(), true);
    }

    public void restoreByNumber(String number) {
        restore(LiveConfig.get().findByChannelNumber(number, groups));
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
        listener.onChannelTuned(channel, syncUi);
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
        channel.switchLine(true);
        viewModel.getEpg(channel);
        listener.onLineChanged(showInfo);
        fetchLive();
    }

    public void prevLine() {
        if (channel == null || channel.isOnly()) return;
        channel.switchLine(false);
        viewModel.getEpg(channel);
        listener.onLineChanged(true);
        fetchLive();
    }

    public void setLine(int position) {
        if (channel == null || position < 0 || position >= channel.getUrls().size()) return;
        if (channel.getIndex() == position) return;
        channel.setIndex(position);
        viewModel.getEpg(channel);
        listener.onLineChanged(false);
        fetchLive();
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
        listener.onPlaybackStart(result);
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
            nextLine(true);
        } else {
            onPlaybackError(msg);
        }
    }

    public void onPlaybackReload(String msg, String fallbackUrl) {
        if (channel == null) {
            onPlaybackError(msg);
            return;
        }
        pendingReloadUrl = playbackKey != null ? playbackKey : fallbackUrl;
        pendingReloadMsg = msg;
        listener.onPlaybackStopping();
        fetchLive();
    }

    public void onPlaybackError(String msg) {
        listener.onPlaybackFailed(msg);
        startFlow();
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

    public void unlock(String pass) {
        Group first = null;
        Iterator<Group> iterator = hides.iterator();
        while (iterator.hasNext()) {
            Group item = iterator.next();
            if (pass != null && !pass.equals(item.getPass())) continue;
            groups.add(item);
            if (first == null) first = item;
            iterator.remove();
        }
        if (first == null) return;
        listener.onGroupsChanged();
        listener.onUnlocked(first);
    }
}
