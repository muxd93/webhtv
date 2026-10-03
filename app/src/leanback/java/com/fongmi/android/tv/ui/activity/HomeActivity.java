package com.fongmi.android.tv.ui.activity;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.PresenterSelector;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.bean.SmbServer;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.CustomTitleView;
import com.fongmi.android.tv.ui.dialog.AppListDialog;
import com.fongmi.android.tv.ui.dialog.SmbServerDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.KeepMorePresenter;
import com.fongmi.android.tv.ui.presenter.KeepPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.AppListUtil;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.CrashRestartMode;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.HistoryOpener;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.TtsSpeaker;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.web.HomeWebController;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.common.collect.Lists;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import com.fongmi.android.tv.ui.custom.HomeRows;
import com.fongmi.android.tv.ui.presenter.HomeEmptyPresenter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener, KeepPresenter.OnClickListener, KeepMorePresenter.OnClickListener, TypeAdapter.OnClickListener, HomeWebController.Listener, SmbServerDialog.Callback {

    private static final String TV_NORMAL = "tv-normal";
    private static final String TV_TOOLBAR_HIDDEN = "tv-toolbar-hidden";
    private static final String TV_OVERLAY = "tv-overlay";
    private static final String TV_FULL = "tv-full";
    /** 历史行与收藏行共用的行内条数上限，超出部分走"查看全部"尾卡 */
    private static final int ROW_LIMIT = 10;

    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mKeepAdapter;
    private ArrayObjectAdapter mFuncAdapter;
    private ArrayObjectAdapter mAdapter;
    private HistoryPresenter mPresenter;
    private SiteViewModel mViewModel;
    private TypeAdapter mTypeAdapter;
    private HomeWebController mWeb;
    private WebView mHomeWeb;
    private Result mResult;
    private Result mHomeResult;
    private Clock mClock;
    private HomeRows mRows;
    private ValueAnimator mInsetAnimator;
    private String webChromeMode = TV_NORMAL;
    private String webDefaultChromeMode = TV_FULL;
    private boolean webToolbarVisible = true;
    private boolean loadingHomeCategory;
    private boolean mConfigLoading;
    private ActivityResultLauncher<Intent> smbDiscoverLauncher;
    private TtsSpeaker mTts;
    private static final long EXIT_DOUBLE_BACK_INTERVAL = 2000;
    private long mLastBackPressedTime = 0;

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 主页键回到桌面：可选清理后台应用，避免残留应用拖慢桌面
        if (isHomeIntent(intent) && Setting.isHomeCleanBackground()) killBackgroundApps();
        checkAction(intent);
    }

    private boolean isHomeIntent(Intent intent) {
        return intent != null && Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_App);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        SpiderDebug.log("startup", "home initView start cost=%sms", System.currentTimeMillis() - App.time());
        registerLaunchers();
        mResult = Result.empty();
        mHomeResult = Result.empty();
        mClock = Clock.create(mBinding.clock).format("MM-dd EEE HH:mm:ss");
        setRecyclerView();
        setViewModel();
        setAdapter();
        // 首屏先渲染本地数据（功能/历史/收藏 + 推荐加载行），配置加载完成后 showContent() 再刷新推荐
        showLocalContent();
        runAfterFirstFrame(this::initAfterFirstFrame);
        if (Setting.isTtsFocus()) mTts = TtsSpeaker.create(this);
        SpiderDebug.log("startup", "home initView end cost=%sms", System.currentTimeMillis() - App.time());
    }

    /** 配置加载前先渲染本地数据，避免慢网络下首屏只有全屏加载态 */
    private void showLocalContent() {
        setTitle();
        setLogo();
        setFunc();
        getHistory();
        getKeepRow();
        showRecommendLoading();
        setFocus();
    }

    private void showRecommendLoading() {
        clearRecommendRows();
        mAdapter.add("progress");
    }

    private void initAfterFirstFrame() {
        SpiderDebug.log("startup", "home first frame cost=%sms", System.currentTimeMillis() - App.time());
        App.post(this::initConfig, 80);
        App.post(() -> PermissionUtil.requestFile(this, allGranted -> PermissionUtil.requestNotify(this)), 1800);
        // DLNA 接收默认关闭，需要投屏时在设置里打开
        App.post(() -> {
            if (Setting.isDlnaEnabled()) DLNARendererService.start(this);
        }, 2500);
    }

    private void registerLaunchers() {
        // 发现向导添加成功后直接打开该服务器的文件浏览，用户无需再点一次
        smbDiscoverLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            String serverId = result.getData().getStringExtra(SmbDiscoverActivity.EXTRA_SERVER_ID);
            if (serverId == null) return;
            SmbServer server = AppDatabase.get().getSmbServerDao().find(serverId);
            if (server != null) startSmbBrowser(server);
        });
    }

    /** 局域网入口：没有已保存服务器时直接进自动发现向导；有则弹出封面卡片选择（长按可换封面/删除服务器） */
    private void onSmbEntry() {
        if (AppDatabase.get().getSmbServerDao().findAll().isEmpty()) {
            smbDiscoverLauncher.launch(new Intent(this, SmbDiscoverActivity.class));
            return;
        }
        SmbServerDialog.create().callback(this).show(this);
    }

    @Override
    public void onServerClick(SmbServer server) {
        startSmbBrowser(server);
    }

    @Override
    public void onAddServer() {
        smbDiscoverLauncher.launch(new Intent(this, SmbDiscoverActivity.class));
    }

    private void startSmbBrowser(SmbServer server) {
        startActivity(new Intent(this, SmbBrowserActivity.class).putExtra(SmbBrowserActivity.EXTRA_SERVER_ID, server.getId()).putExtra(SmbBrowserActivity.EXTRA_PLAY_MODE, true));
    }

    private void runAfterFirstFrame(Runnable runnable) {
        View root = mBinding.getRoot();
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(this);
                root.post(runnable);
                return true;
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.title.setListener(this);
        mBinding.toolbar.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            syncNativeContentInset();
            syncWebOverlayLayout();
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                updateToolbarVisibility(isTopRow(position));
                if (mPresenter.isDelete()) setHistoryDelete(false);
            }
        });
        mBinding.typeRecycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (child != null && parent.hasFocus()) updateToolbarVisibility(true);
            }
        });
    }

    private void updateToolbarVisibility(boolean visible) {
        mBinding.toolbar.setVisibility(visible && webToolbarVisible ? View.VISIBLE : View.GONE);
        syncNativeContentInset();
        syncWebOverlayLayout();
    }

    private void syncNativeContentInset() {
        int top = isToolbarVisible() ? toolbarHeight() : 0;
        if (mBinding.nativeContent.getPaddingTop() == top) return;
        // 补间过渡，避免工具栏收起时内容瞬间上跳一个工具栏高度
        if (mInsetAnimator != null) mInsetAnimator.cancel();
        mInsetAnimator = ValueAnimator.ofInt(mBinding.nativeContent.getPaddingTop(), top);
        mInsetAnimator.setDuration(150);
        mInsetAnimator.addUpdateListener(animation -> {
            int padding = (int) animation.getAnimatedValue();
            mBinding.nativeContent.setPadding(mBinding.nativeContent.getPaddingLeft(), padding, mBinding.nativeContent.getPaddingRight(), mBinding.nativeContent.getPaddingBottom());
        });
        mInsetAnimator.start();
    }

    private void syncWebOverlayLayout() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mBinding.webOverlay.getLayoutParams();
        int top = constrainWebBelowToolbar() ? toolbarHeight() : 0;
        if (params.topMargin == top) return;
        params.topMargin = top;
        mBinding.webOverlay.setLayoutParams(params);
    }

    private boolean constrainWebBelowToolbar() {
        return (TV_NORMAL.equals(webChromeMode) || TV_OVERLAY.equals(webChromeMode)) && isToolbarVisible();
    }

    private boolean isToolbarVisible() {
        return mBinding.toolbar.getVisibility() == View.VISIBLE;
    }

    private int toolbarHeight() {
        int height = mBinding.toolbar.getHeight();
        if (height <= 0) height = mBinding.toolbar.getMeasuredHeight();
        return height > 0 ? height : ResUtil.dp2px(80);
    }

    private boolean isTopRow(int position) {
        int history = mAdapter.indexOf(R.string.home_history);
        return history == -1 || position < history;
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(HomeEmptyPresenter.Marker.class, new HomeEmptyPresenter());
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), FuncPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), HistoryPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), KeepPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.typeRecycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.typeRecycler.setRowHeight(android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.typeRecycler.setAdapter(mTypeAdapter = new TypeAdapter(this));
    }

    private void setWebView() {
        SpiderDebug.log("startup", "webview create start cost=%sms", System.currentTimeMillis() - App.time());
        mWeb = new HomeWebController(this, getHomeWeb(), this);
        mWeb.setViewport(tvViewport(webChromeMode));
        SpiderDebug.log("startup", "webview create end cost=%sms", System.currentTimeMillis() - App.time());
    }

    private void ensureWebView() {
        if (mWeb == null) setWebView();
    }

    private WebView getHomeWeb() {
        if (mHomeWeb != null) return mHomeWeb;
        mHomeWeb = new WebView(this);
        mHomeWeb.setFocusable(true);
        mHomeWeb.setFocusableInTouchMode(true);
        mHomeWeb.setVisibility(View.GONE);
        mBinding.webOverlay.addView(mHomeWeb, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return mHomeWeb;
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            boolean categoryResult = isHomeCategoryResult(result);
            mAdapter.remove("progress");
            if (!categoryResult) {
                Cache.clear().put(result);
                setTypes(mHomeResult = result);
            }
            mResult = result;
            addVideo(result);
        });
    }

    private boolean isHomeCategoryResult(Result result) {
        return loadingHomeCategory && result.getTypes().isEmpty();
    }

    private void setAdapter() {
        // 分区标题不再常驻：历史/收藏区由 HomeRows 按"标题+行"原子增删，空数据时不残留孤儿标题
        mAdapter.add(new ListRow(mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this))));
        mAdapter.add(R.string.home_recommend);
        mRows = new HomeRows(mAdapter, R.string.home_history, R.string.home_keep_row, R.string.home_recommend);
        mHistoryAdapter = newHistoryAdapter();
        initKeepAdapter();
    }

    /** 历史行适配器：行尾追加"查看全部"尾卡（Marker 路由），滚动策略与数据 Presenter 解耦 */
    private ArrayObjectAdapter newHistoryAdapter() {
        HistoryPresenter history = mPresenter = new HistoryPresenter(this);
        KeepMorePresenter more = new KeepMorePresenter(() -> HistoryActivity.start(this));
        return new ArrayObjectAdapter(new PresenterSelector() {
            @NonNull
            @Override
            public Presenter getPresenter(@NonNull Object item) {
                return item instanceof KeepMorePresenter.Marker ? more : history;
            }

            @NonNull
            @Override
            public Presenter[] getPresenters() {
                return new Presenter[]{history, more};
            }
        });
    }

    private void initKeepAdapter() {
        KeepPresenter keep = new KeepPresenter(this);
        KeepMorePresenter more = new KeepMorePresenter(this);
        // 主页选择器靠行内 adapter.getPresenter(ListRow) 的返回类型路由行样式，
        // 因此这里不能按 item 类名精确匹配，未知类型需默认返回卡片 Presenter
        mKeepAdapter = new ArrayObjectAdapter(new PresenterSelector() {
            @NonNull
            @Override
            public Presenter getPresenter(@NonNull Object item) {
                return item instanceof KeepMorePresenter.Marker ? more : keep;
            }

            @NonNull
            @Override
            public Presenter[] getPresenters() {
                return new Presenter[]{keep, more};
            }
        });
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void initConfig() {
        if (CrashRestartMode.consume()) {
            SpiderDebug.log("startup", "skip config load once after crash restart");
            showContent();
            return;
        }
        if (mConfigLoading) return;
        mConfigLoading = true;
        SpiderDebug.log("startup", "config load start cost=%sms", System.currentTimeMillis() - App.time());
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                SpiderDebug.log("startup", "config load success cost=%sms", System.currentTimeMillis() - App.time());
                showContent();
            }

            @Override
            public void error(String msg) {
                SpiderDebug.log("startup", "config load error cost=%sms msg=%s", System.currentTimeMillis() - App.time(), msg);
                // 复位加载标志：失败后允许再次触发 initConfig（重试对话框走独立加载路径，不依赖该标志）
                mConfigLoading = false;
                Notify.retry(getActivity(), msg, () -> VodConfig.get().init().load(getCallback()));
                showContent();
            }
        };
    }

    private void showContent() {
        SpiderDebug.log("startup", "home showContent start cost=%sms", System.currentTimeMillis() - App.time());
        mBinding.progressLayout.showContent();
        checkAction(getIntent());
        setTitle();
        setLogo();
        setFunc();
        getHistory();
        getKeepRow();
        getVideo();
        setFocus();
        App.post(this::prewarmWebView, 1500);
        SpiderDebug.log("startup", "home showContent end cost=%sms", System.currentTimeMillis() - App.time());
    }

    private void prewarmWebView() {
        if (isFinishing() || mWeb != null) return;
        boolean hasWebHome = VodConfig.get().getSites().stream().anyMatch(Site::hasHomePage);
        if (!hasWebHome) return;
        SpiderDebug.log("startup", "webview prewarm start cost=%sms", System.currentTimeMillis() - App.time());
        ensureWebView();
        SpiderDebug.log("startup", "webview prewarm end cost=%sms", System.currentTimeMillis() - App.time());
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    private void setFocus() {
        mBinding.title.setSelected(true);
        mBinding.title.setFocusable(true);
        if (!mBinding.title.hasFocus()) mBinding.recycler.requestFocus();
    }

    private void getVideo() {
        getVideo(false);
    }

    private void getVideo(boolean forceNative) {
        if (!forceNative && getHome().hasHomePage()) {
            ensureWebView();
        }
        if (!forceNative && mWeb != null && mWeb.load(getHome())) {
            mBinding.typeRecycler.setVisibility(View.GONE);
            mBinding.recycler.setVisibility(View.GONE);
            mBinding.progressLayout.showContent();
            showWebOverlay();
            return;
        }
        if (mWeb != null) mWeb.hide();
        hideWebOverlay();
        applyTvChrome(TV_NORMAL);
        mBinding.recycler.setVisibility(View.VISIBLE);
        mResult = Result.empty();
        mHomeResult = Result.empty();
        loadingHomeCategory = false;
        clearRecommendRows();
        mAdapter.add("progress");
        mViewModel.homeContent();
    }

    private void showWebOverlay() {
        mBinding.webOverlay.setVisibility(View.VISIBLE);
        syncWebOverlayLayout();
    }

    private void hideWebOverlay() {
        mBinding.webOverlay.setVisibility(View.GONE);
    }

    private void setTypes(Result result) {
        if (result.getTypes().isEmpty()) {
            mTypeAdapter.addAll(java.util.Collections.emptyList());
            mBinding.typeRecycler.setVisibility(View.GONE);
            return;
        }
        mTypeAdapter.addAll(result.getTypes());
        mBinding.typeRecycler.setVisibility(View.VISIBLE);
    }

    private void addVideo(Result result) {
        if (!loadingHomeCategory && result.getList().isEmpty() && !result.getTypes().isEmpty()) {
            Class type = result.getTypes().get(0);
            SpiderDebug.log("home", "home list empty, auto open first category key=%s tid=%s", getHome().getKey(), type.getTypeId());
            loadingHomeCategory = true;
            mAdapter.remove(HomeEmptyPresenter.Marker.INSTANCE);
            mAdapter.add("progress");
            mViewModel.categoryContent(getHome().getKey(), type.getTypeId(), "1", true, new java.util.HashMap<>());
            return;
        }
        loadingHomeCategory = false;
        Style style = result.getStyle(getHome().getStyle());
        if (style.isList()) mAdapter.addAll(mAdapter.size(), result.getList());
        else addGrid(result.getList(), style);
        if (result.getList().isEmpty() && mAdapter.indexOf(HomeEmptyPresenter.Marker.INSTANCE) == -1) mAdapter.add(HomeEmptyPresenter.Marker.INSTANCE);
    }

    private void clearRecommendRows() {
        mAdapter.remove("progress");
        int index = getRecommendIndex();
        if (mAdapter.size() > index) mAdapter.removeItems(index, mAdapter.size() - index);
    }

    private void addGrid(List<Vod> items, Style style) {
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(items, Product.getColumn(style))) {
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
            adapter.addAll(0, part);
            rows.add(new ListRow(adapter));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private void setFunc() {
        List<Func> items = new ArrayList<>();
        if (LiveConfig.hasUrl()) items.add(Func.create(R.string.home_live));
        items.add(Func.create(R.string.home_search));
        items.add(Func.create(R.string.home_keep));
        items.add(Func.create(R.string.home_push));
        items.add(Func.create(R.string.home_setting));
        items.add(Func.create(R.string.home_smb));
        items.add(Func.create(R.string.home_app));
        items.add(Func.create(R.string.home_file));
        mFuncAdapter.setItems(items, new BaseDiffCallback<Func>());
    }

    private void getHistory() {
        getHistory(false);
    }

    private int historyEpoch;

    private void getHistory(boolean renew) {
        // X16 聚合读取（跨配置全表扫描）移入后台，避免 HISTORY 事件风暴下主线程 DB 卡顿
        final int epoch = ++historyEpoch;
        Task.submit(() -> {
            boolean aggregated = Setting.isHistoryAggregation();
            List<History> items = aggregated ? History.getAll() : History.get();
            Map<Integer, String> configNames = aggregated ? History.configNameMap() : null;
            App.post(() -> {
                if (epoch != historyEpoch || isFinishing() || isDestroyed()) return;
                if (items.isEmpty()) {
                    mRows.remove(R.string.home_history);
                    return;
                }
                if (renew) {
                    mRows.remove(R.string.home_history);
                    mHistoryAdapter = newHistoryAdapter();
                }
                if (configNames != null) mPresenter.setConfigNames(configNames);
                mHistoryAdapter.clear();
                mHistoryAdapter.addAll(0, items.subList(0, Math.min(items.size(), ROW_LIMIT)));
                mHistoryAdapter.add(KeepMorePresenter.Marker.INSTANCE);
                mRows.ensure(R.string.home_history, new ListRow(mHistoryAdapter));
            });
        });
    }

    private void getKeepRow() {
        getKeepRow(false);
    }

    private void getKeepRow(boolean renew) {
        List<Keep> items = Keep.getVod();
        if (items.isEmpty()) {
            mRows.remove(R.string.home_keep_row);
            return;
        }
        if (renew) {
            mRows.remove(R.string.home_keep_row);
            initKeepAdapter();
        }
        mKeepAdapter.clear();
        mKeepAdapter.addAll(0, items.subList(0, Math.min(items.size(), ROW_LIMIT)));
        mKeepAdapter.add(KeepMorePresenter.Marker.INSTANCE);
        mRows.ensure(R.string.home_keep_row, new ListRow(mKeepAdapter));
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        mRows.remove(R.string.home_history);
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
        Task.submit(() -> {
            if (Setting.isHistoryAggregation()) History.deleteAllAndSync();
            else History.deleteAndSync(VodConfig.getCid());
            App.post(this::getHistory);
        });
    }

    private int getRecommendIndex() {
        return mAdapter.indexOf(R.string.home_recommend) + 1;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setLogo();
                break;
            case COMMON:
                setFunc();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                setTitle();
                SpiderDebug.log("site-dialog", "home refresh start key=%s homePage=%s", getHome().getKey(), getHome().hasHomePage());
                if (mWeb != null && mWeb.isVisible()) {
                    if (!mWeb.load(getHome(), true)) getVideo(true);
                } else {
                    getVideo();
                }
                SpiderDebug.log("site-dialog", "home refresh end key=%s", getHome().getKey());
                break;
            case HISTORY:
                getHistory();
                break;
            case KEEP:
                getKeepRow();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                getKeepRow(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, event.history().cid(VodConfig.getCid()).save());
        } else {
            VodConfig.load(event.config(), getCallback(event));
        }
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    private void speakMine(CharSequence text) {
        if (mTts != null) mTts.speak(text);
    }

    @Override
    public void onItemFocus(History item) {
        speakMine(item.getVodName());
    }

    @Override
    public void onItemFocus(Func item) {
        speakMine(item.getText());
    }

    @Override
    public void onItemFocus(Vod item) {
        speakMine(item.getName());
    }

    @Override
    public void onItemClick(Func item) {
        if (item.getResId() == R.string.home_live) LiveActivity.start(this);
        else if (item.getResId() == R.string.home_keep) KeepActivity.start(this);
        else if (item.getResId() == R.string.home_push) PushActivity.start(this);
        else if (item.getResId() == R.string.home_search) SearchActivity.start(this);
        else if (item.getResId() == R.string.home_setting) SettingActivity.start(this);
        else if (item.getResId() == R.string.home_smb) onSmbEntry();
        else if (item.getResId() == R.string.home_app) AppListDialog.show(this);
        else if (item.getResId() == R.string.home_file) startActivity(new Intent(this, FileActivity.class).putExtra("play_mode", true));
    }

    @Override
    public boolean onLongClick(Func item) {
        if (item.getResId() != R.string.home_search) return false;
        SearchActivity.start(this, "", getHome().getKey());
        return true;
    }

    @Override
    public void onItemClick(Class item) {
        Result result = mHomeResult == null || mHomeResult.getTypes().isEmpty() ? mResult : mHomeResult;
        VodActivity.start(this, getHome().getKey(), result, mTypeAdapter.indexOf(item));
    }

    @Override
    public void onRefresh(Class item) {
        onItemClick(item);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) mViewModel.action(getHome().getKey(), item.getAction());
        else if (getHome().isIndex()) CollectActivity.start(this, item.getName());
        else VideoActivity.start(this, getHome().getKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction()) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        HistoryOpener.open(this, item);
    }

    @Override
    public void onItemDelete(History item) {
        mHistoryAdapter.remove(item.deleteAndSync());
        // 行内固定有一个"查看全部"尾卡，只剩尾卡即视为空区
        if (mHistoryAdapter.size() > 1) return;
        mRows.remove(R.string.home_history);
        mPresenter.setDelete(false);
    }

    @Override
    public void onItemClick(Keep item) {
        Config config = Config.find(item.getCid());
        if (config == null) CollectActivity.start(this, item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadKeepConfig(config, item);
        else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onKeepLongClick() {
        KeepActivity.start(this);
    }

    @Override
    public void onMoreClick() {
        KeepActivity.start(this);
    }

    private void loadKeepConfig(Config config, Keep item) {
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                VideoActivity.start(getActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        });
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) confirmClearHistory();
        else {
            setHistoryDelete(true);
            // 删除模式的退出/清空手势不可见，进入时提示一次
            Notify.show(R.string.history_delete_hint);
        }
        return true;
    }

    private void confirmClearHistory() {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.home_history).setMessage(R.string.dialog_clear_history).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> clearHistory()).show();
    }

    @Override
    public void showDialog() {
        long start = System.currentTimeMillis();
        SpiderDebug.log("site-dialog", "open requested cost=%sms", System.currentTimeMillis() - App.time());
        SiteDialog.create().show(this);
        SpiderDebug.log("site-dialog", "show returned delay=%sms", System.currentTimeMillis() - start);
    }

    @Override
    public void onRefresh() {
        if (mWeb != null && mWeb.isVisible()) mWeb.reload();
        else getVideo();
    }

    @Override
    public void reloadConfig() {
        VodConfig.get().clear().config(getConfig()).load(new Callback() {
            @Override
            public void start() {
                mBinding.progressLayout.showProgress();
            }

            @Override
            public void success() {
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
            }
        });
    }

    @Override
    public void setSite(Site item) {
        SpiderDebug.log("site-dialog", "set site key=%s name=%s homePage=%s", item.getKey(), item.getName(), item.hasHomePage());
        VodConfig.get().setHome(item);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) {
            showDialog();
            return true;
        }
        if (mWeb != null && mWeb.isVisible()) {
            if (KeyUtil.isBackKey(event)) {
                if (KeyUtil.isActionUp(event)) onBackInvoked();
                return true;
            }
            if (mBinding.toolbar.hasFocus()) {
                if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event)) return requestWebFocus();
                return super.dispatchKeyEvent(event);
            }
            if (KeyUtil.isUpKey(event) && isToolbarVisible()) return super.dispatchKeyEvent(event);
            if (mWeb.dispatchKeyEvent(event)) return true;
            return super.dispatchKeyEvent(event);
        }
        if (KeyUtil.isActionDown(event) & KeyUtil.isUpKey(event) && mBinding.typeRecycler.hasFocus()) return requestTitleFocus();
        if (KeyUtil.isActionDown(event) & KeyUtil.isDownKey(event) && mBinding.typeRecycler.hasFocus()) return requestContentFocus();
        if (KeyUtil.isActionDown(event) & KeyUtil.isUpKey(event) && mBinding.recycler.hasFocus() && mBinding.typeRecycler.getVisibility() == View.VISIBLE) {
            if (isToolbarVisible()) return requestTitleFocus();
            updateToolbarVisibility(true);
        }
        if (KeyUtil.isActionDown(event) & KeyUtil.isDownKey(event) && getCurrentFocus() == mBinding.title) return requestHomeFocus();
        return super.dispatchKeyEvent(event);
    }

    private boolean requestTitleFocus() {
        updateToolbarVisibility(true);
        mBinding.title.setFocusable(true);
        return mBinding.title.requestFocus();
    }

    private boolean requestHomeFocus() {
        if (mBinding.typeRecycler.getVisibility() == View.VISIBLE) return mBinding.typeRecycler.requestFocus();
        return requestContentFocus();
    }

    private boolean requestWebFocus() {
        return mWeb != null && mWeb.isVisible() && mWeb.requestFocus("toolbar-down");
    }

    private boolean requestContentFocus() {
        if (mBinding.recycler.getVisibility() != View.VISIBLE || mBinding.recycler.getChildCount() == 0) return false;
        View child = mBinding.recycler.getFocusedChild();
        if (child == null) child = mBinding.recycler.getChildAt(0);
        return child != null && child.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
        if (mWeb != null) mWeb.onResume();
        // 长驻进程回前台时补齐点播订阅的超龄静默刷新（12h 内/加载进行中自动跳过）
        VodConfig.get().refreshIfStale();
    }

    /**
     * 清理后台应用，避免看过的直播/B站残留在后台拖慢桌面。
     * 对可启动的第三方应用逐个请求系统回收其后台进程；系统应用与前台应用不受影响。
     * 不能按进程枚举（getRunningAppProcesses 自 Android 5.1 起对第三方应用只返回自己）。
     */
    private void killBackgroundApps() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return;
        String self = getPackageName();
        for (ResolveInfo info : AppListUtil.query(this)) {
            if (info.activityInfo == null || self.equals(info.activityInfo.packageName)) continue;
            manager.killBackgroundProcesses(info.activityInfo.packageName);
        }
    }

    @Override
    protected void onPause() {
        if (mWeb != null) mWeb.onPause();
        super.onPause();
        mClock.stop();
        if (mTts != null) mTts.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (mWeb != null && mWeb.isVisible() && mWeb.handleBack()) {
            return;
        } else if (mWeb != null && mWeb.isVisible() && consumeTvFullscreenBack()) {
            return;
        } else if (mWeb != null && mWeb.isVisible()) {
            exitHome();
            return;
        } else if (mBinding.progressLayout.isProgress()) {
            showContent();
        } else if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else if (mBinding.recycler.getSelectedPosition() != 0) {
            mBinding.recycler.scrollToPosition(0);
        } else {
            exitHome();
        }
    }

    private boolean consumeTvFullscreenBack() {
        if (!TV_FULL.equals(webChromeMode) && !TV_TOOLBAR_HIDDEN.equals(webChromeMode)) return false;
        applyTvChrome(TV_NORMAL);
        requestTitleFocus();
        return true;
    }

    private void exitHome() {
        if (System.currentTimeMillis() - mLastBackPressedTime <= EXIT_DOUBLE_BACK_INTERVAL) {
            mLastBackPressedTime = 0;
            confirmExitHome();
        } else {
            mLastBackPressedTime = System.currentTimeMillis();
            Notify.show(getString(R.string.exit_press_again));
        }
    }

    private void confirmExitHome() {
        if (PlaybackService.isRunning()) Util.moveToBackground(this);
        else super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        if (mWeb != null) mWeb.destroy();
        if (mTts != null) mTts.release();
        DLNARendererService.stop(this);
        LiveConfig.get().clear();
        if (isFinishing()) VodConfig.get().clear();
        AppDatabase.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }

    @Override
    public void onWebLoading() {
        showWebOverlay();
        mBinding.progressLayout.showProgress();
    }

    @Override
    public void onWebReady() {
        showWebOverlay();
        mBinding.progressLayout.showContent();
        mBinding.typeRecycler.setVisibility(View.GONE);
        mBinding.recycler.setVisibility(View.GONE);
    }

    @Override
    public void onWebError() {
        applyTvChrome(TV_NORMAL);
        if (mWeb != null) mWeb.hide();
        hideWebOverlay();
        mBinding.recycler.setVisibility(View.VISIBLE);
        getVideo(true);
    }

    @Override
    public void setToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            applyTvChrome(TV_NORMAL);
            return;
        }
        applyTvChrome(visible ? webDefaultChromeMode : TV_TOOLBAR_HIDDEN);
    }

    @Override
    public void applyDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            webDefaultChromeMode = TV_NORMAL;
            applyTvChrome(TV_NORMAL);
            return;
        }
        webDefaultChromeMode = tvDefaultMode(site == null ? "" : site.getChromeMode());
        applyTvChrome(webDefaultChromeMode);
    }

    @Override
    public void setChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            applyTvChrome(TV_NORMAL);
            return;
        }
        applyTvChrome(tvRuntimeMode(Json.safeString(payload, "mode")));
    }

    @Override
    public void restoreChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            applyTvChrome(TV_NORMAL);
            return;
        }
        applyTvChrome(webDefaultChromeMode);
    }

    @Override
    public WebHomeViewport getViewport() {
        return tvViewport(webChromeMode);
    }

    @Override
    public void openVod() {
        applyTvChrome(TV_NORMAL);
        if (mWeb != null) mWeb.hide();
        hideWebOverlay();
        getVideo(true);
    }

    @Override
    public void openSetting() {
        SettingActivity.start(this);
    }

    private void applyTvChrome(String mode) {
        webChromeMode = mode;
        webToolbarVisible = TV_NORMAL.equals(mode) || TV_OVERLAY.equals(mode);
        updateToolbarVisibility(webToolbarVisible);
        syncWebOverlayLayout();
        if (mWeb != null) mWeb.setViewport(tvViewport(mode));
    }

    private String tvDefaultMode(String mode) {
        return tvMode(mode, TV_FULL);
    }

    private String tvRuntimeMode(String mode) {
        return tvMode(mode, webChromeMode);
    }

    private String tvMode(String mode, String fallback) {
        String value = TextUtils.isEmpty(mode) ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (TV_NORMAL.equals(value) || "normal".equals(value)) return TV_NORMAL;
        if (TV_TOOLBAR_HIDDEN.equals(value)) return TV_TOOLBAR_HIDDEN;
        if (TV_OVERLAY.equals(value)) return TV_OVERLAY;
        if (TV_FULL.equals(value) || "edge".equals(value) || "immersive".equals(value)) return TV_FULL;
        return fallback;
    }

    private WebHomeViewport tvViewport(String mode) {
        return WebHomeViewport.fixed(ResUtil.dp2px(28), ResUtil.dp2px(48), ResUtil.dp2px(28), ResUtil.dp2px(48), mode);
    }

}
