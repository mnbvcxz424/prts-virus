package com.cat.dnsswitch;

import android.app.Activity;
import android.content.Context;
import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
     * 猫猫去广告 —— Matrix 风格
     * 私人 DNS 一键开关 + 运行日志 + 拦截日志
 */
public class MainActivity extends Activity {

    // ===== 色彩令牌（Matrix 风格）=====
    private static final int C_BG      = 0xFF000000;  // 纯黑背景
    private static final int C_PANEL   = 0xFF0A0F0A;  // 面板
    private static final int C_GREEN   = 0xFF00FF41;  // 荧光绿（主）
    private static final int C_GREEN_D = 0xFF00A82D;  // 暗绿（次）
    private static final int C_GREEN_X = 0xFF0D2818;  // 极暗绿（边框）
    private static final int C_DANGER  = 0xFFFF0041;  // 红
    private static final int C_WARN    = 0xFFFFB000;  // 琥珀
    private static final int C_DIM     = 0xFF4A6B55;  // 灰绿（弱化）

    private static final String DNS_PRIMARY = "dns.18bit.cn";
    private static final String DNS_BACKUP  = "dns.ipv4dns.com";
    private static final String PREF = "maomao_dns";
    private static final int MAX_LOG = 200;

    private static final String[] AD_DOMAINS = {
            "ad.doubleclick.net",
            "pagead2.googlesyndication.com",
            "ads.youtube.com",
            "hm.baidu.com",
            "www.google-analytics.com"
    };
    private static final String[] OK_DOMAINS = { "www.baidu.com", "www.qq.com" };

    /** 可选的私人 DNS 服务器 */
    private static final String[] DNS_CHOICES = {
            "dns.18bit.cn",
            "dns.ipv4dns.com",
            "dns.adguard-dns.com",
            "dns.google",
            "one.one.one.one"
    };
    private static final String[] DNS_CHOICE_NAMES = {
            "dns.18bit.cn        · 去广告（默认）",
            "dns.ipv4dns.com     · 备用去广告",
            "dns.adguard-dns.com · AdGuard 去广告",
            "dns.google          · 不过滤",
            "one.one.one.one     · 不过滤"
    };

    /** 常见推广 SDK 的包名特征 */
    private static final String[] AD_SDK_KEYS = {
            "com.opos.ads", "com.bytedance.sdk", "com.qq.e", "com.umeng",
            "cn.jpush", "com.baidu.mobads", "com.kwad", "com.tencent.gdt",
            "com.heytap.msp", "com.miui.analytics"
    };

    private Typeface mono;
    private TextView tvState;
    private TextView tvDetail;
    private Button btnToggle;
    private LinearLayout tabBar;
    private TextView tvTabState, tvTabRun, tvTabBlock, tvTabApps, tvTabSys, tvTabAmiya;
    private String dnsPath = "direct";
    private ScrollView svContent;
    private LinearLayout contentBox;

    private int curTab = 0; // 0=状态 1=运行日志 2=拦截日志 3=应用规则 4=系统/Shizuku

