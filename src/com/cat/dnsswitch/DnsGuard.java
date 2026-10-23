package com.cat.dnsswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * DoT 健康保护（v3.7.4）。
 *
 * 背景：私人 DNS 设成 hostname 模式时，如果那台 DoT 服务器连不上，
 * 整台手机的域名解析会**全部失败** —— 表现是"微信能连、网页全打不开"。
 * 用户（尤其是长辈）不会知道这是拦截开关的锅。
 *
 * 策略：启动时做一次"能不能解析域名"的实测，连续失败 2 次自动关掉拦截。
 * 原则：**"上不了广告"永远不能优先于"上得了网"**。
 *
 * 为什么不用"连服务器 853 端口"来判断：
 *   解析 host:853 本身要走 DNS，而 DoT 挂了 DNS 就死了 —— 先有鸡还是先有蛋。
 *   所以直接测"能不能解析一个普通域名"，这才是用户真正感知到的东西。
 */
public final class DnsGuard {

    private static final String PREF = "prts";
    private static final String KEY_FAILS = "dot_fails";
    private static final String KEY_AUTO = "dot_guard";
    private static final String KEY_LAST = "dot_last";
    private static final String KEY_AUTO_OFF = "auto_off_by_guard";

    /** 探针域名：随便一个稳定、不会被拦的站 */
    private static final String CANARY = "www.baidu.com";
    private static final int FAIL_LIMIT = 2;

    private DnsGuard() {
    }

    public static boolean autoOn(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true);
    }

    public static void setAuto(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO, on).apply();
    }

    public static String lastResult(Context c) {
        String s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LAST, "");
        return s == null ? "" : s;
    }

    public static boolean autoOff(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_OFF, false);
    }

    public static void clearAutoOff(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_AUTO_OFF).apply();
    }

    /** 有没有"能上网"的网络。没有网络时不做判断，避免飞行模式被误判成 DoT 挂了 */
    public static boolean hasNetwork(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;   // 判断不了就当作有，交给下一个检查
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            return cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    /** 能不能把这个域名解析成 IP —— 这就是用户感知的"能不能上网" */
    public static boolean canResolve(String host) {
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            return addrs != null && addrs.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 保留给"立即检测"做补充信息：主机 853 端口能不能连 */
    public static boolean probePort853(String host) {
        if (host == null) return false;
        String h = host.trim();
        if (h.isEmpty()) return false;
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(h, 853), 3500);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) { }
            }
        }
    }

    public static void checkAsync(final Context c) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try { checkNow(app); } catch (Exception ignored) { }
            }
        }, "prts-dotguard").start();
    }

    /**
     * 同步检查一次。
     * @return true = 健康 / 没开拦截 / 保护关闭 / 没网跳过；false = 解析失败
     */
    public static boolean checkNow(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);

        if (!sp.getBoolean(KEY_AUTO, true)) {
            setLast(sp, "保护已关闭");
            return true;
        }
        if (!Blocker.isOn(c)) {
            sp.edit().putInt(KEY_FAILS, 0).apply();
            setLast(sp, "未开启拦截");
            return true;
        }
        if (!hasNetwork(c)) {
            // 没网不是 DoT 的锅，不计数
            setLast(sp, "当前无网络，跳过检测");
            return true;
        }

        if (canResolve(CANARY)) {
            sp.edit().putInt(KEY_FAILS, 0).apply();
            setLast(sp, "健康 · 域名解析正常");
            return true;
        }

        int fails = sp.getInt(KEY_FAILS, 0) + 1;
        if (fails >= FAIL_LIMIT) {
            boolean ok = Blocker.setOn(c, false);
            try { AdBlockVpnService.resetGroupsCache(); } catch (Exception ignored) { }
            sp.edit().putInt(KEY_FAILS, 0).putBoolean(KEY_AUTO_OFF, true).apply();
            setLast(sp, ok ? "解析失败，已自动关闭拦截（保网络）"
                    : "解析失败，自动关闭失败，请手动关掉拦截");
            return false;
        }
        sp.edit().putInt(KEY_FAILS, fails).apply();
        setLast(sp, "第 " + fails + " 次解析失败 · " + CANARY);
        return false;
    }

    private static void setLast(SharedPreferences sp, String s) {
        sp.edit().putString(KEY_LAST, s).apply();
    }
}
