package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;

public class CustomKeyDownLive extends GestureDetector.SimpleOnGestureListener {

    private static final int FLING_MIN_DISTANCE = 120;
    private static final int FLING_MIN_VELOCITY = 1200;

    private final GestureDetector detector;
    private final StringBuilder text;
    private final Listener listener;
    private long holdTime;

    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            listener.onFind(text.toString());
            text.setLength(0);
        }
    };

    public static CustomKeyDownLive create(Context context) {
        return new CustomKeyDownLive(context);
    }

    private CustomKeyDownLive(Context context) {
        this.text = new StringBuilder();
        this.listener = (Listener) context;
        this.detector = new GestureDetector(context, this);
    }

    public boolean onTouchEvent(MotionEvent e) {
        return detector.onTouchEvent(e);
    }

    public boolean hasEvent(KeyEvent event) {
        return KeyUtil.isEnterKey(event) || KeyUtil.isUpKey(event) || KeyUtil.isDownKey(event) || KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event) || KeyUtil.isDigitKey(event) || KeyUtil.isMenuKey(event) || event.isLongPress();
    }

    public void onKeyDown(KeyEvent event) {
        if (listener.dispatch(true)) check(event);
    }

    private void check(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isLeftKey(event)) {
            listener.onSeeking(subTime());
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) {
            listener.onSeeking(addTime());
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event)) {
            listener.onKeyUp();
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event)) {
            listener.onKeyDown();
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isLeftKey(event)) {
            listener.onKeyLeft(holdTime);
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isRightKey(event)) {
            listener.onKeyRight(holdTime);
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isDigitKey(event)) {
            onKeyDown(event.getKeyCode());
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isEnterKey(event)) {
            listener.onKeyCenter();
        } else if (KeyUtil.isMenuKey(event) || event.isLongPress() && KeyUtil.isEnterKey(event)) {
            listener.onMenu();
        }
    }

    private void onKeyDown(int keyCode) {
        if (text.length() >= 4) return;
        text.append(getNumber(keyCode));
        listener.onShow(text.toString());
        App.post(runnable, 2000);
    }

    @Override
    public boolean onDoubleTap(@NonNull MotionEvent e) {
        if (listener.dispatch(false)) listener.onDoubleTap();
        return true;
    }

    @Override
    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        if (listener.dispatch(false)) listener.onSingleTap(e.getRawX(), ResUtil.getScreenWidth(App.get()));
        return true;
    }

    /** 触屏换线:水平为主且过阈值的 fling,catchup(VOD 型流)由调用方跳过,滑动留给后续进度交互。 */
    @Override
    public boolean onFling(@NonNull MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
        if (!listener.dispatch(false)) return false;
        float dx = e2.getX() - e1.getX();
        float dy = e2.getY() - e1.getY();
        if (Math.abs(dx) < FLING_MIN_DISTANCE || Math.abs(dx) < Math.abs(dy) * 2) return false;
        if (Math.abs(velocityX) < FLING_MIN_VELOCITY) return false;
        if (dx > 0) listener.onFlingRight();
        else listener.onFlingLeft();
        return true;
    }

    private int getNumber(int keyCode) {
        return keyCode >= 144 ? keyCode - 144 : keyCode - 7;
    }

    private long addTime() {
        return holdTime = holdTime + Constant.INTERVAL_SEEK;
    }

    private long subTime() {
        return holdTime = holdTime - Constant.INTERVAL_SEEK;
    }

    public void reset() {
        holdTime = 0;
    }

    /** 取消挂起的数字选台定时器：页面销毁后触发会经 restore/fetchLive 触碰已释放的播放器。 */
    public void release() {
        App.removeCallbacks(runnable);
        text.setLength(0);
    }

    public interface Listener {

        boolean dispatch(boolean check);

        void onShow(String number);

        void onFind(String number);

        void onSeeking(long time);

        void onKeyUp();

        void onKeyDown();

        void onKeyLeft(long time);

        void onKeyRight(long time);

        void onKeyCenter();

        void onMenu();

        /** 单击分区:x<width/2 左半屏=频道列表,右半屏=控制条(对齐 mobile 语义)。 */
        void onSingleTap(float x, float width);

        void onDoubleTap();

        void onFlingLeft();

        void onFlingRight();
    }
}
