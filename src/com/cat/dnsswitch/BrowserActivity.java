package com.cat.dnsswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;

/**
 * 内置浏览器 · 早期信息终端风格（白底蓝字）
 * 靠 shouldInterceptRequest 把广告域名的子资源掐掉。
 */
public class BrowserActivity extends Activity {

    // 早期终端配色：白底 + 深蓝
    private static final int C_BG    = 0xFFFFFFFF;   // 纸白
    private static final int C_BLUE  = 0xFF12308C;   // 主蓝
    private static final int C_BLUE2 = 0xFF2E5BFF;   // 亮蓝
    private static final int C_DIM   = 0xFF7A8DB8;   // 淡蓝灰
    private static final int C_BOX   = 0xFFDCE4F5;   // 边框浅蓝
    private static final int C_FILL  = 0xFFF2F5FC;   // 输入框底色

    private WebView web;
    private EditText bar;
    private TextView stat;
    private TextView engineBtn;
    private boolean[] groups;
    private long blocked = 0;
    private long scolded = 0;

    private String seName = "百度";
    private String seUrl  = "https://www.baidu.com/s?wd=%s";

    /** 搜索引擎表：国内 + 国外 + 自定义 */
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

    /** 彩蛋：命中这些关键词就弹「证书错误」喵 */
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
        root.setBackgroundColor(C_BG);

        // ---- 命令栏 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(C_FILL);
        top.setPadding(dp(6), dp(26), dp(6), dp(6));

        top.addView(btn("\u2190", new View.OnClickListener() {
            @Override public void onClick(View v) { if (web.canGoBack()) web.goBack(); }
        }));
        top.addView(btn("\u2192", new View.OnClickListener() {
            @Override public void onClick(View v) { if (web.canGoForward()) web.goForward(); }
        }));
        top.addView(btn("\u21bb", new View.OnClickListener() {
            @Override public void onClick(View v) { web.reload(); }
        }));

        bar = new EditText(this);
        bar.setSingleLine(true);
        bar.setTextColor(C_BLUE);
        bar.setHintTextColor(C_DIM);
        bar.setHint("> 输入网址或搜索词");
        bar.setTextSize(13);
        bar.setTypeface(Typeface.MONOSPACE);
        bar.setBackground(box(0xFFFFFFFF, C_BOX, 1));
        bar.setPadding(dp(8), dp(5), dp(8), dp(5));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        top.addView(bar, lp);

        top.addView(btn("GO", new View.OnClickListener() {
            @Override public void onClick(View v) { go(); }
        }));
        root.addView(top);

        // ---- 引擎栏：终端式 ----
        LinearLayout engBar = new LinearLayout(this);
        engBar.setOrientation(LinearLayout.HORIZONTAL);
        engBar.setGravity(Gravity.CENTER_VERTICAL);
        engBar.setBackgroundColor(C_BG);
        engBar.setPadding(dp(8), dp(4), dp(8), dp(4));

