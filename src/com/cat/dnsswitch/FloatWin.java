package com.cat.dnsswitch;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮窗：显示「拦截了多少条」，可拖动、点击进 App、长按隐藏。
 *
 * 数据直接读 AdBlockVpnService 的静态计数，不另开统计通道。
 * 需要 SYSTEM_ALERT_WINDOW（用户手动授权）。
 */
public class FloatWin extends Service {

    public static final String ACTION_SHOW = "com.cat.dnsswitch.FLOAT_SHOW";
    public static final String ACTION_HIDE = "com.cat.dnsswitch.FLOAT_HIDE";

    public static volatile boolean showing = false;

    private WindowManager wm;
    private View root;
    private TextView label;
    private TextView powerBtn;
    private WindowManager.LayoutParams lp;
    private final Handler h = new Handler();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!showing || label == null) return;
            refreshText();
            h.postDelayed(this, 1000);
        }
    };

    /** 外部统一入口：开 */
    public static void start(Context c) {
        try {
            Intent i = new Intent(c, FloatWin.class).setAction(ACTION_SHOW);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    /** 外部统一入口：关 */
    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, FloatWin.class));
        } catch (Exception ignored) {
        }
    }

    public static boolean canDraw(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return Settings.canDrawOverlays(c);
        } catch (Exception e) {
            return false;
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? ACTION_SHOW : intent.getAction();
        // 服务本身要常驻，但不显示系统通知：用 startForeground 会被要求通知，
        // 这里改成"只在需要时创建视图"，Android 8+ 对后台服务有限制，
        // 所以用一个极低优先级的空前台通知来保住进程。
        try {
            if (Build.VERSION.SDK_INT >= 26) startForeground(2, silentNotification());
        } catch (Exception ignored) {
        }

        if (ACTION_HIDE.equals(act)) {
            hide();
            stopSelf();
            return START_NOT_STICKY;
        }
        show();
        return START_STICKY;
    }

    private android.app.Notification silentNotification() {
        String ch = "prts_float";
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel c = new android.app.NotificationChannel(
                        ch, "悬浮窗", android.app.NotificationManager.IMPORTANCE_MIN);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }
        } catch (Exception ignored) {
        }
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, ch)
                : new android.app.Notification.Builder(this);
        return b.setContentTitle("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
    }

    private void show() {
        if (showing || root != null) return;
        if (!canDraw(this)) return;

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        SharedPreferences sp = getSharedPreferences("prts", MODE_PRIVATE);
        int px = sp.getInt("float_x", -1);
        int py = sp.getInt("float_y", -1);

        label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(10), dp(6), dp(10), dp(6));
        label.setText("拦截 0");

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6202020);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x66FFFFFF);

        powerBtn = new TextView(this);
        powerBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        powerBtn.setTextColor(Color.WHITE);
        powerBtn.setGravity(Gravity.CENTER);
        powerBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        powerBtn.setText("关");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(bg);
        box.addView(label);
        box.addView(powerBtn);
        root = box;

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = px >= 0 ? px : dp(12);
        lp.y = py >= 0 ? py : dp(120);

        root.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;
            long downAt;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        downAt = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            lp.x = startX + dx;
                            lp.y = startY + dy;
                            try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            getSharedPreferences("prts", MODE_PRIVATE).edit()
                                    .putInt("float_x", lp.x)
                                    .putInt("float_y", lp.y)
                                    .apply();
                        } else if (System.currentTimeMillis() - downAt > 600) {
                            // 长按：隐藏并记住
                            getSharedPreferences("prts", MODE_PRIVATE).edit()
                                    .putBoolean("float", false).apply();
                            hide();
                            stopSelf();
                        } else {
                            // 轻点：打开主界面
                            try {
                                Intent i = new Intent(FloatWin.this, MainActivity.class);
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        powerBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final boolean now = Blocker.isOn(FloatWin.this);
                powerBtn.setText("…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        final boolean ok = Blocker.setOn(FloatWin.this, !now);
                        h.post(new Runnable() {
                            @Override public void run() {
                                if (powerBtn != null) powerBtn.setText(ok ? (now ? "开" : "关") : "?");
                                refreshText();
                            }
                        });
                    }
                }, "float-toggle").start();
            }
        });
        try {
            wm.addView(root, lp);
            showing = true;
            refreshText();
            h.postDelayed(tick, 1000);
        } catch (Exception e) {
            root = null;
            showing = false;
        }
    }

    private void hide() {
        h.removeCallbacks(tick);
        if (root != null && wm != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
        }
        root = null;
        label = null;
        showing = false;
        try { stopForeground(true); } catch (Exception ignored) {}
    }

    private void refreshText() {
        if (label == null) return;
        long n = AdBlockVpnService.statBlocked;
        boolean run = AdBlockVpnService.running || Blocker.isOn(this);
        label.setText((run ? "🛡 " : "⏸ ") + n);
        if (powerBtn != null) {
            // 按钮显示"下一次点会发生什么"：工作中显示「关」，停了显示「开」
            powerBtn.setText(run ? "关" : "开");
            powerBtn.setTextColor(run ? 0xFFFF8080 : 0xFF7CFFB0);
        }
        try {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(run ? 0xE6202020 : 0xE2553333);
            bg.setCornerRadius(dp(16));
            bg.setStroke(dp(1), run ? 0x6600E5A0 : 0x66FF8888);
            root.setBackground(bg);
        } catch (Exception ignored) {
        }
    }

    /** 私人 DNS 模式也算"在工作" */
    private boolean dnsOn() {
        try {
            String mode = android.provider.Settings.Global.getString(
                    getContentResolver(), "private_dns_mode");
            String spec = android.provider.Settings.Global.getString(
                    getContentResolver(), "private_dns_specifier");
            return "hostname".equals(mode) && spec != null && spec.trim().length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onDestroy() {
        hide();
        super.onDestroy();
    }
}
