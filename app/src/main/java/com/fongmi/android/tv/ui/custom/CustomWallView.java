package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.palette.graphics.Palette;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ViewWallBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import pl.droidsonroids.gif.GifDrawable;

public class CustomWallView extends FrameLayout implements DefaultLifecycleObserver {

    private static final int DEFAULT_WALL_COLOR = Setting.getBuiltInWallColor(Setting.WALL_DREAM_PURPLE);
    private static final int GREEN_WALL_COLOR = 0xFF40C090;
    private static final int MAX_WALL_BITMAP_SIDE = 1920;
    private static final int TYPE_RES = 0;
    private static final int TYPE_GIF = 1;
    private static final int TYPE_VIDEO = 2;
    // 进程级位图复用：key 含文件版本与目标尺寸，避免不同屏宽复用错图；上限取堆的 1/8，不做无界缓存
    private static final LruCache<String, WallSnapshot> WALL_BITMAPS = new LruCache<>((int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, WallSnapshot value) {
            return value.bitmap.getByteCount();
        }
    };
    private static final AtomicInteger DECODE_COUNT = new AtomicInteger();
    // 最近一次成功解码：供占位阶段直接复用，避免重复解码
    private static final LruCache<String, WallSnapshot> WALL_LAST = new LruCache<>(4);
    private ViewWallBinding binding;
    private GifDrawable drawable;
    private PlayerView video;
    private ExoPlayer player;
    private final Runnable refreshRunnable = this::refresh;
    private boolean observerAdded;
    private boolean motionEnabled = true;
    // 每次请求自增：detach / 新请求都会让迟到的后台结果失效
    private int requestToken;

