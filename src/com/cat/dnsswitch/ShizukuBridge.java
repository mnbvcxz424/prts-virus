package com.cat.dnsswitch;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 通道 —— 把 adb shell 的能力搬进 App。
 *
 * Shizuku 的服务端（shizuku_server）以 shell(uid 2000) 身份运行，
 * 所以授予本 App 权限之后，App 就等于拿到了 adb shell。
 *
 * 授权流程：
 *   1. init() 注册监听（粘性，App 一起来就能知道服务在不在）
 *   2. request() 让 Shizuku 弹自己的授权框
 *   3. 结果通过 OnRequestPermissionResultListener 回来
 *   4. exec() 执行命令
 */
public final class ShizukuBridge {

    public static final int REQ_CODE = 4201;
    public static final String PKG = "moe.shizuku.privileged.api";

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final java.util.List<Runnable> LISTENERS =
            new java.util.ArrayList<Runnable>();
    private static boolean inited = false;
    private static Method mNewProcess;

    public static final class Result {
        public int code = -1;
        public String out = "";
        public String err = "";

        public boolean ok() { return err.isEmpty() && code == 0; }
        public String text() { return err.isEmpty() ? out : ("ERR " + err); }
    }

    private static final Shizuku.OnBinderReceivedListener L_RECV =
            new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() { fire(); }
            };

    private static final Shizuku.OnBinderDeadListener L_DEAD =
            new Shizuku.OnBinderDeadListener() {
                @Override public void onBinderDead() { fire(); }
            };

    private static final Shizuku.OnRequestPermissionResultListener L_PERM =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    fire();
                }
            };

    private static void fire() {
        final Runnable[] arr;
        synchronized (LISTENERS) {
            if (LISTENERS.isEmpty()) return;
            arr = LISTENERS.toArray(new Runnable[LISTENERS.size()]);
        }
        H.post(new Runnable() {
            @Override public void run() {
                for (Runnable r : arr) {
                    try { r.run(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    /** 状态变化回调（主线程）。可以加多个，Activity 销毁时记得摘掉。 */
    public static void addListener(Runnable r) {
        if (r == null) return;
        synchronized (LISTENERS) {
            if (!LISTENERS.contains(r)) LISTENERS.add(r);
        }
    }

    public static void removeListener(Runnable r) {
        if (r == null) return;
        synchronized (LISTENERS) { LISTENERS.remove(r); }
    }

    /** 注册 Shizuku 监听，幂等。Shizuku 没装就什么也不做。 */
    public static void init(Context c) {
        if (inited) return;
        if (!installed(c)) return;
        inited = true;
        try {
            Shizuku.addBinderReceivedListenerSticky(L_RECV);
            Shizuku.addBinderDeadListener(L_DEAD);
            Shizuku.addRequestPermissionResultListener(L_PERM);
        } catch (Throwable t) {
            inited = false;
        }
    }

    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 服务端 binder 在不在 */
    public static boolean running() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    /** 本 App 有没有被授权 */
    public static boolean granted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int version() {
        try { return Shizuku.getVersion(); } catch (Throwable t) { return -1; }
    }

    /** 当前进程跑在哪个用户空间（0=主空间，10=壶中界，999=分身） */
    public static int userId() {
        try { return android.os.Process.myUid() / 100000; } catch (Throwable t) { return -1; }
    }

    public static int uid() {
        try { return Shizuku.getUid(); } catch (Throwable t) { return -1; }
    }

    public static boolean rationale() {
        try { return Shizuku.shouldShowRequestPermissionRationale(); } catch (Throwable t) { return false; }
    }

    /** 让 Shizuku 弹授权框 */
    public static void request() {
        try { Shizuku.requestPermission(REQ_CODE); } catch (Throwable ignored) {}
    }

    /**
     * 用 Shizuku 跑一段 sh 脚本。
     * stderr 合并进 stdout，避免两条流互相卡住。
     */
    public static Result exec(String script) {
        Result r = new Result();
        if (!running()) { r.err = "Shizuku 服务未运行"; return r; }
        if (!granted()) { r.err = "Shizuku 未授权"; return r; }
        Process p = null;
        try {
            p = newProcess(new String[]{"sh", "-c", "(" + script + ") 2>&1"});
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            r.code = p.waitFor();
            r.out = sb.toString().trim();
        } catch (Throwable t) {
            r.err = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
        return r;
    }

    /**
     * Shizuku 13.x 把 newProcess 收成了 private（12.x 还是 public）。
     * 它属于本 App 自己的库，不受 hidden API 限制，反射可以直接拿到。
     */
    private static Process newProcess(String[] cmd) throws Exception {
        if (mNewProcess == null) {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            mNewProcess = m;
        }
        return (Process) mNewProcess.invoke(null, cmd, null, null);
    }

    public static String stateLabel(Context c) {
        if (!installed(c)) return "未安装";
        if (!running()) return "未运行";
        if (!granted()) return "待授权";
        return "已授权";
    }
}
