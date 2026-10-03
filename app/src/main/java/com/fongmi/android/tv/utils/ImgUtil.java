package com.fongmi.android.tv.utils;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.ElderCard;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Json;
import com.google.common.net.HttpHeaders;

import java.io.File;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jahirfiquitiva.libs.textdrawable.TextDrawable;

public class ImgUtil {

    /** 封面加载失败记录：带 TTL，网络恢复后允许重试，避免整个会话停留在文字占位图 */
    private static final long FAILED_TTL = 5 * 60 * 1000L;
    private static final int FAILED_MAX = 256;
    private static final Map<String, Long> failed = new ConcurrentHashMap<>();

    public static void logo(ImageView view) {
        try {
            Glide.with(view).load(UrlUtil.convert(VodConfig.get().getConfig().getLogo())).circleCrop().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL).error(R.drawable.ic_logo).into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String url, CustomTarget<Bitmap> target) {
        try {
            Glide.with(App.get()).asBitmap().load(getUrl(url)).override(ResUtil.dp2px(96), ResUtil.dp2px(96)).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, int width, int height, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(width, height).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void preload(Context context, String url) {
        if (TextUtils.isEmpty(url)) return;
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).preload();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String text, String url, ImageView view) {
        load(text, url, view, true);
    }

    public static void load(String text, String url, ImageView view, boolean vod) {
        load(text, url, view, vod, 0, 0);
    }

    public static void load(String text, String url, ImageView view, int width, int height) {
        load(text, url, view, true, width, height);
    }

    public static void load(String text, String url, ImageView view, boolean vod, int width, int height) {
        view.setScaleType(vod ? CENTER_CROP : FIT_CENTER);
        if (!vod) view.setVisibility(TextUtils.isEmpty(url) ? View.GONE : View.VISIBLE);
        if (TextUtils.isEmpty(url) || failedRecently(url)) view.setImageDrawable(getTextDrawable(text, vod));
        else try {
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(url)).listener(getListener(text, url, view, vod));
            if (width > 0 && height > 0) builder.override(width, height);
            if (vod) builder.centerCrop().into(view);
            else builder.fitCenter().into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static Object getUrl(String url) {
        String param = null;
        url = UrlUtil.convert(url);
        if (url.startsWith("data:")) return url;
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        if (url.contains("@Headers=")) addHeader(builder, param = url.split("@Headers=")[1].split("@")[0]);
        if (url.contains("@Cookie=")) builder.addHeader(HttpHeaders.COOKIE, param = url.split("@Cookie=")[1].split("@")[0]);
        if (url.contains("@Referer=")) builder.addHeader(HttpHeaders.REFERER, param = url.split("@Referer=")[1].split("@")[0]);
        if (url.contains("@User-Agent=")) builder.addHeader(HttpHeaders.USER_AGENT, param = url.split("@User-Agent=")[1].split("@")[0]);
        url = param == null ? url : url.split("@")[0];
        return TextUtils.isEmpty(url) ? null : new GlideUrl(url, builder.build());
    }

    private static void addHeader(LazyHeaders.Builder builder, String header) {
        Map<String, String> map = Json.toMap(Json.parse(header));
        for (Map.Entry<String, String> entry : map.entrySet()) builder.addHeader(UrlUtil.fixHeader(entry.getKey()), entry.getValue());
    }

    private static Drawable getTextDrawable(String text, boolean vod) {
        TextDrawable.Builder builder = new TextDrawable.Builder();
        text = TextUtils.isEmpty(text) ? "！" : text.substring(0, 1);
        if (vod) builder.buildRect(text, ColorGenerator.get400(text));
        return builder.buildRoundRect(text, ColorGenerator.get400(text), ResUtil.dp2px(4));
    }

    private static boolean failedRecently(String url) {
        Long time = failed.get(url);
        if (time == null) return false;
        if (System.currentTimeMillis() - time > FAILED_TTL) {
            failed.remove(url);
            return false;
        }
        return true;
    }

    private static void markFailed(String url) {
        if (failed.size() >= FAILED_MAX) purgeFailed();
        failed.put(url, System.currentTimeMillis());
    }

    private static void purgeFailed() {
        long now = System.currentTimeMillis();
        failed.entrySet().removeIf(entry -> now - entry.getValue() > FAILED_TTL);
    }

    private static RequestListener<Drawable> getListener(String text, String url, ImageView view, boolean vod) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                view.setImageDrawable(getTextDrawable(text, vod));
                markFailed(url);
                return true;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                return false;
            }
        };
    }

    public static boolean isImage(File file) {
        if (file == null || file.isDirectory()) return false;
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp");
    }

    // ===== 老人模式卡片封面 =====

    /** 内置精选图库：key -> 显示文字与配色（占位用文字色块，后续可换真实图） */
    private static final String[][] BUILTIN_COVERS = {
            {"tv", "电视剧", "#E53935"},
            {"movie", "电影", "#1E88E5"},
            {"opera", "戏曲", "#8E24AA"},
            {"dance", "广场舞", "#43A047"},
            {"kids", "少儿", "#FB8C00"},
            {"variety", "综艺", "#00ACC1"},
    };

    public static String[][] getBuiltinCovers() {
        return BUILTIN_COVERS;
    }

    public static Drawable builtinDrawable(String key) {
        for (String[] cover : BUILTIN_COVERS) {
            if (cover[0].equals(key)) {
                TextDrawable.Builder builder = new TextDrawable.Builder();
                return builder.buildRoundRect(cover[1], Color.parseColor(cover[2]), ResUtil.dp2px(8));
            }
        }
        TextDrawable.Builder builder = new TextDrawable.Builder();
        return builder.buildRoundRect("封面", ColorGenerator.get400("封"), ResUtil.dp2px(8));
    }

    /** 统一加载老人模式卡片封面到 ImageView：按 coverType 自动选择来源，零输入兜底 */
    public static void loadElderCover(com.fongmi.android.tv.bean.ElderCard card, ImageView view) {
        try {
            ElderCard.CoverType type = ElderCard.CoverType.from(card.getCoverType());
            switch (type) {
                case BUILTIN:
                    view.setScaleType(CENTER_CROP);
                    view.setImageDrawable(builtinDrawable(card.getCoverValue()));
                    return;
                case URL:
                    loadElderImage(card.getPic(), view);
                    return;
                case LOCAL:
                case FIRST_FRAME:
                    loadElderImage(card.getPic(), view);
                    return;
                case DEFAULT:
                default:
                    if (!TextUtils.isEmpty(card.getVodPic())) {
                        loadElderImage(card.getVodPic(), view);
                    } else if (!TextUtils.isEmpty(card.getPic())) {
                        loadElderImage(card.getPic(), view);
                    } else {
                        view.setScaleType(CENTER_CROP);
                        view.setImageDrawable(elderTextDrawable(card.getName()));
                    }
                    return;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static void loadElderImage(String src, ImageView view) {
        view.setScaleType(CENTER_CROP);
        if (TextUtils.isEmpty(src)) return;
        try {
            if (src.startsWith("/") || src.startsWith("file://")) {
                Glide.with(view).load(new File(src.startsWith("file://") ? src.substring(7) : src)).centerCrop().error(elderTextDrawable("封")).into(view);
            } else {
                Glide.with(view).load(getUrl(src)).centerCrop().listener(getElderListener(src, view)).into(view);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static RequestListener<Drawable> getElderListener(String src, ImageView view) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                view.setImageDrawable(elderTextDrawable("封"));
                return true;
            }
            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                return false;
            }
        };
    }

    private static Drawable elderTextDrawable(String text) {
        TextDrawable.Builder builder = new TextDrawable.Builder();
        text = TextUtils.isEmpty(text) ? "封" : text.substring(0, 1);
        return builder.buildRoundRect(text, ColorGenerator.get400(text), ResUtil.dp2px(8));
    }

    public static void thumb(File file, ImageView view) {
        if (!isImage(file)) return;
        int size = ResUtil.dp2px(64);
        try {
            Glide.with(view).load(file).override(size, size).placeholder(R.drawable.ic_file).error(R.drawable.ic_file).centerCrop().into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
