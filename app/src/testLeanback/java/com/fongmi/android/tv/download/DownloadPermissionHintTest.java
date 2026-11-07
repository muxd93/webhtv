package com.fongmi.android.tv.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 反向用例：授权页拉不起来（电视盒子常见）时，必须给出手动路径与"打不开会怎样"的提示，
 * 而不是继续用只说缺什么权限的短提示。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class DownloadPermissionHintTest {

    @Test
    public void openedSettingsKeepsShortHint() {
        assertEquals(R.string.download_permission, DownloadPath.permissionHint(true));
    }

    @Test
    public void missingSettingsPageExplainsManualPath() {
        assertEquals(R.string.download_permission_manual, DownloadPath.permissionHint(false));
        assertFalse("不能与命中授权页时的短提示混用", DownloadPath.permissionHint(false) == DownloadPath.permissionHint(true));
    }
}
