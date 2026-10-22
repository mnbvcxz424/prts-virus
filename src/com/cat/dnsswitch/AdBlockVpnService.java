package com.cat.dnsswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * 本地广告拦截：只接管 DNS（不劫持全部流量）。
 *
 * 做法：
 *  1. 建立一个很小的 tun，只把 TUN_ADDR 这一个地址的路由进去；
 *  2. 系统 DNS 指向 TUN_ADDR，于是只有 DNS 查询会进隧道；
 *  3. 读出 IPv4/UDP 包，解析 DNS 问题名：
 *       - 命中本地规则 → 直接合成 0.0.0.0 回应（不浪费一次上游查询）
 *       - 没命中 → 转发给上游 DNS，把回包原样塞回隧道
 *  4. 统计总查询数 / 拦截数 / 唯一域名数，供界面显示。
 *
 * 这样其他 App 的普通流量完全不走 VPN，没有"全流量代理"的耗电和兼容性问题。
 */
public class AdBlockVpnService extends VpnService implements Runnable {

    public static final String ACTION_START = "com.cat.dnsswitch.VPN_START";
    public static final String ACTION_STOP  = "com.cat.dnsswitch.VPN_STOP";
    public static final String CH_ID = "prts_vpn";

    private static final String TUN_ADDR = "10.111.222.1";
    private static final int    TUN_PREFIX = 32;
    /** DNS 虚拟服务器地址：必须和接口地址不同，否则系统会把包当成本机包，不会写进 tun */
    private static final String DNS_ADDR = "10.111.222.2";
    /** 上游 DNS（UDP） */
    private static final String UPSTREAM = "223.5.5.5";
    private static final int    MTU = 1500;

    /** 供界面读取的实时统计 */
    public static volatile boolean running = false;
    public static volatile long statQueries = 0;
    public static volatile long statBlocked = 0;
    public static volatile String lastBlocked = "";
    private static final java.util.HashSet<String> seenDomains = new java.util.HashSet<>();

    private Thread worker;
    private ParcelFileDescriptor tun;
    private DatagramSocket up;

    private static android.content.Context appCtx;

    /** 分组开关缓存（5 秒） */
    private static boolean[] groupsCache = null;
    private static long groupsAt = 0;
    /** 信任网络缓存（20 秒） */
    private static boolean trustedCache = false;
    private static long trustedAt = 0;

    static boolean[] groups() {
        long now = System.currentTimeMillis();
        if (groupsCache != null && now - groupsAt < 5000) return groupsCache;
        groupsAt = now;
        boolean[] g = { true, true, true, true };
        try {
            android.content.SharedPreferences sp = appCtx.getSharedPreferences(
                    "prts", android.content.Context.MODE_PRIVATE);
            for (int i = 0; i < 4; i++) g[i] = sp.getBoolean(AdRules.GROUP_KEYS[i], true);
        } catch (Exception ignored) {
        }
        groupsCache = g;
        return g;
    }

    /** 当前 Wi-Fi 在「信任的网络」里吗？在的话暂停拦截 */
    static boolean isTrustedNet() {
        long now = System.currentTimeMillis();
        if (now - trustedAt < 20000) return trustedCache;
        trustedAt = now;
        trustedCache = false;
        try {
            android.content.SharedPreferences sp = appCtx.getSharedPreferences(
                    "prts", android.content.Context.MODE_PRIVATE);
            if (!sp.getBoolean("trust_net", false)) return false;
            String list = sp.getString("trust_ssids", "");
            if (list == null || list.trim().isEmpty()) return false;
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    appCtx.getSystemService(android.content.Context.WIFI_SERVICE);
            if (wm == null) return false;
            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return false;
            String ssid = wi.getSSID();
            if (ssid == null) return false;
            ssid = ssid.replace("\"", "");
            for (String line : list.split("\n")) {
                if (line.trim().equals(ssid)) { trustedCache = true; break; }
            }
        } catch (Exception ignored) {
        }
        return trustedCache;
    }

    /** 浏览器那边拦了一条，也记进总数 */
    public static void countBrowserBlock() {
        statBlocked++;
        bumpBlocked();
    }

