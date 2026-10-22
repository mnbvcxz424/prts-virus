package com.cat.dnsswitch;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

/**
 * 午夜关怀：每天 00:00 检查一次，如果博士正在玩游戏，
 * 就以普瑞赛斯的名义发一条「该睡觉了」的通知。
 *
 * 判断"在玩游戏"用 UsageStatsManager（需要 PACKAGE_USAGE_STATS 特殊权限，
 * 可以用 adb 给：appops set com.cat.dnsswitch GET_USAGE_STATS allow）。
 * 拿不到就退一步：用 FLAG_IS_GAME / CATEGORY_GAME 判断，仍拿不到就不打扰。
 */
public final class NightWatch {

    public static final String CH_ID = "prts_night";
    public static final String ACTION_NIGHT = "com.cat.dnsswitch.NIGHT_CHECK";
    private static final int REQ = 2001;
    private static final String PREF = "prts";
    private static final String KEY_ON = "night_on";

    private NightWatch() {
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(KEY_ON, true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply();
        if (on) schedule(c);
        else cancel(c);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 预约下一次 00:00 */
    public static void schedule(Context c) {
        try {
            if (!enabled(c)) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 5);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            PendingIntent pi = pending(c);
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
        } catch (Exception ignored) {
        }
    }

    public static void cancel(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(c));
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, NightWatchReceiver.class).setAction(ACTION_NIGHT);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ, i, flags);
    }

    /**
     * 当前前台是不是游戏。
     * 返回 null = 判断不了（没有权限/没数据），调用方自己决定要不要打扰。
     */
    public static Boolean inGame(Context c) {
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long now = System.currentTimeMillis();
                List<UsageStats> list = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, now - 6 * 60 * 60 * 1000L, now);
                if (list != null && !list.isEmpty()) {
                    UsageStats last = null;
                    for (UsageStats u : list) {
                        if (last == null || u.getLastTimeUsed() > last.getLastTimeUsed()) last = u;
                    }
                    if (last != null && now - last.getLastTimeUsed() < 90 * 1000L) {
                        // 90 秒内用过 → 大概还在前台
                        String pkg = last.getPackageName();
                        if (pkg != null && !pkg.equals(c.getPackageName())) {
                            return isGamePackage(c, pkg);
                        }
                    }
                    return Boolean.FALSE;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isGamePackage(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return true;
            if (Build.VERSION.SDK_INT >= 26) {
                return ai.category == ApplicationInfo.CATEGORY_GAME;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 发午夜提醒 */
    public static void notifyNight(Context c, String gameName) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "午夜提醒", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("每天 0 点，如果博士还在玩游戏就提醒一次");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(c, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(c, 2002, open, piFlags);

            String body = "博士时间不早了，该睡觉了。";
            if (gameName != null && !gameName.isEmpty()) {
                body = body + "（" + gameName + " 明天还在。）";
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CH_ID)
                    : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_menu_myplaces)
                    .setContentTitle("普瑞赛斯")
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body + "\n—— 留言者：普瑞赛斯"))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(2001, b.build());
        } catch (Exception ignored) {
        }
    }
}
