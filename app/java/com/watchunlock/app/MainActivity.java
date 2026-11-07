package com.watchunlock.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/** 主界面：蓝牙设备解锁（设备 / 条件 / 操作 / 高级） */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1002;
    private static final int C_BG = 0xFFF3F4F6;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_PRIMARY = 0xFF2563EB;
    private static final int C_DANGER = 0xFFDC2626;
    private static final int C_OK = 0xFF16A34A;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_LINE = 0xFFE5E7EB;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private TextView tvDot, tvDaemon, tvShizuku, tvCond, tvNow;
    private TextView tvProtect;
    private Switch swProtect;
    private LinearLayout devBox, wifiBox;
    private Switch swAll, swWifiUnlock, swWifiReq, swHeld, swExtend;
    private TextView tvExtend;
    private Button btnExtendRestore;
    private boolean extendActiveNow = false;
    private boolean extendSyncing = false;
    private EditText etPin, etRssi, etPause;
    private boolean syncing = false;

    private final Set<String> connected = new LinkedHashSet<>();
    private boolean grantedBt = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        Privilege.init(this);
        buildUi();
        ensurePermissions();
        if (Prefs.wantRunning(this)) startMonitor();
        Shizuku.addRequestPermissionResultListener(permListener);
        if (Privilege.SHIZUKU.equals(Prefs.privilegeMode(this))
                && ShizukuBridge.isRunning() && !ShizukuBridge.hasPermission()) {
            // 首次安装后由 Shizuku 弹一次授权框；已授权则不会重复弹
            ShizukuBridge.requestPermission(0);
        }
        refresh();
    }

    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener permListener =
            new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    refresh();
                }
            };

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置授权返回后，自动恢复设备列表（不必退出重进）
        boolean now = Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (now && !grantedBt) {
            grantedBt = true;
            deniedDialogShown = false;
            buildDevices();
        }
        refresh();
        refreshProtectCard();
        if (swProtect != null && swProtect.isChecked() != Prefs.protectEnabled(this)) {
            syncing = true;
            swProtect.setChecked(Prefs.protectEnabled(this));
            syncing = false;
        }
        if (swExtend != null && swExtend.isChecked() != Prefs.extendUnlock(this)) {
            extendSyncing = true;
            swExtend.setChecked(Prefs.extendUnlock(this));
            extendSyncing = false;
        }
        refreshExtendCard();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
        pool.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Drawable cardBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(16));
        return g;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(I18n.t("蓝牙设备解锁", "Bluetooth Device Unlock"));
        title.setTextSize(24);
        title.setTextColor(C_TEXT);
        title.getPaint().setFakeBoldText(true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        // 语言切换：自动 → 中文 → 英文 循环
        TextView btnLang = new TextView(this);
        btnLang.setTextSize(12);
        btnLang.setTextColor(C_PRIMARY);
        btnLang.setPadding(dp(10), dp(6), dp(10), dp(6));
        btnLang.setBackground(cardBg(0xFFE0E7FF));
        updateLangButton(btnLang);
        btnLang.setOnClickListener(v -> {
            String cur = Prefs.lang(MainActivity.this);
            String next = "".equals(cur) ? "zh" : ("zh".equals(cur) ? "en" : "");
            Prefs.setLang(MainActivity.this, next);
            I18n.apply(next);
            recreate();
        });
        LinearLayout.LayoutParams lpLang = new LinearLayout.LayoutParams(-2, -2);
        lpLang.leftMargin = dp(8);
        titleRow.addView(btnLang, lpLang);
        root.addView(titleRow);

        TextView sub = new TextView(this);
        sub.setText(I18n.t("设备在身边时，亮屏自动输入 PIN",
                "Enter your PIN automatically when this device is nearby and the screen wakes"));
        sub.setTextSize(13);
        sub.setTextColor(C_SUB);
        LinearLayout.LayoutParams lpSub = new LinearLayout.LayoutParams(-2, -2);
        lpSub.topMargin = dp(4);
        root.addView(sub, lpSub);

        // 设置改动会热重载；只有首次部署、切换授权方式或升级后才需要重新部署
        TextView deployNote = new TextView(this);
        deployNote.setText(I18n.t("提示：设置改动会自动生效；首次使用、切换授权方式或升级后，请点「部署并启用」。",
                "Note: settings are applied automatically. Tap “Deploy & enable” for the first run, "
                        + "after switching the engine, or after an upgrade."));
        deployNote.setTextSize(12);
        deployNote.setTextColor(0xFFB45309);
        LinearLayout.LayoutParams lpNote = new LinearLayout.LayoutParams(-1, -2);
        lpNote.topMargin = dp(6);
        lpNote.bottomMargin = dp(14);
        root.addView(deployNote, lpNote);

        // 状态卡 + 增强防护：压缩状态卡，右侧并排一个总开关
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout status = card();
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvDot = new TextView(this);
        tvDot.setText("●");
        tvDot.setTextSize(13);
        tvDot.setTextColor(C_SUB);
        tvDaemon = new TextView(this);
        tvDaemon.setText(I18n.t("正在读取状态…", "Reading status…"));
        tvDaemon.setTextSize(14);
        tvDaemon.setTextColor(C_TEXT);
        tvDaemon.getPaint().setFakeBoldText(true);
        tvShizuku = new TextView(this);
        tvShizuku.setText("");
        tvShizuku.setTextSize(11);
        tvShizuku.setTextColor(C_SUB);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tvDot);
        LinearLayout headText = new LinearLayout(this);
        headText.setOrientation(LinearLayout.VERTICAL);
        headText.addView(tvDaemon);
        headText.addView(tvShizuku);
        LinearLayout.LayoutParams lpH = new LinearLayout.LayoutParams(0, -2, 1);
        lpH.leftMargin = dp(6);
        head.addView(headText, lpH);
        status.addView(head);

        tvCond = new TextView(this);
        tvCond.setText(I18n.t("解锁条件：读取中…", "Unlock conditions: reading…"));
        tvCond.setTextSize(11);
        tvCond.setTextColor(C_TEXT);
        tvNow = new TextView(this);
        tvNow.setText("");
        tvNow.setTextSize(10);
        tvNow.setTextColor(C_SUB);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(-1, -2);
        lpC.topMargin = dp(6);
        status.addView(tvCond, lpC);
        LinearLayout.LayoutParams lpN = new LinearLayout.LayoutParams(-1, -2);
        lpN.topMargin = dp(2);
        status.addView(tvNow, lpN);
        topRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1.5f));

        LinearLayout protect = card();
        protect.setPadding(dp(12), dp(10), dp(12), dp(10));
        protect.setOnClickListener(v -> startActivity(new Intent(this, ProtectActivity.class)));
        TextView pTitle = new TextView(this);
        pTitle.setText(I18n.t("增强防护", "Protection"));
        pTitle.setTextSize(14);
        pTitle.setTextColor(C_TEXT);
        pTitle.getPaint().setFakeBoldText(true);
        LinearLayout pHead = row();
        pHead.addView(pTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView pArrow = new TextView(this);
        pArrow.setText("›");
        pArrow.setTextSize(18);
        pArrow.setTextColor(C_PRIMARY);
        pHead.addView(pArrow);
        protect.addView(pHead);
        swProtect = new Switch(this);
        swProtect.setText(I18n.t("总开关", "Master"));
        swProtect.setTextSize(11);
        swProtect.setTextColor(C_TEXT);
        swProtect.setChecked(Prefs.protectEnabled(this));
        swProtect.setOnCheckedChangeListener((btn, v) -> {
            if (syncing) return;
            Prefs.setProtectEnabled(this, v);
            refreshProtectCard();
            applyConfigQuietly();
        });
        LinearLayout.LayoutParams lpSw = new LinearLayout.LayoutParams(-1, -2);
        lpSw.topMargin = dp(4);
        protect.addView(swProtect, lpSw);
        tvProtect = new TextView(this);
        tvProtect.setTextSize(10);
        tvProtect.setTextColor(C_SUB);
        protect.addView(tvProtect, lpSw);
        // 明确告诉用户这张卡可以点进去改，不要只靠「未开启 · 点此设置」这种状态文案
        TextView pOpen = new TextView(this);
        pOpen.setText(I18n.t("点击进入修改 ›", "Tap to edit ›"));
        pOpen.setTextSize(11);
        pOpen.setTextColor(C_PRIMARY);
        protect.addView(pOpen, lpSw);
        refreshProtectCard();
        LinearLayout.LayoutParams lpP = new LinearLayout.LayoutParams(0, -2, 1f);
        lpP.leftMargin = dp(8);
        topRow.addView(protect, lpP);
        addTo(root, topRow, dp(8));

        // 蓝牙设备
        LinearLayout devCard = card();
        LinearLayout devHead = row();
        devHead.addView(sectionTitle(I18n.t("蓝牙设备", "Bluetooth devices")), new LinearLayout.LayoutParams(0, -2, 1));
        Button scan = textButton(I18n.t("重新扫描", "Rescan"));
        scan.setOnClickListener(v -> refresh());
        devHead.addView(scan);
        devCard.addView(devHead);
        TextView devHint = hint(I18n.t("勾选后启用；只对勾选的设备生效。多设备模式下要求勾选的设备全部在线。",
                "Only checked devices are used. In multi-device mode, all checked devices must be online."));
        devCard.addView(devHint);
        devBox = new LinearLayout(this);
        devBox.setOrientation(LinearLayout.VERTICAL);
        devCard.addView(devBox);
        addTo(root, devCard, dp(12));

        // 解锁条件
        LinearLayout condCard = card();
        condCard.addView(sectionTitle(I18n.t("解锁条件", "Unlock conditions")));
        swAll = switchRow(condCard, I18n.t("多设备模式", "Multi-device mode"),
                I18n.t("所有已启用的设备必须同时在身边（关闭时任一在线即可）",
                        "All enabled devices must be nearby at once (when off, any one is enough)"),
                Prefs.modeAll(this), v -> {
                    Prefs.setModeAll(this, v);
                    scheduleApply();
                    renderCondition();
                });
        swWifiUnlock = switchRow(condCard, I18n.t("WiFi 解锁", "Wi-Fi unlock"),
                I18n.t("连着已启用的 WiFi 即可解锁，不要求蓝牙设备在线",
                        "Unlock while on an enabled network, without Bluetooth"),
                Prefs.wifiUnlock(this), v -> {
                    if (syncing) return;
                    Prefs.setWifiUnlock(this, v);
                    if (v && enabledWifiCount() == 0) {
                        toast(I18n.t("还没有启用任何 WiFi：请先添加并打开对应开关",
                                "No Wi-Fi enabled yet. Add a network and turn it on first."));
                    }
                    if (!v && Prefs.wifiRequired(this)) Prefs.setWifiRequired(this, false);
                    syncWifiSwitches();
                    scheduleApply();
                    renderCondition();
                });
        swWifiReq = switchRow(condCard, I18n.t("WiFi 作为蓝牙必要条件", "Wi-Fi required with Bluetooth"),
                I18n.t("必须连着已启用的 WiFi，且蓝牙设备同时在线（判定会多一次 WiFi 查询，约 300ms）",
                        "Must be on an enabled network while the Bluetooth device is nearby "
                                + "(adds a ~300ms Wi-Fi check)"),
                Prefs.wifiRequired(this), v -> {
                    if (syncing) return;
                    if (v) {
                        if (enabledWifiCount() == 0) {
                            toast(I18n.t("还没有启用任何 WiFi：请先添加并打开对应开关",
                                    "No Wi-Fi enabled yet. Add a network and turn it on first."));
                        }
                        if (!Scripts.hasEnabledTarget(Prefs.targets(this))) {
                            toast(I18n.t("还没有勾选蓝牙设备：必要条件需要至少一个蓝牙设备",
                                    "No Bluetooth device checked. This condition needs at least one."));
                        }
                        Prefs.setWifiUnlock(this, true);
                    }
                    Prefs.setWifiRequired(this, v);
                    syncWifiSwitches();
                    scheduleApply();
                    renderCondition();
                });
        condCard.addView(hint(I18n.t("提示：WiFi 判定要看系统当前的 SSID，冷启动或缓存失效时多一次约 300ms 的查询；"
                        + "开启「作为必要条件」会同时要求蓝牙与 WiFi，条件更严格，也可能更慢。",
                "Wi-Fi checks read the current SSID; a cold check costs about 300ms. "
                        + "\"Required with Bluetooth\" demands both, so it is stricter and can be slower.")));

        LinearLayout wifiHead = row();
        TextView wifiTitle = new TextView(this);
        wifiTitle.setText(I18n.t("WiFi 列表", "Wi-Fi networks"));
        wifiTitle.setTextSize(13);
        wifiTitle.setTextColor(C_SUB);
        wifiHead.addView(wifiTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button addCur = textButton(I18n.t("添加当前 WiFi", "Add current Wi-Fi"));
        addCur.setOnClickListener(v -> addCurrentWifi());
        wifiHead.addView(addCur);
        LinearLayout.LayoutParams lpHw = new LinearLayout.LayoutParams(-1, -2);
        lpHw.topMargin = dp(12);
        condCard.addView(wifiHead, lpHw);

        wifiBox = new LinearLayout(this);
        wifiBox.setOrientation(LinearLayout.VERTICAL);
        condCard.addView(wifiBox);
        addTo(root, condCard, dp(12));

        // 操作
        LinearLayout actCard = card();
        actCard.addView(sectionTitle(I18n.t("操作", "Actions")));

        etPin = new EditText(this);
        etPin.setHint(I18n.t("锁屏 PIN（4~16 位数字）", "Lock-screen PIN (4–16 digits)"));
        etPin.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPin.setTextSize(15);
        etPin.setSingleLine(true);
        etPin.setText(Prefs.pin(this));
        actCard.addView(etPin, new LinearLayout.LayoutParams(-1, -2));

        Button deploy = primaryButton(I18n.t("部署并启用", "Deploy & enable"), C_PRIMARY);
        deploy.setOnClickListener(v -> doDeploy());
        addTo(actCard, deploy, dp(12));

        LinearLayout actRow1 = row();
        actRow1.setPadding(0, dp(10), 0, 0);
        Button restart = primaryButton(I18n.t("重启守护", "Restart daemon"), 0xFF374151);
        restart.setOnClickListener(v -> runAction(I18n.t("重启守护", "Restart daemon"), () -> Scripts.restart()));
        Button stop = primaryButton(I18n.t("停止守护", "Stop daemon"), 0xFF9CA3AF);
        stop.setOnClickListener(v -> runAction(I18n.t("停止守护", "Stop daemon"), () -> {
            Prefs.setWantRunning(this, false);
            stopService(new Intent(this, MonitorService.class));
            Scripts.stopHeld();
            return Scripts.stop();
        }));
        addWeighted(actRow1, restart);
        addWeighted(actRow1, stop);
        actCard.addView(actRow1);

        LinearLayout actRow2 = row();
        actRow2.setPadding(0, dp(10), 0, 0);
        Button settings = primaryButton(I18n.t("设置", "Settings"), 0xFF374151);
        settings.setOnClickListener(v -> {
            Prefs.saveRssi(this, etRssi == null ? "0" : etRssi.getText().toString().trim());
            startActivity(new Intent(this, SettingsActivity.class));
        });
        Button clean = primaryButton(I18n.t("一键清理残留", "Clean up all leftovers"), C_DANGER);
        clean.setOnClickListener(v -> confirmClean());
        addWeighted(actRow2, settings);
        addWeighted(actRow2, clean);
        actCard.addView(actRow2);

        LinearLayout actRow3 = row();
        actRow3.setPadding(0, dp(6), 0, 0);
        Button bench = textButton(I18n.t("性能基准", "Performance benchmark"));
        bench.setOnClickListener(v -> runAction(I18n.t("性能基准", "Performance benchmark"), () -> {
            Scripts.Report r = new Scripts.Report();
            r.ok = true;
            r.text = Scripts.bench();
            return r;
        }));
        actRow3.addView(bench, new LinearLayout.LayoutParams(0, -2, 1));
        Button auth = textButton(I18n.t("授权 Shizuku", "Grant Shizuku permission"));
        auth.setOnClickListener(v -> {
            if (Privilege.SHIZUKU.equals(Prefs.privilegeMode(this))) {
                Privilege.requestShizukuPermission();
            } else {
                toast(I18n.t("当前授权方式为 %s，请在设置里切换或完成配置",
                        "Current engine is %s; change or configure it in Settings",
                        Privilege.modeLabel()));
            }
        });
        actRow3.addView(auth, new LinearLayout.LayoutParams(0, -2, 1));
        actCard.addView(actRow3);
        addTo(root, actCard, dp(12));

        // 高级
        LinearLayout advCard = card();
        advCard.addView(sectionTitle(I18n.t("高级", "Advanced")));
        swExtend = switchRow(advCard, I18n.t("延长解锁（激进手段近似版，实验性）",
                        "Extend unlock (aggressive workaround, experimental)"),
                I18n.t("在身边时临时关闭锁屏密码，设备/WiFi 断开后立即写回；会清除生物识别，可能失败",
                        "Temporarily clears the lock-screen PIN while nearby and writes it back as soon as "
                                + "the device/Wi-Fi goes away. Invalidates biometrics and may fail"),
                Prefs.extendUnlock(this), v -> {
                    if (extendSyncing) return;
                    onExtendToggle(v);
                });
        tvExtend = new TextView(this);
        tvExtend.setTextSize(11);
        tvExtend.setTextColor(C_SUB);
        LinearLayout.LayoutParams lpExt = new LinearLayout.LayoutParams(-1, -2);
        lpExt.topMargin = dp(6);
        advCard.addView(tvExtend, lpExt);
        btnExtendRestore = textButton(I18n.t("立即恢复锁屏密码", "Restore lock-screen PIN now"));
        btnExtendRestore.setTextColor(C_DANGER);
        btnExtendRestore.setVisibility(View.GONE);
        btnExtendRestore.setOnClickListener(v -> {
            extendActiveNow = false;
            refreshExtendCard();
            runAction(I18n.t("恢复锁屏密码", "Restore PIN"), () -> Scripts.extendRestore(this));
        });
        advCard.addView(btnExtendRestore);
        swHeld = switchRow(advCard, I18n.t("Shizuku 托管运行", "Run under Shizuku"),
                I18n.t("守护挂在 Shizuku 进程上；关闭则用独立 nohup 进程",
                        "Daemon runs inside the Shizuku process; turn off to use a detached nohup process"),
                false, v -> scheduleApply());
        LinearLayout rssiRow = row();
        TextView rssiLabel = new TextView(this);
        rssiLabel.setText(I18n.t("RSSI 阈值（0 = 不限制）", "RSSI threshold (0 = no limit)"));
        rssiLabel.setTextSize(13);
        rssiLabel.setTextColor(C_TEXT);
        rssiRow.addView(rssiLabel, new LinearLayout.LayoutParams(0, -2, 1));
        etRssi = new EditText(this);
        etRssi.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etRssi.setTextSize(14);
        etRssi.setSingleLine(true);
        etRssi.setText(Prefs.rssi(this));
        etRssi.setWidth(dp(90));
        rssiRow.addView(etRssi);
        advCard.addView(rssiRow);
        advCard.addView(hint(I18n.t("RSSI 是蓝牙信号强度：负值，越接近 0 越强（-60 比 -80 更近）；"
                        + "只有部分系统会暴露该值，读不到时会自动跳过距离限制。填 0 表示不限制。",
                "RSSI is Bluetooth signal strength: negative, closer to 0 is stronger "
                        + "(-60 is nearer than -80). Only some ROMs expose it; if missing, the distance "
                        + "limit is skipped. 0 means no limit.")));

        LinearLayout customPause = row();
        customPause.setPadding(0, dp(10), 0, 0);
        TextView customLabel = new TextView(this);
        customLabel.setText(I18n.t("自定义暂停（秒）", "Custom pause (seconds)"));
        customLabel.setTextSize(13);
        customLabel.setTextColor(C_TEXT);
        customPause.addView(customLabel, new LinearLayout.LayoutParams(0, -2, 1));
        etPause = new EditText(this);
        etPause.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPause.setTextSize(14);
        etPause.setSingleLine(true);
        etPause.setHint("900");
        etPause.setText("900");
        etPause.setWidth(dp(80));
        customPause.addView(etPause);
        Button pauseCustom = textButton(I18n.t("暂停", "Pause"));
        pauseCustom.setOnClickListener(v -> {
            int sec;
            try {
                sec = Integer.parseInt(etPause.getText().toString().trim());
            } catch (Throwable t) {
                sec = 0;
            }
            if (sec < 1 || sec > 86400) {
                toast(I18n.t("请输入 1~86400 秒", "Enter 1–86400 seconds"));
                return;
            }
            final int s = sec;
            runAction(I18n.t("暂停", "Pause"), () -> Scripts.pause(s));
        });
        customPause.addView(pauseCustom);
        advCard.addView(customPause);

        LinearLayout pauseRow = row();
        pauseRow.setPadding(0, dp(8), 0, 0);
        Button p15 = textButton(I18n.t("暂停 15 分钟", "Pause 15 min"));
        p15.setOnClickListener(v -> runAction(I18n.t("暂停", "Pause"), () -> Scripts.pause(900)));
        Button p60 = textButton(I18n.t("暂停 1 小时", "Pause 1 hour"));
        p60.setOnClickListener(v -> runAction(I18n.t("暂停", "Pause"), () -> Scripts.pause(3600)));
        Button p4h = textButton(I18n.t("暂停 4 小时", "Pause 4 hours"));
        p4h.setOnClickListener(v -> runAction(I18n.t("暂停", "Pause"), () -> Scripts.pause(14400)));
        Button pResume = textButton(I18n.t("恢复", "Resume"));
        pResume.setOnClickListener(v -> runAction(I18n.t("恢复", "Resume"), () -> Scripts.resume()));
        addWeighted(pauseRow, p15);
        addWeighted(pauseRow, p60);
        addWeighted(pauseRow, p4h);
        addWeighted(pauseRow, pResume);
        advCard.addView(pauseRow);
        addTo(root, advCard, dp(12));

        // 无线调试入口（页面最底部）
        LinearLayout adbCard = card();
        adbCard.addView(sectionTitle(I18n.t("无线调试", "Wireless debugging")));
        adbCard.addView(hint(I18n.t("进入后可输入 adb 命令直接执行。使用前请先到「设置 → 授权方式 → 无线调试」"
                        + "完成配对与连接。若本机回环 ADB 被限制，可在同一处选择第 4 项「网页版 ADB」"
                        + "作为备用通道（用 USB + 浏览器直接调试安卓设备，不需要回环端口）。",
                "Type and run adb commands inside. Complete pairing and connection in "
                        + "Settings → Privilege → Wireless debugging first. If local loopback ADB is restricted, "
                        + "pick option 4 “Web ADB” there as a fallback: debug an Android device directly over "
                        + "USB in a browser, with no loopback port.")));
        Button adbOpen = primaryButton(I18n.t("无线 ADB 调试", "Wireless ADB debugging"), 0xFF374151);
        adbOpen.setOnClickListener(v -> {
            if (etRssi != null) Prefs.saveRssi(this, etRssi.getText().toString().trim());
            startActivity(new Intent(this, AdbActivity.class));
        });
        addTo(adbCard, adbOpen, dp(10));
        addTo(root, adbCard, dp(12));

        setContentView(sv);
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(cardBg(C_CARD));
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        l.setElevation(dp(1));
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
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

    private TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(C_SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private Button primaryButton(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        b.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        b.setLayoutParams(lp);
        hapticOnPress(b);
        return b;
    }

    private Button textButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(C_PRIMARY);
        b.setBackground(null);
        b.setPadding(dp(6), 0, dp(6), 0);
        hapticOnPress(b);
        return b;
    }

    /** 按下时轻微震动；返回 false 保证原有点击逻辑不受影响 */
    private void hapticOnPress(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) haptic(view);
            return false;
        });
    }

    private void haptic(View v) {
        if (Prefs.vibrate(this)) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    private Switch switchRow(LinearLayout parent, String title, String sub,
                             boolean checked, final OnToggle cb) {
        LinearLayout r = row();
        r.setPadding(0, dp(10), 0, dp(10));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(C_TEXT);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(11);
        s.setTextColor(C_SUB);
        texts.addView(t);
        texts.addView(s);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        final Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((btn, v) -> {
            haptic(sw);
            cb.onChange(v);
        });
        r.addView(sw);
        parent.addView(r);
        return sw;
    }

    private interface OnToggle {
        void onChange(boolean v);
    }

    private void addTo(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = topMargin;
        parent.addView(child, lp);
    }

    /** 普通按钮（授权跳转等局部操作用） */
    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF374151));
        hapticOnPress(b);
        return b;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        return lp;
    }

    private void addWeighted(LinearLayout row, View child) {
        int h = -2;
        if (child.getLayoutParams() != null && child.getLayoutParams().height > 0) {
            h = child.getLayoutParams().height;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1);
        row.addView(child, lp);
    }

    // ------------------------------------------------------- 设备 / WiFi 列表

    private static class Target {
        String mac = "";
        String name = "";
        boolean enabled;
    }

    private static class Wifi {
        String ssid = "";
        String bssid = "";
        /** 0=开放网络，1=加密网络，?=未知（旧数据） */
        String secure = "?";
        boolean enabled;
    }

    private List<Target> parseTargets() {
        List<Target> list = new ArrayList<>();
        for (String line : Prefs.targets(this).split(";")) {
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\\|", -1);
            Target t = new Target();
            t.enabled = p.length > 0 && "1".equals(p[0].trim());
            if (p.length > 1) t.mac = p[1].trim();
            if (p.length > 2) t.name = p[2].trim();
            if (!t.mac.isEmpty() || !t.name.isEmpty()) list.add(t);
        }
        return list;
    }

    private void saveTargets(List<Target> list) {
        StringBuilder sb = new StringBuilder();
        for (Target t : list) {
            if (sb.length() > 0) sb.append(';');
            sb.append(t.enabled ? '1' : '0').append('|').append(t.mac).append('|').append(t.name);
        }
        Prefs.saveTargets(this, sb.toString());
    }

    private List<Wifi> parseWifi() {
        List<Wifi> list = new ArrayList<>();
        for (String line : Prefs.wifiList(this).split(";")) {
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\\|", -1);
            Wifi w = new Wifi();
            w.enabled = p.length > 0 && "1".equals(p[0].trim());
            if (p.length > 1) w.ssid = p[1].trim();
            if (p.length > 2) w.bssid = p[2].trim().toUpperCase();
            if (p.length > 3 && ("0".equals(p[3].trim()) || "1".equals(p[3].trim()))) {
                w.secure = p[3].trim();
            }
            if (!w.ssid.isEmpty()) list.add(w);
        }
        return list;
    }

    private void saveWifi(List<Wifi> list) {
        StringBuilder sb = new StringBuilder();
        for (Wifi w : list) {
            if (sb.length() > 0) sb.append(';');
            sb.append(w.enabled ? '1' : '0').append('|').append(w.ssid).append('|')
                    .append(w.bssid).append('|').append(w.secure);
        }
        Prefs.saveWifiList(this, sb.toString());
    }

    private int enabledWifiCount() {
        int n = 0;
        for (Wifi w : parseWifi()) if (w.enabled) n++;
        return n;
    }

    private void buildDevices() {
        devBox.removeAllViews();
        List<Target> saved = parseTargets();
        List<Target> all = new ArrayList<>(saved);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad != null && grantedBt) {
            try {
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    String mac = d.getAddress() == null ? "" : d.getAddress().toUpperCase();
                    if (mac.startsWith("02:00:00:00:00:00")) mac = "";
                    String nm = d.getName() == null ? "未知设备" : d.getName();
                    Target found = null;
                    for (Target t : all) {
                        if (!mac.isEmpty() && mac.equalsIgnoreCase(t.mac)) found = t;
                        else if (nm.equals(t.name)) found = t;
                    }
                    if (found == null) {
                        Target t = new Target();
                        t.mac = mac;
                        t.name = nm;
                        // 新发现的设备一律默认不启用：避免首次安装时「任一在线」把所有耳机/手表都算进去
                        t.enabled = false;
                        all.add(t);
                    } else if (found.name.isEmpty()) {
                        found.name = nm;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (all.isEmpty()) {
            TextView empty = hint(grantedBt
                    ? I18n.t("没有已配对的设备：先在系统蓝牙里配对，再点「重新扫描」。",
                    "No paired devices. Pair one in system Bluetooth settings, then tap Rescan.")
                    : I18n.t("需要「附近的设备」权限才能读取已配对设备。",
                    "The Nearby devices permission is required to read paired devices."));
            devBox.addView(empty);
            if (!grantedBt) {
                Button go = button(I18n.t("去设置授权「附近的设备」",
                        "Open settings to allow Nearby devices"));
                go.setOnClickListener(v -> openAppSettings());
                devBox.addView(go, gap(8));
            }
            saveTargets(all);
            return;
        }

        for (Target t : all) {
            LinearLayout r = row();
            r.setPadding(0, dp(8), 0, dp(8));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            String label = t.name.isEmpty() ? t.mac : t.name;
            // 「未知设备」是写入配置的占位名，只在这里做显示层翻译
            if ("未知设备".equals(label)) label = I18n.t("未知设备", "Unknown device");
            name.setText(label);
            name.setTextSize(14);
            name.setTextColor(C_TEXT);
            TextView mac = new TextView(this);
            boolean macHit = !t.mac.isEmpty() && (connected.contains(t.mac.toUpperCase())
                    // HyperOS 的 dumpsys 只保留 MAC 末两段（XX:XX:XX:XX:4C:D8），用末 5 位兜底
                    || (t.mac.length() >= 5
                    && connected.contains(t.mac.substring(t.mac.length() - 5).toUpperCase())));
            boolean nameHit = !t.name.isEmpty() && connected.contains(t.name);
            boolean strict = Prefs.protectEnabled(this) && Prefs.macStrict(this);
            boolean unreliable = false;
            boolean on;
            String extra;
            if (strict && !t.mac.isEmpty()) {
                // 有 MAC 就只认 MAC；同名只用于展示，不参与判定
                if (macHit) {
                    on = true;
                    extra = I18n.t("已连接", "Connected");
                } else if (nameHit && Prefs.macNameFallback(this)) {
                    // 允许同名回退时，只有当前确实连着同名设备才放行，并标注身份不可靠
                    on = true;
                    unreliable = true;
                    extra = I18n.t("MAC 不匹配 · 同名设备放行（身份不可靠）",
                            "MAC mismatch · allowed by same name (identity unreliable)");
                } else {
                    on = false;
                    unreliable = nameHit;
                    extra = nameHit
                            ? I18n.t("MAC 不匹配 · 判为不在线（身份不可靠）",
                            "MAC mismatch · treated as offline (identity unreliable)")
                            : I18n.t("未连接", "Not connected");
                }
            } else if (strict) {
                // 确实拿不到 MAC 的旧条目才允许按名称匹配，并明确标注身份不可靠
                on = nameHit;
                unreliable = true;
                extra = (on ? I18n.t("已连接", "Connected") : I18n.t("未连接", "Not connected"))
                        + I18n.t(" · 身份不可靠（无 MAC）", " · identity unreliable (no MAC)");
            } else {
                on = macHit || nameHit;
                extra = on ? I18n.t("已连接", "Connected") : I18n.t("未连接", "Not connected");
            }
            mac.setText((t.mac.isEmpty() ? "" : t.mac + " · ") + extra);
            mac.setTextSize(11);
            mac.setTextColor(unreliable ? 0xFFB45309 : (on ? C_OK : C_SUB));
            texts.addView(name);
            texts.addView(mac);
            r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            Switch sw = new Switch(this);
            sw.setChecked(t.enabled);
            sw.setOnCheckedChangeListener((btn, v) -> {
                haptic(sw);
                t.enabled = v;
                saveTargets(all);
                scheduleApply();
                renderCondition();
                refreshNowText();
            });
            r.addView(sw);
            devBox.addView(r);
        }
        saveTargets(all);
    }

    private void buildWifi() {
        wifiBox.removeAllViews();
        List<Wifi> list = parseWifi();
        if (list.isEmpty()) {
            wifiBox.addView(hint(I18n.t("还没有 WiFi：先连接目标 WiFi，再点右上「添加当前 WiFi」。"
                            + "应用只能读取当前已连接的网络，暂不支持手动输入 SSID。",
                    "No Wi-Fi yet. Connect to the target network, then tap Add current Wi-Fi above. "
                            + "The app can only read the currently connected network; manual SSID entry "
                            + "is not supported.")));
            return;
        }
        for (Wifi w : list) {
            LinearLayout r = row();
            r.setPadding(0, dp(6), 0, dp(6));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(w.ssid);
            name.setTextSize(13);
            name.setTextColor(C_TEXT);
            TextView sub = new TextView(this);
            boolean bind = Prefs.protectEnabled(this) && Prefs.wifiBindBssid(this);
            String sec = I18n.t("加密", "Secure");
            if ("0".equals(w.secure)) sec = I18n.t("开放网络", "Open network");
            else if ("?".equals(w.secure)) sec = I18n.t("安全类型未知", "Security unknown");
            String line = (w.bssid.isEmpty() ? I18n.t("未绑定 BSSID", "No BSSID bound") : w.bssid)
                    + " · " + sec;
            boolean unreliable = bind && w.bssid.isEmpty();
            if (unreliable) line += I18n.t(" · 身份不可靠", " · identity unreliable");
            sub.setText(line);
            sub.setTextSize(10);
            sub.setTextColor(unreliable ? 0xFFB45309 : C_SUB);
            texts.addView(name);
            texts.addView(sub);
            r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            Button del = textButton(I18n.t("删除", "Delete"));
            del.setTextColor(C_DANGER);
            final Wifi fw = w;
            del.setOnClickListener(v -> {
                list.remove(fw);
                saveWifi(list);
                scheduleApply();
                renderCondition();
                buildWifi();
            });
            r.addView(del);
            Switch sw = new Switch(this);
            sw.setChecked(w.enabled);
            sw.setOnCheckedChangeListener((btn, v) -> {
                haptic(sw);
                fw.enabled = v;
                saveWifi(list);
                scheduleApply();
                renderCondition();
                refreshNowText();
            });
            r.addView(sw);
            wifiBox.addView(r);
        }
    }

    private void addCurrentWifi() {
        toast(I18n.t("正在读取当前 WiFi（SSID / BSSID / 安全类型）…",
                "Reading current Wi-Fi (SSID / BSSID / security)…"));
        pool.execute(() -> {
            final String[] info = Scripts.currentWifiInfo();
            final String ssid = info[0];
            final String bssid = info[1];
            final String secure = info[2];
            ui.post(() -> {
                if (ssid.isEmpty()) {
                    toast(I18n.t("当前没有连接 WiFi", "Not connected to Wi-Fi"));
                    return;
                }
                // 增强防护开启且要求绑定 BSSID 时，拒绝开放网络（除非在增强防护里明确允许）
                if (Prefs.protectEnabled(this) && Prefs.wifiBindBssid(this)
                        && "0".equals(secure) && !Prefs.wifiAllowOpen(this)) {
                    toast(I18n.t("「%s」是开放网络，增强防护要求加密网络。\n"
                                    + "如确实需要，可在「增强防护 → WiFi」里允许开放网络。",
                            "\"%s\" is an open network; Protection requires a secured network.\n"
                                    + "If you really need it, allow open networks in Protection → Wi-Fi.",
                            ssid));
                    return;
                }
                List<Wifi> list = parseWifi();
                for (Wifi w : list) {
                    if (ssid.equals(w.ssid)) {
                        // 旧数据只有 SSID：补存 BSSID 与安全类型
                        if (bssid.isEmpty() || !bssid.equals(w.bssid)) w.bssid = bssid;
                        if (!"?".equals(secure)) w.secure = secure;
                        w.enabled = true;
                        saveWifi(list);
                        scheduleApply();
                        renderCondition();
                        buildWifi();
                        toast(I18n.t("已更新 %s（BSSID：%s）",
                                "Updated %s (BSSID: %s)",
                                ssid, bssid.isEmpty() ? "—" : bssid));
                        return;
                    }
                }
                Wifi w = new Wifi();
                w.ssid = ssid;
                w.bssid = bssid;
                w.secure = secure;
                w.enabled = true;
                list.add(w);
                saveWifi(list);
                scheduleApply();
                renderCondition();
                buildWifi();
                toast(I18n.t("已添加 %s（BSSID：%s）", "Added %s (BSSID: %s)",
                        ssid, bssid.isEmpty() ? "—" : bssid));
            });
        });
    }

    // -------------------------------------------------------------- 状态刷新

    private Map<String, String> lastCheck = java.util.Collections.emptyMap();
    private boolean daemonAliveNow = false;

    private void refresh() {
        if (!busy.compareAndSet(false, true)) return;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Set<String> conn = Scripts.connectedMacs();
                final boolean alive = Scripts.daemonAlive();
                final Map<String, String> ck = Scripts.check();
                final String runMode = Scripts.options().get("RUN_MODE");
                final Map<String, String> cfg = Scripts.config();
                final boolean ext = Scripts.extendedActive();
                // Root 的就绪检测会拉起 su（首次可能弹窗），必须留在后台线程，不能放到 UI 线程
                final String engineStatus = Privilege.statusText();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        connected.clear();
                        connected.addAll(conn);
                        daemonAliveNow = alive;
                        lastCheck = ck;
                        if (etPin.getText().toString().isEmpty()) {
                            String p = cfg.get("PIN_TEXT");
                            if (p != null && !p.isEmpty()) {
                                etPin.setText(p);
                                Prefs.savePin(MainActivity.this, p);
                            }
                        }
                        if (Prefs.rssi(MainActivity.this).equals("0")) {
                            String r = cfg.get("RSSI_MIN");
                            if (r != null && !r.isEmpty()) etRssi.setText(r);
                        }
                        if (swHeld != null) {
                            swHeld.setOnCheckedChangeListener(null);
                            swHeld.setChecked("shizuku".equals(runMode));
                            swHeld.setOnCheckedChangeListener((b, v) -> scheduleApply());
                        }
                        applyStatus(alive, ck, runMode, engineStatus);
                        buildDevices();
                        buildWifi();
                        syncWifiSwitches();
                        renderCondition();
                        refreshNowText();
                        extendActiveNow = ext;
                        refreshExtendCard();
                        busy.set(false);
                    }
                });
            }
        });
    }

    /** 增强防护卡片上的简短状态 */
    private void refreshProtectCard() {
        if (tvProtect == null) return;
        if (!Prefs.protectEnabled(this)) {
            tvProtect.setText(I18n.t("未开启 · 点此设置", "Off · tap to set up"));
            return;
        }
        String mac = I18n.t("MAC 匹配：暂时停用（修复中）", "MAC match: disabled (fixing)");
        String wifi = !Prefs.wifiBindBssid(this)
                ? I18n.t("WiFi 宽松", "Wi-Fi loose")
                : Prefs.wifiAllowOpen(this)
                ? I18n.t("BSSID + 允许开放", "BSSID + open allowed")
                : I18n.t("BSSID + 仅安全", "BSSID + secure only");
        tvProtect.setText(mac + "\n" + wifi);
    }

    /** 延长解锁的状态说明与「立即恢复」按钮 */
    private void refreshExtendCard() {
        if (tvExtend == null) return;
        if (extendActiveNow) {
            tvExtend.setText(I18n.t("延长解锁中：锁屏密码已临时关闭。设备 / WiFi 断开后会先连续确认三次（约 1.5 秒），"
                            + "确认离线才写回原密码；关闭开关、停止守护或一键清理会立即写回。",
                    "Extend unlock active: the lock-screen PIN is temporarily cleared. When the device / Wi-Fi "
                            + "leaves, presence is re-checked three times (about 1.5 s) and the PIN is written back "
                            + "only if all three agree it is offline; turning the switch off, stopping the daemon "
                            + "or clean-up restores it immediately."));
            tvExtend.setTextColor(C_DANGER);
        } else if (Prefs.extendUnlock(this)) {
            tvExtend.setText(I18n.t("已开启（等待条件满足）：条件成立后临时关闭锁屏密码，条件失去时自动写回。",
                    "Enabled (waiting): the PIN is cleared once the unlock conditions hold and restored "
                            + "when they fail."));
            tvExtend.setTextColor(C_SUB);
        } else {
            tvExtend.setText(I18n.t("实验性功能：用 locksettings clear 临时清除锁屏密码，指纹 / 人脸会失效；"
                            + "部分 ROM 可能失败。开启前请确认记得原 PIN，并保留自行重设锁屏的手段。",
                    "Experimental: uses locksettings clear to temporarily remove the lock-screen PIN and "
                            + "invalidates fingerprints / face unlock. Some ROMs reject it. Make sure you remember "
                            + "the original PIN and can reset the lock screen yourself."));
            tvExtend.setTextColor(C_SUB);
        }
        if (btnExtendRestore != null) {
            btnExtendRestore.setVisibility(extendActiveNow ? View.VISIBLE : View.GONE);
        }
    }

    private void resetExtendSwitch() {
        if (swExtend == null) return;
        extendSyncing = true;
        swExtend.setChecked(false);
        extendSyncing = false;
    }

    private void onExtendToggle(boolean on) {
        if (!on) {
            Prefs.setExtendUnlock(this, false);
            applyConfigQuietly();
            extendActiveNow = false;
            refreshExtendCard();
            runAction(I18n.t("关闭延长解锁", "Disable extend unlock"), () -> {
                Scripts.Report r = new Scripts.Report();
                r.ok = true;
                r.text = I18n.t("功能已关闭，未处于临时关闭密码状态",
                        "Disabled; the PIN was not being cleared");
                if (Scripts.extendedActive()) return Scripts.extendRestore(this);
                return r;
            });
            return;
        }
        String pin = etPin.getText().toString().trim();
        if (!Scripts.validPin(pin)) {
            toast(I18n.t("请先填写正确的锁屏 PIN（4~16 位数字）",
                    "Enter the correct lock-screen PIN (4–16 digits) first"));
            resetExtendSwitch();
            return;
        }
        Prefs.savePin(this, pin);
        toast(I18n.t("正在校验锁屏 PIN…", "Verifying the lock-screen PIN…"));
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Scripts.verifyLockPin(pin);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (!ok) {
                            toast(I18n.t("PIN 与系统锁屏密码不一致，或授权服务不可用，已取消",
                                    "The PIN does not match the system lock screen, or privilege is unavailable. "
                                            + "Cancelled."));
                            resetExtendSwitch();
                        } else {
                            stagedExtendConfirm(1);
                        }
                    }
                });
            }
        });
    }

    /** 三级确认（5 秒 → 2 秒 → 2 秒），全部通过后才真正写入配置 */
    private void stagedExtendConfirm(final int stage) {
        if (isFinishing() || isDestroyed()) {
            resetExtendSwitch();
            return;
        }
        String title;
        String msg;
        int wait;
        if (stage == 1) {
            title = I18n.t("第 1/3 步：确认风险", "Step 1/3: accept the risk");
            msg = I18n.t("开启后，只要受信任设备或 WiFi 在线，锁屏密码会被系统级清除（locksettings clear）。"
                    + "这段时间里，任何拿到手机的人都可以直接进入系统。\n\n这是一项实验性功能，部分机型会失败。",
                    "While a trusted device or Wi-Fi is online, the lock-screen PIN is removed at the system "
                            + "level (locksettings clear). Anyone holding the phone can get in during that time."
                            + "\n\nThis is experimental and fails on some devices.");
            wait = 5;
        } else if (stage == 2) {
            title = I18n.t("第 2/3 步：生物识别会失效", "Step 2/3: biometrics will break");
            msg = I18n.t("密码被清除期间，系统录入的指纹 / 人脸会被判定失效，恢复后需要重新录入。"
                    + "同时部分应用（支付、银行、加密文件夹）可能要求重新验证身份。",
                    "While the PIN is removed, enrolled fingerprints / face data are invalidated and must be "
                            + "re-enrolled afterwards. Some apps (payments, banking, encrypted folders) may ask "
                            + "you to verify your identity again.");
            wait = 2;
        } else if (stage == 3) {
            title = I18n.t("第 3/3 步：恢复并不保证", "Step 3/3: restore is not guaranteed");
            msg = I18n.t("会写回原 PIN：设备 / WiFi 断开后先连续确认三次离线（约 1.5 秒）才恢复；"
                    + "关闭本开关、停止守护、一键清理会立即恢复。\n"
                    + "但守护被系统杀死、授权失效、重启后守护未运行等异常情况下，密码可能不会自动恢复。"
                    + "\n\n请确认你记得原 PIN，并能自行在系统设置里重新设置锁屏密码。",
                    "The original PIN is written back after three consecutive offline checks (about 1.5 s) when "
                            + "the device / Wi-Fi leaves; turning this switch off, stopping the daemon or clean-up "
                            + "restores it immediately.\nBut if the daemon is killed, privilege fails, or the "
                            + "daemon is not running after a reboot, the PIN may not be restored.\n\nMake sure you "
                            + "remember the original PIN and can reset the lock screen yourself.");
            wait = 2;
        } else {
            Prefs.setExtendUnlock(this, true);
            extendSyncing = true;
            if (swExtend != null) swExtend.setChecked(true);
            extendSyncing = false;
            applyConfigQuietly();
            refreshExtendCard();
            toast(I18n.t("已开启延长解锁：下次满足条件时会临时关闭锁屏密码",
                    "Extend unlock enabled: the PIN will be cleared next time the conditions hold"));
            refresh();
            return;
        }
        countdownConfirm(title, msg, wait,
                I18n.t("取消", "Cancel"), I18n.t("我已知悉", "I understand"),
                () -> stagedExtendConfirm(stage + 1), this::resetExtendSwitch);
    }

    /** 强制等待的确认框：按钮在 seconds 秒内不可点，避免连点一路跳过 */
    private void countdownConfirm(String title, String msg, int seconds,
                                  String cancelText, String okText,
                                  final Runnable onConfirm, final Runnable onCancel) {
        final boolean[] confirmed = {false};
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton(cancelText, (d, w) -> {
                    confirmed[0] = true;
                    onCancel.run();
                })
                .setPositiveButton(okText, null)
                .create();
        dlg.setOnShowListener(d -> {
            final Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            if (pos == null) return;
            pos.setEnabled(false);
            final int[] left = {seconds};
            pos.setText(I18n.t("请等待 %d 秒", "Wait %d s", left[0]));
            final Runnable tick = new Runnable() {
                @Override
                public void run() {
                    left[0]--;
                    if (left[0] <= 0) {
                        pos.setEnabled(true);
                        pos.setText(okText);
                    } else {
                        pos.setText(I18n.t("请等待 %d 秒", "Wait %d s", left[0]));
                        ui.postDelayed(this, 1000);
                    }
                }
            };
            ui.postDelayed(tick, 1000);
            pos.setOnClickListener(v -> {
                confirmed[0] = true;
                dlg.dismiss();
                onConfirm.run();
            });
        });
        // 点外部或返回键取消时也要回滚开关
        dlg.setOnDismissListener(d -> {
            if (!confirmed[0]) onCancel.run();
        });
        dlg.show();
    }

    /** 只重写配置，不阻塞 UI；用于增强防护开关 */
    private void applyConfigQuietly() {
        new Thread(() -> {
            try {
                Scripts.applyConfig(this);
            } catch (Throwable ignored) {
            }
        }, "apply-config").start();
    }

    private void applyStatus(boolean alive, Map<String, String> ck, String runMode, String engineStatus) {
        tvDot.setTextColor(alive ? C_OK : C_SUB);
        tvDaemon.setText(alive ? I18n.t("守护运行中", "Daemon running") : I18n.t("守护未运行", "Daemon stopped"));
        String mode = "shizuku".equals(runMode) ? I18n.t("托管", "Managed") : I18n.t("独立进程", "Detached process");
        String err = ck.get("error");
        if (err != null && (err.contains("No such file") || err.contains("not found"))) {
            err = I18n.t("尚未部署（点下面的「部署并启用」）", "Not deployed yet (tap Deploy and enable below)");
        }
        tvShizuku.setText(Privilege.modeLabel() + " · " + engineStatus
                + " · " + mode + (err != null ? " · " + err : ""));
        String status = ck.get("status");
        if (status != null && !"normal".equals(status)) {
            tvShizuku.setText(tvShizuku.getText() + " · " + statusText(status));
        }
    }

    private String statusText(String s) {
        switch (s) {
            case "skip": return I18n.t("条件不满足，未解锁", "Conditions not met, not unlocked");
            case "pause": return I18n.t("已暂停", "Paused");
            case "cooldown": return I18n.t("冷却中", "Cooling down");
            case "failed": return I18n.t("解锁失败", "Unlock failed");
            default: return s;
        }
    }

    private void renderCondition() {
        int devTotal = 0;
        int devOn = 0;
        for (Target t : parseTargets()) {
            devTotal++;
            if (t.enabled) devOn++;
        }
        StringBuilder sb = new StringBuilder(I18n.t("解锁条件：", "Unlock conditions: "));
        boolean wifiUnlock = Prefs.wifiUnlock(this);
        boolean wifiRequired = Prefs.wifiRequired(this);
        int wn = enabledWifiCount();
        if (devOn == 0 && !wifiUnlock) {
            sb.append(I18n.t("未启用任何设备", "No devices enabled"));
        } else if (devOn == 0) {
            sb.append(I18n.t("仅 WiFi", "Wi-Fi only"));
            sb.append(wn > 0 ? I18n.t("（%d 个可选）", " (%d enabled)", wn)
                    : I18n.t("（未启用任何 WiFi，不会触发）", " (no Wi-Fi enabled, will not trigger)"));
        } else {
            sb.append(I18n.t("%d 个设备", "%d device(s)", devOn));
            sb.append(Prefs.modeAll(this) ? I18n.t(" · 全部在线", " · all devices online")
                    : I18n.t(" · 任一在线", " · any device online"));
            if (wifiRequired) {
                sb.append(I18n.t(" · 且必须在 WiFi 上", " · and on Wi-Fi"));
                sb.append(wn > 0 ? I18n.t("（%d 个可选）", " (%d enabled)", wn)
                        : I18n.t("（未启用任何 WiFi，不会触发）", " (no Wi-Fi enabled, will not trigger)"));
            } else if (wifiUnlock) {
                sb.append(I18n.t(" · 或 WiFi 在线", " · or on Wi-Fi"));
                sb.append(wn > 0 ? I18n.t("（%d 个可选）", " (%d enabled)", wn) : "");
            }
        }
        tvCond.setText(sb.toString());
        if (swAll != null) swAll.setChecked(Prefs.modeAll(this));
        syncWifiSwitches();
    }

    /** 同步两个 WiFi 开关的勾选与联动状态（不能触发监听器，避免循环重写配置） */
    private void syncWifiSwitches() {
        if (swWifiUnlock == null || swWifiReq == null) return;
        syncing = true;
        swWifiUnlock.setChecked(Prefs.wifiUnlock(this) || Prefs.wifiRequired(this));
        swWifiReq.setChecked(Prefs.wifiRequired(this));
        swWifiReq.setEnabled(swWifiUnlock.isChecked());
        swWifiReq.setAlpha(swWifiUnlock.isChecked() ? 1f : 0.5f);
        syncing = false;
    }

    private void updateLangButton(TextView b) {
        String cur = Prefs.lang(this);
        if ("zh".equals(cur)) {
            b.setText(I18n.t("语言：中文", "Language: Chinese"));
        } else if ("en".equals(cur)) {
            b.setText(I18n.t("语言：英文", "Language: English"));
        } else {
            b.setText(I18n.t("语言：自动", "Language: Auto"));
        }
    }

    private void refreshNowText() {
        Map<String, String> ck = lastCheck;
        StringBuilder sb = new StringBuilder(I18n.t("当前：", "Now: "));
        String hit = ck.get("watch_hit");
        if (hit != null) sb.append(I18n.t("在线 ", "Online ")).append(hit);
        String total = ck.get("targets");
        if (total != null) {
            // 守护 --check 输出的 "2 启用 / 4 全部" 是中文固定格式，这里抽数字重新本地化
            java.util.regex.Matcher tm = java.util.regex.Pattern.compile("(\\d+)\\D+(\\d+)").matcher(total);
            if (tm.find()) {
                sb.append(" · ").append(I18n.t(tm.group(1) + " 启用 / " + tm.group(2) + " 全部",
                        tm.group(1) + " enabled / " + tm.group(2) + " total"));
            } else {
                sb.append(" · ").append(total);
            }
        }
        String locked = ck.get("locked");
        if ("yes".equals(locked)) sb.append(I18n.t(" · 已锁屏", " · Screen locked"));
        else if ("no".equals(locked)) sb.append(I18n.t(" · 未锁屏", " · Screen unlocked"));
        String ssid = ck.get("wifi_ssid");
        if (ssid != null && !ssid.isEmpty() && !ssid.contains("未连接")) {
            sb.append(" · WiFi ").append(ssid);
        }
        String cool = ck.get("cooldown_in");
        if (cool != null && !"0s".equals(cool) && !"0".equals(cool)) {
            sb.append(I18n.t(" · 冷却 ", " · Cooldown ")).append(cool);
        }
        String cd = ck.get("cooldowns");
        if (cd != null && !"0".equals(cd)) sb.append(I18n.t(" · 冷却计数 ", " · Cooldowns ")).append(cd);
        String ident = ck.get("identity");
        if (ident != null && !ident.isEmpty()) {
            sb.append(I18n.t(" · 身份不可靠（按名称匹配）",
                    " · identity unreliable (matched by name)"));
        }
        if (ck.isEmpty()) sb.append(I18n.t("未读到状态（守护可能未部署）",
                "No status available (daemon may not be deployed)"));
        tvNow.setText(sb.toString());
    }

    // ---------------------------------------------------------------- 动作

    private void doDeploy() {
        String pin = etPin.getText().toString().trim();
        if (!Scripts.validPin(pin)) {
            toast(I18n.t("PIN 必须是 4~16 位数字", "PIN must be 4–16 digits"));
            return;
        }
        Prefs.savePin(this, pin);
        String rssi = etRssi.getText().toString().trim();
        Prefs.saveRssi(this, rssi.isEmpty() ? "0" : rssi);
        Prefs.setWantRunning(this, true);
        startMonitor();
        toast(I18n.t("正在部署…", "Deploying…"));
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Scripts.Report r = Scripts.deploy(MainActivity.this);
                if (!r.text.isEmpty()) {
                    // 部署详情写进日志页可见位置
                    Privilege.run("printf '%s\\n' '" + r.text.replace("'", "")
                            + "' >> " + Scripts.LOG, 8000);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(r.ok ? I18n.t("部署完成，亮屏试试", "Deployed. Wake the screen to try it.")
                                : I18n.t("部署失败：%s", "Deploy failed: %s", r.text));
                        refresh();
                    }
                });
            }
        });
    }

    private interface Task {
        Scripts.Report run();
    }

    private void runAction(String name, final Task task) {
        toast(name + "…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Scripts.Report r = task.run();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(name + (r.ok ? I18n.t(" 完成", " done") : I18n.t(" 失败", " failed"))
                                + (r.text.isEmpty() ? "" : "\n" + r.text));
                        refresh();
                    }
                });
            }
        });
    }

    private void confirmClean() {
        new AlertDialog.Builder(this)
                .setTitle(I18n.t("一键清理所有残留", "Clean up all leftovers"))
                .setMessage(I18n.t("将停止守护与所有前台服务（含状态监控、无线调试配对的 mDNS 发现），"
                                + "结束所有旧版守护/logcat 监听进程，并删除 /data/local/tmp/watch-unlock、"
                                + "旧版 /sdcard/watch-unlock 与 /data/local/tmp/wu.*（含未来新增的运行时文件）。\n\n"
                                + "若此刻处于「延长解锁」，会先写回锁屏密码再删除。\n\n"
                                + "设备与 WiFi 的选择会保留，清理后可重新部署。",
                        "This stops the daemon and every foreground service (status monitoring and the "
                                + "wireless-debugging pairing/mDNS discovery), ends all old daemon/logcat "
                                + "watching processes, and deletes /data/local/tmp/watch-unlock, the old "
                                + "/sdcard/watch-unlock and all /data/local/tmp/wu.* runtime files."
                                + "\n\nIf extend unlock is active, the lock-screen PIN is written back first."
                                + "\n\nYour device and WiFi selections are kept; you can deploy again afterwards."))
                .setNegativeButton(I18n.t("取消", "Cancel"), null)
                .setPositiveButton(I18n.t("清理", "Clean up"), (d, w) -> {
                    Prefs.setWantRunning(this, false);
                    stopService(new Intent(this, MonitorService.class));
                    runAction(I18n.t("清理", "Clean up"), () -> Scripts.cleanAll(this));
                })
                .show();
    }

    private void scheduleApply() {
        ui.removeCallbacks(applyTask);
        ui.postDelayed(applyTask, 1000);
    }

    private final Runnable applyTask = new Runnable() {
        @Override
        public void run() {
            Prefs.saveRssi(MainActivity.this, etRssi == null ? "0" : etRssi.getText().toString().trim());
            final boolean held = swHeld != null && swHeld.isChecked();
            // 重新拉起服务指令，让 WiFi/蓝牙监听器按最新开关状态注册
            if (Prefs.wantRunning(MainActivity.this)) startMonitor();
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    String cur = Scripts.options().get("RUN_MODE");
                    Scripts.Report r;
                    if (held != "shizuku".equals(cur)) {
                        r = Scripts.saveRunMode(held ? "shizuku" : "detached");
                    } else {
                        r = Scripts.applyConfig(MainActivity.this);
                    }
                    if (!r.ok) {
                        final String msg = r.text;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                toast(I18n.t("配置未生效：%s", "Settings not applied: %s", msg));
                            }
                        });
                    }
                }
            });
        }
    };

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 权限/服务

    private boolean permRequested = false;
    private boolean deniedDialogShown = false;

    /**
     * 主动请求权限：
     * Android 12+ 的「附近的设备」需要 CONNECT + SCAN；Android 13+ 还要通知。
     * 用户勾选「不再询问」后系统不会再弹框，此时必须引导到应用设置页手动打开。
     */
    private void ensurePermissions() {
        List<String> req = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                req.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                req.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        grantedBt = Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (!req.isEmpty()) {
            permRequested = true;
            requestPermissions(req.toArray(new String[0]), REQ_PERMS);
        } else if (!grantedBt && !deniedDialogShown) {
            // 没权限可请求，说明上次已经选了「不再询问」
            showPermissionDeniedDialog();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERMS) {
            grantedBt = Build.VERSION.SDK_INT < 31
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            if (!grantedBt && Build.VERSION.SDK_INT >= 31
                    && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                // 请求后仍不弹理由 = 已被永久拒绝（或系统策略禁止）
                showPermissionDeniedDialog();
            }
            startMonitor();
            refresh();
            buildDevices();
        }
    }

    /** 权限被永久拒绝时，给出明确跳转，而不是无声地显示空列表 */
    private void showPermissionDeniedDialog() {
        deniedDialogShown = true;
        new AlertDialog.Builder(this)
                .setTitle(I18n.t("需要「附近的设备」权限", "Nearby devices permission required"))
                .setMessage(I18n.t(
                        "系统没有授予蓝牙权限，已配对设备无法读取，也不会自动解锁。\n\n"
                                + "如果系统不再弹出授权框，请到应用设置 → 权限 → 附近的设备，"
                                + "选择「允许」；部分 ROM 还需要单独允许「应用列表 / 自启动」。",
                        "Bluetooth permission is missing, so paired devices cannot be read and unlocking "
                                + "will not work. If the system no longer shows a prompt, open App info → "
                                + "Permissions → Nearby devices and allow it. Some ROMs also require the "
                                + "app-list / auto-start switches."))
                .setPositiveButton(I18n.t("去设置授权", "Open app settings"), (d, w) -> openAppSettings())
                .setNegativeButton(I18n.t("稍后", "Later"), null)
                .show();
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast(I18n.t("无法打开应用设置", "Cannot open app settings"));
        }
    }

    private void startMonitor() {
        Intent i = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }
}
