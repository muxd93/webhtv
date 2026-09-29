package com.fongmi.android.tv.utils;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;

import java.util.Locale;

/**
 * 焦点朗读（供不识字的老人）：焦点移动时延迟播报名称，快速移动时自动合并。
 * 受「焦点朗读」通用开关控制；设备没有中文语音引擎时自然不出声。
 */
public class TtsSpeaker {

    private static final long SPEAK_DELAY_MS = 350;

    private TextToSpeech tts;
    private boolean ready;
    private Runnable pending;

    public static TtsSpeaker create(Context context) {
        return new TtsSpeaker(context);
    }

    private TtsSpeaker(Context context) {
        // 回调可能在构造完成前触发，tts 不能声明为 final
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                ready = false;
                return;
            }
            int result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
            SpiderDebug.log("tts", "init status=%s lang=%s ready=%s", status, result, ready);
        });
    }

    public void speak(CharSequence text) {
        if (!Setting.isTtsFocus()) return;
        if (!ready || tts == null || TextUtils.isEmpty(text)) return;
        if (pending != null) App.removeCallbacks(pending);
        String value = text.toString();
        pending = () -> tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "focus");
        App.post(pending, SPEAK_DELAY_MS);
    }

    public void stop() {
        if (pending != null) App.removeCallbacks(pending);
        if (tts != null) tts.stop();
    }

    public void release() {
        stop();
        if (tts != null) {
            tts.shutdown();
        }
        ready = false;
    }
}
