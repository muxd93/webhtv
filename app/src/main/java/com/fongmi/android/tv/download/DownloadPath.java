package com.fongmi.android.tv.download;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.util.Locale;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;

import java.io.File;

public class DownloadPath {

    private static final String DIR = "WebHomeTV";
    private static final int NAME_MAX = 48;

    private DownloadPath() {
    }

    public static File root() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), DIR);
    }

    public static File vod(String vodName) {
        return new File(root(), sanitize(vodName));
    }

    /**
     * 剧集目录名：线路_剧集_短hash。
     * 短 hash 取内容键前 6 位——同一影片同一线路下存在同名但不同 URL 的剧集时
     * （聚合源常见），纯名称会撞目录导致删除一个必删另一个。
     */
    public static File episode(File vodDir, String id, String flag, String episodeName) {
        String name = sanitize(flag) + "_" + sanitize(episodeName) + "_" + (id == null || id.length() < 6 ? "000000" : id.substring(0, 6));
        return new File(vodDir, name);
    }

    public static File playlist(File dir) {
        return new File(dir, "playlist.m3u8");
    }

    public static File manifest(File dir) {
        return new File(dir, "manifest.mpd");
    }

    public static File video(File dir, String ext) {
        return new File(dir, "video." + ext);
    }

    public static File info(File dir) {
        return new File(dir, "info.json");
    }

    public static String ext(String url) {
        String ext = urlExtension(url);
        return ext == null ? "mp4" : ext;
    }

    /** URL 路径中的合法扩展名；没有（或不像扩展名）时返回 null——调用方自行决定兜底 */
    private static String urlExtension(String url) {
        if (url == null) return null;
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return null;
        String ext = path.substring(dot + 1).toLowerCase(Locale.US);
        if (ext.length() > 4 || !ext.matches("[a-z0-9]+")) return null;
        return ext;
    }

    /**
     * 带 content-type 兜底的扩展名：URL 能取到扩展名就用 URL 的（真 .mp4 优先，不被 mime 覆盖）；
     * 取不到（如 /getVideo?id=123）时按探测到的 mime 映射，
     * 避免 video.mp4 名不副实让部分播放器（尤其依赖扩展名的）认不出容器。
     */
    public static String ext(String url, String mimeType) {
        String fromUrl = urlExtension(url);
        if (fromUrl != null) return fromUrl;
        if (mimeType == null) return "mp4";
        String mime = mimeType.toLowerCase(Locale.US);
        if (mime.contains("matroska")) return "mkv";
        if (mime.contains("webm")) return "webm";
        if (mime.contains("quicktime")) return "mov";
        if (mime.contains("x-msvideo")) return "avi";
        if (mime.contains("mp2t")) return "ts";
        if (mime.contains("x-flv")) return "flv";
        if (mime.contains("mpeg") && mime.contains("audio")) return "mp3";
        if (mime.contains("aac")) return "aac";
        if (mime.contains("ogg")) return "ogg";
        if (mime.contains("x-ms-wma")) return "wma";
        if (mime.contains("3gpp")) return "3gp";
        return "mp4";
    }

    public static String sanitize(String name) {
        String value = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").replaceAll("\\s+", " ").trim();
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.length() > NAME_MAX) value = value.substring(0, NAME_MAX).trim();
        return value.isEmpty() ? "unnamed" : value;
    }

    public static boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(App.get(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean mounted() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    public static boolean writable() {
        if (!mounted() || !hasPermission()) return false;
        File dir = root();
        return dir.isDirectory() || dir.mkdirs();
    }

    public static void ensureVodDir(File vodDir) {
        if (!vodDir.isDirectory()) vodDir.mkdirs();
    }

    public static String displayPath() {
        return root().getAbsolutePath();
    }

    public static void scanFile(Context context, File file) {
        try {
            android.media.MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
        } catch (Exception ignored) {
        }
    }

    /**
     * 提示并尝试打开「所有文件访问」授权页。部分电视盒子没有该设置页，
     * 此时给出手动路径与后果，避免用户误以为源失效。
     */
    public static void requestPermission(Context context) {
        Notify.show(permissionHint(openPermissionSettings(context)));
    }

    /** 有授权页就提示缺什么；没有授权页时补充手动路径与打不开的后果 */
    static int permissionHint(boolean settingsOpened) {
        return settingsOpened ? R.string.download_permission : R.string.download_permission_manual;
    }

    /** @return 是否成功拉起授权页 */
    public static boolean openPermissionSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return true;
            } catch (Exception ignored) {
                SpiderDebug.log("download", "permission settings unavailable");
                return false;
            }
        }
    }
}
