package com.watchunlock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 三级页面：无线调试配对与连接 */
public class AdbActivity extends Activity {

    private static final int C_BG = 0xFFF3F4F6;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_OK = 0xFF059669;
    private static final int C_BAD = 0xFFDC2626;
    private static final int C_INFO_BG = 0xFFEFF6FF;
    private static final int C_INFO_TEXT = 0xFF1D4ED8;
    private static final int C_WARN_BG = 0xFFFFF7ED;
    private static final int C_WARN_TEXT = 0xFFC2410C;

    /** 晨钟酱《搞机工具箱》Web 版（GPL-3.0）：仅作为外部入口，不打包进 APK */
    private static final String WEB_ADB_URL = "https://czjam.github.io/gjgjxweb/";
    private static final String WEB_ADB_REPO = "https://github.com/CzJam/gjgjxweb";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private TextView status;
    private TextView guideStatus;
    private TextView searchStatus;
    private LinearLayout stepsCard;
    private LinearLayout notificationCard;
    private EditText portField;
    private EditText pairPortField;
    private boolean notificationsEnabled;
    private BroadcastReceiver stateReceiver;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        Privilege.init(this);
        setTitle(I18n.t("无线调试", "Wireless debugging"));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        sv.addView(root, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText(I18n.t("无线调试（内置 ADB）", "Wireless debugging (built-in ADB)"));
        title.setTextSize(22);
        title.setTextColor(C_TEXT);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        LinearLayout stateCard = card();
        stateCard.addView(sectionTitle(I18n.t("当前状态", "Current state")));
        guideStatus = body(I18n.t("正在检测…", "Checking…"));
        stateCard.addView(guideStatus, gap(8));
        root.addView(stateCard, gap(12));

        notificationsEnabled = notificationsEnabled();

        notificationCard = card(C_WARN_BG);
        notificationCard.addView(sectionTitle(I18n.t("通知未开启", "Notifications are off")));
        notificationCard.addView(body(I18n.t(
                "配对码需要在通知里输入，请先允许本应用发送通知。\n"
                        + "MIUI / 澎湃用户如果通知里没有输入框，请到 系统设置 → 通知管理 → 通知显示设置，"
                        + "把通知样式切换为「原生样式」。",
                "The pairing code is entered in a notification. Allow notifications for this app first.\n"
                        + "On MIUI / HyperOS, if the notification has no input box, open System settings → "
                        + "Notification management → Notification display settings and switch to the native style.")));
        Button notif = button(I18n.t("打开通知设置", "Open notification settings"));
        notif.setOnClickListener(v -> openNotificationSettings());
        notificationCard.addView(notif, gap(10));
        notificationCard.setVisibility(notificationsEnabled ? View.GONE : View.VISIBLE);
        root.addView(notificationCard, gap(12));

        root.addView(notice(I18n.t("一条来自本应用的通知将帮助您完成配对。",
                "A notification from this app will help you finish pairing."), C_INFO_BG, C_INFO_TEXT), gap(12));

        root.addView(notice(I18n.t(
                "配对服务通过局域网发现本机端口，请允许本应用访问网络。"
                        + "小米 / 澎湃用户建议把本应用的电池策略设为「无限制」，避免配对服务被后台清理。",
                "The pairing service discovers the local port over the local network; network access is required. "
                        + "On Xiaomi / HyperOS, set this app's battery policy to “No restrictions” so the pairing service is not killed."),
                C_INFO_BG, C_INFO_TEXT), gap(12));

        if (isMiui()) {
            root.addView(notice(I18n.t(
                    "MIUI / 澎湃用户注意：如果通知里看不到输入框，请到 系统设置 → 通知管理 → 通知显示设置，"
                            + "把通知样式切换为「原生样式」。否则可能无法在通知里输入配对码。",
                    "MIUI / HyperOS: if the notification has no input box, go to System settings → "
                            + "Notification management → Notification display settings and switch to the native style. "
                            + "Otherwise you may not be able to enter the pairing code in the notification."),
                    C_WARN_BG, C_WARN_TEXT), gap(12));
        }

        stepsCard = card();
        stepsCard.addView(sectionTitle(I18n.t("操作步骤", "Steps")));
        stepsCard.addView(body(I18n.t(
                "1. 点下面的「开发者选项」。系统会跳到开发者选项，并尽量把「无线调试」滚动到可见位置。\n"
                        + "2. 点「无线调试」这一行左侧进入子页面（右侧只是开关），把开关打开。\n"
                        + "3. 点「使用配对码配对设备」，保持这个弹窗打开；本应用会自动找到配对端口。\n"
                        + "4. 下拉展开通知，点「输入配对码」并在通知里输入 6 位码；"
                        + "不要点通知正文，也不要切到本应用页面，否则系统会关闭配对弹窗、配对码随之失效。\n"
                        + "提示：如果开发者选项里没有「无线调试」，说明开发者选项还没开启。",
                "1. Tap Developer options below. Settings opens and tries to scroll Wireless debugging into view.\n"
                        + "2. Tap the left side of the Wireless debugging row to open the sub-page (the right side is only the switch). Turn it on.\n"
                        + "3. Tap “Pair device with pairing code” and keep the dialog open; this app finds the pairing port automatically.\n"
                        + "4. Pull down and expand the notification, tap “Enter pairing code” and type the 6-digit code there. "
                        + "Do not tap the notification body and do not switch to this app: the system closes the pairing dialog and the code becomes invalid.\n"
                        + "Note: if Wireless debugging is missing, Developer options is not enabled yet.")));
        Button dev = button(I18n.t("开发者选项", "Developer options"));
        dev.setOnClickListener(v -> openWirelessDebugging());
        stepsCard.addView(dev, gap(10));
        searchStatus = body(I18n.t("配对通知尚未开始", "Pairing notification not started"));
        stepsCard.addView(searchStatus, gap(8));
        Button rescan = button(I18n.t("重新搜索配对服务", "Search for the pairing service again"));
        rescan.setOnClickListener(v -> {
            if (!notificationsEnabled()) {
                toast(I18n.t("请先开启通知", "Enable notifications first"));
                return;
            }
            if (Build.VERSION.SDK_INT < 30) {
                toast(I18n.t("系统版本过低，请使用下面的手动配对", "Android 11+ is required; use manual pairing below"));
                return;
            }
            AdbPairingService.stop(this);
            AdbPairingService.start(this);
            toast(I18n.t("已重新开始搜索", "Searching again"));
        });
        stepsCard.addView(rescan, gap(6));

        Switch autoShizuku = new Switch(this);
        autoShizuku.setText(I18n.t("配对连接成功后尝试启动 Shizuku（默认关闭）",
                "Try starting Shizuku after a successful connection (off by default)"));
        autoShizuku.setTextSize(13);
        autoShizuku.setTextColor(C_TEXT);
        autoShizuku.setChecked(Prefs.shizukuAutoStart(this));
        autoShizuku.setPadding(0, dp(12), 0, 0);
        autoShizuku.setOnCheckedChangeListener((btn, v) -> {
            Prefs.setShizukuAutoStart(this, v);
            if (v && !shizukuInstalled()) {
                toast(I18n.t("没有检测到 Shizuku，仍会保留这个开关",
                        "Shizuku is not installed; the switch stays on"));
            }
        });
        stepsCard.addView(autoShizuku);
        stepsCard.addView(body(I18n.t(
                "开启后，配对并连接成功时会用内置 ADB 执行 Shizuku 官方的 start.sh。"
                        + "只对「可被 ADB 启动」的 Shizuku 版本有效；脚本不存在时会静默跳过，"
                        + "请手动打开 Shizuku 并用「通过无线调试启动」。重启后无线调试会关闭，需要重新配对。",
                "When on, the app runs Shizuku's official start.sh over the built-in ADB after pairing. "
                        + "It only works on Shizuku builds that can be started by ADB; if the script is missing it skips "
                        + "silently — open Shizuku and use “Start via wireless debugging” instead. Wireless debugging "
                        + "turns off after a reboot, so you must pair again.")));
        stepsCard.setVisibility(notificationsEnabled ? View.VISIBLE : View.GONE);
        root.addView(stepsCard, gap(12));

        LinearLayout cfg = card();
        cfg.addView(sectionTitle(I18n.t("手动配对 / 连接（备用）", "Manual pairing / connection (fallback)")));
        cfg.addView(body(I18n.t(
                "通知方式优先：下拉通知，点「输入配对码」，在通知里输入 6 位码；"
                        + "配对期间不要点通知正文，也不要离开系统配对弹窗。\n"
                        + "手动方式仅在通知不可用时使用：从系统的「使用配对码配对设备」弹窗读取配对端口和 6 位配对码，"
                        + "连接端口从无线调试主页面读取。离开弹窗后配对码通常立即失效，成功率很低。",
                "Prefer the notification flow: pull down, tap “Enter pairing code” and type the 6-digit code in the notification; "
                        + "do not tap the notification body and do not leave the system pairing dialog.\n"
                        + "The manual fallback is only for when notifications are unavailable: read the pairing port and 6-digit "
                        + "code from the system dialog, and the connect port from the Wireless debugging main screen. The code "
                        + "usually expires as soon as the dialog is left, so this path is unreliable.")));

        cfg.addView(label(I18n.t("主机（默认 127.0.0.1，即本机）", "Host (default 127.0.0.1, this device)")), gap(8));
        final EditText host = field(Prefs.adbHost(this), InputType.TYPE_CLASS_TEXT);
        cfg.addView(host, gap(4));

        cfg.addView(label(I18n.t("连接端口（无线调试主页面）",
                "Connect port (main Wireless debugging screen)")), gap(8));
        portField = field(Prefs.adbPort(this) > 0 ? String.valueOf(Prefs.adbPort(this)) : "",
                InputType.TYPE_CLASS_NUMBER);
        cfg.addView(portField, gap(4));

        cfg.addView(label(I18n.t("配对端口（配对码弹窗内）",
                "Pairing port (inside the pairing dialog)")), gap(8));
        pairPortField = field(Prefs.adbPairPort(this) > 0 ? String.valueOf(Prefs.adbPairPort(this)) : "",
                InputType.TYPE_CLASS_NUMBER);
        cfg.addView(pairPortField, gap(4));

        cfg.addView(label(I18n.t("配对码（6 位数字）", "Pairing code (6 digits)")), gap(8));
        final EditText code = field("", InputType.TYPE_CLASS_NUMBER);
        code.setHint("000000");
        cfg.addView(code, gap(4));

        status = body(I18n.t("尚未连接", "Not connected"));
        cfg.addView(status, gap(10));

        Button pair = button(I18n.t("配对", "Pair"));
        pair.setOnClickListener(v -> doPair(host, portField, pairPortField, code, pair));
        cfg.addView(pair, gap(6));

        Button test = button(I18n.t("连接测试", "Test connection"));
        test.setOnClickListener(v -> doConnect(host, portField, test));
        cfg.addView(test, gap(6));

        Button off = button(I18n.t("断开连接", "Disconnect"));
        off.setOnClickListener(v -> {
            AdbBridge.close();
            refreshStatus();
        });
        cfg.addView(off, gap(6));

        root.addView(cfg, gap(12));

        LinearLayout console = card();
        console.addView(sectionTitle(I18n.t("ADB 命令行（高级）", "ADB command line (advanced)")));
        console.addView(body(I18n.t(
                "直接输入一条 adb shell 命令并执行，例如 pm list packages | grep watchunlock。",
                "Type and run an adb shell command, e.g. pm list packages | grep watchunlock.")));
        final EditText cmd = field("", InputType.TYPE_CLASS_TEXT);
        cmd.setHint("pm list packages");
        console.addView(cmd, gap(6));
        Button runCmd = button(I18n.t("执行命令", "Run command"));
        console.addView(runCmd, gap(6));
        final TextView out = body(I18n.t("尚未执行", "Not run yet"));
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12);
        out.setTextIsSelectable(true);
        console.addView(out, gap(10));
        runCmd.setOnClickListener(v -> doExec(cmd, runCmd, out));
        root.addView(console, gap(12));

