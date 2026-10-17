package com.cat.dnsswitch;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;

/**
 * 内置浏览器 · 柔和圆角风格（浅蓝白底 + 白色卡片 + 圆角）
 * 靠 shouldInterceptRequest 把广告域名的子资源掐掉。
 */
public class BrowserActivity extends Activity {

    // 柔和配色
    private static final int C_PAGE    = 0xFFF4F7FE;   // 页面底：很浅的蓝白
    private static final int C_CARD    = 0xFFFFFFFF;   // 卡片白
    private static final int C_ACCENT  = 0xFF3D7BFF;   // 主蓝
    private static final int C_ACCENT2 = 0xFF6B9BFF;   // 次蓝
    private static final int C_SOFT    = 0xFFEAF1FF;   // 浅蓝底（选中/胶囊）
    private static final int C_TEXT    = 0xFF1F2A44;   // 主文字
    private static final int C_TEXT2   = 0xFF8093B0;   // 次文字
    private static final int C_LINE    = 0xFFE6ECF7;   // 分割线

    private WebView web;
    private EditText bar;
    private TextView stat;
    private TextView engineBtn;
    private boolean[] groups;
    private long blocked = 0;
    private long scolded = 0;

    private String seName = "百度";
    private String seUrl  = "https://www.baidu.com/s?wd=%s";

    private static final String[][] ENGINES = {
            { "百度",      "https://www.baidu.com/s?wd=%s" },
            { "搜狗",      "https://www.sogou.com/web?query=%s" },
            { "360 搜索",  "https://www.so.com/s?q=%s" },
            { "必应（中国）", "https://cn.bing.com/search?q=%s" },
            { "神马",      "https://m.sm.cn/s?q=%s" },
            { "Google",   "https://www.google.com/search?q=%s" },
            { "Bing",     "https://www.bing.com/search?q=%s" },
            { "DuckDuckGo", "https://duckduckgo.com/?q=%s" },
            { "Yahoo",    "https://search.yahoo.com/search?p=%s" },
            { "Brave",    "https://search.brave.com/search?q=%s" },
            { "Ecosia",   "https://www.ecosia.org/search?q=%s" }
    };

    private static final String[] ADULT = {
            "pornhub", "xvideos", "xnxx", "xhamster", "youporn", "spankbang",
            "redtube", "beeg", "hqporner", "eporner", "txxx", "tube8",
            "thumbzilla", "hanime", "nhentai", "e-hentai", "exhentai",
            "hitomi.la", "jmcomic", "18comic", "madou", "91porn", "91av",
            "t66y", "sehuatang", "kissjav", "javdb", "javbus", "missav",
            "supjav", "jable", "avmoo", "avsox", "onlyfans", "fansly",
            "蜜桃", "麻豆", "禁漫"
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        groups = loadGroups();
        AdRules.setEnabled(groups);
        loadEngine();
        setContentView(buildUi());
        String start = getIntent().getStringExtra("url");
        if (start == null || start.isEmpty()) start = "https://www.baidu.com";
        load(start);
    }

    private boolean[] loadGroups() {
        SharedPreferences sp = getSharedPreferences("prts", MODE_PRIVATE);
        boolean[] g = new boolean[4];
        for (int i = 0; i < 4; i++) g[i] = sp.getBoolean(AdRules.GROUP_KEYS[i], true);
        return g;
    }

    private void loadEngine() {
        SharedPreferences sp = getSharedPreferences("prts", MODE_PRIVATE);
        seName = sp.getString("se_name", "百度");
        seUrl  = sp.getString("se_url", "https://www.baidu.com/s?wd=%s");
    }

    private boolean isAdult(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        for (int i = 0; i < ADULT.length; i++) {
            if (u.contains(ADULT[i])) return true;
        }
        return false;
    }

    // ==================== 界面 ====================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_PAGE);

