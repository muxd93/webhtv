package com.fongmi.android.tv.ui.presenter;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.utils.ResUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

/**
 * 首页功能按钮图标的着色契约：颜色只在 colors.xml 的 func_icon_* 里定义、映射只在 Func 里，
 * 这里钉住"每个按钮都有独立颜色"与"呈现层确实把颜色涂到图标上"，避免改按钮时漏掉着色回落成白色。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class FuncIconTintTest {

    private static final int[] HOME_FUNCS = {R.string.home_live, R.string.home_search, R.string.home_keep, R.string.download_action, R.string.home_push, R.string.home_setting};

    @Test
    public void everyHomeFunctionHasItsOwnColor() {
        Set<Integer> colors = new HashSet<>();
        for (int resId : HOME_FUNCS) {
            Func item = Func.create(resId);
            assertNotEquals("功能按钮必须着色，不能回落白色：" + item.getText(), R.color.white, item.getColor());
            assertTrue("每个功能按钮的图标色必须可区分：" + item.getText(), colors.add(ResUtil.getColor(item.getColor())));
        }
    }

    @Test
    public void presenterTintsTheIcon() {
        FuncPresenter presenter = new FuncPresenter(new FuncPresenter.OnClickListener() {

            @Override
            public void onItemClick(Func item) {
            }

            @Override
            public boolean onLongClick(Func item) {
                return false;
            }
        });
        Presenter.ViewHolder holder = presenter.onCreateViewHolder(new FrameLayout(RuntimeEnvironment.getApplication()));
        presenter.onBindViewHolder(holder, Func.create(R.string.home_keep));
        ImageView icon = holder.view.findViewById(R.id.icon);
        assertNotNull("图标必须应用功能按钮颜色", icon.getColorFilter());
    }
}
