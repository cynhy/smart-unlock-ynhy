package com.watchunlock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;

/**
 * 无线调试配对前台服务。
 *
 * 交互参考 Shizuku（Apache-2.0）的 AdbPairingService：
 * - 自动发现本机 _adb-tls-pairing._tcp 端口
 * - 发一条可直接回复的通知，让用户在通知里输入 6 位配对码
 * - 配对成功后自动发现 _adb-tls-connect._tcp 端口并连接
 */
public class AdbPairingService extends Service {

    public static final String ACTION_START = "com.watchunlock.app.ADB_PAIR_START";
    public static final String ACTION_REPLY = "com.watchunlock.app.ADB_PAIR_REPLY";
    public static final String ACTION_STOP = "com.watchunlock.app.ADB_PAIR_STOP";
    public static final String ACTION_STATE = "com.watchunlock.app.ADB_PAIR_STATE";

    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_CODE = "code";
    public static final String KEY_CODE = "pair_code";

    public static final String CHANNEL = "adb_pairing";
    private static final int NOTIFICATION_ID = 1201;

    public static final int STATE_IDLE = 0;
    public static final int STATE_SEARCHING = 1;
    public static final int STATE_FOUND = 2;
    public static final int STATE_PAIRING = 3;
    public static final int STATE_CONNECTING = 4;
    public static final int STATE_CONNECTED = 5;
    public static final int STATE_FAILED = 6;

