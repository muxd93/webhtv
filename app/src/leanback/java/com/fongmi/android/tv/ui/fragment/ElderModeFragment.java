package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.content.Context;
import android.graphics.Bitmap;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.graphics.Rect;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.ElderCard;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.SmbServer;
import com.fongmi.android.tv.databinding.ActivityElderModeBinding;
import com.fongmi.android.tv.databinding.DialogAddSmbServerBinding;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.FileActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.SettingActivity;
import com.fongmi.android.tv.ui.activity.SmbBrowserActivity;
import com.fongmi.android.tv.ui.activity.SmbDiscoverActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.LiveActivity;
import com.fongmi.android.tv.ui.adapter.ElderCardAdapter;
import com.fongmi.android.tv.ui.dialog.ElderCardMenuDialog;
import com.fongmi.android.tv.ui.dialog.ElderCoverPickerDialog;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SmbHelper;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ElderModeFragment extends Fragment implements ElderCardAdapter.OnClickListener, ElderCardMenuDialog.Callback, ElderCoverPickerDialog.Callback {

    private static final String[] VIDEO_EXTENSIONS = {"mp4", "mkv", "avi", "ts", "flv", "mov", "wmv", "rmvb", "3gp", "webm", "m4v", "mpeg", "mpg", "vob", "m2ts", "m3u8", "ogv", "f4v"};

    // 老人模式新增入口的文案：未走多语言，后续需要国际化时再收进 strings.xml
    private static final String TEXT_LIVE_NAME = "看电视";
    private static final String TEXT_LIVE_EXISTS = "已经添加过看电视了";
    private static final String TEXT_APP_ENTRY = "其他应用";
    private static final String TEXT_APP_NONE = "没有找到可以添加的电视应用";
    private static final String TEXT_APP_MISSING = "这个应用打不开，可能已经卸载了";
    private static final String TEXT_UPDATING = "正在更新，请稍候";
    private static final String TEXT_UPDATE_OK = "更新好了";
    private static final String TEXT_UPDATE_FAIL = "没连上，请稍后再试";
    private static final String TEXT_SPEAK_BACK = "返回标准模式";
    private static final String TEXT_SPEAK_SEARCH = "搜索";
    private static final String TEXT_SPEAK_SETTING = "设置";
    private static final String TEXT_SPEAK_UPDATE = "更新电视";
    private static final long CONFIG_SYNC_INTERVAL_MS = 20 * 60 * 60 * 1000L;

    private ActivityElderModeBinding mBinding;
    private ElderCardAdapter mAdapter;
    private Clock mClock;
    private ActivityResultLauncher<Intent> fileLauncher;
    private ActivityResultLauncher<String> coverLocalLauncher;
    private ActivityResultLauncher<Intent> smbLauncher;
    private ActivityResultLauncher<Intent> smbDiscoverLauncher;
    private ElderCard pendingCoverCard;
    private ElderCard pendingPlayCard;
    private Runnable pendingTimeout;
    private Runnable speakRunnable;
    private boolean refreshing;
    private TextToSpeech tts;
    private boolean ttsReady;
    private static final long PENDING_TIMEOUT_MS = 15000;

    public static ElderModeFragment newInstance() {
        return new ElderModeFragment();
    }

    private boolean alive() {
        Activity activity = getActivity();
        return isAdded() && activity != null && !activity.isFinishing();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SpiderDebug.log("elder-mode", "fragment onCreate");
        registerLaunchers();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        mBinding = ActivityElderModeBinding.inflate(inflater, container, false);
        return mBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        SpiderDebug.log("elder-mode", "fragment onViewCreated");
        // 老人只看几点，默认不显示秒：30 秒刷新一次，比每秒刷新少 30 倍主线程唤醒
        boolean showSecond = Setting.isElderClockSecond();
        mClock = new Clock().view(mBinding.clock).format(showSecond ? "HH:mm:ss" : "HH:mm").period(showSecond ? 1000 : 30_000);
        setDate();
        initTts();
        setRecyclerView();
        loadCards();
        initEvent();
        initPending();
        mBinding.recycler.post(() -> mBinding.recycler.requestFocus());
        checkDailyConfigSync();
    }

    private void registerLaunchers() {
        fileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                Uri uri = result.getData().getData();
                String path = uri.toString();
                String name = uri.getLastPathSegment();
                if (name == null) name = path;
                List<ElderCard> existing = AppDatabase.get().getElderCardDao().findAll();
                ElderCard card = ElderCard.create(ElderCard.Type.LOCAL_FILE, path, name, null, 0, null);
                card.setSortOrder(existing.size());
                card.save();
                loadCards();
            }
        });
        coverLocalLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && pendingCoverCard != null) {
                pendingCoverCard.setPic(uri.toString());
                pendingCoverCard.setCoverType(ElderCard.CoverType.LOCAL);
                pendingCoverCard.setCoverValue(null);
                pendingCoverCard.save();
                pendingCoverCard = null;
                loadCards();
            }
        });
        // 发现向导添加成功后，直接接着打开该服务器的文件浏览，用户无需再点一次
        smbDiscoverLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            String serverId = result.getData().getStringExtra(SmbDiscoverActivity.EXTRA_SERVER_ID);
            if (serverId == null) return;
            SmbServer server = AppDatabase.get().getSmbServerDao().find(serverId);
            if (server != null) startSmbBrowser(server);
        });
        smbLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Intent data = result.getData();
                String smbUrl = data.getStringExtra(SmbBrowserActivity.EXTRA_SMB_URL);
                String smbName = data.getStringExtra(SmbBrowserActivity.EXTRA_SMB_NAME);
                String serverId = data.getStringExtra(SmbBrowserActivity.EXTRA_SMB_SERVER_ID);
                boolean isDir = data.getBooleanExtra(SmbBrowserActivity.EXTRA_SMB_IS_DIR, true);
                if (smbUrl != null && smbName != null) {
                    List<ElderCard> existing = AppDatabase.get().getElderCardDao().findAll();
                    ElderCard card = ElderCard.create(ElderCard.Type.SMB, smbUrl, smbName, null, 0, serverId);
                    card.setDir(isDir);
                    card.setSortOrder(existing.size());
                    card.save();
                    loadCards();
                }
            }
        });
    }

    private void setDate() {
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault());
            mBinding.date.setText(fmt.format(LocalDate.now()));
        } catch (Exception ignored) {
        }
    }

    private void initEvent() {
        mBinding.setting.setOnClickListener(v -> SettingActivity.start(requireActivity()));
        mBinding.backStandard.setOnClickListener(v -> {
            if (getActivity() instanceof HomeActivity) ((HomeActivity) getActivity()).switchToStandard();
        });
        mBinding.search.setOnClickListener(v -> SearchActivity.start(requireActivity()));
        mBinding.update.setOnClickListener(v -> refreshConfig(false));
        bindSpeak(mBinding.backStandard, TEXT_SPEAK_BACK);
        bindSpeak(mBinding.search, TEXT_SPEAK_SEARCH);
        bindSpeak(mBinding.setting, TEXT_SPEAK_SETTING);
        bindSpeak(mBinding.update, TEXT_SPEAK_UPDATE);
    }

    private void setRecyclerView() {
        mAdapter = new ElderCardAdapter(this);
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(requireActivity(), com.fongmi.android.tv.setting.Setting.getElderGridColumns()));
        final int space = ResUtil.dp2px(10);
        mBinding.recycler.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, android.view.View view, RecyclerView parent, RecyclerView.State state) {
                outRect.set(space, space, space, space);
            }
        });
        mBinding.recycler.setAdapter(mAdapter);
    }

    private void loadCards() {
        List<ElderCard> cards = AppDatabase.get().getElderCardDao().findAll();
        SpiderDebug.log("elder-mode", "loadCards count=%s", cards.size());
        syncCardInfo(cards);
        boolean empty = cards.isEmpty();
        ElderCard addCard = ElderCard.create(ElderCard.Type.ADD);
        addCard.setName(getString(R.string.elder_add_card));
        cards.add(addCard);
        mAdapter.setItems(cards);
        mBinding.emptyHint.setVisibility(empty ? android.view.View.VISIBLE : android.view.View.GONE);
        mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
    }

    private void syncCardInfo(List<ElderCard> cards) {
        for (ElderCard card : cards) {
            if (card.getType() == ElderCard.Type.KEEP) {
                Keep keep = Keep.find(card.getCid(), card.getRefKey());
                if (keep != null) {
                    card.setName(keep.getVodName());
                    card.setVodPic(keep.getVodPic());
                }
            } else if (card.getType() == ElderCard.Type.HISTORY) {
                History history = History.find(card.getRefKey());
                if (history != null) {
                    card.setName(history.getVodName());
                    card.setVodPic(history.getVodPic());
                    card.setVodRemarks(history.getVodRemarks());
                }
            } else if ((card.getType() == ElderCard.Type.LOCAL_FILE || card.getType() == ElderCard.Type.SMB)
                    && !card.hasCustomCover() && !card.isDir()) {
                // 本地/分享视频无自带海报：未自定义封面时，自动提取视频首帧作为默认封面
                if (TextUtils.isEmpty(card.getPic())) {
                    extractFirstFrame(card);
                }
            }
        }
    }

    /** 异步提取本地/分享视频首帧作为默认封面（仅在没有自定义封面且尚无 pic 时触发） */
    private void extractFirstFrame(ElderCard card) {
        Context ctx = getContext();
        Activity activity = getActivity();
        if (ctx == null || activity == null) return;
        Task.execute(() -> {
            File out = new File(ctx.getCacheDir(), "elder_cover_" + card.getId() + ".jpg");
            String src = card.getRefKey();
            if (src.startsWith("file://")) src = Uri.parse(src).getPath();
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(src);
                Bitmap bitmap = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bitmap != null) {
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int w = bitmap.getWidth(), h = bitmap.getHeight();
                        int tw = 320;
                        Bitmap scaled = (w > tw) ? Bitmap.createScaledBitmap(bitmap, tw, (int) (h * ((float) tw / w)), true) : bitmap;
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                        if (scaled != bitmap) scaled.recycle();
                        bitmap.recycle();
                    }
                    final String path = out.getAbsolutePath();
                    activity.runOnUiThread(() -> {
                        if (!alive()) return;
                        ElderCard fresh = AppDatabase.get().getElderCardDao().find(card.getId());
                        if (fresh != null && !fresh.hasCustomCover() && TextUtils.isEmpty(fresh.getPic())) {
                            fresh.setPic(path);
                            fresh.setCoverType(ElderCard.CoverType.FIRST_FRAME);
                            fresh.setCoverValue(null);
                            fresh.save();
                            loadCards();
                        }
                    });
                }
            } catch (Throwable e) {
                // 提取失败：保持无封面，后续由文字色块兜底
            } finally {
                try { retriever.release(); } catch (Throwable ignored) {}
            }
        });
    }

    @Override
    public void onItemClick(ElderCard item) {
        SpiderDebug.log("elder-mode", "onItemClick type=%s add=%s", item.getType(), item.isAddType());
        if (item.isAddType()) {
            showAddCardDialog();
            return;
        }
        switch (item.getType()) {
            case KEEP:
            case HISTORY:
                SpiderDebug.log("elder-mode", "KEEP/HISTORY click: refKey=%s siteKey=%s vodId=%s name=%s",
                        item.getRefKey(), item.getSiteKeyFromRef(), item.getVodIdFromRef(), item.getName());
                playKeepOrHistory(item);
                break;
            case LOCAL_FILE:
                onLocalFileClick(item);
                break;
            case SMB:
                onSmbClick(item);
                break;
            case LIVE:
                LiveActivity.start(requireActivity());
                break;
            case APP:
                startApp(item.getRefKey());
                break;
        }
    }

    /** 打开外部应用（B站等），按包名拉取 Leanback 启动入口 */
    private void startApp(String pkg) {
        if (TextUtils.isEmpty(pkg)) return;
        Intent intent = requireActivity().getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Toast.makeText(requireActivity(), TEXT_APP_MISSING, Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        startActivity(intent);
    }

    /** 重新拉取站源配置：一次性网络请求，完成后不留任何常驻开销 */
    private void refreshConfig(boolean silent) {
        if (refreshing) return;
        refreshing = true;
        if (!silent) Toast.makeText(requireActivity(), TEXT_UPDATING, Toast.LENGTH_SHORT).show();
        VodConfig.get().clear().config(VodConfig.get().getConfig()).load(new Callback() {
            @Override
            public void success() {
                refreshing = false;
                Setting.putLastConfigSyncAt(System.currentTimeMillis());
                if (!alive()) return;
                loadCards();
                if (!silent) Toast.makeText(requireActivity(), TEXT_UPDATE_OK, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void error(String msg) {
                refreshing = false;
                if (alive() && !silent) Toast.makeText(requireActivity(), TEXT_UPDATE_FAIL, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 每天首次进入桌面时自动拉一次站源配置，老人不需要做任何操作 */
    private void checkDailyConfigSync() {
        if (!Setting.isElderAutoUpdate()) return;
        if (System.currentTimeMillis() - Setting.getLastConfigSyncAt() < CONFIG_SYNC_INTERVAL_MS) return;
        refreshConfig(true);
    }

    /** 卡片区是否停在最开头 */
    public boolean isAtStart() {
        if (mBinding == null) return true;
        RecyclerView.LayoutManager manager = mBinding.recycler.getLayoutManager();
        if (manager instanceof GridLayoutManager grid) return grid.findFirstVisibleItemPosition() == 0;
        return true;
    }

    /** 主页键/返回键复位：回到第一张卡片，让老人每次面对的都是同一个起点 */
    public void resetToStart() {
        if (mBinding == null) return;
        cancelPending();
        mBinding.recycler.scrollToPosition(0);
        mBinding.recycler.post(() -> {
            if (mBinding == null) return;
            mBinding.recycler.requestFocus();
        });
    }

    private void onLocalFileClick(ElderCard item) {
        String refKey = item.getRefKey();
        File file = refKey.startsWith("file://") ? new File(Uri.parse(refKey).getPath()) : new File(refKey);
        if (file.isDirectory()) {
            Notify.show(R.string.elder_smb_loading);
            String name = file.getName();
            String pic = item.getPic();
            boolean recursive = com.fongmi.android.tv.setting.Setting.isElderRecursive();
            Task.execute(() -> {
                String playUrl = buildFolderPlayUrl(file, recursive);
                App.post(() -> {
                    if (!alive()) return;
                    if (playUrl.isEmpty()) {
                        Toast.makeText(requireActivity(), R.string.elder_no_video_in_folder, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String id = name + "|||" + playUrl;
                    VideoActivity.start(requireActivity(), SiteApi.PUSH, id, name, pic);
                });
            });
        } else {
            VideoActivity.push(requireActivity(), refKey);
        }
    }

    private String buildFolderPlayUrl(File dir) {
        return buildFolderPlayUrl(dir, false);
    }

    private String buildFolderPlayUrl(File dir, boolean recursive) {
        List<String> parts = new ArrayList<>();
        collectFolderVideos(dir, recursive, parts);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('#');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private void collectFolderVideos(File dir, boolean recursive, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            if (f.isDirectory()) {
                if (recursive) collectFolderVideos(f, true, out);
            } else if (isVideoFile(f.getName())) {
                out.add(f.getName() + '$' + Uri.fromFile(f).toString());
            }
        }
    }

    private boolean isVideoFile(String name) {
        if (name == null || !name.contains(".")) return false;
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "mp4": case "mkv": case "avi": case "ts": case "flv":
            case "mov": case "wmv": case "rmvb": case "3gp": case "webm":
            case "m4v": case "mpeg": case "mpg": case "vob": case "m2ts":
            case "m3u8": case "ogv": case "f4v":
                return true;
            default:
                return false;
        }
    }

    private void onSmbClick(ElderCard item) {
        SmbServer server = findSmbServer(item.getSiteKey());
        if (server == null) {
            Toast.makeText(requireActivity(), R.string.elder_smb_connect_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        String refKey = item.getRefKey();
        boolean isDir = item.isDir();
        if (isDir) {
            String path = parseSmbPath(refKey, server.getShareName());
            playSmbFolder(server, path, item.getName());
        } else {
            VideoActivity.push(requireActivity(), refKey);
        }
    }

    private SmbServer findSmbServer(String siteKey) {
        if (siteKey == null || siteKey.isEmpty()) return null;
        return AppDatabase.get().getSmbServerDao().find(siteKey);
    }

    private String parseSmbPath(String url, String shareName) {
        if (url == null || shareName == null) return "";
        // getSmbUrl 会对路径段做 URL-encode（中文/空格、'%2F' 等），先整体解码再按字面 '/share/' 匹配
        try {
            url = java.net.URLDecoder.decode(url, "UTF-8");
        } catch (Exception ignored) {
        }
        String marker = "/" + shareName + "/";
        int idx = url.indexOf(marker);
        if (idx < 0) return "";
        String after = url.substring(idx + marker.length());
        return after.replace('/', '\\');
    }

    private boolean hasVideoExtension(String url) {
        if (url == null || !url.contains(".")) return false;
        String name = url.substring(url.lastIndexOf('/') + 1);
        return isVideoFile(name);
    }

    private void playSmbFolder(SmbServer server, String path, String dirName) {
        Notify.show(R.string.elder_smb_loading);
        boolean recursive = com.fongmi.android.tv.setting.Setting.isElderRecursive();
        Task.execute(() -> {
            List<SmbHelper.SmbFileItem> videos = SmbHelper.listVideoFiles(server, path, recursive);
            if (videos.isEmpty()) {
                App.post(() -> { if (alive()) Toast.makeText(requireActivity(), R.string.elder_no_video_in_folder, Toast.LENGTH_SHORT).show(); });
                return;
            }
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (SmbHelper.SmbFileItem f : videos) {
                if (!first) sb.append('#');
                sb.append(f.getName()).append('$').append(SmbHelper.getSmbUrl(server, f.getPath()));
                first = false;
            }
            String id = sb.toString();
            App.post(() -> { if (alive()) VideoActivity.start(requireActivity(), SiteApi.PUSH, id, dirName, null); });
        });
    }

    @Override
    public boolean onItemLongClick(ElderCard item) {
        if (item.isAddType()) return false;
        ElderCardMenuDialog.create(item).callback(this).show(requireActivity());
        return true;
    }

    @Override
    public void onItemFocus(ElderCard item) {
        speak(item.isAddType() ? getString(R.string.elder_add_card) : item.getName());
    }

    private void initTts() {
        tts = new TextToSpeech(requireActivity(), status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                ttsReady = false;
                return;
            }
            int result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
            SpiderDebug.log("elder-mode", "tts init status=%s lang=%s ready=%s", status, result, ttsReady);
        });
    }

    /** 老人不识字：焦点移动时朗读名称。延迟一拍，避免快速移动时连续打断 */
    public void speak(CharSequence text) {
        if (!Setting.isElderTts()) return;
        if (!ttsReady || tts == null || TextUtils.isEmpty(text)) return;
        if (speakRunnable != null) App.removeCallbacks(speakRunnable);
        String value = text.toString();
        speakRunnable = () -> tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "elder");
        App.post(speakRunnable, 350);
    }

    private void bindSpeak(View view, String text) {
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) speak(text);
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case KEEP:
            case HISTORY:
                loadCards();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (!event.isVod()) return;
        if (pendingPlayCard == null) return;
        SpiderDebug.log("elder-mode", "onConfigEvent VOD ready, auto playing pending card");
        ElderCard card = pendingPlayCard;
        cancelPending();
        startActivityForCard(card);
    }

    private void initPending() {
        mBinding.pendingCancel.setOnClickListener(v -> cancelPending());
    }

    /**
     * KEEP/HISTORY 卡片依赖 VodConfig 站点列表。若配置尚未就绪，记下意图并显示等待遮罩，
     * 待 ConfigEvent.VOD 触发后自动跳转；超时则提示网络异常。这样用户在竞态窗口内点击也不会失败。
     */
    private void playKeepOrHistory(ElderCard item) {
        String siteKey = item.getSiteKeyFromRef();
        if (VodConfig.isReady() || (siteKey != null && siteKey.equals(SiteApi.PUSH))) {
            startActivityForCard(item);
            return;
        }
        SpiderDebug.log("elder-mode", "config not ready, hold click for card=%s", item.getRefKey());
        pendingPlayCard = item;
        mBinding.pendingOverlay.setVisibility(View.VISIBLE);
        mBinding.pendingCancel.requestFocus();
        final ElderCard held = item;
        pendingTimeout = () -> {
            if (pendingPlayCard == null) return;
            SpiderDebug.log("elder-mode", "pending play timeout");
            cancelPending();
            Toast.makeText(requireActivity(), R.string.elder_source_timeout, Toast.LENGTH_LONG).show();
        };
        mBinding.pendingOverlay.postDelayed(pendingTimeout, PENDING_TIMEOUT_MS);
    }

    private void startActivityForCard(ElderCard item) {
        VideoActivity.start(requireActivity(), item.getSiteKeyFromRef(), item.getVodIdFromRef(), item.getName(), item.getVodPic());
    }

    private void cancelPending() {
        if (pendingTimeout != null) {
            mBinding.pendingOverlay.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
        pendingPlayCard = null;
        mBinding.pendingOverlay.setVisibility(View.GONE);
    }



    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        mClock.start();
    }

    @Override
    public void onPause() {
        super.onPause();
        mClock.stop();
        if (tts != null) tts.stop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mClock != null) mClock.release();
        mClock = null;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        ttsReady = false;
        pendingPlayCard = null;
        pendingTimeout = null;
        mBinding = null;
        SpiderDebug.log("elder-mode", "fragment onDestroyView");
    }

    private void showAddCardDialog() {
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.elder_add_card)
                .setItems(new String[]{
                        getString(R.string.elder_from_keep),
                        getString(R.string.elder_from_history),
                        getString(R.string.elder_local_file),
                        getString(R.string.elder_smb),
                        TEXT_LIVE_NAME,
                        TEXT_APP_ENTRY
                }, (dialog, which) -> {
                    switch (which) {
                        case 0: addFromKeep(); break;
                        case 1: addFromHistory(); break;
                        case 2: addLocalFile(); break;
                        case 3: addSmb(); break;
                        case 4: addLiveCard(); break;
                        case 5: addAppCard(); break;
                    }
                })
                .show();
    }

    private void addFromKeep() {
        List<Keep> keeps = Keep.getVod();
        if (keeps.isEmpty()) {
            Toast.makeText(requireActivity(), R.string.elder_no_keep, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[keeps.size()];
        boolean[] checked = new boolean[keeps.size()];
        for (int i = 0; i < keeps.size(); i++) {
            names[i] = keeps.get(i).getVodName() + " (" + keeps.get(i).getSiteName() + ")";
        }
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.elder_select_items)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<ElderCard> existing = AppDatabase.get().getElderCardDao().findAll();
                    int sortOrder = existing.size();
                    for (int i = 0; i < keeps.size(); i++) {
                        if (!checked[i]) continue;
                        Keep keep = keeps.get(i);
                        String refKey = keep.getKey();
                        if (existing.stream().anyMatch(c -> c.getRefKey() != null && c.getRefKey().equals(refKey))) continue;
                        ElderCard card = ElderCard.create(ElderCard.Type.KEEP, refKey, keep.getVodName(), keep.getVodPic(), keep.getCid(), keep.getSiteKey());
                        card.setSortOrder(sortOrder++);
                        card.save();
                    }
                    loadCards();
                })
                .show();
    }

    private void addFromHistory() {
        List<History> histories = History.get();
        if (histories.isEmpty()) {
            Toast.makeText(requireActivity(), R.string.elder_no_history, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[histories.size()];
        boolean[] checked = new boolean[histories.size()];
        for (int i = 0; i < histories.size(); i++) {
            History h = histories.get(i);
            names[i] = h.getVodName() + " " + h.getVodRemarks();
        }
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.elder_select_items)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<ElderCard> existing = AppDatabase.get().getElderCardDao().findAll();
                    int sortOrder = existing.size();
                    for (int i = 0; i < histories.size(); i++) {
                        if (!checked[i]) continue;
                        History h = histories.get(i);
                        String refKey = h.getKey();
                        if (existing.stream().anyMatch(c -> c.getRefKey() != null && c.getRefKey().equals(refKey))) continue;
                        ElderCard card = ElderCard.create(ElderCard.Type.HISTORY, refKey, h.getVodName(), h.getVodPic(), h.getCid(), h.getSiteKey());
                        card.setSortOrder(sortOrder++);
                        card.save();
                    }
                    loadCards();
                })
                .show();
    }

    private void addLocalFile() {
        Intent intent = new Intent(requireActivity(), FileActivity.class);
        intent.putExtra("can_select_dir", true);
        fileLauncher.launch(intent);
    }

    /**
     * 局域网共享入口。
     * <p>
     * 没有已保存的服务器时直接进入自动发现向导；已有则先列出，末尾提供"添加新服务器"再进向导。
     * 向导内部完成扫描、鉴权与共享枚举，用户无需了解 IP 或共享名。
     */
    private void addSmb() {
        List<SmbServer> servers = AppDatabase.get().getSmbServerDao().findAll();
        if (servers.isEmpty()) {
            startSmbDiscover();
            return;
        }
        String[] names = new String[servers.size() + 1];
        for (int i = 0; i < servers.size(); i++) {
            SmbServer s = servers.get(i);
            names[i] = (s.getName() != null && !s.getName().isEmpty() ? s.getName() : s.getHost()) + " (" + s.getHost() + ")";
        }
        names[servers.size()] = getString(R.string.elder_smb_new_server);
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.elder_smb)
                .setItems(names, (dialog, which) -> {
                    if (which == servers.size()) {
                        startSmbDiscover();
                    } else {
                        startSmbBrowser(servers.get(which));
                    }
                })
                .show();
    }

    private void startSmbDiscover() {
        smbDiscoverLauncher.launch(new Intent(requireActivity(), SmbDiscoverActivity.class));
    }

    private void startSmbBrowser(SmbServer server) {
        Intent intent = new Intent(requireActivity(), SmbBrowserActivity.class);
        intent.putExtra(SmbBrowserActivity.EXTRA_SERVER_ID, server.getId());
        smbLauncher.launch(intent);
    }

    /** 添加"看电视"卡片：一键进直播，是老人最主要的目标，只允许有一张 */
    private void addLiveCard() {
        List<ElderCard> existing = AppDatabase.get().getElderCardDao().findAll();
        if (existing.stream().anyMatch(c -> c.getType() == ElderCard.Type.LIVE)) {
            Toast.makeText(requireActivity(), TEXT_LIVE_EXISTS, Toast.LENGTH_SHORT).show();
            return;
        }
        ElderCard card = ElderCard.create(ElderCard.Type.LIVE, "live", TEXT_LIVE_NAME, null, 0, null);
        card.setSortOrder(existing.size());
        card.save();
        loadCards();
    }

    /**
     * 添加外部应用卡片：电视上很多应用只声明了普通桌面入口，只查 Leanback 会漏掉大半，
     * 因此两个 category 都查并按包名去重，再用可滚动的带图标列表呈现。
     */
    private void addAppCard() {
        PackageManager pm = requireActivity().getPackageManager();
        String self = requireActivity().getPackageName();
        List<ResolveInfo> resolved = new ArrayList<>();
        resolved.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        resolved.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
            if (info.activityInfo.packageName.equals(self)) continue;
            unique.putIfAbsent(info.activityInfo.packageName + "/" + info.activityInfo.name, info);
        }
        List<ResolveInfo> apps = new ArrayList<>(unique.values());
        apps.sort(new ResolveInfo.DisplayNameComparator(pm));
        if (apps.isEmpty()) {
            Toast.makeText(requireActivity(), TEXT_APP_NONE, Toast.LENGTH_SHORT).show();
            return;
        }
        List<ElderCard> existing = AppDatabase.get().getElderCardDao().findAll();
        new AlertDialog.Builder(requireActivity())
                .setTitle(TEXT_APP_ENTRY)
                .setAdapter(new AppListAdapter(requireActivity(), pm, apps), (dialog, which) -> {
                    ResolveInfo info = apps.get(which);
                    String pkg = info.activityInfo.packageName;
                    if (existing.stream().anyMatch(c -> c.getType() == ElderCard.Type.APP && pkg.equals(c.getRefKey()))) return;
                    ElderCard card = ElderCard.create(ElderCard.Type.APP, pkg, String.valueOf(info.loadLabel(pm)), null, 0, null);
                    card.setSortOrder(existing.size());
                    card.save();
                    loadCards();
                })
                .show();
    }

    /** 带应用图标的选择列表，用 ListView 承载，遥控器上下键可正常滚动 */
    private static class AppListAdapter extends ArrayAdapter<ResolveInfo> {

        private final PackageManager pm;

        AppListAdapter(Context context, PackageManager pm, List<ResolveInfo> apps) {
            super(context, android.R.layout.simple_list_item_1, apps);
            this.pm = pm;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            ResolveInfo info = getItem(position);
            if (info == null) return view;
            view.setText(info.loadLabel(pm));
            view.setTextSize(20);
            int pad = ResUtil.dp2px(16);
            view.setPadding(pad, pad, pad, pad);
            try {
                Drawable icon = pm.getApplicationIcon(info.activityInfo.packageName);
                int size = ResUtil.dp2px(32);
                icon.setBounds(0, 0, size, size);
                view.setCompoundDrawables(icon, null, null, null);
                view.setCompoundDrawablePadding(pad);
            } catch (Exception ignored) {
            }
            return view;
        }
    }

    @Override
    public void onCardChanged() {
        loadCards();
    }

    @Override
    public void onPickCover(ElderCard card) {
        ElderCoverPickerDialog.create(card).callback(this).show(requireActivity());
    }

    @Override
    public void onPickCoverBuiltin(ElderCard card, String key) {
        card.setPic(null);
        card.setCoverType(ElderCard.CoverType.BUILTIN);
        card.setCoverValue(key);
        card.save();
        loadCards();
    }

    @Override
    public void onPickCoverLocal(ElderCard card) {
        pendingCoverCard = card;
        coverLocalLauncher.launch("image/*");
    }

    @Override
    public void onPickCoverUrl(ElderCard card) {
        EditText input = new EditText(requireActivity());
        input.setHint(R.string.elder_icon_url_hint);
        if (card.getPic() != null && card.getCoverType() == ElderCard.CoverType.URL.value) input.setText(card.getPic());
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.elder_pic_from_url)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    card.setPic(url);
                    card.setCoverType(ElderCard.CoverType.URL);
                    card.setCoverValue(null);
                    card.save();
                    loadCards();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
