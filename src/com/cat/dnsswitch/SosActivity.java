package com.cat.dnsswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 罗德岛全舰防御系统 · 终端安全
 *
 * 先扫描所有进程 → 按可疑度分三级 → 再由你决定停谁（不再无脑全停）
 *
 * 判定规则（写死在这里，界面上也会说明）：
 *   极高威胁：进程名命中推广/追踪 SDK 特征，或者它本来在「隐藏后台」名单里却还活着
 *   中等威胁：属于第三方 App 且当前不在前台
 *   疑似无威胁：系统进程、当前前台 App、没有命中任何特征的
 */
public class SosActivity extends Activity {

    private static final int C_BG     = 0xFF000000;
    private static final int C_GREEN  = 0xFF00FF41;
    private static final int C_GREEN_D= 0xFF00A82D;
    private static final int C_DANGER = 0xFFFF0041;
    private static final int C_WARN   = 0xFFFFB000;
    private static final int C_DIM    = 0xFF4A6B55;
    private static final int C_GREEN_X= 0xFF0D2818;
    private static final int C_WHITE  = 0xFFE8F5EC;

    private static final String PREF = "prts";

    /** 极高威胁：进程名里带这些字样的（只放明确的推广 SDK 特征，别放 tracker 这种泛词） */
    private static final String[] SUSPICIOUS = {
            "mobads", "opos", "bytedance", "pangolin", "csj", "gdt", "umeng",
            "jpush", "jiguang", "getui", "kwad", "toutiao", "adservice",
            "adsdk", "ad_sdk", "promote", "minigame.ads"
    };

    private static class Proc {
        String name;     // 进程名
        String pkg;      // 主包名（去掉 :sub）
        int pid;
        String user;
        int level;       // 0=极高 1=中等 2=疑无
    }

    private LinearLayout box;
    private final List<Proc> procs = new ArrayList<Proc>();
    private boolean scanned = false;
    private boolean scanning = false;
    private String scanAt = "—";

