package com.fongmi.android.tv.player.lyrics;

import java.util.Collections;
import java.util.List;

/**
 * 歌词会话状态：当前歌词行、当前结果、三种签名（loading/active/empty）与请求序号。
 * 纯内存状态迁移，不持有 View / Handler / Player，可在 JVM 单测里直接驱动；
 * {@link LyricsController} 只负责 View 更新、仓库调用与监听通知。
 *
 * 迁移规则与原先散在 LyricsController 里的字段赋值逐条对应：
 * 切歌/换源重置行并推进序号（旧回调按序号丢弃），失败或空结果只记录签名，不触碰播放。
 */
public class LyricsSession {

    private List<LyricsLine> lines = Collections.emptyList();
    private LyricsResult result;
    private String loading;
    private String active;
    private String empty;
    private int sequence;

    /** 后台刷新开始：先清行，记录 loading 签名，序号前进；返回值用于丢弃陈旧回调 */
    public int begin(String signature) {
        loading = signature;
        active = null;
        lines = Collections.emptyList();
        return ++sequence;
    }

    /** 用户主动换源：额外清掉空态签名，允许同签名重新尝试 */
    public int beginReload(String signature) {
        loading = signature;
        active = null;
        empty = null;
        lines = Collections.emptyList();
        return ++sequence;
    }

    public int nextSequence() {
        return ++sequence;
    }

    public int getSequence() {
        return sequence;
    }

    public boolean isCurrent(int seq) {
        return seq == sequence;
    }

    /** 同签名已在途/已生效/已判空时跳过重复请求 */
    public boolean isKnown(String signature) {
        return signature.equals(active) || signature.equals(loading) || signature.equals(empty);
    }

    public void markLoading(String signature) {
        loading = signature;
    }

    public void settleLoading() {
        loading = null;
    }

    public void settleLoading(String signature) {
        if (signature.equals(loading)) loading = null;
    }

    /** 请求失败或结果为空：只记空态签名，行保持为空，播放不受影响 */
    public void markEmpty(String signature) {
        loading = null;
        empty = signature;
    }

    public void clearEmpty() {
        empty = null;
    }

    /** 后台刷新成功：清 loading，active 前移并写入行 */
    public void applyLoaded(String signature, LyricsResult result, List<LyricsLine> lines) {
        loading = null;
        active = signature;
        this.result = result;
        this.lines = lines;
    }

    /** 选中来源 / 内嵌歌词生效：清 loading 与空态，active 前移并写入行 */
    public void applyChoice(String signature, LyricsResult result, List<LyricsLine> lines) {
        loading = null;
        empty = null;
        active = signature;
        this.result = result;
        this.lines = lines;
    }

    /** 只替换行与结果，不改签名（用于内嵌歌词升级为逐字歌词） */
    public void applyLines(LyricsResult result, List<LyricsLine> lines) {
        this.result = result;
        this.lines = lines;
    }

    /** 内嵌歌词已生效：同 key 且已有行时只需按新位置刷新 */
    public boolean isInlineActive(String key) {
        return key.equals(active) && !lines.isEmpty();
    }

    /** 切歌/停止：推进序号让在途结果全部作废 */
    public void clear() {
        sequence++;
        loading = null;
        active = null;
        empty = null;
        result = null;
        lines = Collections.emptyList();
    }

    public void clearResult() {
        result = null;
    }

    public List<LyricsLine> lines() {
        return lines;
    }

    public LyricsResult result() {
        return result;
    }

    public String loading() {
        return loading;
    }

    public String active() {
        return active;
    }

    public String empty() {
        return empty;
    }
}
