package com.cat.dnsswitch;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页 —— Matrix 风格
 *
 * 权限请求都收在这里：
 *   ROOT   （按钮在上，还没做）
 *   SHELL  （Shizuku 授权，按钮在下，再下面是下载链接）
 * 最底下是版本号和作者。
 */
public class SettingsActivity extends Activity {

    private static final int C_BG      = 0xFF000000;
    private static final int C_PANEL   = 0xFF0A0F0A;
    private static final int C_GREEN   = 0xFF00FF41;
    private static final int C_GREEN_D = 0xFF00A82D;
    private static final int C_GREEN_X = 0xFF0D2818;
    private static final int C_DANGER  = 0xFFFF0041;
    private static final int C_WARN    = 0xFFFFB000;
    private static final int C_DIM     = 0xFF4A6B55;
    private static final int C_WHITE   = 0xFFFFFFFF;
    private static final int C_ALERT   = 0xFFFF5A6E;   // 警示红：比 C_DANGER 更亮，弱色觉也能看清

    private static final String SHIZUKU_URL = "https://shizuku.rikka.app/download/";

    private Typeface mono;
    private LinearLayout box;
    private String verName = "?";
    private int verCode = 0;

    private final Runnable onShizuku = new Runnable() {
        @Override public void run() { render(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mono = Typeface.MONOSPACE;
        readVersion();
        setContentView(buildUi());
        ShizukuBridge.init(this);
        ShizukuBridge.addListener(onShizuku);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
        ShizukuBridge.removeListener(onShizuku);
        super.onDestroy();
    }

    private void readVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            verName = pi.versionName == null ? "?" : pi.versionName;
            verCode = pi.versionCode;
        } catch (Exception ignored) {
        }
    }

    // ==================== 骨架 ====================

    private View buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(C_BG);
        page.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = tv("> SETTINGS", 17, C_GREEN, true);
        title.setLetterSpacing(0.08f);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(title);

