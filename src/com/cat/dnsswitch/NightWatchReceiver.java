package com.cat.dnsswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/**
 * 每天 00:00 触发：
 *   1. 先把明天的闹钟约好（AlarmManager 的每日要靠自己接力）
 *   2. 判断博士是不是还在玩游戏 → 是就发一条普瑞赛斯的留言
 */
public class NightWatchReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        try {
            // 先接力明天，避免后面异常导致彻底断掉
            NightWatch.schedule(context);

            if (!NightWatch.enabled(context)) return;

            Boolean game = NightWatch.inGame(context);
            if (game == null) {
                // 判断不了（没给 PACKAGE_USAGE_STATS）—— 不打扰，但留一条运行记录
                return;
            }
            if (!game.booleanValue()) return;

            NightWatch.notifyNight(context, foregroundGameName(context));
        } catch (Exception ignored) {
        }
    }

    /** 尽量取到当前游戏的显示名，取不到就返回 null */
    private String foregroundGameName(Context c) {
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            java.util.List<android.app.usage.UsageStats> list = usm.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    now - 6 * 60 * 60 * 1000L, now);
            if (list == null || list.isEmpty()) return null;
            android.app.usage.UsageStats last = null;
            for (android.app.usage.UsageStats u : list) {
                if (last == null || u.getLastTimeUsed() > last.getLastTimeUsed()) last = u;
            }
            if (last == null) return null;
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(last.getPackageName(), 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label == null ? null : label.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