        root.addView(buildWebAdbCard(), gap(12));

        setContentView(sv);
        refreshGuide();
        refreshStatus();
        requestNotificationPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerStateReceiver();
        if (Build.VERSION.SDK_INT >= 30
                && notificationsEnabled()
                && AdbPairingService.state() != AdbPairingService.STATE_CONNECTED) {
            AdbPairingService.start(this);
        }
    }

    @Override
    protected void onStop() {
        unregisterStateReceiver();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGuide();
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1001) {
            notificationsEnabled = notificationsEnabled();
            refreshGuide();
            if (notificationsEnabled && Build.VERSION.SDK_INT >= 30) AdbPairingService.start(this);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private boolean notificationsEnabled() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        NotificationChannel ch = nm.getNotificationChannel(AdbPairingService.CHANNEL);
        return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private boolean isMiui() {
        String s = (String.valueOf(Build.BRAND) + " " + String.valueOf(Build.MANUFACTURER)).toLowerCase();
        return s.contains("xiaomi") || s.contains("redmi") || s.contains("poco");
    }

    private boolean shizukuInstalled() {
        try {
            getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean devOptionsEnabled() {
        try {
            return Settings.Global.getInt(getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean wirelessDebuggingEnabled() {
        try {
            return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshGuide() {
        notificationsEnabled = notificationsEnabled();
        if (notificationCard != null) {
            notificationCard.setVisibility(notificationsEnabled ? View.GONE : View.VISIBLE);
        }
        if (stepsCard != null) {
            stepsCard.setVisibility(notificationsEnabled ? View.VISIBLE : View.GONE);
        }
        if (guideStatus == null) return;
        boolean dev = devOptionsEnabled();
        boolean wifi = wirelessDebuggingEnabled();
        int st = AdbPairingService.state();
        int port = AdbPairingService.port();
        String stateText = pairingStateText(st, port);
        guideStatus.setTextColor(st == AdbPairingService.STATE_CONNECTED ? C_OK : C_SUB);
        guideStatus.setText(I18n.t("开发者选项：", "Developer options: ")
                + (dev ? I18n.t("已开启", "on") : I18n.t("未开启", "off"))
                + "\n" + I18n.t("无线调试：", "Wireless debugging: ")
                + (wifi ? I18n.t("已开启", "on") : I18n.t("未开启", "off"))
                + "\n" + I18n.t("配对通知：", "Pairing notification: ") + stateText);
        if (searchStatus != null) searchStatus.setText(stateText);
        if (portField != null && portField.getText().toString().trim().isEmpty() && Prefs.adbPort(this) > 0) {
            portField.setText(String.valueOf(Prefs.adbPort(this)));
        }
        if (pairPortField != null && pairPortField.getText().toString().trim().isEmpty()
                && Prefs.adbPairPort(this) > 0) {
            pairPortField.setText(String.valueOf(Prefs.adbPairPort(this)));
        }
    }

    private String pairingStateText(int state, int port) {
        String msg = AdbPairingService.message();
        switch (state) {
            case AdbPairingService.STATE_SEARCHING:
                return I18n.t("正在搜索配对服务；请先在开发者选项里打开「使用配对码配对设备」弹窗",
                        "Searching; open “Pair device with pairing code” in Developer options first");
            case AdbPairingService.STATE_FOUND:
                return I18n.t("已找到配对服务（端口 %d）。下拉通知点「输入配对码」，不要点正文、不要离开系统弹窗",
                        "Pairing service found (port %d). Pull down, tap “Enter pairing code” in the notification; "
                                + "do not tap the body or leave the system dialog", port);
            case AdbPairingService.STATE_PAIRING:
                return I18n.t("正在配对…", "Pairing…");
            case AdbPairingService.STATE_CONNECTING:
                return I18n.t("配对成功，正在连接…", "Paired. Connecting…");
            case AdbPairingService.STATE_CONNECTED:
                return I18n.t("已连接（端口 %d）", "Connected (port %d)", port);
            case AdbPairingService.STATE_FAILED:
                return msg;
            default:
                return I18n.t("尚未开始", "Not started");
        }
    }

    private void registerStateReceiver() {
        if (stateReceiver != null) return;
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshGuide();
            }
        };
        IntentFilter filter = new IntentFilter(AdbPairingService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    private void unregisterStateReceiver() {
        if (stateReceiver == null) return;
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {
        }
        stateReceiver = null;
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
    }

    private void showDevOptionsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(I18n.t("开发者选项未开启", "Developer options are off"))
                .setMessage(I18n.t(
                        "请先开启开发者选项（各品牌位置不同）：\n\n"
                                + "• 小米 / Redmi / 澎湃：设置 → 我的设备 → 全部参数与信息 → 连续点击「OS 版本」7 次\n"
                                + "• 华为（非鸿蒙 NEXT）：设置 → 关于手机 → 连续点击「版本号」7 次\n"
                                + "• 荣耀：设置 → 关于手机 → 连续点击「版本号」7 次\n"
                                + "• OPPO / realme / 一加：设置 → 关于本机 → 版本信息 → 连续点击「版本号」7 次\n"
                                + "• vivo / iQOO：设置 → 关于手机 → 连续点击「软件版本号」7 次\n"
                                + "• 原生 Android：设置 → 关于手机 → 连续点击「版本号」7 次\n\n"
                                + "开启后返回本页重试。",
                        "Enable Developer options first (the location varies by brand):\n\n"
                                + "• Xiaomi / Redmi / HyperOS: Settings → My device → All specs → tap “OS version” 7 times\n"
                                + "• Huawei (not HarmonyOS NEXT): Settings → About phone → tap “Build number” 7 times\n"
                                + "• Honor: Settings → About phone → tap “Build number” 7 times\n"
                                + "• OPPO / realme / OnePlus: Settings → About device → Version info → tap “Build number” 7 times\n"
                                + "• vivo / iQOO: Settings → About phone → tap “Software version” 7 times\n"
                                + "• Stock Android: Settings → About phone → tap “Build number” 7 times\n\n"
                                + "Then return to this page and retry."))
                .setPositiveButton(I18n.t("去「关于手机」", "Open About phone"), (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable t) {
                        toast(I18n.t("无法打开系统设置", "Cannot open system settings"));
                    }
                })
                .setNegativeButton(I18n.t("关闭", "Close"), null)
                .show();
    }

    private void doPair(EditText host, EditText port, EditText pairPort, EditText code, Button btn) {
        String h = host.getText().toString().trim();
        int pp = parseInt(pairPort.getText().toString());
        int cp = parseInt(port.getText().toString());
        String c = code.getText().toString().trim();
        if (h.isEmpty()) h = "127.0.0.1";
        if (pp <= 0) {
            toast(I18n.t("请填写配对端口", "Enter the pairing port"));
            return;
        }
        if (c.length() != 6) {
            toast(I18n.t("配对码必须是 6 位数字", "The pairing code must be 6 digits"));
            return;
        }
        if (cp <= 0) {
            toast(I18n.t("请填写连接端口", "Enter the connect port"));
            return;
        }
        Prefs.setAdbHost(this, h);
        Prefs.setAdbPairPort(this, pp);
        final String fh = h;
        final int fpp = pp;
        final int fcp = cp;
        final String fc = c;
        setBusy(btn, I18n.t("配对中…", "Pairing…"));
        pool.execute(() -> {
            String err = AdbBridge.pair(this, fh, fpp, fc);
            if (err != null) {
                ui.post(() -> {
                    idle(btn, I18n.t("配对", "Pair"));
                    status.setTextColor(C_BAD);
                    status.setText(I18n.t("配对失败：", "Pairing failed: ") + err);
                });
                return;
            }
            Prefs.setAdbPort(this, fcp);
            boolean ok = AdbBridge.connect(this);
            if (ok) {
                Prefs.setPrivilegeMode(this, Privilege.ADB);
                Scripts.applyConfig(this);
            }
            ui.post(() -> {
                idle(btn, I18n.t("配对", "Pair"));
                status.setTextColor(ok ? C_OK : C_BAD);
                status.setText(ok
                        ? I18n.t("配对成功，已连接（端口 %d）", "Paired and connected (port %d)", fcp)
                        : I18n.t("配对成功，但连接失败：%s", "Paired, but connection failed: %s",
                        AdbBridge.lastError()));
            });
        });
    }

    private void doConnect(EditText host, EditText port, Button btn) {
        String h = host.getText().toString().trim();
        int cp = parseInt(port.getText().toString());
        if (h.isEmpty()) h = "127.0.0.1";
        if (cp <= 0) {
            toast(I18n.t("请填写连接端口", "Enter the connect port"));
            return;
        }
        Prefs.setAdbHost(this, h);
        Prefs.setAdbPort(this, cp);
        final int fcp = cp;
        setBusy(btn, I18n.t("连接中…", "Connecting…"));
        pool.execute(() -> {
            boolean ok = AdbBridge.connect(this);
            if (ok) {
                Prefs.setPrivilegeMode(this, Privilege.ADB);
                Scripts.applyConfig(this);
            }
            ui.post(() -> {
                idle(btn, I18n.t("连接测试", "Test connection"));
                status.setTextColor(ok ? C_OK : C_BAD);
                status.setText(ok
                        ? I18n.t("已连接，shell 可用（端口 %d）", "Connected, shell available (port %d)", fcp)
                        : I18n.t("连接失败：%s", "Connection failed: %s", AdbBridge.lastError()));
            });
        });
    }

    /**
     * 网页版 ADB：不依赖本机回环端口，用浏览器 + WebUSB 直接调试安卓设备。
     * 页面属于晨钟酱的《搞机工具箱》Web 版（GPL-3.0），这里只做入口与说明，不打包其代码。
     */
    private LinearLayout buildWebAdbCard() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("网页版 ADB（USB，免回环端口）",
                "Web ADB (USB, no loopback port)")));
        c.addView(body(I18n.t(
                "为什么需要它：谷歌可能进一步收紧「本机回环 ADB」（127.0.0.1）的配对与授权。"
                        + "这个方式不走手机自己的 adbd 回环端口，而是用 WebUSB 把目标安卓设备直接连到另一台设备，"
                        + "在浏览器里执行 ADB 命令，可作为无线调试失效时的备用通道。\n\n"
                        + "归属与版本：网页由 晨钟酱（CzJam）所有并维护，版本 6.3.9，"
                        + "属于开源项目《搞机工具箱》Web 版（GPL-3.0）。本应用只提供入口和使用说明，"
                        + "没有内置该网页代码，也不控制其内容；版权与许可见项目仓库。\n\n"
                        + "适用设备：主机可以是除 iOS 以外的任何设备——Windows、macOS、Linux、Android 平板或另一台手机，"
                        + "只要浏览器基于 Chromium（Chrome / Edge / Brave / Opera 等）并支持 WebUSB；"
                        + "被调试端必须是安卓设备。iOS 上的浏览器没有 WebUSB，不能用作主机；Firefox 也不支持 WebUSB。\n\n"
                        + "使用步骤：\n"
                        + "1. 目标安卓：开发者选项 → 打开「USB 调试」。\n"
                        + "2. 连接数据线：电脑直连；手机作主机时用 OTG 转接头；两台手机用 C-to-C 数据线。\n"
                        + "3. 在主机上用 Chrome / Edge 打开下面的网页（首次加载需要联网）。\n"
                        + "4. 点网页里的「连接」，选择 USB 设备，并在目标手机上允许 USB 调试授权。\n"
                        + "5. 在网页的 ADB 终端执行命令，例如查看设备信息、临时授权或部署脚本。\n\n"
                        + "注意：这是外部网页，请从下面的官方地址打开；本应用本身仍不联网、不上传数据，"
                        + "打开后由浏览器直接与设备通信。网页首次加载需要网络，之后能否离线使用取决于浏览器缓存。",
                "Why: Google may further restrict pairing/authorization for local loopback ADB (127.0.0.1). "
                        + "This path does not use the phone's own adbd loopback port. Instead, WebUSB connects the "
                        + "target Android device directly to another device and runs ADB commands in a browser, "
                        + "as a fallback when wireless debugging is unavailable.\n\n"
                        + "Owner and version: the page is owned and maintained by 晨钟酱 (CzJam), version 6.3.9, "
                        + "part of the open-source “搞机工具箱” Web edition (GPL-3.0). This app only provides an "
                        + "entry point and instructions; it does not bundle the page or control its content. "
                        + "Copyright and license are in the project repository.\n\n"
                        + "Host devices: any device except iOS — Windows, macOS, Linux, Android tablets or another "
                        + "phone — with a Chromium-based browser (Chrome / Edge / Brave / Opera, etc.) that supports "
                        + "WebUSB. The device being debugged must be Android. iOS browsers have no WebUSB and cannot "
                        + "be used as the host; Firefox does not support WebUSB either.\n\n"
                        + "Steps:\n"
                        + "1. On the target Android device: Developer options → enable “USB debugging”.\n"
                        + "2. Connect a data cable: a computer directly; an OTG adapter when a phone is the host; "
                        + "a C-to-C cable between two phones.\n"
                        + "3. Open the page below in Chrome / Edge on the host (the first load needs network).\n"
                        + "4. Tap “Connect” on the page, choose the USB device, and allow USB debugging on the target.\n"
                        + "5. Run commands in the page's ADB terminal, e.g. read device info, grant temporary "
                        + "permissions, or deploy a script.\n\n"
                        + "Note: this is an external page; open it from the official address below. This app itself "
                        + "still makes no network requests and uploads nothing; after opening, the browser talks to "
                        + "the device directly. The first load needs the network; offline use depends on the "
                        + "browser cache.")));
        Button open = button(I18n.t("打开网页版搞机工具箱 6.3.9",
                "Open the web toolbox 6.3.9"));
        open.setOnClickListener(v -> openWebPage(WEB_ADB_URL, true));
        c.addView(open, gap(10));
        Button copy = button(I18n.t("复制网页地址", "Copy the page address"));
        copy.setOnClickListener(v -> copyText(I18n.t("网页地址已复制", "Page address copied"), WEB_ADB_URL));
        c.addView(copy, gap(6));
        Button repo = button(I18n.t("查看晨钟酱的开源项目（GitHub）",
                "View 晨钟酱's open-source project (GitHub)"));
        repo.setOnClickListener(v -> openWebPage(WEB_ADB_REPO, false));
        c.addView(repo, gap(6));
        c.addView(notice(I18n.t(
                "本项目与晨钟酱无隶属关系，仅致谢并链接其公开作品；网页的可用性、内容与数据安全由该网页自行负责。",
                "This project is not affiliated with 晨钟酱; we thank and link to their public work. "
                        + "The page's availability, content and data handling are its own responsibility."),
                C_WARN_BG, C_WARN_TEXT), gap(10));
        return c;
    }

    /** 优先拉起支持 WebUSB 的 Chromium 浏览器；找不到时交给系统选择器 */
    private void openWebPage(String url, boolean needWebUsb) {
        Uri uri = Uri.parse(url);
        if (needWebUsb) {
            String[] packages = {
                    "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
                    "com.microsoft.emmx", "com.brave.browser", "com.opera.browser", "com.vivaldi.browsers"
            };
            for (String pkg : packages) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg);
                    if (i.resolveActivity(getPackageManager()) != null) {
                        startActivity(i);
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
            toast(I18n.t("未检测到 Chrome / Edge，请在系统选择器里选择支持 WebUSB 的 Chromium 浏览器",
                    "Chrome / Edge not found; pick a Chromium browser that supports WebUSB in the chooser"));
        }
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri),
                    I18n.t("选择浏览器", "Choose a browser")));
        } catch (Throwable t) {
            toast(I18n.t("没有可用的浏览器，请手动访问：%s", "No browser available; open manually: %s", url));
        }
    }

    private void copyText(String msg, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("webadb", text));
                toast(msg);
                return;
            }
        } catch (Throwable ignored) {
        }
        toast(I18n.t("复制失败，请手动输入：%s", "Copy failed; type it manually: %s", text));
    }

    private void refreshStatus() {
        if (status == null) return;
        status.setTextColor(C_SUB);
        status.setText(I18n.t("正在检测…", "Checking…"));
        pool.execute(() -> {
            boolean ready = AdbBridge.isReady();
            String err = AdbBridge.lastError();
            ui.post(() -> {
                status.setTextColor(ready ? C_OK : C_SUB);
                status.setText(ready ? I18n.t("无线调试已连接", "Wireless debugging connected")
                        : I18n.t("尚未连接", "Not connected") + (err.isEmpty() ? "" : " · " + err));
            });
        });
    }

    private void setBusy(Button b, String text) {
        b.setEnabled(false);
        b.setText(text);
    }

    private void idle(Button b, String text) {
        b.setEnabled(true);
        b.setText(text);
    }

    private int parseInt(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 && v < 65536 ? v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 打开系统开发者选项，并尽量把「无线调试」滚动/高亮到可见位置。
     *
     * 不再硬编码 SubSettings + show_fragment：HyperOS 16 与 Android 17 上
     * Settings$SubSettings 已不存在，那个 fragment extra 会被静默忽略。
     * 这里改成「先问系统谁响应开发者选项 ACTION，再逐个校验常见 OEM 组件名」，
     * 兼容小米/澎湃、华为、荣耀、OPPO/realme/一加、vivo/iQOO 与原生 AOSP。
     */
    private void openWirelessDebugging() {
        if (!devOptionsEnabled()) {
            showDevOptionsDialog();
            return;
        }
        ComponentName target = firstDevComponent();
        if (target != null) {
            try {
                Intent i = new Intent();
                i.setComponent(target);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                // Shizuku 同款 key：让系统把无线调试那一行滚动到可见区域并高亮
                i.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
                startActivity(i);
                if (!wirelessDebuggingEnabled()) {
                    toast(I18n.t("已打开开发者选项，请把「无线调试」开关打开",
                            "Developer options opened; turn on Wireless debugging"));
                }
                return;
            } catch (Throwable ignored) {
                // 落到下面的 ACTION 兜底
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            i.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
            startActivity(i);
            if (!wirelessDebuggingEnabled()) {
                toast(I18n.t("已打开开发者选项，请把「无线调试」开关打开",
                        "Developer options opened; turn on Wireless debugging"));
            }
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                toast(I18n.t("请手动进入「开发者选项 → 无线调试」",
                        "Go to Developer options → Wireless debugging manually"));
            } catch (Throwable ignored) {
                toast(I18n.t("无法打开系统设置", "Cannot open system settings"));
            }
        }
    }

    /**
     * 找到当前 ROM 的开发者选项页面组件。
     * 顺序：系统实际解析结果 → 各 OEM 固定组件名（存在性校验）。
     */
    private ComponentName firstDevComponent() {
        try {
            Intent probe = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            java.util.List<android.content.pm.ResolveInfo> list =
                    getPackageManager().queryIntentActivities(probe, 0);
            for (android.content.pm.ResolveInfo r : list) {
                if (r.activityInfo == null || !r.activityInfo.exported) continue;
                return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            }
        } catch (Throwable ignored) {
        }
        String[] pkgs = {
                "com.android.settings",
                "com.miui.settings",
                "com.huawei.settings",
                "com.hihonor.settings",
                "com.oplus.settings",
                "com.coloros.settings",
                "com.vivo.settings"
        };
        String[] classes = {
                "Settings$DevelopmentSettingsActivity",
                "Settings$DevelopmentSettingsDashboardActivity",
                "Settings"
        };
        for (String p : pkgs) {
            for (String c : classes) {
                ComponentName cn = new ComponentName(p, p + "." + c);
                try {
                    getPackageManager().getActivityInfo(cn, 0);
                    return cn;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 执行一条 adb shell 命令，把退出码与输出回显到页面。 */
    private void doExec(EditText cmd, Button btn, TextView out) {
        final String c = cmd.getText().toString().trim();
        if (c.isEmpty()) {
            toast(I18n.t("请输入要执行的命令", "Enter a command first"));
            return;
        }
        setBusy(btn, I18n.t("执行中…", "Running…"));
        out.setTextColor(C_SUB);
        out.setText(I18n.t("执行中…", "Running…"));
        pool.execute(() -> {
            ShizukuBridge.Result r = AdbBridge.run(this, c, 15000);
            StringBuilder sb = new StringBuilder();
            sb.append("$ ").append(c).append('\n');
            if (!r.ok()) sb.append("[exit ").append(r.code).append("]\n");
            if (r.out != null && !r.out.isEmpty()) sb.append(r.out).append('\n');
            if (r.err != null && !r.err.isEmpty()) sb.append(r.err).append('\n');
            String text = sb.toString().trim();
            if (text.length() > 4000) text = text.substring(0, 4000) + "\n…";
            final String shown = text;
            final int code = r.code;
            ui.post(() -> {
                idle(btn, I18n.t("执行命令", "Run command"));
                out.setTextColor(code == 0 ? C_TEXT : C_BAD);
                out.setText(shown);
            });
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        return lp;
    }

    private LinearLayout card() {
        return card(C_CARD);
    }

    private LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(16));
        l.setBackground(g);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        l.setElevation(dp(1));
        return l;
    }

    private LinearLayout notice(String text, int bg, int textColor) {
        LinearLayout l = card(bg);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(textColor);
        t.setLineSpacing(dp(3), 1f);
        l.addView(t);
        return l;
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTextColor(C_TEXT);
        t.getPaint().setFakeBoldText(true);
        return t;
    }

    private TextView label(String s) {
        return label(s, 6);
    }

    private TextView label(String s, int top) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(C_SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(C_SUB);
        t.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private EditText field(String text, int inputType) {
        EditText e = new EditText(this);
        e.setText(text);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setPadding(dp(8), dp(6), dp(8), dp(6));
        return e;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(android.graphics.Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF374151));
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(44)));
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) haptic(v);
            return false;
        });
        return b;
    }

    private void haptic(View v) {
        if (Prefs.vibrate(this)) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        unregisterStateReceiver();
        pool.shutdownNow();
        super.onDestroy();
    }
}