        engineBtn = new TextView(this);
        engineBtn.setTextColor(C_BLUE);
        engineBtn.setTextSize(11);
        engineBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        engineBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        engineBtn.setBackground(box(0xFFFFFFFF, C_BLUE, 1));
        engineBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickEngine(); }
        });
        updateEngineBtn();
        engBar.addView(engineBtn);

        stat = new TextView(this);
        stat.setTextColor(C_BLUE);
        stat.setTextSize(11);
        stat.setTypeface(Typeface.MONOSPACE);
        stat.setPadding(dp(10), 0, 0, 0);
        stat.setText("PRTS://BROWSER  已拦 0");
        engBar.addView(stat, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(engBar);

        View line = new View(this);
        line.setBackgroundColor(C_BLUE);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // ---- 网页 ----
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setBackgroundColor(C_BG);
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
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void updateEngineBtn() {
        if (engineBtn != null) engineBtn.setText(" 搜索引擎: " + seName + " \u25be");
    }

    // ==================== 搜索引擎 ====================

    private void pickEngine() {
        final String[] items = new String[ENGINES.length + 1];
        for (int i = 0; i < ENGINES.length; i++) items[i] = ENGINES[i][0];
        items[ENGINES.length] = "自定义…";

        int checked = 0;
        for (int i = 0; i < ENGINES.length; i++) {
            if (ENGINES[i][0].equals(seName)) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择搜索引擎")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which < ENGINES.length) {
                            seName = ENGINES[which][0];
                            seUrl  = ENGINES[which][1];
                            saveEngine();
                            updateEngineBtn();
                            toast("引擎已切换：" + seName);
                        } else {
                            customEngine();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void customEngine() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        TextView t1 = smallLabel("名称（显示在按钮上）");
        box.addView(t1);
        final EditText eName = input(seName, InputType.TYPE_CLASS_TEXT);
        box.addView(eName);

        TextView t2 = smallLabel("搜索地址，用 %s 代替关键词");
        t2.setPadding(0, dp(10), 0, 0);
        box.addView(t2);
        final EditText eUrl = input(seUrl.contains("%s") ? seUrl : "", InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(eUrl);

        TextView t3 = smallLabel("例：https://www.baidu.com/s?wd=%s");
        t3.setTextColor(C_DIM);
        t3.setPadding(0, dp(6), 0, 0);
        box.addView(t3);

        new AlertDialog.Builder(this)
                .setTitle("自定义搜索引擎")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
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
                        toast("已保存自定义引擎：" + n);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
                        ? "PRTS://BROWSER  已拦 " + blocked + "  劝返 " + scolded
                        : "PRTS://BROWSER  已拦 " + blocked);
            }
        });
    }

    // ==================== 彩蛋页 ====================

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
            + "body{background:#FFFFFF;color:#12308C;margin:0;padding:26px 20px;"
            + "font-family:'Courier New',monospace}"
            + "h1{font-size:19px;margin:14px 0 10px;letter-spacing:1px}"
            + ".tag{display:inline-block;border:1px solid #12308C;padding:2px 8px;"
            + "font-size:11px;letter-spacing:2px}"
            + "p{font-size:13px;line-height:1.8;color:#3A4F86;margin:0 0 8px}"
            + "code{color:#2E5BFF;font-size:12px}"
            + ".box{border:1px solid #DCE4F5;background:#F2F5FC;padding:12px 14px;"
            + "margin-top:14px}"
            + ".err{color:#B00020;font-size:12px;letter-spacing:1px}"
            + ".prts{color:#12308C;font-size:14px;font-weight:bold;margin:0}"
            + ".sig{color:#7A8DB8;font-size:11px;margin:6px 0 0;text-align:right}"
            + ".btn{display:inline-block;border:1px solid #12308C;color:#12308C;"
            + "padding:8px 18px;margin:16px 8px 0 0;font-size:12px;letter-spacing:1px}"
            + "</style></head><body>"
            + "<div class=\"tag\">PRTS://SECURE-LAYER</div>"
            + "<h1>[ 连接不是私密连接 ]</h1>"
            + "<p>攻击者可能会试图从 <code>" + host + "</code> 窃取您的信息"
            + "（例如：密码、通讯方式或信用卡）。</p>"
            + "<div class=\"box\">"
            + "<p class=\"err\">NET::ERR_CERT_AUTHORITY_INVALID</p>"
            + "<p style=\"margin:8px 0 0\">ISSUER &nbsp;<code>PRTS-ROOT-CA</code><br>"
            + "VALID &nbsp;<code>N/A</code><br>"
            + "SUBJECT &nbsp;<code>" + host + "</code></p>"
            + "</div>"
            + "<div class=\"box\">"
            + "<p class=\"prts\">博士，您今天的 325 计划还没有完成。</p>"
            + "<p class=\"sig\">—— 普瑞赛斯</p>"
            + "</div>"
            + "<a class=\"btn\" href=\"javascript:history.back()\">返回</a>"
            + "<a class=\"btn\" href=\"javascript:location.reload()\">重试</a>"
            + "</body></html>";
    }

    // ==================== 小工具 ====================

    private TextView btn(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_BLUE);
        t.setTextSize(15);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9), dp(6), dp(9), dp(6));
        t.setBackground(box(0xFFFFFFFF, C_BOX, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    private TextView smallLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_BLUE);
        t.setTextSize(11);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private EditText input(String val, int type) {
        EditText e = new EditText(this);
        e.setText(val);
        e.setSingleLine(true);
        e.setTextSize(13);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextColor(C_BLUE);
        e.setInputType(type);
        e.setPadding(dp(6), dp(4), dp(6), dp(4));
        e.setBackground(box(0xFFFFFFFF, C_BOX, 1));
        return e;
    }

    private GradientDrawable box(int fill, int stroke, int width) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(width), stroke);
        g.setCornerRadius(dp(1));
        return g;
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
