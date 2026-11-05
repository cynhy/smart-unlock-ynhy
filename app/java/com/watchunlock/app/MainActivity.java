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
    private LinearLayout devBox, wifiBox;
    private Switch swAll, swWifiUnlock, swWifiReq, swHeld;
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
        refresh();
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
        lpSub.bottomMargin = dp(14);
        root.addView(sub, lpSub);

        // 状态卡
        LinearLayout status = card();
        tvDot = new TextView(this);
        tvDot.setText("●");
        tvDot.setTextSize(14);
        tvDot.setTextColor(C_SUB);
        tvDaemon = new TextView(this);
        tvDaemon.setText(I18n.t("正在读取状态…", "Reading status…"));
        tvDaemon.setTextSize(16);
        tvDaemon.setTextColor(C_TEXT);
        tvDaemon.getPaint().setFakeBoldText(true);
        tvShizuku = new TextView(this);
        tvShizuku.setText("");
        tvShizuku.setTextSize(12);
        tvShizuku.setTextColor(C_SUB);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tvDot);
        LinearLayout headText = new LinearLayout(this);
        headText.setOrientation(LinearLayout.VERTICAL);
        headText.addView(tvDaemon);
        headText.addView(tvShizuku);
        LinearLayout.LayoutParams lpH = new LinearLayout.LayoutParams(-2, -2);
        lpH.leftMargin = dp(8);
        head.addView(headText, lpH);
        status.addView(head);

        tvCond = new TextView(this);
        tvCond.setText(I18n.t("解锁条件：读取中…", "Unlock conditions: reading…"));
        tvCond.setTextSize(13);
        tvCond.setTextColor(C_TEXT);
        tvNow = new TextView(this);
        tvNow.setText("");
        tvNow.setTextSize(12);
        tvNow.setTextColor(C_SUB);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(-1, -2);
        lpC.topMargin = dp(10);
        status.addView(tvCond, lpC);
        LinearLayout.LayoutParams lpN = new LinearLayout.LayoutParams(-1, -2);
        lpN.topMargin = dp(4);
        status.addView(tvNow, lpN);
        addTo(root, status, dp(8));

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
        swWifiUnlock = switchRow(condCard, I18n.t("WiFi 独立解锁", "Wi-Fi can unlock alone"),
                I18n.t("已启用的 WiFi 在线即可解锁，不要求蓝牙设备在线",
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
        adbCard.addView(hint(I18n.t("进入后可输入 adb 命令直接执行。使用前请先到「本应用 → 设置 → 无线调试」"
                        + "完成配对与连接（既然已经内置了 adb 调试功能，那就要物尽其用，不加白不加）。",
                "Type and run adb commands inside. Complete pairing and connection in "
                        + "Settings → Wireless debugging first. (ADB is already built in, so make the most of it.)")));
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
            if (!w.ssid.isEmpty()) list.add(w);
        }
        return list;
    }

    private void saveWifi(List<Wifi> list) {
        StringBuilder sb = new StringBuilder();
        for (Wifi w : list) {
            if (sb.length() > 0) sb.append(';');
            sb.append(w.enabled ? '1' : '0').append('|').append(w.ssid);
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
            boolean on = connected.contains(t.mac.toUpperCase()) || connected.contains(t.name)
                    // HyperOS 的 dumpsys 只保留 MAC 末两段（XX:XX:XX:XX:EE:FF），用末 5 位兜底
                    || (t.mac.length() >= 5 && connected.contains(t.mac.substring(t.mac.length() - 5).toUpperCase()));
            String extra = on ? I18n.t("已连接", "Connected") : I18n.t("未连接", "Not connected");
            mac.setText((t.mac.isEmpty() ? "" : t.mac + " · ") + extra);
            mac.setTextSize(11);
            mac.setTextColor(on ? C_OK : C_SUB);
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
            TextView name = new TextView(this);
            name.setText(w.ssid);
            name.setTextSize(13);
            name.setTextColor(C_TEXT);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
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
        toast(I18n.t("正在读取当前 WiFi…", "Reading current Wi-Fi…"));
        pool.execute(() -> {
            final String ssid = Scripts.currentSsid();
            ui.post(() -> {
                if (ssid.isEmpty()) {
                    toast(I18n.t("当前没有连接 WiFi", "Not connected to Wi-Fi"));
                    return;
                }
                List<Wifi> list = parseWifi();
                for (Wifi w : list) {
                    if (ssid.equals(w.ssid)) {
                        w.enabled = true;
                        saveWifi(list);
                        scheduleApply();
                        renderCondition();
                        buildWifi();
                        toast(I18n.t("已启用 %s", "Enabled %s", ssid));
                        return;
                    }
                }
                Wifi w = new Wifi();
                w.ssid = ssid;
                w.enabled = true;
                list.add(w);
                saveWifi(list);
                scheduleApply();
                renderCondition();
                buildWifi();
                toast(I18n.t("已添加 %s", "Added %s", ssid));
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
                        applyStatus(alive, ck, runMode);
                        buildDevices();
                        buildWifi();
                        syncWifiSwitches();
                        renderCondition();
                        refreshNowText();
                        busy.set(false);
                    }
                });
            }
        });
    }

    private void applyStatus(boolean alive, Map<String, String> ck, String runMode) {
        tvDot.setTextColor(alive ? C_OK : C_SUB);
        tvDaemon.setText(alive ? I18n.t("守护运行中", "Daemon running") : I18n.t("守护未运行", "Daemon stopped"));
        String mode = "shizuku".equals(runMode) ? I18n.t("托管", "Managed") : I18n.t("独立进程", "Detached process");
        String err = ck.get("error");
        if (err != null && (err.contains("No such file") || err.contains("not found"))) {
            err = I18n.t("尚未部署（点下面的「部署并启用」）", "Not deployed yet (tap Deploy and enable below)");
        }
        tvShizuku.setText(Privilege.modeLabel() + " · " + Privilege.statusText()
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
                .setMessage(I18n.t("将停止守护与前台服务，结束所有旧版守护/logcat 监听进程，"
                                + "并删除 /sdcard/watch-unlock 与 /data/local/tmp/wu.*。\n\n"
                                + "设备与 WiFi 的选择会保留，清理后可重新部署。",
                        "This stops the daemon and the foreground service, ends all old daemon/logcat "
                                + "watching processes, and deletes /sdcard/watch-unlock and /data/local/tmp/wu.*."
                                + "\n\nYour device and WiFi selections are kept; you can deploy again afterwards."))
                .setNegativeButton(I18n.t("取消", "Cancel"), null)
                .setPositiveButton(I18n.t("清理", "Clean up"), (d, w) -> {
                    Prefs.setWantRunning(this, false);
                    stopService(new Intent(this, MonitorService.class));
                    runAction(I18n.t("清理", "Clean up"), () -> Scripts.cleanAll());
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

    private void ensurePermissions() {
        List<String> req = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        grantedBt = Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (!req.isEmpty()) requestPermissions(req.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERMS) {
            grantedBt = Build.VERSION.SDK_INT < 31
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            startMonitor();
            refresh();
        }
    }

    private void startMonitor() {
        Intent i = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }
}
