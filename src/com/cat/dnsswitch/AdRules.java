package com.cat.dnsswitch;

/**
 * 规则库：按组管理，DNS 拦截和内置浏览器共用同一份。
 * 全部是「域名后缀匹配」，所以写 doubleclick.net 也能拦 ad.doubleclick.net。
 */
public final class AdRules {

    public static final int G_AD    = 0;   // 广告
    public static final int G_TRACK = 1;   // 统计与追踪
    public static final int G_SDK   = 2;   // 推广 SDK
    public static final int G_MAL   = 3;   // 恶意与诈骗

    public static final String[] GROUP_NAMES = {
            "广告", "统计与追踪", "推广 SDK", "恶意与诈骗"
    };
    public static final String[] GROUP_KEYS = {
            "grp_ad", "grp_track", "grp_sdk", "grp_mal"
    };
    public static final String[] GROUP_DESC = {
            "横幅、插屏、视频贴片",
            "埋点、行为统计、热力图",
            "穿山甲、优量汇、友盟、极光",
            "钓鱼、诈骗、挖矿"
    };

    private static final String[] AD = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "ads.youtube.com", "pagead2.googlesyndication.com",
            "partner.googleadservices.com", "pubads.g.doubleclick.net",
            "securepubads.g.doubleclick.net", "stats.g.doubleclick.net",
            "googleads.g.doubleclick.net", "tpc.googlesyndication.com",
            "hm.baidu.com", "pos.baidu.com", "cpro.baidu.com", "union.baidu.com",
            "tanx.com", "alimama.com", "simba.taobao.com", "adash.m.taobao.com",
            "gdt.qq.com", "e.qq.com", "l.qq.com", "pingfore.qq.com",
            "adsmind.apdcdn.tc.qq.com", "mi.gdt.qq.com", "pgdt.gtimg.cn",
            "cpro.baidustatic.com", "cbjs.baidu.com", "dup.baidustatic.com",
            "adview.cn", "domob.cn", "waps.cn", "admaster.com.cn", "miaozhen.com",
            "irs01.com", "irs01.net", "meituan.xinshuru.com",
            "mi.gdt.qq.com", "ad.xiaomi.com", "ad.mi.com", "api.ad.xiaomi.com",
            "applovin.com", "unityads.unity3d.com", "vungle.com",
            "inmobi.com", "chartboost.com", "tapjoy.com", "ironsrc.com",
            "criteo.com", "criteo.net", "moatads.com", "scorecardresearch.com",
            "amazon-adsystem.com", "adnxs.com", "rubiconproject.com",
            "pubmatic.com", "openx.net", "smartadserver.com", "adcolony.com",
            "mopub.com", "smaato.net", "yandex.ru/ads", "adform.net",
            "360.cn/ad", "union.360.cn", "s.360.cn", "mobads.baidu.com",
            "oppomobile.com/ad", "ads.oppomobile.com", "adsfs.oppomobile.com",
            "h2.oppomobile.com", "g.oppomobile.com",
            "vivo.com.cn/ad", "adx.vivo.com.cn", "adnet.vivo.com.cn",
            "miui.com/ad", "ads.mi.com", "tracking.miui.com",
            "samsungads.com", "ad.samsungadhub.com",
            "baidustatic.com/ad", "yingxiao.baidu.com"
    };

    private static final String[] TRACK = {
            "google-analytics.com", "googletagmanager.com", "analytics.google.com",
            "ssl.google-analytics.com", "region1.google-analytics.com",
            "umeng.com", "umengcloud.com", "umeng.co", "cnzz.com",
            "sensorsdata.cn", "growingio.com", "talkingdata.com",
            "flurry.com", "appsflyer.com", "adjust.com", "branch.io",
            "kochava.com", "singular.net", "tenjin.io", "bugly.qq.com",
            "beacon.qq.com", "pingjs.qq.com", "tj.qq.com",
            "click.taobao.com", "log.mmstat.com", "w.mmstat.com",
            "at.alicdn.com", "arms-retcode.aliyuncs.com",
            "tongji.baidu.com", "v.baidu.com", "eclick.baidu.com",
            "stats.miui.com", "data.mistat.xiaomi.com",
            "analytics.oppomobile.com", "data.oppomobile.com",
            "hotjar.com", "mouseflow.com", "fullstory.com", "mixpanel.com",
            "amplitude.com", "segment.com", "segment.io", "heap.io",
            "newrelic.com", "datadoghq.com", "sentry.io"
    };

    private static final String[] SDK = {
            "pangolin.snssdk.com", "open.e.kuaishou.com", "adkwai.com",
            "jpush.cn", "jiguang.cn", "getui.com", "igexin.com",
            "oppo.mobads", "com.opos.ads", "heytap.com/ad",
            "unityads.unitychina.cn", "gromore.pangolin.com",
            "ad.qq.com", "e.360.cn", "union.chuanying.com",
            "dsp.360.cn", "show.360.cn", "tuia.360.cn",
            "ads.toutiao.com", "ad.toutiao.com", "partner.toutiao.com",
            "csjplatform.com", "ads.byteoversea.com"
    };

    private static final String[] MAL = {
            "doubleclick-cdn.com", "adf.ly", "shorte.st", "ouo.io",
            "coinhive.com", "coin-hive.com", "webminepool.com",
            "cryptoloot.pro", "jsecoin.com", "minero.cc",
            "malware-check.com", "phishing-test.com"
    };

    private static final String[][] GROUPS = { AD, TRACK, SDK, MAL };

    private static volatile boolean[] enabledCache = null;

    public static String[] group(int g) {
        return (g >= 0 && g < GROUPS.length) ? GROUPS[g] : new String[0];
    }

    public static int totalCount() {
        int n = 0;
        for (int i = 0; i < GROUPS.length; i++) n += GROUPS[i].length;
        return n;
    }

    /** enabled 为 null 表示全部启用 */
    public static void setEnabled(boolean[] en) {
        enabledCache = en;
    }

    /**
     * 是否该拦。
     * @param host 域名（小写）
     * @param enabled 四组的开关；null = 全开
     */
    public static boolean isBlocked(String host, boolean[] enabled) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (int g = 0; g < GROUPS.length; g++) {
            if (enabled != null && g < enabled.length && !enabled[g]) continue;
            String[] list = GROUPS[g];
            for (int i = 0; i < list.length; i++) {
                String r = list[i];
                if (h.equals(r) || h.endsWith("." + r)) return true;
                // 规则里带路径的（如 yandex.ru/ads）退化成域名比对
                int slash = r.indexOf('/');
                if (slash > 0) {
                    String d = r.substring(0, slash);
                    if (h.equals(d) || h.endsWith("." + d)) return true;
                }
            }
        }
        return false;
    }

    /** 从完整 URL 里取域名后判断 */
    public static boolean isBlockedUrl(String url, boolean[] enabled) {
        if (url == null) return false;
        String u = url.toLowerCase();
        int p = u.indexOf("://");
        String rest = p >= 0 ? u.substring(p + 3) : u;
        int slash = rest.indexOf('/');
        String host = slash >= 0 ? rest.substring(0, slash) : rest;
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        int at = host.indexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        return isBlocked(host, enabled);
    }

    private AdRules() {}
}