    private static volatile int sState = STATE_IDLE;
    private static volatile int sPort;
    private static volatile String sMessage = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private AdbMdns pairMdns;
    private AdbMdns connectMdns;
    private NotificationManager nm;
    private boolean foreground;
    private boolean connectingNow;
    private boolean retryable;
    private volatile int connectPort;
    private int pairRetries;
    private int connectRetries;

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (sState == STATE_CONNECTING) {
                connectingNow = false;
                stopConnectDiscovery();
                fail(I18n.t("配对成功，但没找到连接端口。请回到应用，在「手动配对 / 连接」里填写无线调试主页面的端口，再点「连接测试」",
                        "Paired, but the connect port was not found. Go back to the app, enter the port shown on the "
                                + "Wireless debugging main screen under “Manual pairing / connection”, then tap “Test connection”."),
                        false);
            }
        }
    };

    private final Runnable pairRetry = new Runnable() {
        @Override
        public void run() {
            if (sState == STATE_SEARCHING && pairRetries < 6) {
                pairRetries++;
                if (pairMdns != null) pairMdns.restart();
                main.postDelayed(this, 8000);
            }
        }
    };

    private final Runnable connectRetry = new Runnable() {
        @Override
        public void run() {
            if (sState == STATE_CONNECTING && connectPort <= 0 && connectRetries < 8) {
                connectRetries++;
                if (connectMdns != null) connectMdns.restart();
                main.postDelayed(this, 4000);
            }
        }
    };

    public static int state() {
        return sState;
    }

    public static int port() {
        return sPort;
    }

    public static String message() {
        return sMessage;
    }

    public static void start(Context context) {
        Intent i = new Intent(context, AdbPairingService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, AdbPairingService.class);
        i.setAction(ACTION_STOP);
        try {
            context.startService(i);
        } catch (Throwable ignored) {
            context.stopService(new Intent(context, AdbPairingService.class));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        I18n.init(this);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        foreground = true;
        setState(STATE_SEARCHING, I18n.t("正在搜索配对服务…", "Searching for the pairing service…"));
        startPairDiscovery();
        startConnectDiscovery();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPairDiscovery();
            stopConnectDiscovery();
            stopForeground(true);
            foreground = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REPLY.equals(action)) {
            handleReply(intent);
            return START_NOT_STICKY;
        }
        // ACTION_START 或系统重启服务：确保在搜索状态
        if (sState == STATE_IDLE || sState == STATE_FAILED) {
            pairRetries = 0;
            connectRetries = 0;
            setState(STATE_SEARCHING, I18n.t("正在搜索配对服务…", "Searching for the pairing service…"));
            startPairDiscovery();
            startConnectDiscovery();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(connectTimeout);
        main.removeCallbacks(pairRetry);
        main.removeCallbacks(connectRetry);
        stopPairDiscovery();
        stopConnectDiscovery();
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL,
                I18n.t("无线调试配对", "Wireless debugging pairing"),
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(I18n.t("在通知里输入系统显示的 6 位配对码", "Enter the 6-digit pairing code shown by the system"));
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private void startPairDiscovery() {
        if (pairMdns != null) return;
        pairMdns = new AdbMdns(this, AdbMdns.TLS_PAIRING, new AdbMdns.Callback() {
            @Override
            public void onPort(int port) {
                onPairPort(port);
            }
        });
        pairMdns.start();
        main.removeCallbacks(pairRetry);
        main.postDelayed(pairRetry, 8000);
    }

    private void stopPairDiscovery() {
        main.removeCallbacks(pairRetry);
        if (pairMdns != null) {
            pairMdns.stop();
            pairMdns = null;
        }
    }

    /**
     * 连接端口发现从一开始就并行运行，并缓存结果。
     * 配对成功后系统会关闭配对服务，但 _adb-tls-connect 一直存在；
     * 提前发现可以避开「配对成功却找不到连接端口」的竞态。
     */
    private void startConnectDiscovery() {
        if (connectMdns != null) return;
        connectMdns = new AdbMdns(this, AdbMdns.TLS_CONNECT, new AdbMdns.Callback() {
            @Override
            public void onPort(int port) {
                if (port > 0) {
                    connectPort = port;
                    Prefs.setAdbPort(AdbPairingService.this, port);
                    if (sState == STATE_CONNECTING) {
                        connectNow(port);
                    }
                }
            }
        });
        connectMdns.start();
    }

    private void stopConnectDiscovery() {
        main.removeCallbacks(connectRetry);
        if (connectMdns != null) {
            connectMdns.stop();
            connectMdns = null;
        }
    }

    private void onPairPort(int port) {
        if (port < 0) {
            if (sState == STATE_FOUND) {
                setState(STATE_SEARCHING, I18n.t("配对弹窗已关闭，请重新打开「使用配对码配对设备」",
                        "The pairing dialog closed. Open “Pair device with pairing code” again."));
            }
            return;
        }
        sPort = port;
        pairRetries = 0;
        Prefs.setAdbPairPort(this, port);
        setState(STATE_FOUND, I18n.t(
                "已找到配对服务。下拉通知，点「输入配对码」在通知里输入；"
                        + "或点「大按钮输入」打开输入页。保持系统配对弹窗打开，不要离开无线调试页面。",
                "Pairing service found. Pull down the notification and tap “Enter pairing code” to type it inline, "
                        + "or tap “Big input” to open a full-size input page. Keep the system pairing dialog open "
                        + "and do not leave the Wireless debugging page."));
    }

    private void handleReply(Intent intent) {
        CharSequence code = readCode(intent);
        int port = sPort > 0 ? sPort : Prefs.adbPairPort(this);
        if (code == null || code.toString().trim().length() != 6) {
            fail(I18n.t("配对码必须是 6 位数字", "The pairing code must be 6 digits"), true);
            return;
        }
        if (port <= 0) {
            fail(I18n.t("还没找到配对端口，请先打开「使用配对码配对设备」弹窗",
                    "Pairing port not found yet. Open “Pair device with pairing code” first."), false);
            return;
        }
        final String c = code.toString().trim();
        final int p = port;
        stopPairDiscovery();
        setState(STATE_PAIRING, I18n.t("正在配对…", "Pairing…"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = AdbBridge.pair(AdbPairingService.this, "127.0.0.1", p, c);
                if (err != null) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            fail(I18n.t("配对失败：%s", "Pairing failed: %s", err), true);
                        }
                    });
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        connectRetries = 0;
                        setState(STATE_CONNECTING, I18n.t("配对成功，正在连接…", "Paired. Connecting…"));
                        startConnectDiscovery();
                        main.removeCallbacks(connectTimeout);
                        main.postDelayed(connectTimeout, 25000);
                        main.removeCallbacks(connectRetry);
                        main.postDelayed(connectRetry, 4000);
                        int cached = connectPort > 0 ? connectPort : Prefs.adbPort(AdbPairingService.this);
                        if (cached > 0) {
                            connectNow(cached);
                        }
                    }
                });
            }
        }).start();
    }

    private void connectNow(final int port) {
        if (connectingNow || port <= 0 || sState != STATE_CONNECTING) return;
        connectingNow = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Prefs.setAdbPort(AdbPairingService.this, port);
                boolean ok = AdbBridge.connect(AdbPairingService.this);
                connectingNow = false;
                if (ok) {
                    Prefs.setPrivilegeMode(AdbPairingService.this, Privilege.ADB);
                    Scripts.applyConfig(AdbPairingService.this);
                    // 可选：用刚连上的 ADB 启动 Shizuku，失败不影响配对结果
                    startShizukuIfPossible();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            main.removeCallbacks(connectTimeout);
                            main.removeCallbacks(connectRetry);
                            stopPairDiscovery();
                            stopConnectDiscovery();
                            sPort = port;
                            setState(STATE_CONNECTED, I18n.t("配对成功，已连接（端口 %d）",
                                    "Paired and connected (port %d)", port));
                            detachAndStop();
                        }
                    });
                } else {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (sState == STATE_CONNECTING) {
                                // 端口可能已变化：保持等待，继续尝试 mDNS 发现的新端口
                                setState(STATE_CONNECTING, I18n.t("连接端口 %d 失败，继续查找…（%s）",
                                        "Connect port %d failed, still searching… (%s)",
                                        port, AdbBridge.lastError()));
                            }
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 可选功能（默认关闭）：配对连接成功后，用内置 ADB 执行 Shizuku 官方的 start.sh。
     * 需要在设置里打开「配对连接成功后尝试启动 Shizuku」。
     * 只对仍带 start.sh 的 Shizuku 版本有效；没有脚本或未安装时静默跳过，
     * 结果写入守护日志，方便排查。重启后无线调试关闭，仍需重新配对。
     */
    private void startShizukuIfPossible() {
        if (!Prefs.shizukuAutoStart(this)) return;
        try {
            getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
        } catch (Throwable t) {
            return;
        }
        final String sh = "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh";
        new Thread(new Runnable() {
            @Override
            public void run() {
                String cmd = "if [ -f " + sh + " ]; then sh " + sh + " >/dev/null 2>&1; rc=$?; "
                        + "echo \"$(date '+%m-%d %H:%M:%S') shizuku: start.sh rc=$rc\" >> "
                        + Scripts.LOG + "; else "
                        + "echo \"$(date '+%m-%d %H:%M:%S') shizuku: start.sh 不存在，已跳过\" >> "
                        + Scripts.LOG + "; fi";
                AdbBridge.run(AdbPairingService.this, cmd, 20000);
            }
        }).start();
    }

    private CharSequence readCode(Intent intent) {
        if (intent == null) return null;
        // 通知 RemoteInput 的输入
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results != null) {
            CharSequence c = results.getCharSequence(KEY_CODE);
            if (c != null) return c;
        }
        // 输入页（PairCodeActivity）用普通 extra 提交
        CharSequence extra = intent.getCharSequenceExtra(EXTRA_CODE);
        if (extra != null) return extra;
        return intent.getStringExtra(EXTRA_CODE) == null ? null : intent.getStringExtra(EXTRA_CODE);
    }

    private void fail(String message, boolean allowRetry) {
        retryable = allowRetry;
        setState(STATE_FAILED, message);
        if (allowRetry) {
            // 配对弹窗可能还开着，重新开始搜索，通知里会再次出现输入入口
            if (pairMdns == null) startPairDiscovery();
        } else {
            detachAndStop();
        }
    }

    private void detachAndStop() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
        foreground = false;
        stopSelf();
    }

    private void setState(int state, String message) {
        String m = message == null ? "" : message;
        // 相同状态/文案不重复刷新通知：通知内联输入框在刷新时会收起，
        // 用户正在输码时反复刷新会导致「点发送没反应、要点好几次」。
        if (state == sState && m.equals(sMessage)) return;
        sState = state;
        sMessage = m;
        if (state != STATE_FAILED) retryable = false;
        updateNotification();
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, sState);
        i.putExtra(EXTRA_MESSAGE, sMessage);
        i.putExtra(EXTRA_PORT, sPort);
        sendBroadcast(i);
    }

    private void updateNotification() {
        if (nm == null) return;
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(I18n.t("无线调试配对", "Wireless debugging pairing"))
                .setContentText(sMessage)
                .setStyle(new Notification.BigTextStyle().bigText(sMessage))
                .setColor(Color.parseColor("#2563EB"))
                .setOnlyAlertOnce(false);

        // 等待输码时不要给通知正文设置点击目标：点正文会打开应用、离开系统配对弹窗，
        // 部分 ROM 会立刻关闭弹窗，配对端口和 6 位配对码随之失效。
        // 只保留「输入配对码」这个 RemoteInput 动作，用户在下拉通知里输入即可。
        PendingIntent content = contentIntent();
        if (content != null) b.setContentIntent(content);

        if (sState == STATE_CONNECTED || sState == STATE_FAILED) {
            b.setOngoing(false).setAutoCancel(true);
        } else {
            b.setOngoing(true).setAutoCancel(false);
        }

        if (sState == STATE_FOUND || (sState == STATE_FAILED && retryable)) {
            RemoteInput input = new RemoteInput.Builder(KEY_CODE)
                    .setLabel(I18n.t("6 位配对码", "6-digit pairing code"))
                    .setAllowFreeFormInput(true)
                    .build();
            Intent reply = new Intent(this, AdbPairingService.class);
            reply.setAction(ACTION_REPLY);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getForegroundService(this, 2, reply, flags);
            Notification.Action action = new Notification.Action.Builder(
                    R.drawable.ic_stat,
                    I18n.t("输入配对码", "Enter pairing code"),
                    pi).addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                    .build();
            b.addAction(action);

            // 通知里的系统输入框较小，另给一个「大按钮输入」入口：
            // 打开 PairCodeActivity，输入框和「发送配对码」按钮都更大。
            Intent big = new Intent(this, PairCodeActivity.class);
            big.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            big.putExtra(EXTRA_PORT, sPort);
            b.addAction(new Notification.Action.Builder(
                    R.drawable.ic_stat,
                    I18n.t("大按钮输入", "Big input"),
                    PendingIntent.getActivity(this, 4, big,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        }

        if (sState != STATE_CONNECTED) {
            Intent stop = new Intent(this, AdbPairingService.class);
            stop.setAction(ACTION_STOP);
            int stopFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                stopFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            b.addAction(new Notification.Action.Builder(
                    R.drawable.ic_stat,
                    I18n.t("停止", "Stop"),
                    PendingIntent.getForegroundService(this, 3, stop, stopFlags)).build());
        }

        return b.build();
    }

    /**
     * 通知主体点击后的去处。
     * 等待输码时返回 null：此时点正文会把用户带出系统「无线调试」页面，
     * 配对弹窗随之关闭，配对码失效。用户只需要下拉通知并使用动作按钮。
     * 失败（尤其是没找到连接端口）时允许点正文回到应用手动填写端口。
     */
    private PendingIntent contentIntent() {
        if (sState == STATE_FOUND || (sState == STATE_FAILED && retryable)) {
            return null;
        }
        Intent i = new Intent(this, AdbActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
