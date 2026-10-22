package com.cat.dnsswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 两个来源都会进来：
 *   ① 开机完成 / App 被覆盖安装 —— 把用户上次开着的状态恢复回来
 *   ② KeepAlive 预约的闹钟 —— 被系统清掉之后自己爬起来
 *
 * 重试策略：最多 5 次。连着 5 次起不来就放弃，不再自我唤醒 ——
 * 免得出现「权限被撤了，App 还每 30 秒醒一次」这种死循环。
 */
public class ReviveReceiver extends BroadcastReceiver {

    public static final String ACTION_REVIVE = "com.cat.dnsswitch.REVIVE";

    private static final int MAX_TRIES = 5;
    private static final int RETRY_MS = 30000;

    @Override public void onReceive(Context context, Intent intent) {
        try {
            SharedPreferences sp = context.getSharedPreferences(
                    KeepAlive.PREF, Context.MODE_PRIVATE);
            int tries = sp.getInt("revive_tries", 0);

            KeepAlive.revive(context);

            if (AdBlockVpnService.running
                    || (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))) {
                // 起来了（或刚开机，给它点时间）：清零、取消闹钟
                sp.edit().putInt("revive_tries", 0).apply();
                if (AdBlockVpnService.running) {
                    KeepAlive.cancelRevive(context);
                } else {
                    KeepAlive.scheduleRevive(context, RETRY_MS);
                }
            } else if (tries < MAX_TRIES) {
                sp.edit().putInt("revive_tries", tries + 1).apply();
                KeepAlive.scheduleRevive(context, RETRY_MS);
            } else {
                // 连续失败太多：清计数，停止自我唤醒
                sp.edit().putInt("revive_tries", 0).apply();
            }
        } catch (Exception ignored) {
        }
    }
}
