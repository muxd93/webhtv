package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.LiveApi;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.utils.Task;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class LiveViewModel extends ViewModel {

    private final MutableLiveData<Boolean> xml;
    private final MutableLiveData<Result> url;
    private final MutableLiveData<Live> live;
    private final MutableLiveData<Epg> epg;
    private final MutableLiveData<String> message;

    private final Map<TaskType, ListenableFuture<?>> futures;
    private final Map<TaskType, AtomicInteger> taskIds;
    private volatile ZoneId zoneId;
    /** 正在后台解析分组的 Live（引用比较）；主线程 softReload/preview 据此避开并发遍历同一棵树。 */
    private volatile Live parsing;

    public LiveViewModel() {
        this.epg = new MutableLiveData<>();
        this.xml = new MutableLiveData<>();
        this.url = new MutableLiveData<>();
        this.live = new MutableLiveData<>();
        this.message = new MutableLiveData<>();
        this.zoneId = ZoneId.systemDefault();
        this.futures = new EnumMap<>(TaskType.class);
        this.taskIds = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) taskIds.put(type, new AtomicInteger(0));
    }

    public LiveData<Result> url() {
        return url;
    }

    public LiveData<Boolean> xml() {
        return xml;
    }

    public LiveData<Epg> epg() {
        return epg;
    }

    public LiveData<Live> live() {
        return live;
    }

    public LiveData<String> message() {
        return message;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public void parse(Live item) {
        parsing = item;
        execute(TaskType.LIVE, () -> {
            try {
                LiveApi.parse(item);
                setTimeZone(item);
                return item;
            } finally {
                // 解析结束（含失败/取消）即发布完整分组树；仅当仍是本任务的对象才清空，
                // 防止后启动解析任务的 finally 误清新标志导致 isParsing 失真、主线程遍历半成品树
                if (parsing == item) parsing = null;
            }
        }, live::postValue, error -> {
            if (error instanceof ExtractException) {
                url.postValue(Result.error(error.getMessage()));
            } else {
                live.postValue(new Live());
                message.postValue(error.getMessage() == null ? "" : error.getMessage());
            }
        });
    }

    /** 该 Live 是否正在后台解析分组（未发布完整树）；主线程遍历其 groups 前必须检查。 */
    public boolean isParsing(Live live) {
        return live != null && parsing == live;
    }

    public void parseXml(Live item) {
        execute(TaskType.XML, () -> LiveApi.parseXml(item), xml::postValue, error -> xml.postValue(false));
    }

    public void getEpg(Channel item) {
        // 失败/超时不发空 Epg：回贴频道现有数据（可为空 Epg），UI 保持旧节目单不清屏（LIVE9 SWR）
        execute(TaskType.EPG, () -> LiveApi.getEpg(item, zoneId), epg::postValue, error -> epg.postValue(item.getData(zoneId)));
    }

    public void getUrl(Channel item) {
        execute(TaskType.URL, () -> LiveApi.getUrl(item), url::postValue, this::handleUrlError);
    }

    public void getUrl(Channel item, EpgData data) {
        execute(TaskType.URL, () -> LiveApi.getUrl(item, data), url::postValue, this::handleUrlError);
    }

    private void handleUrlError(Throwable t) {
        if (t instanceof ExtractException) url.postValue(Result.error(t.getMessage()));
        else url.postValue(new Result());
    }

    private void setTimeZone(Live live) {
        try {
            this.zoneId = live.getTimeZone().isEmpty() ? ZoneId.systemDefault() : ZoneId.of(live.getTimeZone());
        } catch (Exception ignored) {
        }
    }

    private <T> void execute(TaskType type, Callable<T> callable, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        AtomicInteger taskId = taskIds.get(type);
        int currentId = taskId.incrementAndGet();
        ListenableFuture<?> old = futures.get(type);
        if (old != null) old.cancel(true);
        FluentFuture<T> future = FluentFuture.from(Task.executor().submit(callable)).withTimeout(type.timeout, TimeUnit.MILLISECONDS, Task.scheduler());
        futures.put(type, future);
        future.addCallback(Task.callback(
                result -> {
                    if (taskId.get() == currentId) onSuccess.accept(result);
                },
                error -> {
                    if (error instanceof CancellationException) return;
                    if (taskId.get() != currentId) return;
                    onError.accept(error);
                }
        ), MoreExecutors.directExecutor());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        futures.values().forEach(future -> future.cancel(true));
    }

    private enum TaskType {

        LIVE(Constant.TIMEOUT_LIVE),
        EPG(Constant.TIMEOUT_EPG),
        XML(Constant.TIMEOUT_XML),
        URL(Constant.TIMEOUT_PARSE_LIVE);

        final long timeout;

        TaskType(long timeout) {
            this.timeout = timeout;
        }
    }
}