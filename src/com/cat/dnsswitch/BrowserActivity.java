package com.cat.dnsswitch;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
 * 内置浏览器：靠 shouldInterceptRequest 把广告域名的子资源直接掐掉。
 * 只对「这个浏览器里的页面」有效，系统浏览器管不到（那要靠 DNS）。
 */
public class BrowserActivity extends Activity {

    private static final int C_BG       = 0xFF000000;
    private static final int C_GREEN    = 0xFF00FF41;
    private static final int C_GREEN_D  = 0xFF00A82D;
    private static final int C_DIM      = 0xFF4A6B55;

    private WebView web;
    private EditText bar;
    private TextView stat;
    private boolean[] groups;
    private long blocked = 0;
    private long scolded = 0;

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

    private boolean isAdult(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        for (int i = 0; i < ADULT.length; i++) {
            if (u.contains(ADULT[i])) return true;
        }
        return false;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        groups = loadGroups();
        AdRules.setEnabled(groups);
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

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // 顶栏：← → ↻ + 地址栏 + GO
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(28), dp(8), dp(6));

        top.addView(btn("←", new View.OnClickListener() {
            @Override public void onClick(View v) { if (web.canGoBack()) web.goBack(); }
        }));
        top.addView(btn("→", new View.OnClickListener() {
            @Override public void onClick(View v) { if (web.canGoForward()) web.goForward(); }
        }));
        top.addView(btn("↻", new View.OnClickListener() {
            @Override public void onClick(View v) { web.reload(); }
        }));

        bar = new EditText(this);
        bar.setSingleLine(true);
        bar.setTextColor(C_GREEN);
        bar.setHintTextColor(C_DIM);
        bar.setHint("输入网址或搜索");
        bar.setTextSize(13);
        bar.setTypeface(Typeface.MONOSPACE);
        bar.setBackgroundColor(0xFF0D2818);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        bar.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        bar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId,
                                                    android.view.KeyEvent e) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                        || (e != null && e.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                    go();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        top.addView(bar, lp);

        top.addView(btn("GO", new View.OnClickListener() {
            @Override public void onClick(View v) { go(); }
        }));
        root.addView(top);

        // 状态条：拦截计数
        stat = new TextView(this);
        stat.setTextColor(C_GREEN_D);
        stat.setTextSize(11);
        stat.setTypeface(Typeface.MONOSPACE);
        stat.setPadding(dp(12), 0, dp(12), dp(4));
        stat.setText("PRTS_BROWSER  ·  已拦 0");
        root.addView(stat);

        // 网页
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
                // 只让 http/https 在浏览器里打开，data:/intent:/market: 一律不理
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
                    return null;   // 主文档一般不拦，否则整页打不开
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

    private TextView btn(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_GREEN);
        t.setTextSize(16);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    private void go() {
        String t = bar.getText().toString().trim();
        if (t.isEmpty()) return;
        if (!t.startsWith("http://") && !t.startsWith("https://")) {
            if (t.contains(".") && !t.contains(" ")) t = "https://" + t;
            else t = "https://www.baidu.com/s?wd=" + android.net.Uri.encode(t);
        }
        load(t);
        hideKeyboard();
    }

    private void load(String url) {
        bar.setText(url);
        web.loadUrl(url);
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(bar.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    /** 彩蛋页：假装是 HTTPS 证书错误，其实是博士的 325 计划提醒喵 */
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
            + "body{background:#000;color:#d6dcd8;margin:0;padding:30px 22px;"
            + "font-family:-apple-system,'Noto Sans SC',sans-serif}"
            + ".lock{font-size:40px;line-height:1}"
            + "h1{font-size:21px;color:#fff;margin:20px 0 10px;font-weight:600}"
            + "p{font-size:14px;line-height:1.75;color:#98a49c;margin:0 0 10px}"
            + "code{background:#0d2818;color:#00FF41;padding:2px 6px;border-radius:3px;"
            + "font-size:12px;font-family:monospace}"
            + ".box{border:1px solid #0d2818;background:#050d08;padding:14px 16px;"
            + "margin-top:16px;border-radius:6px}"
            + ".err{color:#FF0041;font-family:monospace;font-size:13px}"
            + ".prts{color:#00FF41;font-size:15px;font-weight:700;margin:0}"
            + ".sig{color:#4a6b55;font-size:12px;margin:8px 0 0;text-align:right}"
            + ".btn{display:inline-block;border:1px solid #00FF41;color:#00FF41;"
            + "padding:10px 20px;margin:18px 10px 0 0;border-radius:4px;font-size:13px}"
            + "</style></head><body>"
            + "<div class=\"lock\">\ud83d\udd12</div>"
            + "<h1>您的连接不是私密连接</h1>"
            + "<p>攻击者可能会试图从 <code>" + host + "</code> 窃取您的信息"
            + "（例如：密码、通讯方式或信用卡）。</p>"
            + "<div class=\"box\">"
            + "<p class=\"err\">NET::ERR_CERT_AUTHORITY_INVALID</p>"
            + "<p style=\"margin:10px 0 0\">颁发者 &nbsp;<code>PRTS-ROOT-CA</code><br>"
            + "有效期 &nbsp;<code>不适用</code><br>"
            + "证书使用者 &nbsp;<code>" + host + "</code></p>"
            + "</div>"
            + "<div class=\"box\">"
            + "<p class=\"prts\">博士，您今天的 325 计划还没有完成。</p>"
            + "<p class=\"sig\">—— 普瑞赛斯</p>"
            + "</div>"
            + "<a class=\"btn\" href=\"javascript:history.back()\">返回</a>"
            + "<a class=\"btn\" href=\"javascript:location.reload()\">重试</a>"
            + "</body></html>";
    }

    private void ui() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                stat.setText(scolded > 0
                        ? "PRTS_BROWSER  ·  已拦 " + blocked + "  ·  劝返 " + scolded
                        : "PRTS_BROWSER  ·  已拦 " + blocked);
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        try { if (web != null) web.destroy(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @SuppressWarnings("unused")
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
