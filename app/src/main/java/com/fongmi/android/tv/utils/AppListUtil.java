package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可启动的电视应用列表：很多 TV 应用只声明了普通桌面入口，
 * 因此 LEANBACK_LAUNCHER 与 LAUNCHER 两个 category 都查，按 activity 去重后按名称排序。
 */
public class AppListUtil {

    public static List<ResolveInfo> query(Context context) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> resolved = new ArrayList<>();
        resolved.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        resolved.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();
        String self = context.getPackageName();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
            if (info.activityInfo.packageName.equals(self)) continue;
            unique.putIfAbsent(info.activityInfo.packageName + "/" + info.activityInfo.name, info);
        }
        List<ResolveInfo> apps = new ArrayList<>(unique.values());
        apps.sort(new ResolveInfo.DisplayNameComparator(pm));
        return apps;
    }

    public static void launch(Context context, ResolveInfo info) {
        if (info == null || info.activityInfo == null) return;
        // 直启目标组件：不带 category 的 MAIN 意图可命中任何 launcher 入口，
        // 避免 getLaunchIntentForPackage（只认 LAUNCHER，leanback-only 应用返回 null）与双重 category 匹配失败
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