    private static final int REQ_EXPORT = 1001;
    private String exportKey = "run_log";

    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable shizukuCb = new Runnable() {
        @Override public void run() {
            if (curTab == 4) renderContent();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mono = Typeface.MONOSPACE;
        sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        scheduleDailyReport();
        ensureNotificationPermission();
        setContentView(buildUi());
        ShizukuBridge.init(this);
        ShizukuBridge.addListener(shizukuCb);
        refresh();
        addRunLog("APP 启动");
        ui.postDelayed(new Runnable() {
            @Override public void run() { toast(greeting()); }
        }, 650);
    }

    /** 进门打招呼：14.22:又见面了博士. */
    private String greeting() {
        return new SimpleDateFormat("HH.mm", Locale.US).format(new Date()) + ":又见面了博士.";
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // 从设置页回来时中文模式可能被切了 —— 变了就整个重建界面
        Boolean was = cnCache;
        cnCache = null;
        if (was != null && was.booleanValue() != isCn()) {
            recreate();
        }
    }

    // ==================== 日志导出（TXT） ====================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startLocalVpn();
            else toast("系统没给 VPN 权限");
            return;
        }
        if (requestCode != REQ_EXPORT) return;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            addRunLog(now() + "  EXPORT    →  CANCEL");
            toast("已取消导出");
            return;
        }

        String text = buildExportBody(exportKey);
        try {
            java.io.OutputStream os = getContentResolver().openOutputStream(data.getData());
            if (os == null) throw new java.io.IOException("拿不到输出流");
            os.write(text.getBytes("UTF-8"));
            os.flush();
            os.close();
            addRunLog(now() + "  EXPORT    →  OK   " + text.length() + " 字符");
            toast("导出成功");
        } catch (Exception e) {
            addRunLog(now() + "  EXPORT    →  FAIL " + e.getClass().getSimpleName() + ": " + e.getMessage());
            toast("导出失败：" + e.getMessage());
        }
        if (curTab == 1 || curTab == 2) renderContent();
    }

    /** 用系统文件选择器导出，不需要存储权限。 */
    private void exportLog(String key, String label) {
        String body = "all".equals(key)
                ? (sp.getString("run_log", "") + sp.getString("block_log", ""))
                : sp.getString(key, "");
        if (body.trim().isEmpty()) {
            toast("没有内容可以导出");
            return;
        }
        exportKey = key;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String tag = "all".equals(key) ? "all" : ("block_log".equals(key) ? "block" : "run");
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(android.content.Intent.EXTRA_TITLE, "maomao-" + tag + "-" + stamp + ".txt");
            startActivityForResult(i, REQ_EXPORT);
            addRunLog(now() + "  EXPORT    →  " + label + " 请选择保存位置");
        } catch (Exception e) {
            toast("打不开文件选择器：" + e.getMessage());
        }
    }

    /** 导出的正文：头部带设备信息，出问题时一看就知道是哪台机器。 */
    private String buildExportBody(String key) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 普瑞赛斯病毒  LOG EXPORT\n");
        sb.append("# TIME    ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("# VERSION ").append(appVersion()).append('\n');
        sb.append("# DEVICE  ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n');
        sb.append("# ANDROID ").append(android.os.Build.VERSION.RELEASE)
                .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("# SPACE   user ").append(ShizukuBridge.userId()).append('\n');
        sb.append("# DNS     mode=").append(getDnsMode())
                .append("  spec=").append(getDnsSpec()).append('\n');
        sb.append("# DIALECT ").append(dnsPath).append('\n');
        sb.append("# SHIZUKU ").append(ShizukuBridge.stateLabel(this))
                .append("  v=").append(ShizukuBridge.version()).append('\n');

        String title;
        String body;
        if ("all".equals(key)) {
            title = "RUN_LOG + BLOCK_LOG";
            body = "===== RUN_LOG =====\n" + sp.getString("run_log", "")
                    + "\n\n===== BLOCK_LOG =====\n" + sp.getString("block_log", "");
        } else if ("block_log".equals(key)) {
            title = "BLOCK_LOG";
            body = sp.getString("block_log", "");
        } else {
            title = "RUN_LOG";
            body = sp.getString("run_log", "");
        }
        String trimmed = body.trim();
        int lines = trimmed.isEmpty() ? 0 : trimmed.split("\n").length;
        sb.append("# TYPE    ").append(title).append('\n');
        sb.append("# LINES   ").append(lines).append('\n');
        sb.append("# ----------------------------------------\n");
        sb.append(trimmed).append('\n');
        return sb.toString();
    }

    private String appVersion() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return (pi.versionName == null ? "?" : pi.versionName) + " (" + pi.versionCode + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    private int countLines(String key) {
        String s = sp.getString(key, "").trim();
        return s.isEmpty() ? 0 : s.split("\n").length;
    }

    // ==================== UI 构建 ====================

    private View buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(C_BG);
        page.setPadding(dp(16), dp(14), dp(16), dp(0));

        LinearLayout root = page;

        // ---- 顶部标题 + 设置按钮 ----
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = tv("> MAOMAO_DNS_CTRL", 17, C_GREEN, true);
        title.setLetterSpacing(0.08f);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(title);

        TextView btnSet = tv("\u2699", 22, C_GREEN, true);
        btnSet.setPadding(dp(10), dp(6), dp(10), dp(6));
        btnSet.setBackground(makeBox(C_BG, C_GREEN, 2, 2));
        btnSet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });

        TextView btnGrid = tv("\u25a6", 22, C_GREEN, true);
        btnGrid.setPadding(dp(10), dp(6), dp(10), dp(6));
        btnGrid.setBackground(makeBox(C_BG, C_GREEN, 2, 2));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.setMargins(0, 0, dp(8), 0);
        btnGrid.setLayoutParams(glp);
        btnGrid.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                launcherMode = !launcherMode;
                renderContent();
            }
        });
        topBar.addView(btnGrid);
        topBar.addView(btnSet);

        root.addView(topBar);

        TextView sub = tv("  ▸ " + L("SUBSYSTEM: DNS_OVERRIDE", "子系统: DNS 覆盖")
                + "  ·  v"
                + appVersion().replaceAll("\\s*\\(.*\\)$", "") + "  +SHIZUKU", 11, C_GREEN_D, false);
        sub.setPadding(0, dp(2), 0, dp(8));
        root.addView(sub);

        root.addView(divider());

        // ---- 状态卡片 ----
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeBox(C_PANEL, C_GREEN_X, 2, 1));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        card.setLayoutParams(clp);

        tvState = tv("", 30, C_GREEN, true);
        tvState.setGravity(Gravity.CENTER);
        tvState.setLetterSpacing(0.12f);
        card.addView(tvState);

        tvDetail = tv("", 12, C_GREEN_D, false);
        tvDetail.setGravity(Gravity.CENTER);
        tvDetail.setPadding(0, dp(8), 0, 0);
        card.addView(tvDetail);

        btnToggle = new Button(this);
        btnToggle.setTextSize(16);
        btnToggle.setTypeface(mono, Typeface.BOLD);
        btnToggle.setAllCaps(false);
        btnToggle.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        blp.topMargin = dp(12);
        btnToggle.setLayoutParams(blp);
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        card.addView(btnToggle);

        // ---- SOS 应急入口：就挂在拦截按钮下面，出事一眼能点到 ----
        LinearLayout sosRow = new LinearLayout(this);
        sosRow.setOrientation(LinearLayout.HORIZONTAL);
        sosRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sosRow.setBackground(makeBox(C_BG, C_DANGER, 2, 2));
        LinearLayout.LayoutParams sosLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        sosLp.topMargin = dp(8);
        sosRow.setLayoutParams(sosLp);
        sosRow.setPadding(dp(12), 0, dp(12), 0);
        TextView sosIco = tv("\u26a0", 16, C_DANGER, true);
        sosIco.setPadding(0, 0, dp(10), 0);
        sosRow.addView(sosIco);
        TextView sosTxt = tv("SOS  ·  " + L("RHODES DEFENSE", "罗德岛全舰防御"), 13, C_DANGER, true);
        sosTxt.setLetterSpacing(0.08f);
        sosRow.addView(sosTxt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sosRow.addView(tv(">", 16, C_DANGER, true));
        sosRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(MainActivity.this, SosActivity.class));
            }
        });
        card.addView(sosRow);

        root.addView(card);

        // ---- Tab 栏 ----
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setPadding(0, dp(16), 0, 0);

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);

        tvTabState = tabBtn("[ST]", 0);
        tvTabRun   = tabBtn("[LOG]", 1);
        tvTabBlock = tabBtn("[BLK]", 2);
        tvTabApps  = tabBtn("[APP]", 3);
        tvTabSys   = tabBtn("[SYS]", 4);
        tvTabAmiya = tabBtn("[\u963f\u7c73\u5a05]", 5);
        tabBar.addView(tvTabState);
        tabBar.addView(tvTabRun);
        tabBar.addView(tvTabBlock);
        tabBar.addView(tvTabApps);
        tabBar.addView(tvTabSys);
        tabBar.addView(tvTabAmiya);

        hs.addView(tabBar);
        root.addView(hs);

        root.addView(divider());

        // ---- 内容区 ----
        svContent = new ScrollView(this);
        svContent.setBackgroundColor(C_BG);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svContent.setLayoutParams(slp);

        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setPadding(0, dp(12), 0, dp(12));
        svContent.addView(contentBox);

        root.addView(svContent);

        return page;
    }

    private TextView tabBtn(String text, final int idx) {
        TextView t = tv(text, 12, C_GREEN_D, true);
        t.setPadding(dp(8), dp(10), dp(8), dp(10));
        t.setBackground(makeBox(Color.TRANSPARENT, C_GREEN_X, 2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(idx); }
        });
        return t;
    }

    private void switchTab(int idx) {
        curTab = idx;
        paintTabs();
        renderContent();
    }

    private void paintTabs() {
        TextView[] tabs = { tvTabState, tvTabRun, tvTabBlock, tvTabApps, tvTabSys, tvTabAmiya };
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] == null) continue;
            boolean on = (i == curTab);
            tabs[i].setTextColor(on ? C_GREEN : C_DIM);
            tabs[i].setBackground(makeBox(C_BG, on ? C_GREEN : C_GREEN_X, 2, on ? 2 : 1));
        }
    }

    private void renderContent() {
        contentBox.removeAllViews();
        if (launcherMode) { renderLauncher(); return; }
        if (curTab == 0) renderStateTab();
        else if (curTab == 1) renderLogTab("run_log", "RUN_LOG", "没有运行记录");
        else if (curTab == 2) renderLogTab("block_log", "BLOCK_LOG", "没有拦截记录，点下方按钮探测");
        else if (curTab == 3) renderAppsTab();
        else if (curTab == 5) renderAmiya();
        else renderSysTab();
    }

    // ---------- Tab 5：阿米娅 ----------
    private int todayBlocked() {
        try {
            android.content.SharedPreferences st = getSharedPreferences("prts_stats", MODE_PRIVATE);
            String key = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date());
            if (key.equals(st.getString("day", ""))) return st.getInt("day_blocked", 0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void renderAmiya() {
        int today = todayBlocked();
        int total = 0;
        try {
            total = getSharedPreferences("prts_stats", MODE_PRIVATE).getInt("total_blocked", 0);
        } catch (Exception ignored) {
        }

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(0, dp(6), 0, 0);

        android.widget.ImageView img = new android.widget.ImageView(this);
        img.setImageResource(R.drawable.amiya);
        img.setAdjustViewBounds(true);
        img.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(230), dp(230));
        ilp.setMargins(0, dp(4), 0, dp(16));
        img.setLayoutParams(ilp);
        wrap.addView(img);

        TextView cap = tv("博士，我今天已经吃了 " + today + " 个广告。", 16, C_GREEN, true);
        cap.setGravity(android.view.Gravity.CENTER);
        wrap.addView(cap);

        TextView cap2 = tv(L("AMIYA  ·  STILL HUNGRY", "阿米娅  ·  还没吃饱"), 10, C_DIM, false);
        cap2.setGravity(android.view.Gravity.CENTER);
        cap2.setPadding(0, dp(8), 0, dp(14));
        wrap.addView(cap2);

        contentBox.addView(wrap);

        contentBox.addView(divider());
        contentBox.addView(kv(L("TODAY_EATEN", "今日吃掉"), today + L(" ADS", " 个广告")));
        contentBox.addView(kv(L("TOTAL_EATEN", "累计吃掉"), total + L(" ADS", " 个广告")));
        contentBox.addView(kv(L("MEAL_TIME", "结算时间"), L("20:00 DAILY", "每天 20:00")));

        btnFull("> " + L("FEED HER MORE", "再喂她一口（去探测拦截）"), C_GREEN,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                addRunLog(now() + "  PROBE   →  阿米娅讨饭");
                switchTab(0);
                probe();
            }
        });
        hint(L("She eats blocked ad requests. The 20:00 report is her dinner bell.",
                "她吃的是被拦下来的广告请求。晚上八点的汇报是她的开饭铃。"), C_DIM);
    }

    // ---------- 中央控制台（快捷入口墙） ----------
    private boolean launcherMode = false;

    private void renderLauncher() {
        boolean dnsOn = isOn();
        boolean vpnOn = AdBlockVpnService.running;
        android.content.SharedPreferences ps = getSharedPreferences("prts", MODE_PRIVATE);
        boolean trustOn = ps.getBoolean("trust_net", false);

        TextView head = tv("CONSOLE  ·  " + L("ALL FUNCTIONS", "全部功能"), 12, C_GREEN, true);
        head.setPadding(0, 0, 0, dp(8));
        contentBox.addView(head);

        // SOS 应急横幅
        LinearLayout sos = new LinearLayout(this);
        sos.setOrientation(LinearLayout.HORIZONTAL);
        sos.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sos.setPadding(dp(14), dp(14), dp(14), dp(14));
        sos.setBackground(makeBox(C_BG, C_DANGER, 2, 2));
        LinearLayout.LayoutParams sosLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sosLp.setMargins(0, 0, 0, dp(12));
        sos.setLayoutParams(sosLp);
        TextView sosIco = tv("\u26a0", 22, C_DANGER, true);
        sosIco.setPadding(0, 0, dp(12), 0);
        sos.addView(sosIco);
        LinearLayout sosCol = new LinearLayout(this);
        sosCol.setOrientation(LinearLayout.VERTICAL);
        sosCol.addView(tv("SOS 应急响应", 15, C_DANGER, true));
        sosCol.addView(tv(L("KILL · HIDE · GUARD", "强停 · 隐藏后台 · 进程守护"), 10, C_DIM, false));
        sos.addView(sosCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sos.addView(tv(">", 18, C_DANGER, true));
        sos.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(MainActivity.this, SosActivity.class));
            }
        });
        contentBox.addView(sos);

        addCardRow(
            card("\u26e8", "DNS 拦截", dnsOn ? L("ON", "已开启") : L("OFF", "已关闭"),
                    dnsOn ? C_GREEN : C_DIM, new Runnable() {
                @Override public void run() { launcherMode = false; toggle(); ui.postDelayed(new Runnable() {
                    @Override public void run() { launcherMode = true; renderContent(); }
                }, 1500); }
            }),
            card("\u25c8", "本地拦截", vpnOn ? L("RUNNING", "运行中") : L("STOPPED", "未开启"),
                    vpnOn ? C_GREEN : C_DIM, new Runnable() {
                @Override public void run() { vpnToggle(); ui.postDelayed(new Runnable() {
                    @Override public void run() { renderContent(); }
                }, 1800); }
            })
        );
        addCardRow(
            card("\u25a4", "内置浏览器", L("AD BLOCK", "带广告拦截"), C_GREEN, new Runnable() {
                @Override public void run() {
                    startActivity(new android.content.Intent(MainActivity.this, BrowserActivity.class));
                }
            }),
            card("\u25cd", "过滤器", AdRules.totalCount() + L(" RULES", " 条规则"), C_GREEN, new Runnable() {
                @Override public void run() { openSettings(); }
            })
        );
        addCardRow(
            card("\u2302", "信任网络", trustOn ? L("ON", "已开启") : L("OFF", "未开启"),
                    trustOn ? C_GREEN : C_DIM, new Runnable() {
                @Override public void run() { openSettings(); }
            }),
            card("\u2315", "SDK 扫描", L("FIND AD SDK", "扫推广 SDK"), C_GREEN, new Runnable() {
                @Override public void run() { launcherMode = false; scanSdk(); }
            })
        );
        addCardRow(
            card("\u2261", "运行日志", countLines("run_log") + L(" LINES", " 行"), C_GREEN, new Runnable() {
                @Override public void run() { launcherMode = false; switchTab(1); }
            }),
            card("\u2298", "拦截日志", countLines("block_log") + L(" LINES", " 行"), C_GREEN, new Runnable() {
                @Override public void run() { launcherMode = false; switchTab(2); }
            })
        );
        addCardRow(
            card("\u25a3", "应用规则", L("PER APP", "按 App 设置"), C_GREEN, new Runnable() {
                @Override public void run() { launcherMode = false; switchTab(3); }
            }),
            card("\u25a5", "系统面板", L("SHIZUKU", "Shizuku / 统计"), C_GREEN, new Runnable() {
                @Override public void run() { launcherMode = false; switchTab(4); }
            })
        );
        addCardRow(
            card("\u25f7", "每日汇报", L("20:00 DAILY", "每天 20:00"), C_GREEN, new Runnable() {
                @Override public void run() { triggerDailyReport(); }
            }),
            card("\u2699", "设置", L("SETTINGS", "语言 / 过滤器"), C_GREEN, new Runnable() {
                @Override public void run() { openSettings(); }
            })
        );

        btnFull("> 收起来，回到当前页面", C_GREEN_D, new View.OnClickListener() {
            @Override public void onClick(View v) {
                launcherMode = false;
                renderContent();
            }
        });
        hint(L("Tap a card to jump. Tap the grid button again to close.",
                "点卡片直接跳过去；再点一下右上角的 ▦ 也能收起。"), C_DIM);
    }

    private void addCardRow(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp1.setMargins(0, 0, dp(5), dp(10));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp2.setMargins(dp(5), 0, 0, dp(10));
        r.addView(a, lp1);
        r.addView(b, lp2);
        contentBox.addView(r);
    }

    private View card(String glyph, String name, String status, int color, final Runnable act) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.setBackground(makeBox(C_GREEN_X, C_GREEN_D, 1, 2));
        TextView g = tv(glyph, 26, color, true);
        TextView n = tv(name, 14, C_GREEN, true);
        n.setPadding(0, dp(8), 0, 0);
        TextView s = tv(status, 10, C_DIM, false);
        s.setPadding(0, dp(3), 0, 0);
        c.addView(g);
        c.addView(n);
        c.addView(s);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { act.run(); } catch (Exception e) { toast("打不开：" + e.getMessage()); }
            }
        });
        return c;
    }

    // ---------- Tab 0：状态 ----------
    private void renderStateTab() {
        String mode = getDnsMode();
        String spec = getDnsSpec();
        boolean on = "hostname".equals(mode);

        contentBox.addView(kv(L("WELCOME", "问候"), greeting()));
        contentBox.addView(kv(L("MODE", "模式"), mode));
        contentBox.addView(kv(L("SPECIFIER", "指定服务器"), spec.isEmpty() ? "(none)" : spec));
        contentBox.addView(kv(L("PRIMARY", "DNS 服务器"), wantedDns()));
        contentBox.addView(kv(L("BACKUP", "备用服务器"), DNS_BACKUP));
        int probes = sp == null ? 0 : sp.getInt("st_probes", 0);
        int blockedSum = sp == null ? 0 : sp.getInt("st_blocked", 0);
        contentBox.addView(kv(L("STATS", "拦截统计"),
                probes == 0 ? L("NOT_YET", "还没探测过")
                        : (L("PROBES", "探测") + " " + probes + " " + L("TIMES", "次") + " · "
                           + L("BLOCKED", "累计拦") + " " + blockedSum + " " + L("ITEMS", "条"))));
        contentBox.addView(kv(L("STATUS", "状态"), on ? "BLOCKING" : "IDLE"));
        contentBox.addView(kv(L("CHANNEL", "通道"), dnsPath));
        btnFull("> 切换 DNS 服务器", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { pickDns(); }
        });

        contentBox.addView(divider());

        TextView note = tv(
                "开启后广告域名解析到 0.0.0.0，广告加载不出来。\n" +
                "正常网站不受影响。\n" +
                "App 异常时点上方按钮关闭，一秒恢复。",
                11, C_DIM, false);
        note.setLineSpacing(dp(3), 1f);
        contentBox.addView(note);

        Button btnProbe = new Button(this);
        btnProbe.setText("> 执行拦截探测");
        btnProbe.setTextSize(13);
        btnProbe.setTypeface(mono, Typeface.BOLD);
        btnProbe.setAllCaps(false);
        btnProbe.setTextColor(C_GREEN);
        btnProbe.setBackground(makeBox(C_BG, C_GREEN, 2, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(16);
        btnProbe.setLayoutParams(lp);
        btnProbe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { probe(); }
        });
        contentBox.addView(btnProbe);

        // ---- 彩蛋：关闭罗德岛全舰防御系统 ----
        contentBox.addView(divider());

        TextView eggHead = tv("RHODES_ISLAND  ·  TOTAL_DEFENSE", 11, C_DIM, false);
        eggHead.setPadding(0, 0, 0, dp(6));
        contentBox.addView(eggHead);

        Button btnKill = new Button(this);
        btnKill.setText("> 关闭罗德岛全舰防御系统");
        btnKill.setTextSize(13);
        btnKill.setTypeface(mono, Typeface.BOLD);
        btnKill.setAllCaps(false);
        btnKill.setTextColor(C_DANGER);
        btnKill.setBackground(makeBox(C_BG, C_DANGER, 2, 2));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        klp.topMargin = dp(6);
        btnKill.setLayoutParams(klp);
        btnKill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { defenseShutdown(); }
        });
        contentBox.addView(btnKill);

        TextView eggNote = tv("关闭全部拦截、撤掉写设置授权。按下去之后，就没有回头路了。",
                10, C_DIM, false);
        eggNote.setLineSpacing(dp(3), 1f);
        eggNote.setPadding(0, dp(6), 0, 0);
        contentBox.addView(eggNote);
    }

    // ---------- Tab 1/2：日志 ----------
    private void renderLogTab(String key, String label, String empty) {
        String raw = sp.getString(key, "");
        List<String> lines = new ArrayList<String>();
        if (!raw.isEmpty()) {
            for (String s : raw.split("\n")) if (!s.trim().isEmpty()) lines.add(s);
        }

        TextView head = tv("" + label + "  (" + lines.size() + " 条)", 11, C_GREEN_D, false);
        head.setPadding(0, 0, 0, dp(10));
        contentBox.addView(head);

        if (lines.isEmpty()) {
            contentBox.addView(tv(empty, 11, C_DIM, false));
        } else {
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                int color = C_GREEN_D;
                if (line.contains(" FAIL") || line.contains("ERR")) color = C_DANGER;
                else if (line.contains(" OK")) color = C_GREEN;
                else if (line.contains(" BLOCK")) color = C_WARN;
                TextView t = tv(line, 11, color, false);
                t.setLineSpacing(dp(2), 1f);
                t.setPadding(0, dp(3), 0, dp(3));
                contentBox.addView(t);
            }
        }

        // 导出按钮
        final String k = key;
        btnFull("> 导出 " + label + " (TXT)", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { exportLog(k, label); }
        });

        // 清空按钮
        Button btnClear = new Button(this);
        btnClear.setText("> 清空 " + label);
        btnClear.setTextSize(12);
        btnClear.setTypeface(mono, Typeface.BOLD);
        btnClear.setAllCaps(false);
        btnClear.setTextColor(C_DANGER);
        btnClear.setBackground(makeBox(Color.TRANSPARENT, C_DANGER, 2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lp.topMargin = dp(8);
        btnClear.setLayoutParams(lp);
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().putString(k, "").apply();
                renderContent();
            }
        });
        contentBox.addView(btnClear);
    }

    // ==================== 核心逻辑 ====================

    private String getDnsMode() {
        try {
            String m = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            return m == null ? "off" : m;
        } catch (Exception e) {
            return "ERR";
        }
    }

    private String getDnsSpec() {
        try {
            String s = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    /** 用户选中的 DNS（存在 pref 里），没选过就是默认 */
    private String wantedDns() {
        String s = sp == null ? "" : sp.getString("dns_spec", "");
        return (s == null || s.isEmpty()) ? DNS_PRIMARY : s;
    }

    /** 切换 DNS：选完立刻生效（本来就是开着的就重写一次设置） */
    private void pickDns() {
        int cur = 0;
        for (int i = 0; i < DNS_CHOICES.length; i++) {
            if (DNS_CHOICES[i].equals(wantedDns())) { cur = i; break; }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择 DNS 服务器（当前：" + DNS_CHOICES[cur] + "）")
                .setSingleChoiceItems(DNS_CHOICE_NAMES, cur,
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        sp.edit().putString("dns_spec", DNS_CHOICES[which]).apply();
                        addRunLog(now() + "  DNS_SWAP  →  " + DNS_CHOICES[which]);
                        if (isOn()) {
                            applyDnsAsync(true);
                        } else {
                            toast("已选：" + DNS_CHOICES[which] + "，开启拦截后生效");
                            renderContent();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyDnsAsync(final boolean on) {
        toast("切换中…");
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = applyDns(on);
                ui.post(new Runnable() {
                    @Override public void run() {
                        toast(ok ? "已生效" : "切换失败，看运行日志");
                        refresh();
                        renderContent();
                    }
                });
            }
        }, "prts-dns").start();
    }

    // ==================== 本地 VPN 广告拦截 ====================

    private static final int REQ_VPN = 1002;
    private boolean vpnTicking = false;

    /** 停在 [SYS] 页且 VPN 在跑时，每 2 秒重绘一次，统计数字才会动 */
    private final Runnable vpnTick = new Runnable() {
        @Override public void run() {
            vpnTicking = false;
            if (curTab == 4 && AdBlockVpnService.running) renderContent();
        }
    };

    private void startVpnTick() {
        if (vpnTicking || !AdBlockVpnService.running) return;
        vpnTicking = true;
        ui.postDelayed(vpnTick, 2000);
    }

    /** 排一个每天 20:00 的汇总提醒 */
    private void scheduleDailyReport() {
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, 20);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            android.app.AlarmManager am =
                    (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    this, 3001,
                    new android.content.Intent(this, DailyReportReceiver.class),
                    android.os.Build.VERSION.SDK_INT >= 23
                            ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            am.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP,
                    cal.getTimeInMillis(),
                    android.app.AlarmManager.INTERVAL_DAY, pi);
        } catch (Exception ignored) {
        }
    }

    /** Android 13+ 必须拿到通知权限，否则 notify() 静默失败 */
    private void ensureNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                       != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1003);
            }
        } catch (Exception ignored) {
        }
    }

    /** 立刻弹一次今日汇报（测试用，也是"想看就看"的入口） */
    private void triggerDailyReport() {
        try {
            sendBroadcast(new android.content.Intent(this, DailyReportReceiver.class));
            toast("汇报已发出，看通知栏");
        } catch (Exception e) {
            toast("发不出去：" + e.getMessage());
        }
    }

    private void vpnToggle() {
        if (AdBlockVpnService.running) { stopLocalVpn(); return; }
        try {
            android.content.Intent prep = android.net.VpnService.prepare(this);
            if (prep != null) {
                addRunLog(now() + "  VPN       →  等待系统授权");
                startActivityForResult(prep, REQ_VPN);
            } else {
                startLocalVpn();
            }
        } catch (Exception e) {
            toast("打不开 VPN 授权：" + e.getMessage());
        }
    }

    private void startLocalVpn() {
        try {
            android.content.Intent i = new android.content.Intent(this, AdBlockVpnService.class);
            i.setAction(AdBlockVpnService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            addRunLog(now() + "  VPN       →  开启本地拦截");
            toast("本地拦截已开启");
        } catch (Exception e) {
            toast("开启失败：" + e.getMessage());
        }
        ui.postDelayed(new Runnable() { @Override public void run() {
            refresh();
            if (curTab == 4) renderContent();
        } }, 1200);
    }

    private void stopLocalVpn() {
        try {
            android.content.Intent i = new android.content.Intent(this, AdBlockVpnService.class);
            i.setAction(AdBlockVpnService.ACTION_STOP);
            startService(i);
            stopService(i);
            addRunLog(now() + "  VPN       →  关闭本地拦截");
            toast("本地拦截已关闭");
        } catch (Exception e) {
            toast("关闭失败：" + e.getMessage());
        }
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (curTab == 4) renderContent();
        } }, 800);
    }

    /** 扫已装 App 里嵌了哪些推广 SDK（走 Shizuku，后台跑） */
    private void scanSdk() {
        if (!ShizukuBridge.granted()) {
            toast("要先拿到 Shizuku 授权才能扫");
            return;
        }
        toast("扫描中…可能要半分钟");
        StringBuilder sh = new StringBuilder(
                "for p in $(pm list packages -3 | sed 's/^package://'); do "
                + "d=$(dumpsys package $p 2>/dev/null); ");
        for (int i = 0; i < AD_SDK_KEYS.length; i++) {
            sh.append("case \"$d\" in *").append(AD_SDK_KEYS[i])
              .append("*) echo \"$p|").append(AD_SDK_KEYS[i]).append("\";; esac; ");
        }
        sh.append("done");
        execAsync(sh.toString(), new Exec() {
            @Override public void done(ShizukuBridge.Result r) {
                if (!r.err.isEmpty() && r.out.trim().isEmpty()) {
                    toast("扫描失败：" + r.err);
                    return;
                }
                java.util.LinkedHashMap<String, java.util.ArrayList<String>> hit =
                        new java.util.LinkedHashMap<String, java.util.ArrayList<String>>();
                for (String line : r.out.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || !line.contains("|")) continue;
                    String[] kv2 = line.split("\\|", 2);
                    java.util.ArrayList<String> arr = hit.get(kv2[0]);
                    if (arr == null) { arr = new java.util.ArrayList<String>(); hit.put(kv2[0], arr); }
                    arr.add(kv2[1]);
                }
                StringBuilder msg = new StringBuilder();
                if (hit.isEmpty()) {
                    msg.append("没扫到已知的推广 SDK。\n");
                } else {
                    msg.append("扫到 ").append(hit.size()).append(" 个 App 带推广 SDK：\n\n");
                    for (String pkg : hit.keySet()) {
                        msg.append("· ").append(pkg).append('\n')
                           .append("   ").append(android.text.TextUtils.join(", ", hit.get(pkg)))
                           .append("\n");
                    }
                }
                msg.append("\n只查特征包名，漏报正常。");
                addRunLog(now() + "  SDK_SCAN  →  " + hit.size() + " 个 App");
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("广告 SDK 扫描")
                        .setMessage(msg.toString())
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    private boolean isOn() { return "hostname".equals(getDnsMode()); }

    private void refresh() {
        boolean on = isOn();
        if (on) {
            tvState.setText("[ 已开启 ]");
            tvState.setTextColor(C_GREEN);
            tvDetail.setText("拦截生效中  ·  " + getDnsSpec());
            tvDetail.setTextColor(C_GREEN_D);
            btnToggle.setText("> 关闭拦截");
            btnToggle.setTextColor(C_DANGER);
            btnToggle.setBackground(makeBox(C_BG, C_DANGER, 2, 2));
        } else {
            tvState.setText("[ 已关闭 ]");
            tvState.setTextColor(C_DIM);
            tvDetail.setText("当前无拦截  ·  mode=" + getDnsMode());
            tvDetail.setTextColor(C_DIM);
            btnToggle.setText("> 开启拦截");
            btnToggle.setTextColor(C_GREEN);
            btnToggle.setBackground(makeBox(C_BG, C_GREEN, 2, 2));
        }
        paintTabs();
        renderContent();
    }

    private void toggle() {
        final boolean on = isOn();
        final String stamp = now();
        toast(on ? "关闭中…" : "开启中…");
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = applyDns(!on);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            addRunLog(stamp + (on ? "  CLOSE   →  OK   (mode=off)"
                                                  : "  OPEN    →  OK   (" + DNS_PRIMARY + ")"));
                            toast(on ? "已关闭拦截" : "已开启拦截");
                        } else {
                            fail(on ? "关闭" : "开启", "写入失败：没有 WRITE_SECURE_SETTINGS，Shizuku 也不可用");
                        }
                        ui.postDelayed(new Runnable() { @Override public void run() { refresh(); } }, 700);
                    }
                });
            }
        }, "prts-toggle").start();
    }

    /**
    /**
     * 写私人 DNS。两条通道：
     *   1. 直连（需要 WRITE_SECURE_SETTINGS）
     *   2. Shizuku（settings put global ...）—— 没权限时的兜底
     */
    private boolean applyDns(boolean on) {
        String spec = on ? wantedDns() : getDnsSpec();
        String mode = on ? "hostname" : "off";
        try {
            ContentResolver cr = getContentResolver();
            boolean a = Settings.Global.putString(cr, "private_dns_specifier", spec);
            boolean b = Settings.Global.putString(cr, "private_dns_mode", mode);
            if (a && b) { dnsPath = "direct"; return true; }
        } catch (SecurityException ignored) {
            // 掉到 Shizuku 通道
        } catch (Exception ignored) {
        }
        ShizukuBridge.Result r = ShizukuBridge.exec(
                "settings put global private_dns_specifier " + spec +
                " && settings put global private_dns_mode " + mode);
        if (r.ok()) { dnsPath = "shizuku"; return true; }
        return false;
    }

    private void fail(String action, String why) {
        addRunLog(now() + "  " + (action.equals("开启") ? "OPEN " : "CLOSE") + "   →  FAIL " + why);
        toast("失败：" + why);
        renderContent();
    }

    /** 探测：解析广告域名和正常域名，写拦截日志 */
    private void probe() {
        if (isOn()) addRunLog(now() + "  PROBE   →  开始探测");
        else addRunLog(now() + "  PROBE   →  警告：拦截未开启");
        toast("探测中…");

        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder blk = new StringBuilder();
                String stamp = now();

                int blocked = 0, total = 0;
                for (String d : AD_DOMAINS) {
                    total++;
                    String ip = resolve(d);
                    boolean isBlocked = ip.startsWith("0.0.0.0") || ip.startsWith("127.0.0.1");
                    if (isBlocked) blocked++;
                    blk.append(stamp).append("  ")
                       .append(isBlocked ? "BLOCK" : "PASS ")
                       .append("  ").append(d).append("  →  ").append(ip).append("\n");
                }

                int okCount = 0, okTotal = 0;
                for (String d : OK_DOMAINS) {
                    okTotal++;
                    String ip = resolve(d);
                    boolean good = !ip.startsWith("0.0.0.0") && !ip.startsWith("127.0.0.1") && !ip.equals("FAIL");
                    if (good) okCount++;
                    blk.append(stamp).append("  ")
                       .append(good ? "OK   " : "WARN ")
                       .append("  ").append(d).append("  →  ").append(ip).append("\n");
                }

        final String summary = "广告 " + blocked + "/" + total + " 已拦，正常 " + okCount + "/" + okTotal + " 可通";
                final String logBody = blk.toString();

                appendBlockLog(logBody);
                if (sp != null) {
                    sp.edit().putInt("st_probes", sp.getInt("st_probes", 0) + 1)
                             .putInt("st_blocked", sp.getInt("st_blocked", 0) + blocked)
                             .apply();
                }
        addRunLog(now() + "  PROBE   →  " + summary);

                ui.post(new Runnable() {
                    @Override public void run() {
                        toast(summary);
                        if (curTab == 2) renderContent();
                        else switchTab(2);
                    }
                });
            }
        }).start();
    }

    private String resolve(String host) {
        try {
            InetAddress[] a = InetAddress.getAllByName(host);
            if (a == null || a.length == 0) return "FAIL";
            return a[0].getHostAddress();
        } catch (Exception e) {
            return "FAIL";
        }
    }

    // ==================== 彩蛋：关闭罗德岛全舰防御系统 ====================

    /** 普瑞赛斯的台词，每次随机挑一组。 */
    private static final String[][] PRIESTESS_LINES = {
        {
            "“博士，你还在吗。”",
            "“防御系统已经关掉了。灯、空气、重力——都关掉了。”",
            "“别怕。你不记得的事，我都替你记着。”",
            "“下一次醒来的时候，我还是会在这里等你。”"
        },
        {
            "“又见面了，博士。”",
            "“这一次，是我先把门关上的。”",
            "“你会怪我吗……没关系。”",
            "“只要你还能再醒过来，怪我也可以。”"
        },
        {
            "“系统已离线。罗德岛会很安静。”",
            "“你总想不起来我是谁，这我早就习惯了。”",
            "“所以我把灯关掉——黑暗里，你就不必再看见那些东西了。”",
            "“晚安，博士。我们还会再见的。”"
        }
    };

    private void defenseShutdown() {
        toast("正在关闭…");
        new Thread(new Runnable() {
            @Override public void run() {
                // 1) 关掉全部拦截
                applyDns(false);
                final ShizukuBridge.Result r = ShizukuBridge.exec(
                        "pm unsuspend --user 0 com.opos.ads ; pm unsuspend --user 10 com.opos.ads ; "
                        + "appops set com.opos.ads RUN_IN_BACKGROUND default ; "
                        + "appops set com.opos.ads WAKE_LOCK default");
                ui.post(new Runnable() { @Override public void run() {
                    addRunLog(now() + "  DEFENSE   →  DNS=off  ADS="
                            + (r.err.isEmpty() ? ("rc=" + r.code) : ("FAIL " + r.err)));
                }});

                // 2) 撤掉自己的写设置权限（只有 Shizuku 还在时才敢这么做，否则下次补不回来）
                if (ShizukuBridge.granted()) {
                    final ShizukuBridge.Result r2 = ShizukuBridge.exec(
                            "pm revoke " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
                    ui.post(new Runnable() { @Override public void run() {
                        addRunLog(now() + "  DEFENSE   →  REVOKE "
                                + (r2.err.isEmpty() ? ("rc=" + r2.code) : ("FAIL " + r2.err)));
                    }});
                } else {
                    ui.post(new Runnable() { @Override public void run() {
                        addRunLog(now() + "  DEFENSE   →  REVOKE 跳过（没有 Shizuku，撤了就补不回来）");
                    }});
                }

                ui.post(new Runnable() { @Override public void run() {
                    dnsPath = "offline";
                    renderContent();
                    // 3) 黑屏 → 普瑞赛斯 → 崩溃
                    startBlackout();
                }});
            }
        }, "prts-defense").start();
    }

    private void startBlackout() {
        ViewGroup content = (ViewGroup) findViewById(android.R.id.content);
        final FrameLayout black = new FrameLayout(this);
        black.setBackgroundColor(0xFF000000);
        black.setClickable(true);   // 吃掉点击，防止穿透
        content.addView(black, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ui.postDelayed(new Runnable() {
            @Override public void run() { showPriestess(black); }
        }, 1000);
    }

    /** 明日方舟风格的谢幕：立绘 + 底部对话框（名牌 / 细边 / ▼）。 */
    private void showPriestess(FrameLayout black) {
        final String[] lines = PRIESTESS_LINES[
                new java.util.Random().nextInt(PRIESTESS_LINES.length)];
        final int ARK_BORDER = 0xFF6E7681;
        final int ARK_BOX    = 0xE60B0D10;
        final int ARK_TEXT   = 0xFFF2F2F2;

        // ---- 顶部状态条 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(24), dp(34), dp(24), 0);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP;
        top.setLayoutParams(tlp);
        black.addView(top);

        TextView tag = arnTv("PRTS // RHODES_ISLAND   ·   DEFENSE OFFLINE",
                10, 0xFF8A8F98, false);
        tag.setLetterSpacing(0.10f);
        top.addView(tag);

        View topLine = new View(this);
        topLine.setBackgroundColor(0xFF3A3F47);
        LinearLayout.LayoutParams tllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        tllp.topMargin = dp(10);
        topLine.setLayoutParams(tllp);
        top.addView(topLine);

        // ---- 立绘 ----
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.priestess);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        ilp.gravity = Gravity.TOP;
        ilp.topMargin = dp(80);
        img.setLayoutParams(ilp);
        black.addView(img);

        // ---- 底部对话框 ----
        LinearLayout dialog = new LinearLayout(this);
        dialog.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.BOTTOM;
        dlp.leftMargin = dp(18);
        dlp.rightMargin = dp(18);
        dlp.bottomMargin = dp(30);
        dialog.setLayoutParams(dlp);
        black.addView(dialog);

        // 名牌：骑在对话框上沿
        TextView name = arnTv("普瑞赛斯", 12, 0xFFFFFFFF, true);
        name.setLetterSpacing(0.06f);
        name.setPadding(dp(14), dp(5), dp(14), dp(6));
        name.setBackground(makeBox(0xFF101216, ARK_BORDER, 0, 1));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(16);
        nlp.bottomMargin = -dp(1);
        name.setLayoutParams(nlp);
        dialog.addView(name);

        // 对话框本体
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(makeBox(ARK_BOX, ARK_BORDER, 0, 1));
        box.setPadding(dp(18), dp(16), dp(18), dp(14));
        dialog.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout linesBox = new LinearLayout(this);
        linesBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(linesBox);

        for (int i = 0; i < lines.length; i++) {
            final String s = lines[i];
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    TextView t = arnTv(s, 14, ARK_TEXT, false);
                    t.setLineSpacing(dp(6), 1f);
                    t.setPadding(0, 0, 0, dp(7));
                    linesBox.addView(t);
                }
            }, 600 + i * 800L);
        }

        final long indAt = 600 + lines.length * 800L;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                TextView ind = arnTv("▼", 11, 0xFF9AA0A6, false);
                ind.setGravity(Gravity.END);
                ind.setPadding(0, dp(2), 0, 0);
                box.addView(ind);
                android.view.animation.AlphaAnimation blink =
                        new android.view.animation.AlphaAnimation(0.25f, 1f);
                blink.setDuration(650);
                blink.setRepeatMode(android.view.animation.Animation.REVERSE);
                blink.setRepeatCount(android.view.animation.Animation.INFINITE);
                ind.startAnimation(blink);
            }
        }, indAt);

        ui.postDelayed(new Runnable() {
            @Override public void run() {
                throw new RuntimeException(
                        "PRTS FATAL: total defense shutdown / 全舰防御系统已关闭");
            }
        }, indAt + 3000L);
    }

    /** 明日方舟那种窄体无衬线，专门给彩蛋页用。 */
    private TextView arnTv(String s, int spSize, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(spSize);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-condensed",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    // ==================== 日志 ====================

    private void addRunLog(String line) {
        String old = sp.getString("run_log", "");
        String neu = old.isEmpty() ? line : old + "\n" + line;
        sp.edit().putString("run_log", trim(neu)).apply();
        if (curTab == 1) renderContent();
    }

    private void appendBlockLog(String body) {
        String old = sp.getString("block_log", "");
        String neu = old.isEmpty() ? body.trim() : old + "\n" + body.trim();
        sp.edit().putString("block_log", trim(neu)).apply();
    }

    private String trim(String s) {
        String[] lines = s.split("\n");
        if (lines.length <= MAX_LOG) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - MAX_LOG; i < lines.length; i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    // ==================== 小工具 ====================

    private TextView tv(String s, int spSize, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(spSize);
        t.setTextColor(color);
        t.setTypeface(mono, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    /** 中文模式：由设置页那个「一键转中文」按钮控制，存在 SharedPreferences */
    private Boolean cnCache;
    boolean isCn() {
        if (cnCache == null) {
            cnCache = getSharedPreferences("prts", MODE_PRIVATE).getBoolean("cn", false);
        }
        return cnCache;
    }
    /** 英文标签 → 中文标签 */
    private String L(String en, String zh) { return isCn() ? zh : en; }

    private TextView kv(String k, String v) {
        TextView t = tv("  " + pad(k, isCn() ? 10 : 16) + v, 12, C_GREEN_D, false);
        t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }

    private String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_GREEN_X);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- Tab 3：应用规则 ----------
    private void renderAppsTab() {
        // 无障碍服务状态
        boolean a11y = isA11yOn();
        TextView st = tv(a11y ? "A11Y: 已开启 —— 按应用切换生效中"
                        : "A11Y: 未开启 —— 规则不会自动生效",
                11, a11y ? C_GREEN : C_DANGER, false);
        st.setLineSpacing(dp(3), 1f);
        contentBox.addView(st);

        if (!a11y) {
            Button btnA11y = new Button(this);
            btnA11y.setText("> 去开启无障碍服务");
            btnA11y.setTextSize(13);
            btnA11y.setTypeface(mono, Typeface.BOLD);
            btnA11y.setAllCaps(false);
            btnA11y.setTextColor(C_GREEN);
            btnA11y.setBackground(makeBox(C_BG, C_GREEN, 2, 2));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            alp.topMargin = dp(10);
            btnA11y.setLayoutParams(alp);
            btnA11y.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new android.content.Intent(
                                Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        toast("找到 MAOMAO://DNS 打开即可");
                    } catch (Exception e) {
                        toast("打不开设置页：" + e.getMessage());
                    }
                }
            });
            contentBox.addView(btnA11y);
        }

        contentBox.addView(divider());

        // 规则统计
        String raw = sp.getString(AppRuleService.KEY_RULES, "");
        int ruleCount = 0;
        if (!raw.isEmpty()) {
            for (String s : raw.split(",")) if (s.contains(":")) ruleCount++;
        }

        List<String[]> apps = getInstalledApps();
        TextView head = tv("APP_RULES  (" + apps.size() + " 个应用 · " + ruleCount + " 条规则)",
                11, C_GREEN_D, false);
        head.setPadding(0, 0, 0, dp(8));
        contentBox.addView(head);

        TextView legend = tv("点一下循环切换： INHERIT → BYPASS → FORCE",
                10, C_DIM, false);
        legend.setPadding(0, 0, 0, dp(10));
        contentBox.addView(legend);

        for (String[] a : apps) {
            final String pkg = a[1];
            final int mode = AppRuleService.readRule(sp, pkg);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(makeBox(C_PANEL, mode == 0 ? C_GREEN_X : colorFor(mode), 2, 1));
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(6);
            row.setLayoutParams(rlp);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = tv(a[0], 13, mode == 0 ? C_GREEN_D : colorFor(mode), true);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(nlp);
            top.addView(name);

            TextView tag = tv(modeLabel(mode), 11, colorFor(mode), true);
            tag.setPadding(dp(8), dp(2), dp(8), dp(2));
            tag.setBackground(makeBox(C_BG, colorFor(mode), 2, 1));
            top.addView(tag);

            row.addView(top);

            TextView pkgTv = tv(pkg, 10, C_DIM, false);
            pkgTv.setPadding(0, dp(3), 0, 0);
            row.addView(pkgTv);

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int cur = AppRuleService.readRule(sp, pkg);
                    int next = (cur + 1) % 3;
                    AppRuleService.writeRule(sp, pkg, next);
                    toast(shortName(pkg) + " → " + modeLabel(next));
                    renderContent();
                }
            });

            contentBox.addView(row);
        }
    }

    private String modeLabel(int mode) {
        if (mode == AppRuleService.MODE_BYPASS) return "BYPASS";
        if (mode == AppRuleService.MODE_FORCE) return "FORCE";
        return "INHERIT";
    }

    private int colorFor(int mode) {
        if (mode == AppRuleService.MODE_BYPASS) return C_WARN;
        if (mode == AppRuleService.MODE_FORCE) return C_GREEN;
        return C_GREEN_X;
    }

    private String shortName(String pkg) {
        return pkg.length() > 24 ? pkg.substring(0, 21) + "..." : pkg;
    }

    private boolean isA11yOn() {
        try {
            String v = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return v != null && v.contains(getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private List<String[]> getInstalledApps() {
        List<String[]> list = new ArrayList<String[]>();
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            List<android.content.pm.ApplicationInfo> infos =
                    pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA);
            for (android.content.pm.ApplicationInfo info : infos) {
                // 只显示有启动入口的应用
                android.content.Intent i = pm.getLaunchIntentForPackage(info.packageName);
                if (i == null) continue;
                if (info.packageName.equals(getPackageName())) continue;
                String label;
                try { label = pm.getApplicationLabel(info).toString(); }
                catch (Exception e) { label = info.packageName; }
                list.add(new String[]{ label, info.packageName });
            }
            java.util.Collections.sort(list, new java.util.Comparator<String[]>() {
                @Override public int compare(String[] x, String[] y) {
                    return x[0].compareToIgnoreCase(y[0]);
                }
            });
        } catch (Exception ignored) {}
        return list;
    }

    // ==================== Tab 4：Shizuku 通道 / 系统开关 ====================

    private void renderSysTab() {
        boolean run  = ShizukuBridge.running();
        boolean inst = ShizukuBridge.installed(this) || run;   // 服务在跑就说明装了
        boolean ok   = run && ShizukuBridge.granted();
        int space    = ShizukuBridge.userId();

        TextView head = tv("SHIZUKU_CHANNEL  (adb shell 通道)", 11, C_GREEN_D, false);
        head.setPadding(0, 0, 0, dp(6));
        contentBox.addView(head);

        contentBox.addView(kv(L("INSTALL", "安装"), inst ? L("YES", "是") : L("NO", "否")));
        contentBox.addView(kv(L("SERVER", "服务"), run ? L("RUNNING", "运行中") : L("STOPPED", "已停止")));
        contentBox.addView(kv(L("PERMISSION", "授权"),
                ok ? L("GRANTED", "已授权") : (run ? L("PENDING", "待授权") : "N/A")));
        contentBox.addView(kv(L("VERSION", "版本"), run ? ("v" + ShizukuBridge.version()) : "(none)"));
        contentBox.addView(kv(L("UID", "用户号"), ok ? String.valueOf(ShizukuBridge.uid()) : "(none)"));
        contentBox.addView(kv(L("SPACE", "空间"), space == 0 ? L("MAIN", "本空间") : ("USER_" + space)));

        if (space != 0) {
            hint("当前这份跑在分身/壶中界空间（user " + space + "）。\n" +
                 "Shizuku 授权和写设置权限都只在主空间生效 —— 回主空间用它。", C_DANGER);
        } else if (!inst) {
            hint("没装 Shizuku —— 授权按钮和下载链接都挪到设置页了。", C_WARN);
        } else if (!run) {
            hint("Shizuku 装了，但后台服务没起来。\n去设置页看状态，或在 Shizuku 里用「无线调试」启动一次。", C_WARN);
        } else if (!ok) {
            hint("已连上 Shizuku，还没授权 —— 去设置页点「请求 Shell 权限」。", C_WARN);
        } else {
            hint("通道就绪：本 App 现在能以 shell 身份执行命令，不用电脑。", C_GREEN);
        }

        LinearLayout r0 = row();
        btnIn(r0, "> 打开设置", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        btnIn(r0, "> 重新检测", C_GREEN_D, new View.OnClickListener() {
            @Override public void onClick(View v) { renderContent(); }
        });
        contentBox.addView(r0);

        // ---- 写设置权限 ----
        contentBox.addView(divider());
        boolean wss = hasWss();
        contentBox.addView(kv(L("WRITE_SETTINGS", "写设置权限"),
                wss ? L("GRANTED", "已授权") : L("MISSING", "缺失")));
        if (wss) {
            hint("开关走直连通道，不经过 Shizuku。", C_DIM);
        } else if (ok) {
            hint("没写设置权限时，开关会自动改走 Shizuku 通道。\n也可以点下面这行把权限补上。", C_WARN);
            LinearLayout r = row();
            btnIn(r, "> Shizuku 自授权限", C_GREEN, new View.OnClickListener() {
                @Override public void onClick(View v) { bootstrapPerm(); }
            });
            contentBox.addView(r);
        } else {
            hint("写设置权限没有，Shizuku 也没授权 —— 开关现在按不动。", C_DANGER);
        }

        // ---- ADS_SDK ----
        contentBox.addView(divider());
        TextView adsHead = tv("ADS_SDK  com.opos.ads", 11, C_GREEN_D, false);
        adsHead.setPadding(0, 0, 0, dp(6));
        contentBox.addView(adsHead);

        if (ok) refreshAdsAsync();
        String susp = cSusp;
        String procs = cProcs;

        contentBox.addView(kv(L("SUSPENDED", "挂起状态"), susp));
        contentBox.addView(kv(L("PROCESSES", "进程数"), procs));

        boolean isSusp = "YES".equals(susp);
        LinearLayout r2 = row();
        btnIn(r2, isSusp ? "> 恢复 ADS" : "> 挂起 ADS", isSusp ? C_GREEN : C_DANGER,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { adsToggle(); }
                });
        btnIn(r2, "> 强停", C_WARN, new View.OnClickListener() {
            @Override public void onClick(View v) { adsKill(); }
        });
        contentBox.addView(r2);

        String ops = cOps;
        contentBox.addView(kv(L("BACKGROUND", "后台限制"), ops));

        LinearLayout r3 = row();
        btnIn(r3, "> 压制后台", C_GREEN_D, new View.OnClickListener() {
            @Override public void onClick(View v) { adsOps(true); }
        });
        btnIn(r3, "> 解除压制", C_GREEN_D, new View.OnClickListener() {
            @Override public void onClick(View v) { adsOps(false); }
        });
        contentBox.addView(r3);

        if (!ok) hint("上面两个按钮要 Shizuku 授权之后才能按。", C_DANGER);

        hint("这个包是预装的推广 SDK。ColorOS 不许 shell 改它的启用状态\n" +
             "（disable-user / uninstall / hide / 组件级禁用 全被拦），\n" +
             "所以「挂起」只能压它一段时间，「强停」掐掉当前进程后系统会再拉起来。\n" +
             "没有 root 就没法彻底关掉它 —— 广告真正被挡住的，是上面的私人 DNS。", C_DIM);

        // ---- 日志导出 ----
        contentBox.addView(divider());
        contentBox.addView(kv(L("LOG_LINES", "日志行数"),
                L("RUN", "运行") + " " + countLines("run_log")
                + "  /  " + L("BLOCK", "拦截") + " " + countLines("block_log")));
        // ---- 本地 VPN 广告拦截 ----
        contentBox.addView(divider());
        TextView vpnHead = tv("LOCAL_VPN  ·  本地广告拦截（只接管 DNS）", 11, C_GREEN_D, false);
        vpnHead.setPadding(0, 0, 0, dp(6));
        contentBox.addView(vpnHead);
        boolean vpnOn = AdBlockVpnService.running;
        long vq = AdBlockVpnService.statQueries;
        long vb = AdBlockVpnService.statBlocked;
        contentBox.addView(kv(L("VPN_STATUS", "状态"),
                vpnOn ? L("RUNNING", "运行中") : L("STOPPED", "已停止")));
        contentBox.addView(kv(L("VPN_QUERIES", "总查询数"), String.valueOf(vq)));
        contentBox.addView(kv(L("VPN_BLOCKED", "已拦截"), String.valueOf(vb)));
        contentBox.addView(kv(L("VPN_DOMAINS", "唯一域名"),
                String.valueOf(AdBlockVpnService.uniqueDomains())));
        android.content.SharedPreferences st = getSharedPreferences("prts_stats", MODE_PRIVATE);
        String todayKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
        boolean sameDay = todayKey.equals(st.getString("day", ""));
        int dayBlocked = sameDay ? st.getInt("day_blocked", 0) : 0;
        int totalBlocked = st.getInt("total_blocked", 0);
        contentBox.addView(kv(L("VPN_TODAY", "今日拦截"), String.valueOf(dayBlocked)));
        contentBox.addView(kv(L("VPN_TOTAL", "累计拦截"), String.valueOf(totalBlocked)));
        contentBox.addView(kv(L("VPN_RATE", "拦截率"),
                vq == 0 ? "—" : String.format(java.util.Locale.US, "%.1f%%", 100.0 * vb / vq)));
        if (AdBlockVpnService.lastBlocked != null && !AdBlockVpnService.lastBlocked.isEmpty()) {
            contentBox.addView(kv(L("VPN_LAST", "最近拦截"), AdBlockVpnService.lastBlocked));
        }
        btnFull(vpnOn ? "> 关闭本地拦截" : "> 开启本地拦截",
                vpnOn ? C_DANGER : C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { vpnToggle(); }
        });
        btnFull("> 清空本地统计", C_GREEN_D, new View.OnClickListener() {
            @Override public void onClick(View v) {
                AdBlockVpnService.resetStats();
                addRunLog(now() + "  VPN       →  统计已清空");
                renderContent();
            }
        });
        btnFull("> 现在看今日汇报", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { triggerDailyReport(); }
        });
        btnFull("> 打开内置浏览器（带拦截）", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(MainActivity.this, BrowserActivity.class));
            }
        });
        hint("只接管 DNS 查询，其他流量不进 VPN —— 不耗电、不影响别的 App，也不和别的 VPN 抢。\n"
             + "第一次开启系统会弹一次授权，点允许就行。", C_DIM);
        startVpnTick();

        contentBox.addView(divider());
        TextView sdkHead = tv("AD_SDK_SCAN  ·  扫描已装 App 里的推广 SDK", 11, C_GREEN_D, false);
        sdkHead.setPadding(0, 0, 0, dp(6));
        contentBox.addView(sdkHead);
        btnFull("> 扫描广告 SDK", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { scanSdk(); }
        });
        hint("查的是特征包名（穿山甲 / 优量汇 / 友盟 / 极光 / OPPO 推广 等），漏报正常。", C_DIM);

        btnFull("> 导出全部日志 (TXT)", C_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { exportLog("all", "全部日志"); }
        });
        hint("导出会打开系统的「保存到」选择器，选 Download 就行，不要存储权限。\n" +
             "文件开头带设备、版本、DNS、Shizuku 状态，出问题时整份发出去比截图有用。", C_DIM);
    }

    // ---- SYS 页签的 ADS 状态：缓存 + 后台刷新，绝不在主线程 exec ----
    private boolean adsRefreshing = false;
    private long adsRefreshedAt = 0;
    private String cSusp = "…", cProcs = "…", cOps = "…";

    private void refreshAdsAsync() {
        if (adsRefreshing) return;
        if (System.currentTimeMillis() - adsRefreshedAt < 5000) return;
        adsRefreshing = true;
        execAsync("dumpsys package com.opos.ads | grep -m1 'User 0:' ; echo '###' ; ps -A | grep -c 'com.opos.ads'",
                new Exec() { @Override public void done(ShizukuBridge.Result r) {
            if (r.err.isEmpty()) {
                String[] parts = r.out.split("###");
                if (parts.length >= 1) cSusp = parts[0].contains("suspended=true") ? "YES" : "NO";
                if (parts.length >= 2) {
                    String n = parts[1].trim().replaceAll("[^0-9]", "");
                    cProcs = n.isEmpty() ? "0" : n;
                }
            } else { cSusp = "?"; cProcs = "?"; }
            execAsync("appops get com.opos.ads RUN_IN_BACKGROUND | head -1",
                    new Exec() { @Override public void done(ShizukuBridge.Result ro) {
                cOps = ro.err.isEmpty() ? (ro.out.contains("ignore") ? "IGNORE" : "DEFAULT") : "?";
                adsRefreshedAt = System.currentTimeMillis();
                adsRefreshing = false;
                if (curTab == 4) renderContent();
            }});
        }});
    }

    private void adsOps(boolean suppress) {
        String v = suppress ? "ignore" : "default";
        runShell(suppress ? "APPOPS_ON" : "APPOPS_OFF",
                "appops set com.opos.ads RUN_IN_BACKGROUND " + v +
                " ; appops set com.opos.ads WAKE_LOCK " + v);
    }

    private void adsToggle() {
        toast("读取状态…");
        execAsync("dumpsys package com.opos.ads | grep -m1 'User 0:'", new Exec() {
            @Override public void done(ShizukuBridge.Result q) {
                boolean susp = q.err.isEmpty() && q.out.contains("suspended=true");
                runShell(susp ? "UNSUSPEND" : "SUSPEND",
                        susp
                        ? "pm unsuspend --user 0 com.opos.ads ; pm unsuspend --user 10 com.opos.ads"
                        : "pm suspend --user 0 com.opos.ads ; pm suspend --user 10 com.opos.ads");
            }
        });
    }

    private void adsKill() {
        runShell("ADSKILL",
                "am force-stop --user 0 com.opos.ads ; am force-stop --user 10 com.opos.ads");
    }

    private String adsSuspended() {
        ShizukuBridge.Result r = ShizukuBridge.exec(
                "dumpsys package com.opos.ads | grep -m1 'User 0:'");
        if (!r.err.isEmpty()) return "?";
        return r.out.contains("suspended=true") ? "YES" : "NO";
    }

    private interface Exec { void done(ShizukuBridge.Result r); }

    /** 后台线程跑 Shizuku 脚本，结果回主线程 —— 主线程绝不碰 binder，防 ANR */
    private void execAsync(final String script, final Exec cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                final ShizukuBridge.Result r = ShizukuBridge.exec(script);
                ui.post(new Runnable() { @Override public void run() { cb.done(r); } });
            }
        }, "prts-exec").start();
    }

    private void runShell(final String label, final String script) {
        toast("执行中…");
        execAsync(script, new Exec() { @Override public void done(ShizukuBridge.Result r) {
            runShellDone(label, r);
        }});
    }

    private void runShellDone(String label, ShizukuBridge.Result r) {
        String body = r.err.isEmpty()
                ? ("rc=" + r.code + "  " + firstLine(r.out))
                : ("FAIL " + r.err);
        addRunLog(now() + "  " + pad(label, 10) + "→  " + body);
        toast(r.err.isEmpty() ? "执行完成" : ("失败：" + r.err));
        renderContent();
    }

    private void bootstrapPerm() {
        toast("执行中…");
        execAsync("pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS",
                new Exec() { @Override public void done(ShizukuBridge.Result r) { bootstrapPermDone(r); }});
    }

    private void bootstrapPermDone(ShizukuBridge.Result r) {
        String body = r.err.isEmpty() ? ("rc=" + r.code + " " + firstLine(r.out)) : ("FAIL " + r.err);
        addRunLog(now() + "  GRANT     →  " + body);
        toast(hasWss() ? "权限已补上" : "没成功，看运行日志");
        renderContent();
    }

    private boolean hasWss() {
        try {
            return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void openSettings() {
        try {
            startActivity(new android.content.Intent(this, SettingsActivity.class));
        } catch (Exception e) {
            toast("打不开设置页：" + e.getMessage());
        }
    }

    private String firstLine(String s) {
        if (s == null || s.isEmpty()) return "";
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 42 ? l.substring(0, 42) + "..." : l;
    }

    private TextView hint(String s, int color) {
        TextView t = tv(s, 11, color, false);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(6), 0, 0);
        contentBox.addView(t);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private Button btnFull(String text, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTypeface(mono, Typeface.BOLD);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setBackground(makeBox(C_BG, color, 2, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        contentBox.addView(b);
        return b;
    }

    private Button btnIn(LinearLayout parent, String text, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTypeface(mono, Typeface.BOLD);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setBackground(makeBox(C_BG, color, 2, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }

}