        TextView back = tv("[BACK]", 12, C_GREEN, true);
        back.setPadding(dp(10), dp(6), dp(10), dp(6));
        back.setBackground(makeBox(C_BG, C_GREEN_X, 2, 1));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back);
        page.addView(top);

        TextView sub = tv("  ▸ SUBSYSTEM: CONTROL_PANEL  //  v" + verName, 11, C_GREEN_D, false);
        sub.setPadding(0, dp(2), 0, dp(6));
        page.addView(sub);

        page.addView(divider());

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(10));
        sv.addView(box);
        page.addView(sv);

        return page;
    }

    // ==================== 内容 ====================

    private void render() {
        box.removeAllViews();

        boolean run  = ShizukuBridge.running();
        boolean inst = ShizukuBridge.installed(this) || run;
        boolean ok   = run && ShizukuBridge.granted();
        int space    = ShizukuBridge.userId();

        // ---------- ROOT ----------
        box.addView(section("// ROOT"));
        box.addView(kv("STATUS", "NOT_AVAILABLE"));
        box.addView(hint("还在画图纸。点了会得到一句实话。", C_DIM));
        btn("> 请求 ROOT 权限", C_DANGER, new View.OnClickListener() {
            @Override public void onClick(View v) {
                toast("我还没有做好呢，想什么呢？喵。");
            }
        });

        // ---------- SHELL / SHIZUKU ----------
        box.addView(divider());
        box.addView(section("// SHELL  (SHIZUKU)"));
        box.addView(kv("INSTALL", inst ? "YES" : "NO"));
        box.addView(kv("SERVER", run ? "RUNNING" : "STOPPED"));
        box.addView(kv("PERMISSION", ok ? "GRANTED" : (run ? "PENDING" : "N/A")));
        box.addView(kv("VERSION", run ? ("v" + ShizukuBridge.version()) : "(none)"));
        box.addView(kv("UID", ok ? String.valueOf(ShizukuBridge.uid()) : "(none)"));
        box.addView(kv("SPACE", space == 0 ? "MAIN" : ("USER_" + space)));

        if (space != 0) {
            box.addView(hint("当前这份跑在分身/壶中界空间（user " + space + "）。\n" +
                    "Shizuku 授权和写设置权限都只在主空间生效 —— 回主空间再授权。", C_DANGER));
            btn("> 请求 Shell 权限", C_DIM, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    toast("分身空间里授权不了喵，回主空间用");
                }
            });
        } else if (!inst) {
            box.addView(hint("没装 Shizuku。先把下面的下载链接点开装一个。", C_WARN));
            btn("> 请求 Shell 权限", C_DIM, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    toast("还没装 Shizuku，点了也没用喵");
                }
            });
        } else if (!run) {
            box.addView(hint("Shizuku 装了，后台服务没起来。\n在 Shizuku 里用「无线调试」启动一次再来。", C_WARN));
            btn("> 请求 Shell 权限", C_GREEN, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ShizukuBridge.request();
                    toast("先把 Shizuku 服务启动起来喵");
                }
            });
        } else if (!ok) {
            box.addView(hint("已连上 Shizuku，还差一步授权。\n弹窗是 Shizuku 自己弹的，点「允许」就行。", C_WARN));
            btn("> 请求 Shell 权限", C_GREEN, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ShizukuBridge.request();
                    toast("等 Shizuku 弹窗，点允许");
                }
            });
        } else {
            box.addView(hint("通道就绪：本 App 现在能以 shell 身份执行命令。", C_GREEN));
            btn("> 请求 Shell 权限", C_GREEN_D, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ShizukuBridge.request();
                    toast("已经是授权状态了喵");
                }
            });
        }

        // 下载链接放在权限按钮下面
        btn("> 下载 Shizuku", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl(SHIZUKU_URL); }
        });
        TextView url = tv("  " + SHIZUKU_URL, 10, C_DIM, false);
        url.setPadding(0, dp(4), 0, dp(4));
        box.addView(url);

        // ---------- 使用说明 ----------
        box.addView(divider());
        box.addView(section("// 使用说明"));

        // 警示：红框 + 黑底 + 红字（不靠颜色区分，边框和文字都是同一处提醒）
        box.addView(warnBox("⚠ 先看这段",
                "一、没有 root 的手机，系统预装的推广 SDK 关不掉，只能压制。\n" +
                "二、开启拦截后，少数 App 的图片、视频、登录可能加载失败。\n" +
                "三、遇到异常立刻点「关闭拦截」，一秒恢复，再回来查 [LOG]。\n" +
                "四、本 App 只在主空间有效；分身、壶中界里授权不了。"));

        itemHead("① DNS 拦截");
        box.addView(whiteBox(
                "首页那颗大按钮就是总开关。\n" +
                "开启后广告域名解析到 0.0.0.0，广告加载不出来，正常网站不受影响。\n" +
                "需要「写系统设置」权限；没有的话会自动改走 Shizuku 通道，看 [SYS] 页的 CHANNEL。"));

        itemHead("② 拦截探测");
        box.addView(whiteBox(
                "状态页底部「执行拦截探测」会解析 5 个广告域名和 2 个正常域名。\n" +
                "结果写进 [BLK] 拦截日志：显示 0.0.0.0 就是被拦住了。"));

        itemHead("③ 应用规则");
        box.addView(whiteBox(
                "在 [APP] 页点任意应用，循环切换：\n" +
                "INHERIT（跟随总开关）→ BYPASS（这个应用放行）→ FORCE（这个应用强制拦）。\n" +
                "要先生成「无障碍服务」，规则才会自动生效。"));

        itemHead("④ Shizuku 通道");
        box.addView(whiteBox(
                "装上 Shizuku 并启动服务，再回到上面点「请求 Shell 权限」。\n" +
                "授权后 App 就能以 shell 身份执行 pm / settings 这类命令，不用连电脑。"));

        itemHead("⑤ ADS 压制");
        box.addView(whiteBox(
                "[SYS] 页可以挂起、强停、压制后台。\n" +
                "挂起能挡住普通启动，强停立刻掐掉进程，压制后台是关掉它的后台活动和唤醒。\n" +
                "系统会在 1~2 秒内把它重新拉起来 —— 这是 ColorOS 的行为，不是 App 没生效。"));

        itemHead("⑥ 日志");
        box.addView(whiteBox(
                "[LOG] 是操作记录，出错先看这里；[BLK] 是拦截记录。\n" +
                "两个页面底部都有清空按钮，各保留最近 200 条。"));

        // ---------- 页脚 ----------
        box.addView(divider());

        TextView foot = tv("普瑞赛斯病毒", 15, C_GREEN, true);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(12), 0, 0);
        box.addView(foot);

        TextView ver = tv("VERSION   v" + verName + "  (" + verCode + ")", 11, C_DIM, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(6), 0, 0);
        box.addView(ver);

        TextView author = tv("AUTHOR    猫猫  ·  愚鸟", 13, C_GREEN, true);
        author.setGravity(Gravity.CENTER);
        author.setPadding(0, dp(6), 0, dp(24));
        box.addView(author);
    }

    // ==================== 小工具 ====================

    private TextView section(String s) {
        TextView t = tv(s, 11, C_GREEN_D, false);
        t.setPadding(0, dp(4), 0, dp(6));
        return t;
    }

    /** 警示框：红框 + 黑底 + 红字。边框和文字同色，不靠颜色深浅也能看见。 */
    private LinearLayout warnBox(String title, String body) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(makeBox(C_BG, C_DANGER, 2, 3));
        v.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        v.setLayoutParams(lp);

        TextView t = tv(title, 14, C_ALERT, true);
        v.addView(t);

        TextView b = tv(body, 12, C_ALERT, true);
        b.setLineSpacing(dp(4), 1f);
        b.setPadding(0, dp(8), 0, 0);
        v.addView(b);
        return v;
    }

    /** 说明框：黑底 + 白字，外面一层细绿边做分组。 */
    private LinearLayout whiteBox(String body) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(makeBox(0xFF000000, C_GREEN_X, 2, 1));
        v.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);

        TextView t = tv(body, 11, C_WHITE, false);
        t.setLineSpacing(dp(4), 1f);
        v.addView(t);
        return v;
    }

    private void itemHead(String s) {
        TextView t = tv(s, 12, C_WHITE, true);
        t.setPadding(0, dp(14), 0, dp(2));
        box.addView(t);
    }

    private TextView kv(String k, String v) {
        TextView t = tv("  " + pad(k, 16) + v, 12, C_GREEN_D, false);
        t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }

    private TextView hint(String s, int color) {
        TextView t = tv(s, 11, color, false);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private Button btn(String text, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setTypeface(mono, Typeface.BOLD);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setBackground(makeBox(C_BG, color, 2, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        box.addView(b);
        return b;
    }

    private TextView tv(String s, int spSize, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(spSize);
        t.setTextColor(color);
        t.setTypeface(mono, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_GREEN_X);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable makeBox(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("打不开浏览器：" + url);
        }
    }
}
