package com.cat.dnsswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 每天 20:00 的汇总提醒：普瑞赛斯告诉博士今天拦了多少。
 * 由 MainActivity 里用 AlarmManager 每天排一次。
 */
public class DailyReportReceiver extends BroadcastReceiver {

    public static final String CH_DAILY = "prts_daily";

    @Override public void onReceive(Context ctx, Intent intent) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("prts_stats", Context.MODE_PRIVATE);
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String day = sp.getString("day", "");
            int d = day.equals(today) ? sp.getInt("day_blocked", 0) : 0;
            int total = sp.getInt("total_blocked", 0);
            int q = day.equals(today) ? sp.getInt("day_queries", 0) : 0;

            String title;
            String body;
            if (!AdBlockVpnService.running && d == 0) {
                title = "普瑞赛斯 · 今日汇报";
                body = "今天没开本地拦截，一条广告也没拦。";
            } else if (d == 0) {
                title = "普瑞赛斯 · 今日汇报";
                body = "跑了 " + q + " 次查询，一条广告都没拦到。今天挺干净。";
            } else {
                title = "普瑞赛斯 · 今日拦截 " + d + " 条";
                body = "博士，今天为您挡下了 " + d + " 条广告。累计 " + total + " 条。";
            }

            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CH_DAILY, "每日汇报", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }

            Intent open = new Intent(ctx, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CH_DAILY)
                    : new Notification.Builder(ctx);
            b.setContentTitle(title)
             .setContentText(body)
             .setStyle(new Notification.BigTextStyle().bigText(body))
             .setSmallIcon(android.R.drawable.stat_notify_chat)
             .setAutoCancel(true)
             .setContentIntent(pi);
            nm.notify(2001, b.build());
        } catch (Exception ignored) {
        }
    }
}