    public CustomWallView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomWallView setMotionEnabled(boolean motionEnabled) {
        this.motionEnabled = motionEnabled;
        return this;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) return;
        boolean loadedPlaceholder = false;
        if (binding == null) {
            binding = ViewWallBinding.inflate(LayoutInflater.from(getContext()), this, true);
            loadPlaceholder();
            loadedPlaceholder = true;
        }
        if (!observerAdded) {
            ((ComponentActivity) getContext()).getLifecycle().addObserver(this);
            observerAdded = true;
        }
        removeCallbacks(refreshRunnable);
        if (loadedPlaceholder && isStaticBuiltInWall()) theme();
        else post(refreshRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        requestToken++;
        removeCallbacks(refreshRunnable);
        super.onDetachedFromWindow();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.WALL) refresh();
    }

    private void refresh() {
        if (!isReady()) return;
        long start = System.currentTimeMillis();
        stop();
        load();
        theme();
        SpiderDebug.log("startup", "wall refresh cost=%sms", System.currentTimeMillis() - start);
    }

    private boolean isReady() {
        return binding != null && binding.image != null && isAttachedToWindow();
    }

    private void stop() {
        if (player != null && player.isPlaying()) {
            player.stop();
            player.clearMediaItems();
        }
        if (video != null) {
            video.setPlayer(null);
            video.setVisibility(GONE);
        }
        if (drawable != null) {
            drawable.stop();
            drawable.recycle();
            drawable = null;
        }
    }

    private void load() {
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        if (isBuiltInColor(wall, type)) loadColor(Setting.getBuiltInWallColor(wall));
        else if (isBuiltInDesign(wall, type)) loadDesign(wall);
        else if (isGreen(wall, type)) loadRes(R.drawable.wallpaper_1);
        else if (motionEnabled && type == TYPE_VIDEO) loadVideo(FileUtil.getWall(wall));
        else if (motionEnabled && type == TYPE_GIF) loadGif(FileUtil.getWall(wall));
        else loadImage();
    }

    private void theme() {
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        // 文件壁纸的取色依赖解码，统一由 requestWallSnapshot 的后台任务回填，主线程不再读文件
        if (type == TYPE_RES && Setting.isBuiltInWall(wall)) applyWallColor(Setting.getBuiltInWallColor(wall));
        else if (isGreen(wall, type)) applyWallColor(GREEN_WALL_COLOR);
    }

    private void applyWallColor(int newColor) {
        int oldColor = Setting.getWallColor();
        if (newColor == oldColor) return;
        Setting.putWallColor(newColor);
        if (Setting.getThemeColor() == 0) RefreshEvent.theme();
    }

    private void loadRes(int resId) {
        if (!isReady()) return;
        binding.image.setImageResource(resId);
    }

    private void loadColor(int color) {
        if (!isReady()) return;
        binding.image.setImageDrawable(new ColorDrawable(color));
    }

    private void loadDesign(int wall) {
        if (!isReady()) return;
        int resId = getDesignResId(wall);
        if (resId != 0) binding.image.setImageResource(resId);
        else loadColor(Setting.getBuiltInWallColor(wall));
    }

    private void loadImage() {
        if (!isReady()) return;
        requestWallSnapshot();
    }

    private void loadPlaceholder() {
        if (binding == null || binding.image == null) return;
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        Drawable cache = cachedWallDrawable();
        if (isBuiltInColor(wall, type)) binding.image.setImageDrawable(new ColorDrawable(Setting.getBuiltInWallColor(wall)));
        else if (isBuiltInDesign(wall, type)) loadDesign(wall);
        else if (isGreen(wall, type)) binding.image.setImageResource(R.drawable.wallpaper_1);
        else if (cache != null) binding.image.setImageDrawable(cache);
        else binding.image.setImageDrawable(new ColorDrawable(DEFAULT_WALL_COLOR));
    }

    private int getDesignResId(int wall) {
        return switch (wall) {
            case Setting.WALL_AURORA_GLASS -> R.drawable.wallpaper_design_10_aurora_glass;
            case Setting.WALL_SUNSET_PRISM -> R.drawable.wallpaper_design_11_sunset_prism;
            case Setting.WALL_MINT_GLACIER -> R.drawable.wallpaper_design_12_mint_glacier;
            case Setting.WALL_LIQUID_CHROME -> R.drawable.wallpaper_design_13_liquid_chrome;
            case Setting.WALL_NEON_BERRY -> R.drawable.wallpaper_design_14_neon_berry;
            case Setting.WALL_CHAMPAGNE_MIST -> R.drawable.wallpaper_design_15_champagne_mist;
            case Setting.WALL_GLASS_GRADIENT -> R.drawable.wallpaper_design_16_glass_gradient;
            case Setting.WALL_DEEP_SPACE_GLASS -> R.drawable.wallpaper_design_17_deep_space_glass;
            case Setting.WALL_POLAR_LIGHT_GLASS -> R.drawable.wallpaper_design_18_polar_light_glass;
            case Setting.WALL_NEON_CYBER -> R.drawable.wallpaper_design_19_neon_cyber;
            case Setting.WALL_WARM_MOON_GLASS -> R.drawable.wallpaper_design_20_warm_moon_glass;
            case Setting.WALL_CRYSTAL_SKY -> R.drawable.wallpaper_design_21_crystal_sky;
            case Setting.WALL_DREAM_PURPLE -> R.drawable.wallpaper_design_22_dream_purple;
            case Setting.WALL_SKY_MINT -> R.drawable.wallpaper_design_23_sky_mint;
            case Setting.WALL_FOREST_MIST -> R.drawable.wallpaper_design_24_forest_mist;
            case Setting.WALL_DAYLIGHT_MINIMAL -> R.drawable.wallpaper_design_25_daylight_minimal;
            case Setting.WALL_DEEP_SEA -> R.drawable.wallpaper_design_26_deep_sea;
            case Setting.WALL_VIOLET_SMOKE -> R.drawable.wallpaper_design_27_violet_smoke;
            case Setting.WALL_ROSE_VEIL -> R.drawable.wallpaper_design_28_rose_veil;
            case Setting.WALL_EMERALD_AURORA -> R.drawable.wallpaper_design_29_emerald_aurora;
            case Setting.WALL_BLUE_SILK -> R.drawable.wallpaper_design_30_blue_silk;
            case Setting.WALL_PEACH_DAWN -> R.drawable.wallpaper_design_31_peach_dawn;
            case Setting.WALL_GRAPHITE_SMOKE -> R.drawable.wallpaper_design_32_graphite_smoke;
            case Setting.WALL_PASTEL_PRISM -> R.drawable.wallpaper_design_33_pastel_prism;
            case Setting.WALL_MIDNIGHT_MOON -> R.drawable.wallpaper_design_34_midnight_moon;
            case Setting.WALL_CYAN_CRYSTAL -> R.drawable.wallpaper_design_35_cyan_crystal;
            case Setting.WALL_LAVENDER_CRYSTAL -> R.drawable.wallpaper_design_36_lavender_crystal;
            default -> 0;
        };
    }

    private void loadVideo(File file) {
        if (!isReady()) return;
        ensurePlayer();
        ensureVideoView();
        video.setPlayer(player);
        video.setVisibility(VISIBLE);
        requestWallSnapshot();
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.prepare();
    }

    private void loadGif(File file) {
        if (!isReady()) return;
        drawable = gif(file);
        if (drawable != null) binding.image.setImageDrawable(drawable);
        else loadImage();
    }

    /** 只在内存缓存里取，未命中返回 null：主线程不读文件、不解码 */
    private Drawable cachedWallDrawable() {
        File file = FileUtil.getWallCache();
        WallSnapshot snapshot = file.exists() ? WALL_LAST.get(bufferKey(file)) : null;
        return snapshot == null ? null : new BitmapDrawable(getResources(), snapshot.bitmap);
    }

    /** 后台解码+取色，一次读文件；结果回主线程，且只有当前请求（未 detach）才允许回填 */
    private void requestWallSnapshot() {
        if (!isReady()) return;
        File file = FileUtil.getWallCache();
        if (!file.exists()) {
            loadPlaceholder();
            return;
        }
        int width = targetWallWidth();
        int height = targetWallHeight();
        String key = wallKey(file, width, height);
        WallSnapshot cached = WALL_BITMAPS.get(key);
        if (cached != null) {
            applyWallSnapshot(cached);
            return;
        }
        int token = beginWallRequest();
        Task.execute(() -> {
            WallSnapshot snapshot = loadWallSnapshot(file, width, height);
            App.post(() -> applyWallSnapshot(token, snapshot));
        });
    }

    /** 命中进程缓存直接返回，未命中才读文件解码 */
    static WallSnapshot loadWallSnapshot(File file, int width, int height) {
        String key = wallKey(file, width, height);
        WallSnapshot cached = WALL_BITMAPS.get(key);
        return cached != null ? cached : decodeWallSnapshot(file, key, width, height);
    }

    static WallSnapshot decodeWallSnapshot(File file, String key, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) return null;
            DECODE_COUNT.incrementAndGet();
            WallSnapshot snapshot = new WallSnapshot(bitmap, swatchColor(bitmap));
            WALL_BITMAPS.put(key, snapshot);
            WALL_LAST.put(bufferKey(file), snapshot);
            return snapshot;
        } catch (OutOfMemoryError | RuntimeException e) {
            SpiderDebug.log("startup", "wall bitmap decode failed file=%s error=%s", file.getName(), e.getClass().getSimpleName());
            return null;
        }
    }

    static int sampleSize(int outWidth, int outHeight, int reqWidth, int reqHeight) {
        int sampleSize = 1;
        while (outWidth / sampleSize > reqWidth || outHeight / sampleSize > reqHeight) sampleSize *= 2;
        return sampleSize;
    }

    static String wallKey(File file, int width, int height) {
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified() + "|" + width + "x" + height;
    }

    private static String bufferKey(File file) {
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    }

    private int targetWallWidth() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return Math.max(1, Math.min(metrics.widthPixels, MAX_WALL_BITMAP_SIDE));
    }

    private int targetWallHeight() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return Math.max(1, Math.min(metrics.heightPixels, MAX_WALL_BITMAP_SIDE));
    }

    int beginWallRequest() {
        return ++requestToken;
    }

    boolean isCurrentWallRequest(int token) {
        return token == requestToken && isReady();
    }

    void applyWallSnapshot(int token, WallSnapshot snapshot) {
        if (snapshot == null || !isCurrentWallRequest(token)) return;
        applyWallSnapshot(snapshot);
    }

    private void applyWallSnapshot(WallSnapshot snapshot) {
        if (binding == null || binding.image == null) return;
        binding.image.setImageDrawable(new BitmapDrawable(getResources(), snapshot.bitmap));
        applyWallColor(snapshot.color);
    }

    static int decodeCount() {
        return DECODE_COUNT.get();
    }

    static void resetForTest() {
        DECODE_COUNT.set(0);
        WALL_BITMAPS.evictAll();
        WALL_LAST.evictAll();
    }

    static final class WallSnapshot {
        final Bitmap bitmap;
        final int color;

        WallSnapshot(Bitmap bitmap, int color) {
            this.bitmap = bitmap;
            this.color = color;
        }
    }

    private GifDrawable gif(File file) {
        try {
            return new GifDrawable(file);
        } catch (IOException e) {
            return null;
        }
    }

    private void ensurePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(getContext()).build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setPlayWhenReady(true);
        player.mute();
    }

    private void ensureVideoView() {
        if (video != null) return;
        video = (PlayerView) LayoutInflater.from(getContext()).inflate(R.layout.view_wall_video, this, false);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private boolean hasVideo() {
        return player != null && video != null && video.getVisibility() == VISIBLE && player.getMediaItemCount() > 0;
    }

    private static int swatchColor(Bitmap bitmap) {
        try {
            Palette palette = Palette.from(bitmap).maximumColorCount(8).generate();
            Palette.Swatch swatch = palette.getVibrantSwatch();
            if (swatch == null) swatch = palette.getDominantSwatch();
            return swatch != null ? swatch.getRgb() : DEFAULT_WALL_COLOR;
        } catch (RuntimeException e) {
            SpiderDebug.log("startup", "wall palette failed error=%s", e.getClass().getSimpleName());
            return DEFAULT_WALL_COLOR;
        }
    }

    private boolean isBuiltInColor(int wall, int type) {
        return type == TYPE_RES && Setting.isBuiltInColorWall(wall);
    }

    private boolean isBuiltInDesign(int wall, int type) {
        return type == TYPE_RES && Setting.isBuiltInDesignWall(wall);
    }

    private boolean isGreen(int wall, int type) {
        return type == TYPE_RES && wall == Setting.WALL_GREEN;
    }

    private boolean isStaticBuiltInWall() {
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        return isBuiltInColor(wall, type) || isBuiltInDesign(wall, type) || isGreen(wall, type);
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().register(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        if (drawable != null) drawable.start();
        if (!hasVideo()) return;
        video.setPlayer(player);
        player.play();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        if (drawable != null) drawable.pause();
        if (!hasVideo()) return;
        video.setPlayer(null);
        player.pause();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        removeCallbacks(refreshRunnable);
        EventBus.getDefault().unregister(this);
        if (drawable != null) drawable.recycle();
        if (video != null) removeView(video);
        if (player != null) player.release();
        observerAdded = false;
        drawable = null;
        binding = null;
        player = null;
        video = null;
    }
}