        // ---- 顶栏白卡片 ----
        LinearLayout topCard = new LinearLayout(this);
        topCard.setOrientation(LinearLayout.HORIZONTAL);
        topCard.setGravity(Gravity.CENTER_VERTICAL);
        topCard.setBackground(round(C_CARD, 0, C_CARD, 20));
        topCard.setElevation(dp(2));
        topCard.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(10), dp(26), dp(10), 0);
        topCard.setLayoutParams(cardLp);

        topCard.addView(roundBtn("\u2190", new View.OnClickListener() {
            @Override public void onClick(View v) { if (web.canGoBack()) web.goBack(); }
        }));
        topCard.addView(roundBtn("\u2192", new View.OnClickListener() {
            @Override public void onClick(View v) { if (web.canGoForward()) web.goForward(); }
        }));
        topCard.addView(roundBtn("\u21bb", new View.OnClickListener() {
            @Override public void onClick(View v) { web.reload(); }
        }));

        bar = new EditText(this);
        bar.setSingleLine(true);
        bar.setTextColor(C_TEXT);
        bar.setHintTextColor(C_TEXT2);
        bar.setHint("搜索或输入网址");
        bar.setTextSize(13);
        bar.setBackground(round(C_PAGE, 0, C_PAGE, 16));
        bar.setPadding(dp(14), dp(7), dp(14), dp(7));
        bar.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        bar.setImeOptions(EditorInfo.IME_ACTION_GO);
        bar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId,
                                                    android.view.KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || (e != null && e.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                    go();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.setMargins(dp(6), 0, dp(6), 0);
        topCard.addView(bar, blp);

        TextView goBtn = new TextView(this);
        goBtn.setText("GO");
        goBtn.setTextColor(0xFFFFFFFF);
        goBtn.setTextSize(12);
        goBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        goBtn.setBackground(round(C_ACCENT, 0, C_ACCENT, 16));
        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { go(); }
        });
        topCard.addView(goBtn);

        root.addView(topCard);

        // ---- 引擎胶囊 + 状态 ----
        LinearLayout engRow = new LinearLayout(this);
        engRow.setOrientation(LinearLayout.HORIZONTAL);
        engRow.setGravity(Gravity.CENTER_VERTICAL);
        engRow.setPadding(dp(16), dp(8), dp(16), dp(6));

        engineBtn = new TextView(this);
        engineBtn.setTextColor(C_ACCENT);
        engineBtn.setTextSize(11);
        engineBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        engineBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
        engineBtn.setBackground(round(C_SOFT, 0, C_SOFT, 20));
        engineBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickEngine(); }
        });
        updateEngineBtn();
        engRow.addView(engineBtn);

        stat = new TextView(this);
        stat.setTextColor(C_TEXT2);
        stat.setTextSize(11);
        stat.setPadding(dp(12), 0, 0, 0);
        stat.setText("已拦截 0");
        engRow.addView(stat, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(engRow);

        // ---- 网页 ----
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setBackgroundColor(C_CARD);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String u = req.getUrl() == null ? "" : req.getUrl().toString();
                return !(u.startsWith("http://") || u.startsWith("https://"));
            }

            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest req) {
                String url = req.getUrl() == null ? null : req.getUrl().toString();

                if (req.isForMainFrame()) {
                    if (isAdult(url)) {
                        scolded++;
                        ui();
                        try {
                            return new WebResourceResponse("text/html", "utf-8",
                                    new ByteArrayInputStream(
                                            fakeCertPage(url).getBytes("UTF-8")));
                        } catch (Exception e) {
                            return null;
                        }
                    }
                    return null;
                }
                if (url != null && AdRules.isBlockedUrl(url, groups)) {
                    blocked++;
                    AdBlockVpnService.countBrowserBlock();
                    ui();
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    bar.setText(url);
                }
            }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wlp.setMargins(dp(10), dp(4), dp(10), dp(10));
        root.addView(web, wlp);

        return root;
    }

    private void updateEngineBtn() {
        if (engineBtn != null) engineBtn.setText("搜索引擎 · " + seName + "  \u25be");
    }

    // ==================== 搜索引擎选择 ====================

    private void pickEngine() {
        final Dialog d = baseDialog();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(round(C_CARD, 0, C_CARD, 24));
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));

        TextView title = rounded("选择搜索引擎", 16, C_TEXT, true);
        panel.addView(title);

        TextView sub = rounded("共 " + ENGINES.length + " 个内置，也可自己填", 11, C_TEXT2, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        panel.addView(sub);

        panel.addView(secLabel("国内"));
        for (int i = 0; i < 5 && i < ENGINES.length; i++) panel.addView(engineRow(d, i));

        panel.addView(secLabel("国外"));
        for (int i = 5; i < ENGINES.length; i++) panel.addView(engineRow(d, i));

        panel.addView(secLabel("更多"));
        TextView custom = rounded("＋  自定义搜索引擎", 13, C_ACCENT, true);
        custom.setGravity(Gravity.CENTER);
        custom.setPadding(dp(10), dp(14), dp(10), dp(14));
        custom.setBackground(round(C_SOFT, 0, C_SOFT, 16));
        custom.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); customEngine(); }
        });
        panel.addView(custom);

        TextView cancel = rounded("取消", 13, C_TEXT2, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(10), dp(14), dp(10), dp(6));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        panel.addView(cancel);

        showPanel(d, panel);
    }

    private View engineRow(final Dialog d, final int i) {
        boolean sel = ENGINES[i][0].equals(seName);
        String host = ENGINES[i][1];
        host = host.replace("https://", "").replace("http://", "");
        int q = host.indexOf('?');
        if (q > 0) host = host.substring(0, q);
        int s = host.indexOf('/');
        if (s > 0) host = host.substring(0, s);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(round(sel ? C_SOFT : C_CARD,
                sel ? 2 : 1, sel ? C_ACCENT2 : C_LINE, 16));

        TextView dot = rounded(sel ? "\u25cf" : "\u25cb", 12,
                sel ? C_ACCENT : C_TEXT2, false);
        dot.setPadding(0, 0, dp(12), 0);
        row.addView(dot);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = rounded(ENGINES[i][0], 14, sel ? C_ACCENT : C_TEXT, true);
        TextView addr = rounded(host, 10, C_TEXT2, false);
        addr.setPadding(0, dp(3), 0, 0);
        col.addView(name);
        col.addView(addr);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (sel) {
            TextView badge = rounded("使用中", 9, 0xFFFFFFFF, true);
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            badge.setBackground(round(C_ACCENT, 0, C_ACCENT, 10));
            row.addView(badge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                seName = ENGINES[i][0];
                seUrl  = ENGINES[i][1];
                saveEngine();
                updateEngineBtn();
                toast("已切换到 " + seName);
                d.dismiss();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(lp);
        return row;
    }

    private void customEngine() {
        final Dialog d = baseDialog();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(round(C_CARD, 0, C_CARD, 24));
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));

        panel.addView(rounded("自定义搜索引擎", 16, C_TEXT, true));
        TextView sub = rounded("地址里用 %s 代替关键词", 11, C_TEXT2, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        panel.addView(sub);

        panel.addView(label("名称"));
        final EditText eName = input(seName, InputType.TYPE_CLASS_TEXT);
        panel.addView(eName);

        panel.addView(label("搜索地址"));
        final EditText eUrl = input(seUrl, InputType.TYPE_TEXT_VARIATION_URI);
        panel.addView(eUrl);

        TextView eg = rounded("例：https://www.baidu.com/s?wd=%s", 10, C_TEXT2, false);
        eg.setPadding(dp(4), dp(8), 0, dp(14));
        panel.addView(eg);

        TextView save = rounded("保存", 14, 0xFFFFFFFF, true);
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(10), dp(14), dp(10), dp(14));
        save.setBackground(round(C_ACCENT, 0, C_ACCENT, 16));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String n = eName.getText().toString().trim();
                String u = eUrl.getText().toString().trim();
                if (n.isEmpty() || !u.contains("%s")) {
                    toast("名称不能空，地址里必须包含 %s");
                    return;
                }
                seName = n;
                seUrl  = u;
                saveEngine();
                updateEngineBtn();
                toast("已保存：" + n);
                d.dismiss();
            }
        });
        panel.addView(save);

        TextView cancel = rounded("取消", 13, C_TEXT2, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(10), dp(14), dp(10), dp(6));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        panel.addView(cancel);

        showPanel(d, panel);
    }

    private void saveEngine() {
        getSharedPreferences("prts", MODE_PRIVATE).edit()
                .putString("se_name", seName)
                .putString("se_url", seUrl)
                .apply();
    }

    // ==================== 导航 ====================

    private void go() {
        String t = bar.getText().toString().trim();
        if (t.isEmpty()) return;
        if (t.startsWith("http://") || t.startsWith("https://")) {
            load(t);
        } else if (t.contains(".") && !t.contains(" ")) {
            load("https://" + t);
        } else {
            load(seUrl.replace("%s", android.net.Uri.encode(t)));
        }
        hideKeyboard();
    }

    private void load(String url) {
        bar.setText(url);
        web.loadUrl(url);
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(bar.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    private void ui() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                stat.setText(scolded > 0
                        ? "已拦截 " + blocked + "  ·  劝返 " + scolded
                        : "已拦截 " + blocked);
            }
        });
    }

    // ==================== 彩蛋页（柔和风） ====================

    private String fakeCertPage(String url) {
        String host = url == null ? "unknown" : url;
        try {
            int p = host.indexOf("://");
            if (p >= 0) host = host.substring(p + 3);
            int s = host.indexOf('/');
            if (s >= 0) host = host.substring(0, s);
        } catch (Exception ignored) {
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>"
            + "*{box-sizing:border-box}"
            + "body{background:#F4F7FE;color:#1F2A44;margin:0;padding:22px 18px;"
            + "font-family:-apple-system,'Noto Sans SC',sans-serif}"
            + ".card{background:#fff;border-radius:20px;padding:20px 18px;"
            + "box-shadow:0 6px 18px rgba(61,123,255,.08);margin-bottom:14px}"
            + ".ico{width:46px;height:46px;border-radius:14px;background:#EAF1FF;"
            + "display:flex;align-items:center;justify-content:center;font-size:22px}"
            + "h1{font-size:18px;margin:14px 0 8px;font-weight:600}"
            + "p{font-size:13px;line-height:1.75;color:#5B6B87;margin:0 0 8px}"
            + "code{color:#3D7BFF;font-size:12px;background:#EAF1FF;"
            + "padding:2px 6px;border-radius:6px}"
            + ".row{display:flex;justify-content:space-between;padding:7px 0;"
            + "border-bottom:1px solid #EEF2FA;font-size:12px;color:#8093B0}"
            + ".row:last-child{border-bottom:0}"
            + ".err{color:#E23D5A;font-size:12px;font-weight:600}"
            + ".prts{color:#3D7BFF;font-size:15px;font-weight:600;margin:0}"
            + ".sig{color:#8093B0;font-size:11px;margin:8px 0 0;text-align:right}"
            + ".btn{display:block;text-align:center;background:#3D7BFF;color:#fff;"
            + "padding:13px;border-radius:14px;font-size:14px;text-decoration:none;"
            + "margin-top:12px}"
            + ".btn2{display:block;text-align:center;background:#EAF1FF;color:#3D7BFF;"
            + "padding:13px;border-radius:14px;font-size:14px;text-decoration:none;"
            + "margin-top:10px}"
            + "</style></head><body>"
            + "<div class=\"card\">"
            + "<div class=\"ico\">\ud83d\udd12</div>"
            + "<h1>连接不是私密连接</h1>"
            + "<p>攻击者可能会试图从 <code>" + host + "</code> 窃取您的信息"
            + "（例如：密码、通讯方式或信用卡）。</p>"
            + "</div>"
            + "<div class=\"card\">"
            + "<p class=\"err\">NET::ERR_CERT_AUTHORITY_INVALID</p>"
            + "<div class=\"row\"><span>颁发者</span><span>PRTS-ROOT-CA</span></div>"
            + "<div class=\"row\"><span>有效期</span><span>不适用</span></div>"
            + "<div class=\"row\"><span>证书使用者</span><span>" + host + "</span></div>"
            + "</div>"
            + "<div class=\"card\">"
            + "<p class=\"prts\">博士，您今天的 325 计划还没有完成。</p>"
            + "<p class=\"sig\">—— 普瑞赛斯</p>"
            + "</div>"
            + "<a class=\"btn\" href=\"javascript:history.back()\">返回</a>"
            + "<a class=\"btn2\" href=\"javascript:location.reload()\">重试</a>"
            + "</body></html>";
    }

    // ==================== 小工具 ====================

    private LinearLayout rowWrap() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView roundBtn(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_ACCENT);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
        lp.setMargins(dp(2), 0, dp(2), 0);
        t.setLayoutParams(lp);
        t.setBackground(round(C_PAGE, 0, C_PAGE, 12));
        t.setOnClickListener(l);
        return t;
    }

    private TextView rounded(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private TextView secLabel(String s) {
        TextView t = rounded(s, 11, C_TEXT2, true);
        t.setPadding(dp(6), dp(6), 0, dp(8));
        return t;
    }

    private TextView label(String s) {
        TextView t = rounded(s, 11, C_TEXT2, true);
        t.setPadding(dp(6), dp(4), 0, dp(6));
        return t;
    }

    private EditText input(String val, int type) {
        EditText e = new EditText(this);
        e.setText(val);
        e.setSingleLine(true);
        e.setTextSize(13);
        e.setTextColor(C_TEXT);
        e.setInputType(type);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setBackground(round(C_PAGE, 0, C_PAGE, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        e.setLayoutParams(lp);
        return e;
    }

    private GradientDrawable round(int fill, int strokeDp, int strokeColor, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return g;
    }

    private Dialog baseDialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    private void showPanel(Dialog d, View panel) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.TRANSPARENT);
        sv.setPadding(0, 0, 0, 0);
        sv.addView(panel);
        d.setContentView(sv);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        try { if (web != null) web.destroy(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
