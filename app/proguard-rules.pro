# TV
-keep class androidx.leanback.widget.** { *; }
-keep class com.fongmi.quickjs.method.** { *; }
-keep class com.fongmi.android.tv.bean.** { *; }

# MPV JNI bridge
-keep class is.xyz.mpv.MPVLib { *; }
-keep class is.xyz.mpv.MPVLib$* { *; }

# libplayer.so resolves this class and these methods by their literal JNI names.
-keep class com.fongmi.android.tv.player.iso.IsoSessionManager {
    public static long length(long);
    public static int readAt(long, long, java.nio.ByteBuffer, int);
    public static void close(long);
    public static void prepareTrackMetadata(long, int);
}

# MPV owns one process-wide native context. Keep its lifecycle code intact so
# release inlining does not amplify timing-sensitive create/destroy transitions.
-keep,allowobfuscation class androidx.media3.mpvplayer.MpvPlayer { *; }
-keep,allowobfuscation class androidx.media3.mpvplayer.MpvPlayer$* { *; }
-keep,allowobfuscation class com.fongmi.android.tv.player.engine.MpvPlayerEngine { *; }

# Gson
# 必须保留泛型签名(Signature)：否则 R8 会把 List<JsonElement> 的元素类型擦除成 Object，
# Gson 退化为 ObjectTypeAdapter，JSON 对象被反序列化成 LinkedTreeMap，后续强转 JsonElement 即崩溃。
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.google.gson.internal.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep class com.fongmi.android.tv.remote.** { *; }
-keep class com.fongmi.android.tv.gitcloud.** { *; }

# Gson 持久化模型：字段名就是 JSON 键。这些类只被 Gson 读写，R8 看不到反射访问，
# 会把字段改名（键失配 → 反序列化后全是 null）或把"只写不读"的字段整条删除（键消失）。
# 下面只按类保留，不做整包 keep。
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 自建音频歌单（Prefers: audio_playlists / audio_metadata）
-keep class com.fongmi.android.tv.player.lyrics.AudioPlaylistStore$Playlist { *; }
-keep class com.fongmi.android.tv.player.lyrics.AudioPlaylistStore$Entry { *; }
-keep class com.fongmi.android.tv.player.lyrics.AudioPlaylistStore$Metadata { *; }
# 歌词磁盘缓存（LyricsResult[] 与单条 LyricsResult）
-keep class com.fongmi.android.tv.player.lyrics.LyricsResult { *; }
# 登录态学习快照（Snapshot/Candidate 及路径树模型）
-keep class com.fongmi.android.tv.utils.LoginStateSync$* { *; }
# 播放 webhook / 远端同步配置（Prefers: playback_webhook_configs / playback_remote_sync_configs）
-keep class com.fongmi.android.tv.playback.WebhookConfig { *; }
-keep class com.fongmi.android.tv.playback.RemoteSyncConfig { *; }
# WebHome 扩展源（Prefers: webhome_extension_sources）
-keep class com.fongmi.android.tv.web.ext.WebHomeExtensionSourceStore$Entry { *; }
# 规则池（Prefers: rule_pool，字段名即 JSON 键，代理/header/DNS/规则全靠它）
-keep class com.fongmi.android.tv.api.config.RulePool$Data { *; }
# 自定义 CSP 注册表（TV/CustomCsp/registry.json）
-keep class com.fongmi.android.tv.setting.CustomCspSetting$Registry { *; }
-keep class com.fongmi.android.tv.setting.CustomCspSetting$Item { *; }
# 离线下载 sidecar（下载目录 *.json）
-keep class com.fongmi.android.tv.download.DownloadStore$Sidecar { *; }
# 远端/服务端进度的请求体
-keep class com.fongmi.android.tv.playback.PlaybackProgressInput { *; }
-keep class com.fongmi.android.tv.playback.PlaybackProgressDeleteInput { *; }
# catvod DoH 配置（RulePool 的 doh 数组元素）
-keep class com.github.catvod.bean.Doh { *; }
# 首页信息流缓存（Prefers: home_feed_v1，Map<String, Entry>，字段 time/json 即 JSON 键）
-keep class com.fongmi.android.tv.setting.HomeFeedCache$Entry { *; }
# 站点健康统计（Prefers: site_health，Map<String, Health>，字段名即 JSON 键）
-keep class com.fongmi.android.tv.setting.SiteHealthStore$Health { *; }

# SimpleXML
-keep interface org.simpleframework.xml.core.Label { public *; }
-keep class * implements org.simpleframework.xml.core.Label { public *; }
-keep interface org.simpleframework.xml.core.Parameter { public *; }
-keep class * implements org.simpleframework.xml.core.Parameter { public *; }
-keep interface org.simpleframework.xml.core.Extractor { public *; }
-keep class * implements org.simpleframework.xml.core.Extractor { public *; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Path <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Root <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Text <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Element <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Attribute <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.ElementList <fields>; }

# OkHttp
-dontwarn okhttp3.**
-keep class okio.** { *; }
-keep class okhttp3.** { *; }

# Kotlin
-keeppackagenames kotlin.**
-keep class kotlin.** { *; }

# JGit
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.ManagementFactory
-dontwarn org.eclipse.jgit.**
-keep class org.eclipse.jgit.** { *; }
-keeppackagenames org.slf4j.**
-keep class org.slf4j.** { *; }

# CatVod
-keep class com.github.catvod.Proxy { *; }
-keep class com.github.catvod.crawler.** { *; }
-keep class * extends com.github.catvod.crawler.Spider

# Jianpian
-keep class com.p2p.** { *; }

# JUPnP
-dontwarn org.jupnp.**
-keep class org.jupnp.** { *; }
-keep class javax.xml.** { *; }

# Nano
-keep class fi.iki.elonen.** { *; }

# NewPipeExtractor
-keep class javax.script.** { *; }
-keep class jdk.dynalink.** { *; }
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn com.google.re2j.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Sardine
-keep class com.thegrizzlylabs.sardineandroid.** { *; }

# TVBus
-keep class com.tvbus.engine.** { *; }

# XunLei
-keep class com.xunlei.downloadlib.** { *; }

# Zxing
-keep class com.google.zxing.** { *; }
