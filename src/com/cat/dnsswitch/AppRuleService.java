package com.cat.dnsswitch;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * 猫猫去广告 - 前台应用监听服务
 *
 * 只监听窗口切换（TYPE_WINDOW_STATE_CHANGED），
 * 只读包名，不读任何窗口内容。
 */
public class AppRuleService extends AccessibilityService {

    static final String PREF = "maomao_dns";
    static final String KEY_RULES = "app_rules";
    static final String KEY_INTERCEPT = "intercept_on";
    static final String DNS_PRIMARY = "dns.18bit.cn";

    /** 规则：0=跟随 1=放行(关拦截) 2=强制(开拦截) */
    static final int MODE_INHERIT = 0;
    static final int MODE_BYPASS = 1;
    static final int MODE_FORCE = 2;

    private static final long DEBOUNCE_MS = 350;

    private String lastPkg = "";
    private long lastAt = 0;
    private String appliedPkg = "";

    // ---- SOS 紧急快捷键 ----
    private long lastVolAt = 0;
    private int volHits = 0;
    private static final long VOL_WINDOW_MS = 1200;

    /**
     * 全局按键：音量+ 连按三次（1.2 秒内）触发 SOS。
     * 单次和两次照旧调音量，不打扰。
     */
    @Override protected boolean onKeyEvent(android.view.KeyEvent e) {
        if (e == null) return false;
        if (e.getKeyCode() != android.view.KeyEvent.KEYCODE_VOLUME_UP) return false;
        if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
        if (e.getRepeatCount() > 0) return false;   // 长按的自动重复不算

        long now = System.currentTimeMillis();
        if (now - lastVolAt > VOL_WINDOW_MS) volHits = 0;
        lastVolAt = now;
        volHits++;
        if (volHits >= 3) {
            volHits = 0;
            triggerSos();
            return true;      // 第三次吃掉，不再调音量
        }
        return false;
    }

    private void triggerSos() {
        try {
            android.widget.Toast.makeText(this, "SOS · 罗德岛全舰防御",
                    android.widget.Toast.LENGTH_SHORT).show();
            android.content.Intent i = new android.content.Intent(this, SosActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence p = event.getPackageName();
        if (p == null) return;
        final String pkg = p.toString();

        // 过滤系统窗口
        if (pkg.equals("com.android.systemui")
                || pkg.contains("inputmethod")
                || pkg.contains("permissioncontroller")
                || pkg.contains("packageinstaller")
                || pkg.equals(getPackageName())) {
            return;
        }

        long now = System.currentTimeMillis();
        if (pkg.equals(lastPkg) && (now - lastAt) < DEBOUNCE_MS) return;
        lastPkg = pkg;
        lastAt = now;

        Settings.Global.putString(getContentResolver(),
                "maomao_last_pkg", pkg);
        applyFor(pkg);
    }

    private void applyFor(final String pkg) {
        SharedPreferences sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int mode = readRule(sp, pkg);

        if (mode == MODE_INHERIT) {
            // 跟随全局 —— 恢复默认拦截状态
            restoreDefault(sp);
            appliedPkg = pkg;
            return;
        }

        ContentResolver cr = getContentResolver();
        try {
            if (mode == MODE_BYPASS) {
                Settings.Global.putString(cr, "private_dns_mode", "off");
                addLog(sp, pkg, "BYPASS → DNS off");
            } else if (mode == MODE_FORCE) {
                Settings.Global.putString(cr, "private_dns_specifier", DNS_PRIMARY);
                Settings.Global.putString(cr, "private_dns_mode", "hostname");
                addLog(sp, pkg, "FORCE  → " + DNS_PRIMARY);
            }
            appliedPkg = pkg;
        } catch (Exception e) {
            addLog(sp, pkg, "ERR " + e.getClass().getSimpleName());
        }
    }

    /** 回到全局默认：看 intercept_on 开关 */
    private void restoreDefault(SharedPreferences sp) {
        boolean on = sp.getBoolean(KEY_INTERCEPT, true);
        ContentResolver cr = getContentResolver();
        try {
            if (on) {
                Settings.Global.putString(cr, "private_dns_specifier", DNS_PRIMARY);
                Settings.Global.putString(cr, "private_dns_mode", "hostname");
            } else {
                Settings.Global.putString(cr, "private_dns_mode", "off");
            }
        } catch (Exception ignored) {}
    }

    static int readRule(SharedPreferences sp, String pkg) {
        String raw = sp.getString(KEY_RULES, "");
        if (raw.isEmpty()) return MODE_INHERIT;
        for (String seg : raw.split(",")) {
            int i = seg.lastIndexOf(':');
            if (i <= 0) continue;
            if (seg.substring(0, i).equals(pkg)) {
                try { return Integer.parseInt(seg.substring(i + 1)); }
                catch (Exception e) { return MODE_INHERIT; }
            }
        }
        return MODE_INHERIT;
    }

    static void writeRule(SharedPreferences sp, String pkg, int mode) {
        String raw = sp.getString(KEY_RULES, "");
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        if (!raw.isEmpty()) {
            for (String seg : raw.split(",")) {
                int i = seg.lastIndexOf(':');
                if (i <= 0) continue;
                String k = seg.substring(0, i);
                if (k.equals(pkg)) {
                    found = true;
                    if (mode != MODE_INHERIT) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(pkg).append(':').append(mode);
                    }
                } else {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(seg);
                }
            }
        }
        if (!found && mode != MODE_INHERIT) {
            if (sb.length() > 0) sb.append(',');
            sb.append(pkg).append(':').append(mode);
        }
        sp.edit().putString(KEY_RULES, sb.toString()).apply();
    }

    private void addLog(SharedPreferences sp, String pkg, String what) {
        String t = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        String line = t + "  APP     " + shortPkg(pkg) + "  " + what;
        String old = sp.getString("run_log", "");
        String neu = old.isEmpty() ? line : old + "\n" + line;
        String[] lines = neu.split("\n");
        if (lines.length > 200) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 200; i < lines.length; i++) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(lines[i]);
            }
            neu = sb.toString();
        }
        sp.edit().putString("run_log", neu).apply();
    }

    private String shortPkg(String pkg) {
        if (pkg.length() <= 28) return pkg;
        return pkg.substring(0, 25) + "...";
    }

    @Override
    public void onInterrupt() { }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        SharedPreferences sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        addLog(sp, "-", "A11Y_SERVICE 已连接");
    }
}