    static void resetGroupsCache() { groupsCache = null; groupsAt = 0; trustedAt = 0; }
    private static int pendingQ = 0, pendingB = 0;

    /** 查询数：攒 10 次写一次盘 */
    private static void bumpQuery() {
        pendingQ++;
        if (pendingQ >= 10) flushStats();
    }

    /** 拦截数：攒 3 次写一次盘 */
    private static void bumpBlocked() {
        pendingB++;
        if (pendingB >= 3) flushStats();
    }

    /** 把攒着的今日/累计数写进 SharedPreferences（跨天自动归零今日） */
    static void flushStats() {
        android.content.Context c = appCtx;
        int dq = pendingQ, db = pendingB;
        pendingQ = 0; pendingB = 0;
        if (c == null || (dq == 0 && db == 0)) return;
        try {
            android.content.SharedPreferences sp =
                    c.getSharedPreferences("prts_stats", android.content.Context.MODE_PRIVATE);
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date());
            boolean sameDay = today.equals(sp.getString("day", ""));
            int dday   = sameDay ? sp.getInt("day_blocked", 0) : 0;
            int dquery = sameDay ? sp.getInt("day_queries", 0) : 0;
            int total  = sp.getInt("total_blocked", 0);
            sp.edit()
              .putString("day", today)
              .putInt("day_blocked", dday + db)
              .putInt("day_queries", dquery + dq)
              .putInt("total_blocked", total + db)
              .apply();
        } catch (Exception ignored) {
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        appCtx = getApplicationContext();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(act)) {
            // 用户主动关：记下来，这样被销毁时不会又自己爬起来
            KeepAlive.prefs(this).edit().putBoolean(KeepAlive.KEY_WANT, false).apply();
            KeepAlive.cancelRevive(this);
            FloatWin.stop(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        KeepAlive.prefs(this).edit().putBoolean(KeepAlive.KEY_WANT, true).apply();
        KeepAlive.cancelRevive(this);
        if (running) {
            syncFloat();
            return START_STICKY;
        }
        startForeground(1, buildNotification());
        worker = new Thread(this, "prts-vpn");
        running = true;
        worker.start();
        syncFloat();
        return START_STICKY;
    }

    /** 按设置决定要不要把悬浮窗挂上 */
    private void syncFloat() {
        try {
            if (KeepAlive.prefs(this).getBoolean(KeepAlive.KEY_FLOAT, true)
                    && FloatWin.canDraw(this)) {
                FloatWin.start(this);
            }
        } catch (Exception ignored) {
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // 用户从"最近任务"里划掉：立刻预约复活（划掉 ≠ 主动关）
        KeepAlive.scheduleRevive(this, 1000);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onRevoke() {
        running = false;
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        running = false;
        flushStats();
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        try { if (up != null) up.close(); } catch (Exception ignored) {}
        tun = null; up = null;
        stopForeground(true);
        // 正常停止时 KEY_WANT 已被置 false，这里不会复活；被系统杀掉时才会
        KeepAlive.scheduleRevive(this, 3000);
        super.onDestroy();
    }

    @Override public void run() {
        try {
            Builder b = new Builder();
            b.setSession("普瑞赛斯病毒");
            b.setMtu(MTU);
            b.addAddress(TUN_ADDR, TUN_PREFIX);
            b.addDnsServer(DNS_ADDR);
            b.addRoute(DNS_ADDR, 32);
            b.addRoute(TUN_ADDR, TUN_PREFIX);
            b.setBlocking(false);
            tun = b.establish();
            if (tun == null) { running = false; stopSelf(); return; }
            up = new DatagramSocket();
            up.setSoTimeout(3000);

            FileInputStream in = new FileInputStream(tun.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
            ByteBuffer pkt = ByteBuffer.allocate(MTU);

            while (running) {
                pkt.clear();
                int n = in.read(pkt.array());
                if (n <= 0) { Thread.sleep(8); continue; }
                handle(pkt.array(), n, out);
            }
        } catch (Exception e) {
            running = false;
        }
    }

    /** 处理一个从 tun 里读出来的包 */
    private void handle(byte[] buf, int len, FileOutputStream out) {
        if (len < 28) return;
        int ver = (buf[0] >> 4) & 0xF;
        if (ver != 4) return;
        int ihl = (buf[0] & 0xF) * 4;
        if (ihl < 20 || len < ihl + 8) return;
        int proto = buf[9] & 0xFF;
        if (proto != 17) return;                     // 只处理 UDP
        int dstPort = ((buf[ihl + 2] & 0xFF) << 8) | (buf[ihl + 3] & 0xFF);
        if (dstPort != 53) return;                   // 只处理 DNS

        int udpLen = ((buf[ihl + 4] & 0xFF) << 8) | (buf[ihl + 5] & 0xFF);
        int dnsOff = ihl + 8;
        int dnsLen = udpLen - 8;
        if (dnsLen <= 12 || dnsOff + dnsLen > len) return;

        String domain = parseQName(buf, dnsOff, dnsLen);
        statQueries++;
        bumpQuery();
        if (domain == null) return;
        synchronized (seenDomains) { seenDomains.add(domain); }

        if (!isTrustedNet() && AdRules.isBlocked(domain, groups())) {
            statBlocked++;
            bumpBlocked();
            lastBlocked = domain;
            byte[] resp = synthAnswer(buf, dnsOff, dnsLen);
            if (resp != null) writeBack(buf, ihl, len, resp, out);
            return;
        }
        // 放行：转发给上游，拿回包塞回去
        try {
            byte[] q = new byte[dnsLen];
            System.arraycopy(buf, dnsOff, q, 0, dnsLen);
            DatagramPacket sp = new DatagramPacket(q, q.length,
                    InetAddress.getByName(UPSTREAM), 53);
            up.send(sp);
            byte[] rbuf = new byte[MTU];
            DatagramPacket rp = new DatagramPacket(rbuf, rbuf.length);
            up.receive(rp);
            byte[] payload = new byte[rp.getLength()];
            System.arraycopy(rp.getData(), 0, payload, 0, rp.getLength());
            writeBack(buf, ihl, len, payload, out);
        } catch (Exception ignored) {
            // 上游超时：合成一个 SERVFAIL，避免 App 干等
            byte[] resp = synthAnswer(buf, dnsOff, dnsLen);
            if (resp != null) writeBack(buf, ihl, len, resp, out);
        }
    }

    /** 读 DNS 问题名（不展开压缩指针，问题段不含指针） */
    private String parseQName(byte[] b, int off, int dnsLen) {
        StringBuilder sb = new StringBuilder();
        int p = off + 12;
        int end = off + dnsLen;
        while (p < end) {
            int l = b[p] & 0xFF;
            if (l == 0) return sb.length() == 0 ? null : sb.toString().toLowerCase();
            if ((l & 0xC0) != 0) return null;        // 压缩指针：不是问题段，放弃
            p++;
            if (p + l > end) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(b, p, l, java.nio.charset.Charset.forName("ISO-8859-1")));
            p += l;
        }
        return null;
    }


    /** 合成 0.0.0.0 的 A 记录回应（失败返回 null） */
    private byte[] synthAnswer(byte[] q, int dnsOff, int dnsLen) {
        try {
            int p = dnsOff + 12;
            int end = dnsOff + dnsLen;
            while (p < end && (q[p] & 0xFF) != 0) {
                int l = q[p] & 0xFF;
                if ((l & 0xC0) != 0) return null;
                p += l + 1;
            }
            if (p + 5 > end) return null;
            int qEnd = p + 1 + 4;                       // 0 + QTYPE + QCLASS
            int qLen = qEnd - dnsOff;
            byte[] r = new byte[qLen + 16];
            System.arraycopy(q, dnsOff, r, 0, qLen);
            r[2] = (byte) 0x81;                         // QR=1, RD=1
            r[3] = (byte) 0x80;                         // RA=1, RCODE=0
            r[6] = 0; r[7] = 1;                         // ANCOUNT = 1
            int o = qLen;
            r[o++] = (byte) 0xC0; r[o++] = 0x0C;        // 指向问题名
            r[o++] = 0; r[o++] = 1;                     // TYPE A
            r[o++] = 0; r[o++] = 1;                     // CLASS IN
            r[o++] = 0; r[o++] = 0; r[o++] = 0; r[o++] = 6;   // TTL 6s（短一点，规则变了立刻生效）
            r[o++] = 0; r[o++] = 4;                     // RDLENGTH
            r[o++] = 0; r[o++] = 0; r[o++] = 0; r[o++] = 0;   // 0.0.0.0
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 payload 包成 UDP/IP 回包写回 tun（源/目标对调） */
    private void writeBack(byte[] req, int ihl, int reqLen, byte[] payload, FileOutputStream out) {
        try {
            int srcPort = ((req[ihl] & 0xFF) << 8) | (req[ihl + 1] & 0xFF);
            int dstPort = ((req[ihl + 2] & 0xFF) << 8) | (req[ihl + 3] & 0xFF);
            int total = 20 + 8 + payload.length;
            byte[] p = new byte[total];
            p[0] = 0x45;
            p[1] = 0;
            p[2] = (byte) (total >> 8); p[3] = (byte) total;
            p[4] = 0; p[5] = 0; p[6] = 0x40; p[7] = 0;   // DF
            p[8] = 64;
            p[9] = 17;
            p[10] = 0; p[11] = 0;
            System.arraycopy(req, 16, p, 12, 4);          // 源 IP = 请求的目标（TUN_ADDR）
            System.arraycopy(req, 12, p, 16, 4);          // 目标 IP = 请求的源
            int c = ipChecksum(p, 0, 20);
            p[10] = (byte) (c >> 8); p[11] = (byte) c;
            // UDP 头
            int u = 20;
            p[u] = (byte) (dstPort >> 8); p[u + 1] = (byte) dstPort;   // 源端口 = 53
            p[u + 2] = (byte) (srcPort >> 8); p[u + 3] = (byte) srcPort;
            int uLen = 8 + payload.length;
            p[u + 4] = (byte) (uLen >> 8); p[u + 5] = (byte) uLen;
            p[u + 6] = 0; p[u + 7] = 0;
            System.arraycopy(payload, 0, p, u + 8, payload.length);
            int uc = udpChecksum(p, 20, uLen, 0);        // ipOff=0：伪首部要从 IP 头取源/目的地址
            p[u + 6] = (byte) (uc >> 8); p[u + 7] = (byte) uc;
            out.write(p);
        } catch (Exception ignored) {
        }
    }

    private int ipChecksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = 0; i < len; i += 2) {
            sum += ((b[off + i] & 0xFF) << 8) | (b[off + i + 1] & 0xFF);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    /** UDP 校验和：伪首部（源 IP + 目的 IP + 0 + 协议 + UDP 长度）+ UDP 头 + 数据 */
    private int udpChecksum(byte[] b, int udpOff, int udpLen, int ipOff) {
        long sum = 0;
        for (int i = ipOff + 12; i < ipOff + 20; i += 2) {
            sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        }
        sum += 17;
        sum += udpLen;
        for (int i = 0; i < udpLen; i += 2) {
            int hi = b[udpOff + i] & 0xFF;
            int lo = (i + 1 < udpLen) ? (b[udpOff + i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        int c = (int) (~sum & 0xFFFF);
        return c == 0 ? 0xFFFF : c;
    }

    // ==================== 通知 ====================

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "广告拦截", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setContentTitle("普瑞赛斯病毒")
         .setContentText("本地广告拦截运行中")
         .setSmallIcon(android.R.drawable.stat_notify_sync)
         .setOngoing(true)
         .setContentIntent(pi);
        return b.build();
    }

    // ==================== 对外小接口 ====================

    public static int uniqueDomains() {
        synchronized (seenDomains) { return seenDomains.size(); }
    }

    public static void resetStats() {
        statQueries = 0;
        statBlocked = 0;
        lastBlocked = "";
        synchronized (seenDomains) { seenDomains.clear(); }
    }
}
