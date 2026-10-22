package com.cat.dnsswitch;

import android.content.Context;
import android.provider.Settings;

/**
 * 功能授权。
 *
 * 规则（博士定的）：
 *   1. 作者自己的这台设备 —— 直接解锁，不需要 root
 *   2. 其他任何设备 —— 必须有 root 才能用自定义拦截
 *   3. 没有 root 又不是作者设备 —— 功能锁死，只显示提示
 *
 * OWNER_ID 在构建时写入（见 build 脚本），所以别人拿到的 APK 里
 * 只有博士那台机器的指纹能对上，功能对他们是锁着的。
 * 注意：这挡的是普通用户，挡不住反编译 —— 真要防得联网校验，那是另一回事。
 */
public final class License {

    /** 作者设备指纹（构建时自动写入；空 = 谁都不解锁） */
    public static final String OWNER_ID = "1f7b83e2cf282a96";

    private static Boolean rootCache = null;

    private License() {}

    /** 本 App 看到的 ANDROID_ID（Android 8+ 按签名+用户+设备派生，重装不变） */
    public static String deviceId(Context c) {
        try {
            String id = Settings.Secure.getString(
                    c.getContentResolver(), Settings.Secure.ANDROID_ID);
            return id == null ? "" : id.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 是不是作者这台设备 */
    public static boolean isOwner(Context c) {
        String want = OWNER_ID == null ? "" : OWNER_ID.trim();
        if (want.isEmpty()) return false;
        return want.equalsIgnoreCase(deviceId(c));
    }

    /** 有没有 root（能 su -c id 拿到 uid=0 就算有）；结果缓存 */
    public static boolean hasRoot() {
        if (rootCache != null) return rootCache;
        rootCache = Boolean.FALSE;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            if (line != null && line.contains("uid=0")) rootCache = Boolean.TRUE;
            try { p.waitFor(); } catch (Exception ignored) { }
        } catch (Exception ignored) {
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
        return rootCache;
    }

    /** 能不能用自定义拦截 */
    public static boolean canUseCustom(Context c) {
        return isOwner(c) || hasRoot();
    }

    /** 清掉 root 检测缓存（用户刚 ROOT 完可以点重试） */
    public static void resetRootCache() {
        rootCache = null;
    }

    /** 一行状态文字，给设置页显示 */
    public static String status(Context c) {
        if (isOwner(c)) return "作者设备 · 已解锁";
        if (hasRoot()) return "已 ROOT · 已解锁";
        return "未授权 · 需 ROOT";
    }

    /** 解锁方式说明 */
    public static String reason(Context c) {
        if (isOwner(c)) return "这台是作者的设备，免 ROOT 直接解锁。";
        if (hasRoot()) return "检测到 ROOT，已解锁。";
        return "自定义拦截只对作者设备开放；其他设备需要 ROOT 权限才能解锁。"
                + "这是故意设的门槛：名单由使用者自己负责。";
    }
}
