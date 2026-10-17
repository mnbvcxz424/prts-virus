package com.cat.dnsswitch;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
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
        final Dialog d = baseDialog();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(box(C_BG, C_BLUE, 2));
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = mono("PRTS://ENGINE-SELECT", 13, C_BLUE, true);
        title.setLetterSpacing(0.05f);
        panel.addView(title);

        TextView sub = mono("选择搜索引擎 · 共 " + ENGINES.length + " 个内置", 10, C_DIM, false);
        sub.setPadding(0, dp(4), 0, dp(10));
        panel.addView(sub);

        panel.addView(secLabel("[ 国内 ]"));
        for (int i = 0; i < 5 && i < ENGINES.length; i++) panel.addView(engineRow(d, i));

        panel.addView(secLabel("[ 国外 ]"));
        for (int i = 5; i < ENGINES.length; i++) panel.addView(engineRow(d, i));

        panel.addView(secLabel("[ 其他 ]"));
        TextView custom = mono("  + 自定义搜索引擎…", 13, C_BLUE2, true);
        custom.setPadding(dp(6), dp(10), dp(6), dp(10));
        custom.setBackground(box(C_BG, C_BOX, 1));
        custom.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); customEngine(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, dp(4));
        custom.setLayoutParams(clp);
        panel.addView(custom);

        TextView cancel = mono("  × 取消", 12, C_DIM, false);
        cancel.setPadding(dp(6), dp(9), dp(6), dp(9));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        panel.addView(cancel);

        showPanel(d, panel);
    }

    /** 一行引擎选项，终端风格的方框 */
    private View engineRow(final Dialog d, final int i) {
        boolean sel = ENGINES[i][0].equals(seName);
        String host = ENGINES[i][1];
        host = host.replace("https://", "").replace("http://", "");
        int q = host.indexOf('?');
        if (q > 0) host = host.substring(0, q);
        int s = host.indexOf('/');
        if (s > 0) host = host.substring(0, s);

        String mark = sel ? " \u25c9  " : " \u25cb  ";
        String label = mark + ENGINES[i][0] + "\n        " + host;
        TextView t = mono(label, 13, sel ? C_BLUE2 : C_BLUE, sel);
        t.setPadding(dp(6), dp(8), dp(6), dp(8));
        t.setLineSpacing(dp(2), 1f);
        t.setBackground(box(sel ? C_FILL : C_BG, sel ? C_BLUE2 : C_BOX, sel ? 2 : 1));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                seName = ENGINES[i][0];
                seUrl   = ENGINES[i][1];
                saveEngine();
                updateEngineBtn();
                toast("引擎已切换：" + seName);
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(4));
        t.setLayoutParams(lp);
        return t;
    }

    private void customEngine() {
        final Dialog d = baseDialog();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(box(C_BG, C_BLUE, 2));
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));

        panel.addView(mono("PRTS://ENGINE-CUSTOM", 13, C_BLUE, true));
        TextView sub = mono("用 %s 代替搜索关键词", 10, C_DIM, false);
        sub.setPadding(0, dp(4), 0, dp(12));
        panel.addView(sub);

        panel.addView(label("NAME / 名称"));
        final EditText eName = input(seName, InputType.TYPE_CLASS_TEXT);
        panel.addView(eName);

        panel.addView(label("URL / 搜索地址"));
        final EditText eUrl = input(seUrl, InputType.TYPE_TEXT_VARIATION_URI);
        panel.addView(eUrl);

        TextView eg = mono("例：https://www.baidu.com/s?wd=%s", 10, C_DIM, false);
        eg.setPadding(0, dp(6), 0, dp(12));
        panel.addView(eg);

        TextView save = mono("  [ 保存 ]", 13, C_BLUE, true);
        save.setPadding(dp(6), dp(10), dp(6), dp(10));
        save.setBackground(box(C_FILL, C_BLUE, 1));
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
                toast("已保存自定义引擎：" + n);
                d.dismiss();
            }
        });
        panel.addView(save);

        TextView cancel = mono("  × 取消", 12, C_DIM, false);
        cancel.setPadding(dp(6), dp(10), dp(6), dp(10));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        panel.addView(cancel);

        showPanel(d, panel);
    }

    // ---------- 对话框基建（全自绘，不用系统 Material 样式） ----------

    private Dialog baseDialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    private void showPanel(Dialog d, View panel) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
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

    private TextView mono(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private TextView secLabel(String s) {
        TextView t = mono(s, 11, C_DIM, true);
        t.setPadding(dp(2), dp(12), 0, dp(6));
        t.setLetterSpacing(0.1f);
        return t;
    }

    private TextView label(String s) {
        TextView t = mono(s, 10, C_DIM, true);
        t.setPadding(0, dp(2), 0, dp(4));
        t.setLetterSpacing(0.08f);
        return t;
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
