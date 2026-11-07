package com.watchunlock.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * 状态守护：常驻通知显示解锁状态，冷却/停用/失败时给出可见信号，
 * 并在守护进程被杀后（Shizuku 可用时）自动拉起。
 */
public class MonitorService extends Service {

    private static final String CH_STATUS = "wu_status";
    private static final String CH_ALERT = "wu_alert";
    private static final int ID_STATUS = 1;
    private static final int ID_ALERT = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService bg;
    /** 解锁与状态查询分队列：守护重启要 sleep 2 秒，不能把亮屏解锁排到它后面 */
    private ExecutorService unlockBg;
    private String lastAlertKey = "";
    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver btReceiver;
    private BroadcastReceiver wifiReceiver;
    private long lastIdleMs = 0L;
    private static boolean legacyChecked = false;

    private final Shizuku.OnBinderDeadListener deadListener = new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                            I18n.t("授权服务已断开，监控暂停",
                                    "Privilege service disconnected, monitoring paused"));
                }
            });
        }
    };

    /** 状态刷新只在事件发生时触发（亮屏 / 解锁完成 / 蓝牙变化），不做定时轮询 */
    private final Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
            poll();
        }
    };

    /** 合并短时间内的连续事件，避免一次亮屏触发多次 Shizuku 调用 */
    private void scheduleStatus(long delayMs) {
        handler.removeCallbacks(statusRunnable);
        handler.postDelayed(statusRunnable, delayMs);
    }

    private void wakeOnce() {
        if (!Prefs.wantRunning(this)) return;
        if (unlockBg == null || unlockBg.isShutdown()) return;
        unlockBg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Scripts.wakeUnlock();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private int wakeGen = 0;

    /**
     * 应用自己的 ACTION_SCREEN_ON 广播比 logcat 事件更可靠，亮屏时直接执行解锁。
     * 第一次可能落在 PIN 键盘出来之前，脚本会快速返回；后面几次逐步补上。
     * 脚本内部有跨进程互斥与 1.5 秒防重复注入，多次重试不会重复输入 PIN。
     */
    private void wakeUnlockChain() {
        final int gen = ++wakeGen;
        // 250ms / 1.4s / 2.6s / 3.9s：第一次快速试，失败后等键盘/动画就绪再试
        long[] delays = {250L, 1400L, 2600L, 3900L};
        for (long d : delays) {
            handler.postDelayed(() -> {
                if (gen == wakeGen) wakeOnce();
            }, d);
        }
    }

    /** 守护进程看门狗：被杀后 1 分钟内自动拉起，不依赖亮屏事件 */
    private static final long WATCHDOG_MS = 60_000L;
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            ensureDaemon();
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private void ensureDaemon() {
        if (!Prefs.wantRunning(this)) return;
        run(new Runnable() {
            @Override
            public void run() {
                if (!Scripts.daemonAlive()) {
                    Scripts.Report r = Scripts.start();
                    // 守护刚启动时没有在线缓存：用当前真实的监听状态补一次，
                    // 否则 wu.present 会一直停在 track=0，每次亮屏都现场查（+200~350ms）
                    if (r.ok) refreshPresence(btReceiver != null);
                }
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        I18n.init(this);
        Privilege.init(this);
        bg = Executors.newSingleThreadExecutor();
        unlockBg = Executors.newSingleThreadExecutor();
        createChannels();
        startForeground(ID_STATUS, buildStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                I18n.t("正在检查…", "Checking…")));
        try {
            Shizuku.addBinderDeadListener(deadListener);
        } catch (Throwable ignored) {
        }
        ensureTracking();
        // 升级迁移：清掉旧版 /sdcard/watch-unlock 里的明文 PIN（必要时自动重新部署）
        if (!legacyChecked) {
            legacyChecked = true;
            bg.execute(new Runnable() {
                @Override
                public void run() {
                    Scripts.migrateLegacy(MonitorService.this);
                }
            });
        }
        handler.post(statusRunnable);
        handler.postDelayed(watchdog, WATCHDOG_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 用户可能刚授权「附近的设备」，每次启动指令都重试一次注册
        ensureTracking();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregister(screenReceiver);
        unregister(btReceiver);
        unregister(wifiReceiver);
        screenReceiver = null;
        btReceiver = null;
        wifiReceiver = null;
        try {
            Shizuku.removeBinderDeadListener(deadListener);
        } catch (Throwable ignored) {
        }
        if (bg != null) bg.shutdownNow();
        if (unlockBg != null) unlockBg.shutdownNow();
        super.onDestroy();
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(r, f);
        }
    }

    private void unregister(BroadcastReceiver r) {
        if (r == null) return;
        try {
            unregisterReceiver(r);
        } catch (Throwable ignored) {
        }
    }

    private void ensureTracking() {
        if (screenReceiver == null) startScreenTracking();
        if (btReceiver == null) startBtTracking();
        if (wifiReceiver == null) startWifiTracking();
    }

    /** 亮屏 = 立即触发解锁 + 刷新状态；息屏 = 提前刷新在线缓存 */
    private void startScreenTracking() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent == null ? "" : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    refreshPresence(false);
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    // 应用层广播是主触发源：logcat 在 HyperOS 上会被频繁杀掉，不能只靠它
                    wakeUnlockChain();
                    scheduleStatus(1500);
                } else {
                    scheduleStatus(0);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            register(screenReceiver, f);
        } catch (Throwable t) {
            screenReceiver = null;
        }
    }

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 监听蓝牙连接/断开：这是在线缓存的唯一来源，替代原来的定时轮询 */
    private void startBtTracking() {
        if (!hasBtPermission()) {
            notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"), I18n.t(
                    "缺少「附近的设备」权限：蓝牙事件监听未开启，亮屏时会现查（略慢）",
                    "Nearby devices permission missing: Bluetooth event tracking is off, "
                            + "so status is checked when the screen wakes (a bit slower)"));
            run(new Runnable() {
                @Override
                public void run() {
                    Scripts.untrack();
                }
            });
            return;
        }
        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshPresence(true);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            register(btReceiver, f);
        } catch (Throwable t) {
            btReceiver = null;
            return;
        }
        refreshPresence(true);
    }

    /** WiFi 连接/断开也刷新在线缓存，WiFi 独立解锁或作为必要条件时才注册 */
    private void startWifiTracking() {
        if (!Prefs.wifiUnlock(this) && !Prefs.wifiRequired(this)) return;
        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Prefs.wifiUnlock(MonitorService.this) || Prefs.wifiRequired(MonitorService.this)) {
                    refreshPresence(true);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        try {
            register(wifiReceiver, f);
        } catch (Throwable t) {
            wifiReceiver = null;
        }
    }

    /**
     * 让守护重算「手表在不在」并写入缓存。
     * tracking=true 只在蓝牙事件里用：此时应用确实在监听，缓存可以长期信任。
     * 蓝牙事件是缓存的唯一来源，所以不做节流 —— 漏掉一次就会「连上了却不解锁」；
     * tracking=false 用于息屏预热，3 秒内只做一次，避免反复开关屏时刷爆 dumpsys。
     */
    private void refreshPresence(boolean tracking) {
        if (!tracking) {
            long now = System.currentTimeMillis();
            if (now - lastIdleMs < 3000) return;
            lastIdleMs = now;
        }
        run(new Runnable() {
            @Override
            public void run() {
                Scripts.presentRefresh(tracking);
            }
        });
    }

    private void run(final Runnable task) {
        if (bg == null || bg.isShutdown()) return;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void poll() {
        if (bg == null || bg.isShutdown()) return;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    evaluate();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void evaluate() {
        if (!Privilege.ensureReady()) {
            notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                    I18n.t("%s · %s，监控暂停", "%s · %s, monitoring paused",
                            Privilege.modeLabel(), Privilege.statusText()));
            return;
        }
        Map<String, String> st = Scripts.state();
        if (st.isEmpty()) {
            notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                    I18n.t("尚未部署，打开应用完成首次部署",
                            "Not deployed yet. Open the app to deploy."));
            return;
        }
        String status = st.containsKey("status") ? st.get("status") : "ok";
        String reason = st.containsKey("reason") ? st.get("reason") : "ok";
        long until = parseLong(st.get("paused_until"));
        long left = until - System.currentTimeMillis() / 1000L;
        if (left < 0) left = 0;
        int fails = (int) parseLong(st.get("fails"));
        String cooldowns = st.containsKey("cooldowns") ? st.get("cooldowns") : "0";
        String updated = st.containsKey("updated") ? st.get("updated") : "";

        String clock = "";
        if (!updated.isEmpty()) {
            clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(parseLong(updated) * 1000L));
        }

        // 自动修复：守护被杀（重启/被清理）且未停用时重新拉起
        if (Prefs.wantRunning(this) && !"disabled".equals(status) && !Scripts.daemonAlive()) {
            Scripts.Report rep = Scripts.start();
            if (rep.ok) {
                // 拉起后补一次在线缓存，避免下一次亮屏退化成本次现查
                refreshPresence(btReceiver != null);
                notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                        I18n.t("守护已自动重启（%s）", "Daemon restarted automatically (%s)", clock));
            }
        }

        if ("disabled".equals(status)) {
            notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                    I18n.t("已停用：疑似锁屏密码已变更", "Disabled: lock-screen PIN may have changed"));
            notifyAlert("disabled", I18n.t("守护已停用", "Daemon disabled"),
                    I18n.t("连续两个冷却周期失败，多半是锁屏密码已变更。请打开应用核对密码后重新部署。",
                            "Two cooling periods failed in a row, so the lock-screen PIN has likely "
                                    + "changed. Open the app, verify the PIN, and deploy again."));
        } else if ("paused".equals(status)) {
            long pleft = parseLong(st.get("pause_until")) - System.currentTimeMillis() / 1000L;
            if (pleft < 0) pleft = 0;
            long min = (pleft + 59) / 60;
            notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                    I18n.t("已暂停：还剩 %d 分钟", "Paused: %d min left", min));
        } else if ("cooling".equals(status)) {
            long min = (left + 59) / 60;
            notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                    I18n.t("冷却中：还剩 %d 分钟", "Cooling down: %d min left", min));
            notifyAlert("cooling" + cooldowns, I18n.t("解锁进入冷却", "Unlock cooling down"),
                    I18n.t("连续失败 3 次，暂停 %d 分钟。若是刚改过锁屏密码，请重新部署；否则检查键盘是否被遮挡。",
                            "3 failures in a row; pausing for %d min. If you just changed the lock-screen "
                                    + "PIN, deploy again. Otherwise check that the keyboard is not covered.", min));
        } else if ("failed".equals(status)) {
            notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                    I18n.t("失败 %d/3，下一次亮屏将重试（%s）",
                            "Failure %d/3, will retry on the next screen wake (%s)", fails, clock));
        } else if ("skip".equals(status)) {
            notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                    I18n.t("本轮跳过：%s（%s）", "Skipped this time: %s (%s)", reason, clock));
        } else {
            notifyStatus(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"),
                    I18n.t("运行中 · 状态正常（%s）", "Running · normal (%s)", clock));
        }
    }

    private long parseLong(String s) {
        if (s == null) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private void notifyStatus(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_STATUS, buildStatus(title, text));
    }

    private void notifyAlert(String key, String title, String text) {
        if (key.equals(lastAlertKey)) return;
        lastAlertKey = key;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification.Builder b = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .setOnlyAlertOnce(false);
        nm.notify(ID_ALERT, b.build());
    }

    private Notification buildStatus(String title, String text) {
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .build();
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel status = new NotificationChannel(
                CH_STATUS, I18n.t("解锁状态", "Unlock status"), NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        nm.createNotificationChannel(status);
        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, I18n.t("解锁异常提醒", "Unlock alerts"), NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(alert);
    }
}
