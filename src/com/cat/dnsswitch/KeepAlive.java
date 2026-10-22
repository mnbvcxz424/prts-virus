package com.cat.dnsswitch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 防后台杀进程：两件事
 *   ① 申请电池优化白名单（系统不再随意冻结/清理我们）
 *   ② 被清掉之后，用 AlarmManager 定时把自己拉回来
 *
 * 没有 root、没有 Xposed，这就是普通 App 能做到的上限。
 */
public final class KeepAlive {

    public static final String PREF = "prts";
    public static final String KEY_WANT = "want_vpn";     // 用户希望 VPN 开着
    public static final String KEY_FLOAT = "float";        // 悬浮窗开关
    public static final String KEY_KEEP = "keepalive";     // 强力保活开关

    private static final int REQ_REVIVE = 1001;

    private KeepAlive() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 是否已经在电池优化白名单里 */
    public static boolean ignoringBattery(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** 弹系统对话框申请白名单（用户点一下就行） */
    public static void requestIgnoreBattery(Context c) {
        if (Build.VERSION.SDK_INT < 23) return;
        if (ignoringBattery(c)) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            // 有些 ROM 没实现这个 action，退回电池优化列表
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    /** 预约一次"复活" */
    public static void scheduleRevive(Context c, long delayMs) {
        try {
            SharedPreferences sp = prefs(c);
            if (!sp.getBoolean(KEY_KEEP, true)) return;
            if (!sp.getBoolean(KEY_WANT, false)) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = reviveIntent(c);
            long at = System.currentTimeMillis() + Math.max(1000, delayMs);
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception ignored) {
        }
    }

    public static void cancelRevive(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(reviveIntent(c));
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent reviveIntent(Context c) {
        Intent i = new Intent(c, ReviveReceiver.class);
        i.setAction(ReviveReceiver.ACTION_REVIVE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ_REVIVE, i, flags);
    }

    /** 把 VPN（和悬浮窗）拉起来 */
    public static void revive(Context c) {
        SharedPreferences sp = prefs(c);
        if (!sp.getBoolean(KEY_WANT, false)) return;
        try {
            Intent v = new Intent(c, AdBlockVpnService.class)
                    .setAction(AdBlockVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(v);
            else c.startService(v);
        } catch (Exception ignored) {
        }
        if (sp.getBoolean(KEY_FLOAT, false) && FloatWin.canDraw(c)) {
            FloatWin.start(c);
        }
    }
}
