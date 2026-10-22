package com.cat.dnsswitch;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

/**
 * 拦截开关的公共实现 —— 悬浮窗和主界面都调这里，避免两套逻辑打架。
 *
 * 写私人 DNS 两条通道：
 *   1. 直连（需要 WRITE_SECURE_SETTINGS，adb 授过）
 *   2. Shizuku 兜底（settings put global ...）
 */
public final class Blocker {

    public static final String DEFAULT_DNS = "dns.18bit.cn";

    private Blocker() {
    }

    public static String mode(Context c) {
        try {
            return Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
        } catch (Exception e) {
            return null;
        }
    }

    public static String spec(Context c) {
        try {
            return Settings.Global.getString(c.getContentResolver(), "private_dns_specifier");
        } catch (Exception e) {
            return null;
        }
    }

    /** 现在算不算"拦截生效中" */
    public static boolean isOn(Context c) {
        return "hostname".equals(mode(c));
    }

    /** 打开时用哪个 DNS：优先用之前设过的，否则默认 */
    public static String wantedSpec(Context c) {
        String s = spec(c);
        return (s == null || s.trim().isEmpty()) ? DEFAULT_DNS : s;
    }

    /** 开关拦截。返回是否成功。会阻塞，别在主线程调用。 */
    public static boolean setOn(Context c, boolean on) {
        final String spec = on ? wantedSpec(c) : spec(c);
        final String mode = on ? "hostname" : "off";
        try {
            ContentResolver cr = c.getContentResolver();
            boolean a = Settings.Global.putString(cr, "private_dns_specifier",
                    spec == null ? DEFAULT_DNS : spec);
            boolean b = Settings.Global.putString(cr, "private_dns_mode", mode);
            if (a && b) return true;
        } catch (SecurityException ignored) {
            // 掉到 Shizuku
        } catch (Exception ignored) {
        }
        try {
            ShizukuBridge.Result r = ShizukuBridge.exec(
                    "settings put global private_dns_specifier "
                            + (spec == null ? DEFAULT_DNS : spec)
                            + " && settings put global private_dns_mode " + mode);
            return r != null && r.ok();
        } catch (Exception e) {
            return false;
        }
    }
}
