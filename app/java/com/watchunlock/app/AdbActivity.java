package com.watchunlock.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private TextView status;

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

        LinearLayout console = card();
        console.addView(sectionTitle(I18n.t("ADB 命令行", "ADB command line")));
        console.addView(body(I18n.t(
                "直接输入一条 adb shell 命令并执行，例如 pm list packages | grep watchunlock。\n"
                        + "使用前请先到「本应用 → 设置 → 无线调试」完成配对与连接。"
                        + "（既然已经内置了 adb 调试功能，那就要物尽其用，不加白不加。）",
                "Type an adb shell command and run it, e.g. pm list packages | grep watchunlock.\n"
                        + "Complete pairing and connection in Settings → Wireless debugging first. "
                        + "(ADB is already built in, so make the most of it.)")));
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

        LinearLayout how = card();
        how.addView(sectionTitle(I18n.t("操作步骤", "Steps")));
        how.addView(body(I18n.t(
                "1. 打开「开发者选项 → 无线调试」，保持开关为开。\n"
                        + "2. 在无线调试主页面找到「IP 地址和端口」，把端口号填到下面的「连接端口」。\n"
                        + "3. 点「使用配对码配对设备」，弹窗里会显示另一个端口和 6 位配对码，"
                        + "分别填到「配对端口」和「配对码」。\n"
                        + "4. 保持配对弹窗打开，点「配对」，成功后再点「连接测试」。\n"
                        + "5. 重启手机后无线调试会自动关闭，需要重新打开并按上述步骤再配对一次。",
                "1. Open Developer options → Wireless debugging and keep it on.\n"
                        + "2. On the main Wireless debugging screen, find the IP address and port and "
                        + "enter the port under Connect port below.\n"
                        + "3. Tap Pair device with pairing code. The dialog shows a different port and a "
                        + "6-digit code; enter them under Pairing port and Pairing code.\n"
                        + "4. Keep the pairing dialog open, tap Pair, then tap Test connection.\n"
                        + "5. After a reboot, wireless debugging turns off automatically; enable it and "
                        + "pair again following the steps above.")));
        root.addView(how, gap(12));

        LinearLayout jump = card();
        jump.addView(sectionTitle(I18n.t("快速跳转", "Shortcut")));
        jump.addView(body(I18n.t("如果「无线调试」页面找不到，说明开发者选项还没启用。"
                        + "配对弹窗必须在系统页面里打开，本应用读不到里面的配对码。",
                "If Wireless debugging is missing, Developer options is not enabled yet. "
                        + "The pairing dialog must be opened in system Settings; this app cannot read the code.")));
        Button openWd = button(I18n.t("打开无线调试设置", "Open Wireless debugging settings"));
        openWd.setOnClickListener(v -> openWirelessDebugging());
        jump.addView(openWd, gap(10));
        root.addView(jump, gap(12));

        LinearLayout cfg = card();
        cfg.addView(sectionTitle(I18n.t("连接参数", "Connection")));

        cfg.addView(label(I18n.t("主机（默认 127.0.0.1，即本机）", "Host (default 127.0.0.1, this device)")));
        final EditText host = field(Prefs.adbHost(this), InputType.TYPE_CLASS_TEXT);
        cfg.addView(host, gap(4));

        cfg.addView(label(I18n.t("连接端口（无线调试主页面）",
                        "Connect port (main Wireless debugging screen)")), gap(8));
        final EditText port = field(Prefs.adbPort(this) > 0 ? String.valueOf(Prefs.adbPort(this)) : "",
                InputType.TYPE_CLASS_NUMBER);
        cfg.addView(port, gap(4));

        cfg.addView(label(I18n.t("配对端口（配对码弹窗内）",
                "Pairing port (inside the pairing dialog)")), gap(8));
        final EditText pairPort = field(Prefs.adbPairPort(this) > 0
                ? String.valueOf(Prefs.adbPairPort(this)) : "", InputType.TYPE_CLASS_NUMBER);
        cfg.addView(pairPort, gap(4));

        cfg.addView(label(I18n.t("配对码（6 位数字）", "Pairing code (6 digits)")), gap(8));
        final EditText code = field("", InputType.TYPE_CLASS_NUMBER);
        code.setHint("000000");
        cfg.addView(code, gap(4));

        status = body(I18n.t("尚未连接", "Not connected"));
        cfg.addView(status, gap(10));

        Button pair = button(I18n.t("配对", "Pair"));
        pair.setOnClickListener(v -> doPair(host, port, pairPort, code, pair));
        cfg.addView(pair, gap(6));

        Button test = button(I18n.t("连接测试", "Test connection"));
        test.setOnClickListener(v -> doConnect(host, port, test));
        cfg.addView(test, gap(6));

        Button off = button(I18n.t("断开连接", "Disconnect"));
        off.setOnClickListener(v -> {
            AdbBridge.close();
            refreshStatus();
        });
        cfg.addView(off, gap(6));

        root.addView(cfg, gap(12));

        setContentView(sv);
        refreshStatus();
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

    private void refreshStatus() {
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

    /** 跳到系统的「无线调试」子页面；失败则逐级退回开发者选项。 */
    private void openWirelessDebugging() {
        String[] hosts = {
                "com.android.settings.Settings$SubSettings",
                "com.android.settings.Settings$DevelopmentSettingsActivity"
        };
        for (String host : hosts) {
            try {
                Intent i = new Intent();
                i.setClassName("com.android.settings", host);
                i.putExtra(":settings:show_fragment",
                        "com.android.settings.development.WirelessDebuggingFragment");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast(I18n.t("已打开开发者选项，请点「无线调试」", "Opened Developer options; tap Wireless debugging"));
        } catch (Throwable t) {
            toast(I18n.t("无法打开系统设置，请手动进入「开发者选项 → 无线调试」",
                    "Cannot open Settings; go to Developer options → Wireless debugging manually"));
        }
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
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_CARD);
        g.setCornerRadius(dp(16));
        l.setBackground(g);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        l.setElevation(dp(1));
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
        pool.shutdownNow();
        super.onDestroy();
    }
}
