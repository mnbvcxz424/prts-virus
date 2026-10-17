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
import java.util.List;
import java.util.Locale;

/**
 * SOS · 应急响应
 *
 * 三个手段（全走 Shizuku，不需要 root）：
 *  1. 一键强停：am kill-all + 对黑名单 am force-stop
 *  2. 隐藏后台：pm suspend 冻结 + force-stop，让它彻底不活动
 *  3. 进程守护：每 30 秒巡检，发现黑名单进程又活了就再掐一次
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

    private LinearLayout box;
    private volatile boolean guarding = false;
    private Thread guardThread;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(30), dp(16), dp(30));
        sv.addView(box);
        setContentView(sv);

        TextView title = tv("\u26a0  SOS  ·  应急响应", 20, C_DANGER, true);
        title.setLetterSpacing(0.08f);
        box.addView(title);

        TextView sub = tv("子系统: 应急控制  //  v" + version() + "  +SHIZUKU", 11, C_DIM, false);
        sub.setPadding(0, dp(4), 0, dp(10));
        box.addView(sub);

        if (guarding) startGuard();
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    @Override protected void onDestroy() {
        // 守护线程故意不停：App 退到后台也要继续守
        super.onDestroy();
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
    private String lastPanic() { return sp().getString("sos_last", "—"); }

    // ==================== 界面 ====================

    private void render() {
        box.removeAllViews();

        TextView title = tv("\u26a0  SOS  ·  应急响应", 20, C_DANGER, true);
        title.setLetterSpacing(0.08f);
        box.addView(title);
        TextView sub = tv("子系统: 应急控制  //  v" + version() + "  +SHIZUKU", 11, C_DIM, false);
        sub.setPadding(0, dp(4), 0, dp(10));
        box.addView(sub);
        box.addView(divider());

        boolean shizukuOk = ShizukuBridge.running() && ShizukuBridge.granted();

        // ---------- 一键强停 ----------
        box.addView(section("// PANIC  ·  一键强停"));
        box.addView(kv("状态", shizukuOk ? "已就绪" : "需要一个 Shell 授权"));
        box.addView(kv("上次执行", lastPanic()));
        box.addView(kv("黑名单", sosList().size() + " 个 App"));
        btn("> 强停一切可疑进程", C_DANGER, new View.OnClickListener() {
            @Override public void onClick(View v) { panic(); }
        });
        box.addView(hint("会执行 am kill-all 掐掉所有后台进程，再对黑名单逐个 am force-stop。\n"
                + "正在前台用的 App 不会被动，除非它就在黑名单里。", C_DIM));

        // ---------- 隐藏后台 ----------
        box.addView(section("// HIDE  ·  隐藏后台"));
        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        List<String> hidden = sosList();
        if (hidden.isEmpty()) {
            listBox.addView(hint("还没有隐藏任何 App。点下面按钮挑几个。", C_DIM));
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
                listBox.addView(row);
            }
        }
        box.addView(listBox);
        btn("> 添加要隐藏的 App", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { pickApp(); }
        });
        btn("> 全部解除隐藏", C_WARN, new View.OnClickListener() {
            @Override public void onClick(View v) { unhideAll(); }
        });
        box.addView(hint("隐藏 = pm suspend（系统级冻结）+ am force-stop。\n"
                + "冻结后 App 打不开、不会自启、不会后台刷新，除非你手动解除。", C_DIM));

        // ---------- 进程守护 ----------
        box.addView(section("// GUARD  ·  进程守护"));
        box.addView(kv("开关", guardOn() ? "开启" : "关闭"));
        box.addView(kv("已守护", guardCount() + " 次"));
        btn(guardOn() ? "> 关闭守护" : "> 开启守护",
                guardOn() ? C_DANGER : C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !guardOn();
                sp().edit().putBoolean("sos_guard", now).apply();
                if (now) startGuard(); else stopGuard();
                render();
            }
        });
        btn("> 清零守护计数", C_DIM, new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp().edit().putInt("sos_guard_count", 0).apply();
                render();
            }
        });
        box.addView(hint("每 30 秒巡检一次黑名单。ColorOS 会把 App 重新拉起来，\n"
                + "守护就是发现它又活了就再掐一次。关掉 App 也会继续守。", C_DIM));

        box.addView(divider());
        btn("> 返回主界面", C_GREEN_D, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    // ==================== 动作 ====================

    private void panic() {
        final List<String> pkgs = sosList();
        StringBuilder sh = new StringBuilder("am kill-all; ");
        if (!pkgs.isEmpty()) {
            sh.append("for p in ");
            for (String p : pkgs) sh.append(p).append(' ');
            sh.append("; do am force-stop $p; echo \"STOPPED|$p\"; done; ");
        }
        sh.append("echo \"PANIC_DONE\"");
        toast("正在强停…");
        exec(sh.toString(), new Runnable() {
            @Override public void run() {
            }
        }, new Result() {
            @Override public void done(ShizukuBridge.Result r) {
                int n = 0;
                if (r.out != null) {
                    for (String line : r.out.split("\n")) {
                        if (line.startsWith("STOPPED|")) n++;
                    }
                }
                final int fn = n;
                sp().edit()
                  .putString("sos_last", nowStr() + "  强停 " + fn + " 个 + kill-all")
                  .apply();
                toast("强停完成" + (fn > 0 ? "，黑名单 " + fn + " 个已掐" : ""));
                render();
            }
        });
    }

    private void hide(final String pkg) {
        exec("pm suspend --user 0 " + pkg + "; am force-stop " + pkg + "; echo OK",
                new Runnable() { @Override public void run() { } },
                new Result() {
            @Override public void done(ShizukuBridge.Result r) {
                toast("已隐藏：" + pkg);
                render();
            }
        });
    }

    private void unhide(final String pkg) {
        exec("pm unsuspend --user 0 " + pkg + "; echo OK",
                new Runnable() { @Override public void run() { } },
                new Result() {
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
        exec(sh.toString(), new Runnable() { @Override public void run() { } },
                new Result() {
            @Override public void done(ShizukuBridge.Result r) {
                sosSave(new ArrayList<String>());
                toast("已解除全部隐藏");
                render();
            }
        });
    }

    // ==================== 选 App ====================

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
        final CharSequence[] items = new CharSequence[user.size()];
        for (int i = 0; i < user.size(); i++) {
            items[i] = label(user.get(i).packageName) + "  ·  " + user.get(i).packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle("选一个 App 隐藏后台")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final ApplicationInfo ai = user.get(which);
                        List<String> list = sosList();
                        if (!list.contains(ai.packageName)) list.add(ai.packageName);
                        sosSave(list);
                        hide(ai.packageName);
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

    // ==================== 进程守护 ====================

    private void startGuard() {
        if (guarding) return;
        if (!(ShizukuBridge.running() && ShizukuBridge.granted())) return;
        guarding = true;
        guardThread = new Thread(new Runnable() {
            @Override public void run() {
                while (guarding) {
                    try {
                        List<String> list = sosList();
                        if (!list.isEmpty()) {
                            StringBuilder sh = new StringBuilder(
                                    "for p in ");
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
                                int total = guardCount() + n;
                                sp().edit().putInt("sos_guard_count", total).apply();
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        toast("守护：又掐掉一个复活的后台");
                                    }
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

    // ==================== 线程 ====================

    private interface Result {
        void done(ShizukuBridge.Result r);
    }

    private void exec(final String script, final Runnable before, final Result cb) {
        if (!(ShizukuBridge.running() && ShizukuBridge.granted())) {
            toast("先要在设置页拿到 Shell 授权");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final ShizukuBridge.Result r = ShizukuBridge.exec(script);
                runOnUiThread(new Runnable() {
                    @Override public void run() { cb.done(r); }
                });
            }
        }, "prts-sos").start();
    }

    // ==================== 小工具 ====================

    private String nowStr() {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date());
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

    private View kv(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView a = tv(k, 12, C_GREEN, false);
        a.setLayoutParams(new LinearLayout.LayoutParams(dp(96),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView b = tv(v, 12, C_WHITE, false);
        row.addView(a);
        row.addView(b, new LinearLayout.LayoutParams(0,
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