    private volatile boolean guarding = false;
    private Thread guardThread;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(28), dp(16), dp(30));
        sv.addView(box);
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        if (guardOn()) startGuard();
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ==================== 存储 ====================

    private SharedPreferences sp() { return getSharedPreferences(PREF, MODE_PRIVATE); }

    private String sosPkgs() {
        String s = sp().getString("sos_pkgs", "");
        return s == null ? "" : s.trim();
    }

    private List<String> sosList() {
        List<String> out = new ArrayList<String>();
        for (String line : sosPkgs().split("\n")) {
            if (line.trim().length() > 0) out.add(line.trim());
        }
        return out;
    }

    private void sosSave(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String p : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        sp().edit().putString("sos_pkgs", sb.toString()).apply();
    }

    private boolean guardOn() { return sp().getBoolean("sos_guard", false); }
    private int guardCount() { return sp().getInt("sos_guard_count", 0); }

    // ==================== 界面 ====================

    private void render() {
        box.removeAllViews();

        // 兔子 + 标题
        TextView rabbit = tv("   (\\_/)\n   ( •_•)\n   c(\")(\")", 13, C_GREEN, false);
        rabbit.setLineSpacing(dp(1), 1f);
        box.addView(rabbit);

        TextView title = tv("罗德岛全舰防御系统", 21, C_DANGER, true);
        title.setLetterSpacing(0.06f);
        title.setPadding(0, dp(10), 0, 0);
        box.addView(title);

        TextView sub = tv("保卫您终端的安全", 12, C_GREEN, false);
        sub.setPadding(0, dp(4), 0, dp(2));
        box.addView(sub);

        TextView ver = tv("v" + version() + "  ·  +SHIZUKU  ·  " + (shizukuOk() ? "链路正常" : "链路断开"), 10, C_DIM, false);
        ver.setPadding(0, dp(2), 0, dp(10));
        box.addView(ver);
        box.addView(divider());

        // ---- SOS 硬件快捷键 ----
        TextView sc = tv("快捷键   音量+ 连按三次  →  直接进这里", 12, C_WARN, true);
        sc.setPadding(0, dp(12), 0, dp(2));
        box.addView(sc);
        boolean a11y = false;
        try {
            String s = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            a11y = s != null && s.contains(getPackageName());
        } catch (Exception ignored) {
        }
        box.addView(hint(a11y
                ? "无障碍服务已开启，快捷键可用。单按、连按两次照旧调音量。"
                : "现在无障碍服务没开，快捷键不生效。\n"
                  + "去 系统设置 → 无障碍 → 已下载的服务 → 普瑞赛斯病毒 打开即可。", 
                a11y ? C_GREEN_D : C_DANGER));
        box.addView(divider());

        // ---------- 威胁扫描 ----------
        box.addView(section("THREAT_SCAN  ·  威胁分析"));
        int hi = 0, mid = 0, low = 0;
        for (Proc p : procs) {
            if (p.level == 0) hi++;
            else if (p.level == 1) mid++;
            else low++;
        }
        box.addView(kv("极高威胁", hi + " 个", C_DANGER));
        box.addView(kv("中等威胁", mid + " 个", C_WARN));
        box.addView(kv("疑似无威胁", low + " 个", C_GREEN_D));
        box.addView(kv("上次扫描", scanAt, C_WHITE));

        btn(scanning ? "> 扫描中…" : "> 扫描所有进程", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { scan(); }
        });

        if (!scanned) {
            box.addView(hint("还没扫描过。点上面按钮，猫猫会列出所有进程再分级。\n"
                    + "判定规则：\n"
                    + "  极高 = 命中推广/追踪 SDK 特征，或本来被你隐藏了却还活着\n"
                    + "  中等 = 第三方 App 且不在前台\n"
                    + "  疑无 = 系统进程 / 当前前台 / 没命中任何特征", C_DIM));
        } else {
            // 极高威胁
            addLevelBlock("极高威胁", 0, C_DANGER);
            // 中等威胁
            addLevelBlock("中等威胁", 1, C_WARN);
            // 疑无只给数字
            box.addView(section("疑似无威胁"));
            box.addView(hint("共 " + low + " 个系统进程和当前前台 App，不列出来占地方。", C_DIM));
        }

        // ---------- 隐藏后台 ----------
        box.addView(section("HIDE  ·  隐藏并冻结"));
        List<String> hidden = sosList();
        if (hidden.isEmpty()) {
            box.addView(hint("还没有隐藏任何 App。", C_DIM));
        } else {
            for (final String pkg : hidden) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setBackground(boxDraw(C_GREEN_X, C_GREEN_D, 1, 1));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 0, 0, dp(4));
                row.setLayoutParams(rlp);
                TextView name = tv(label(pkg), 12, C_GREEN, false);
                name.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(name);
                TextView un = tv(" 解除 ", 11, C_DANGER, true);
                un.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { unhide(pkg); }
                });
                row.addView(un);
                box.addView(row);
            }
        }
        btn("> 添加要隐藏冻结的 App", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { pickApp(); }
        });
        btn("> 全部解除冻结", C_WARN, new View.OnClickListener() {
            @Override public void onClick(View v) { unhideAll(); }
        });

        // ---------- 进程守护 ----------
        box.addView(section("GUARD  ·  进程守护"));
        box.addView(kv("开关", guardOn() ? "开启" : "关闭", guardOn() ? C_GREEN : C_DIM));
        box.addView(kv("已守护", guardCount() + " 次", C_WHITE));
        btn(guardOn() ? "> 关闭守护" : "> 开启守护",
                guardOn() ? C_DANGER : C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !guardOn();
                sp().edit().putBoolean("sos_guard", now).apply();
                if (now) startGuard(); else stopGuard();
                render();
            }
        });
        box.addView(hint("每 30 秒巡检「隐藏」名单，发现复活就再掐一次。关掉 App 也继续守。", C_DIM));

        box.addView(divider());
        btn("> 返回主界面", C_GREEN_D, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    /** 一级威胁一块：标题 + 计数 + 全部强停 + 进程行 */
    private void addLevelBlock(String name, int level, int color) {
        List<Proc> list = new ArrayList<Proc>();
        for (Proc p : procs) if (p.level == level) list.add(p);

        box.addView(section(name));
        if (list.isEmpty()) {
            box.addView(hint("这一级没有进程。", C_DIM));
            return;
        }
        // 按名字排序
        Collections.sort(list, new Comparator<Proc>() {
            @Override public int compare(Proc a, Proc b) { return a.name.compareTo(b.name); }
        });
        int shown = Math.min(list.size(), 30);
        for (int i = 0; i < shown; i++) {
            final Proc p = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(7), dp(10), dp(7));
            row.setBackground(boxDraw(C_BG, C_GREEN_X, 1, 1));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(3));
            row.setLayoutParams(rlp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(tv(p.name, 11, C_WHITE, false));
            col.addView(tv("pid " + p.pid + "  ·  " + p.user, 9, C_DIM, false));
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView kill = tv(" 强停 ", 11, color, true);
            kill.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { killOne(p); }
            });
            row.addView(kill);
            box.addView(row);
        }
        if (list.size() > shown) {
            box.addView(hint("还有 " + (list.size() - shown) + " 个没列出来，用下面的「全部强停」。", C_DIM));
        }

        final List<Proc> all = new ArrayList<Proc>(list);
        btn("> 全部强停（" + all.size() + " 个）", color, new View.OnClickListener() {
            @Override public void onClick(View v) { killLevel(all); }
        });
    }

    // ==================== 扫描与分析 ====================

    private void scan() {
        if (scanning) return;
        if (!shizukuOk()) { toast("要先在设置页拿到 Shell 授权"); return; }
        scanning = true;
        render();
        toast("扫描中…");
        exec("pm list packages -3; echo '#####'; ps -A", new Runnable() {
            @Override public void run() { }
        }, new Result() {
            @Override public void done(ShizukuBridge.Result r) {
                scanning = false;
                analyze(r.out);
                scanned = true;
                scanAt = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                render();
            }
        });
    }

    private void analyze(String out) {
        procs.clear();
        if (out == null) return;
        String userPkgsRaw = out;
        String psRaw = "";
        int cut = out.indexOf("#####");
        if (cut >= 0) {
            userPkgsRaw = out.substring(0, cut);
            psRaw = out.substring(cut + 5);
        }

        Set<String> userPkgs = new HashSet<String>();
        for (String line : userPkgsRaw.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) userPkgs.add(line.substring(8).trim());
        }

        Set<String> hidden = new HashSet<String>(sosList());
        String self = getPackageName();

        for (String line : psRaw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] cols = line.split("\\s+");
            if (cols.length < 9) continue;
            if (cols[0].equals("USER")) continue;      // 表头
            String user = cols[0];
            int pid;
            try { pid = Integer.parseInt(cols[1]); } catch (Exception e) { continue; }
            String name = cols[cols.length - 1].trim();
            if (name.isEmpty() || name.matches("\\d+")) continue;   // ps 列不齐的行跳过

            Proc p = new Proc();
            p.name = name;
            p.pid = pid;
            p.user = user;
            int colon = name.indexOf(':');
            p.pkg = colon > 0 ? name.substring(0, colon) : name;

            String lower = name.toLowerCase();
            boolean suspicious = false;
            for (int i = 0; i < SUSPICIOUS.length; i++) {
                if (lower.contains(SUSPICIOUS[i])) { suspicious = true; break; }
            }
            // 被隐藏了却还活着 —— 这是最该处理的
            boolean revived = hidden.contains(p.pkg);

            // 系统/厂商核心进程绝不判极高，停它们会把手机搞坏
            boolean systemish = user.equals("system") || user.equals("root")
                    || lower.startsWith("vendor.")
                    || lower.startsWith("android.")
                    || lower.startsWith("com.android.");

            if (revived) {
                p.level = 0;
            } else if (suspicious && !systemish) {
                p.level = 0;
            } else if (userPkgs.contains(p.pkg) && !p.pkg.equals(self)) {
                p.level = 1;
            } else {
                p.level = 2;
            }
            procs.add(p);
        }
    }

    // ==================== 强停 ====================

    private void killOne(final Proc p) {
        if (!p.pkg.contains(".")) { toast("这不是应用进程：" + p.name); return; }
        exec("am force-stop " + p.pkg + "; echo OK", new Runnable() {
            @Override public void run() { }
        }, new Result() {
            @Override public void done(ShizukuBridge.Result r) {
                toast("已强停 " + p.pkg);
                for (Proc x : procs) if (x.pid == p.pid) x.level = 2;
                render();
            }
        });
    }

    private void killLevel(final List<Proc> list) {
        StringBuilder sh = new StringBuilder();
        Set<String> done = new HashSet<String>();
        for (Proc p : list) {
            if (!p.pkg.contains(".")) continue;
            if (done.contains(p.pkg)) continue;
            done.add(p.pkg);
            sh.append("am force-stop ").append(p.pkg).append("; ");
        }
        if (sh.length() == 0) { toast("没有可停的应用进程"); return; }
        sh.append("echo OK");
        toast("正在强停 " + done.size() + " 个…");
        exec(sh.toString(), new Runnable() {
            @Override public void run() { }
        }, new Result() {
            @Override public void done(ShizukuBridge.Result r) {
                toast("已强停 " + done.size() + " 个");
                for (Proc x : list) x.level = 2;
                render();
            }
        });
    }

    // ==================== 隐藏/冻结 ====================

    private void hide(final String pkg) {
        exec("pm suspend --user 0 " + pkg + "; am force-stop " + pkg + "; echo OK",
                new Runnable() { @Override public void run() { } }, new Result() {
            @Override public void done(ShizukuBridge.Result r) {
                toast("已冻结：" + pkg);
                render();
            }
        });
    }

    private void unhide(final String pkg) {
        exec("pm unsuspend --user 0 " + pkg + "; echo OK",
                new Runnable() { @Override public void run() { } }, new Result() {
            @Override public void done(ShizukuBridge.Result r) {
                List<String> list = sosList();
                list.remove(pkg);
                sosSave(list);
                toast("已解除：" + pkg);
                render();
            }
        });
    }

    private void unhideAll() {
        final List<String> list = sosList();
        if (list.isEmpty()) { toast("列表是空的"); return; }
        StringBuilder sh = new StringBuilder();
        for (String p : list) sh.append("pm unsuspend --user 0 ").append(p).append("; ");
        sh.append("echo OK");
        exec(sh.toString(), new Runnable() { @Override public void run() { } }, new Result() {
            @Override public void done(ShizukuBridge.Result r) {
                sosSave(new ArrayList<String>());
                toast("已解除全部冻结");
                render();
            }
        });
    }

    private void pickApp() {
        final PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Exception e) {
            toast("读不到应用列表：" + e.getMessage());
            return;
        }
        final List<ApplicationInfo> user = new ArrayList<ApplicationInfo>();
        for (ApplicationInfo ai : apps) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            user.add(ai);
        }
        Collections.sort(user, new Comparator<ApplicationInfo>() {
            @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                return label(a.packageName).compareToIgnoreCase(label(b.packageName));
            }
        });
        CharSequence[] items = new CharSequence[user.size()];
        for (int i = 0; i < user.size(); i++) {
            items[i] = label(user.get(i).packageName);
        }
        new AlertDialog.Builder(this)
                .setTitle("选一个 App 冻结")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String pkg = user.get(which).packageName;
                        List<String> list = sosList();
                        if (!list.contains(pkg)) list.add(pkg);
                        sosSave(list);
                        hide(pkg);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ==================== 守护 ====================

    private void startGuard() {
        if (guarding) return;
        if (!shizukuOk()) return;
        guarding = true;
        guardThread = new Thread(new Runnable() {
            @Override public void run() {
                while (guarding) {
                    try {
                        List<String> list = sosList();
                        if (!list.isEmpty()) {
                            StringBuilder sh = new StringBuilder("for p in ");
                            for (String p : list) sh.append(p).append(' ');
                            sh.append("; do if pidof $p >/dev/null 2>&1; then am force-stop $p; "
                                    + "echo \"KILLED|$p\"; fi; done");
                            ShizukuBridge.Result r = ShizukuBridge.exec(sh.toString());
                            int n = 0;
                            if (r.out != null) {
                                for (String line : r.out.split("\n")) {
                                    if (line.startsWith("KILLED|")) n++;
                                }
                            }
                            if (n > 0) {
                                sp().edit().putInt("sos_guard_count", guardCount() + n).apply();
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { toast("守护：又掐掉一个复活的后台"); }
                                });
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "prts-sos-guard");
        guardThread.setDaemon(true);
        guardThread.start();
    }

    private void stopGuard() {
        guarding = false;
        if (guardThread != null) guardThread.interrupt();
        guardThread = null;
    }

    // ==================== 基础设施 ====================

    private boolean shizukuOk() {
        return ShizukuBridge.running() && ShizukuBridge.granted();
    }

    private interface Result { void done(ShizukuBridge.Result r); }

    private void exec(final String script, final Runnable before, final Result cb) {
        if (!shizukuOk()) { toast("先在设置页拿 Shell 授权"); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                final ShizukuBridge.Result r = ShizukuBridge.exec(script);
                runOnUiThread(new Runnable() {
                    @Override public void run() { cb.done(r); }
                });
            }
        }, "prts-sos").start();
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private TextView section(String s) {
        TextView t = tv(s, 11, C_GREEN_D, false);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private View kv(String k, String v, int vColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView a = tv(k, 12, C_GREEN, false);
        a.setLayoutParams(new LinearLayout.LayoutParams(dp(96),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(a);
        row.addView(tv(v, 12, vColor, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView hint(String s, int color) {
        TextView t = tv(s, 10, color, false);
        t.setPadding(0, dp(6), 0, dp(4));
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_GREEN_X);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private void btn(String text, int color, View.OnClickListener l) {
        TextView t = tv(text, 13, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(12), dp(10), dp(12));
        t.setBackground(boxDraw(C_BG, color, 2, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(2));
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        box.addView(t);
    }

    private GradientDrawable boxDraw(int bg, int stroke, int w, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setStroke(Math.max(1, dp(w)), stroke);
        g.setCornerRadius(dp(r));
        return g;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
