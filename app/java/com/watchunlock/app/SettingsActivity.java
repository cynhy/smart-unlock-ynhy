package com.watchunlock.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 二级页面：设置（授权方式 / 震感 / 音乐锁屏返回 / 日志 / 公告 / 交流群） */
public class SettingsActivity extends Activity {

    private static final int C_BG = 0xFFF3F4F6;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_PRIMARY = 0xFF2563EB;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_SUB = 0xFF6B7280;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        Privilege.init(this);
        setTitle(I18n.t("设置", "Settings"));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        sv.addView(root, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText(I18n.t("设置", "Settings"));
        title.setTextSize(22);
        title.setTextColor(C_TEXT);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(I18n.t("修改后会自动重启守护；首次部署后生效。",
                "Changes restart the daemon automatically and apply after the first deploy."));
        sub.setTextSize(12);
        sub.setTextColor(C_SUB);
        LinearLayout.LayoutParams lpSub = new LinearLayout.LayoutParams(-1, -2);
        lpSub.topMargin = dp(4);
        lpSub.bottomMargin = dp(12);
        root.addView(sub, lpSub);

        root.addView(buildEngine(), gap(12));
        root.addView(buildVibrate(), gap(12));
        root.addView(buildMusic(), gap(12));
        root.addView(buildEnhance(), gap(12));
        root.addView(buildLogs(), gap(12));
        root.addView(buildNotice(), gap(12));
        root.addView(buildAbout(), gap(12));

        setContentView(sv);
    }

    // ---------- 授权方式 ----------

    private LinearLayout buildEngine() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("授权方式", "Privilege engine")));
        final TextView status = hint(I18n.t("正在检测…", "Checking…"));
        c.addView(status);

        final RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(LinearLayout.VERTICAL);
        rg.setPadding(0, dp(4), 0, 0);
        final RadioButton rShizuku = radio(I18n.t("Shizuku（默认，免 root）", "Shizuku (default, no root)"));
        final RadioButton rRoot = radio(I18n.t("Root（需设备已 root）", "Root (device must be rooted)"));
        final RadioButton rAdb = radio(I18n.t("无线调试（内置 ADB，免 root）",
                "Wireless debugging (built-in ADB, no root)"));
        rg.addView(rShizuku);
        rg.addView(rRoot);
        rg.addView(rAdb);

        String mode = Prefs.privilegeMode(this);
        rg.check("root".equals(mode) ? rRoot.getId()
                : "adb".equals(mode) ? rAdb.getId() : rShizuku.getId());

        final Button adbSetup = button(I18n.t("无线调试配对与连接", "Wireless debugging setup"));
        final TextView adbHint = hint(I18n.t("无线调试需在开发者选项手动打开，重启后会自动关闭；"
                        + "端口每次开启都会变化，需要重新配对。",
                "Wireless debugging must be enabled manually in Developer options and turns itself off "
                        + "after a reboot. The port changes every time, so pair again when it changes."));

        rg.setOnCheckedChangeListener((g, id) -> {
            String m = id == rRoot.getId() ? Privilege.ROOT
                    : id == rAdb.getId() ? Privilege.ADB : Privilege.SHIZUKU;
            Prefs.setPrivilegeMode(this, m);
            boolean adb = Privilege.ADB.equals(m);
            adbSetup.setVisibility(adb ? View.VISIBLE : View.GONE);
            adbHint.setVisibility(adb ? View.VISIBLE : View.GONE);
            if (adb && Prefs.adbPort(this) <= 0) {
                toast(I18n.t("请先完成一次无线调试配对", "Pair wireless debugging first"));
            }
            refreshStatus(status, m);
            applyQuietly();
        });

        c.addView(rg);
        c.addView(adbHint, gap(8));
        adbSetup.setOnClickListener(v -> startActivity(new Intent(this, AdbActivity.class)));
        c.addView(adbSetup, gap(10));

        boolean adb = Privilege.ADB.equals(mode);
        adbSetup.setVisibility(adb ? View.VISIBLE : View.GONE);
        adbHint.setVisibility(adb ? View.VISIBLE : View.GONE);

        c.addView(hint(I18n.t("切换后会重启守护脚本。Root 与无线调试不需要安装 Shizuku。",
                "Switching restarts the daemon. Root and wireless debugging do not require Shizuku.")));
        refreshStatus(status, mode);
        return c;
    }

    private RadioButton radio(String s) {
        RadioButton r = new RadioButton(this);
        r.setId(View.generateViewId());
        r.setText(s);
        r.setTextSize(14);
        r.setTextColor(C_TEXT);
        r.setPadding(0, dp(6), 0, dp(6));
        r.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) haptic(v);
            return false;
        });
        return r;
    }

    private void refreshStatus(final TextView tv, String mode) {
        tv.setText(I18n.t("正在检测…", "Checking…"));
        pool.execute(() -> {
            String s = Privilege.statusText();
            ui.post(() -> tv.setText(s));
        });
    }

    // ---------- 震感 ----------

    private LinearLayout buildVibrate() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("震感反馈", "Haptics")));
        switchRow(c,
                I18n.t("点击震动", "Tap vibration"),
                I18n.t("点击按钮和开关时轻微震动一次；关闭后完全静音。",
                        "A light vibration when tapping buttons and switches; off means silent."),
                Prefs.vibrate(this), v -> {
                    Prefs.setVibrate(this, v);
                });
        return c;
    }

    // ---------- 音乐锁屏返回 ----------

    private LinearLayout buildMusic() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("音乐锁屏返回", "Music lock screen return")));
        c.addView(hint(I18n.t("部分系统在音乐锁屏界面输入密码后会停在音乐页。开启后，"
                        + "解锁成功再执行一次返回主界面的动作。",
                "On some ROMs the music lock screen stays after the PIN is entered. When on, "
                        + "the app performs one extra action to return to the home screen after unlocking.")));

        final Switch[] subs = new Switch[2];
        final Switch master = switchRow(c,
                I18n.t("启用音乐锁屏返回", "Enable music lock screen return"),
                I18n.t("总开关。下面的两个方式可单独开启，也可同时开启。",
                        "Master switch. The two methods below can be used alone or together."),
                Prefs.musicReturn(this), v -> {
                    Prefs.setMusicReturn(this, v);
                    subs[0].setEnabled(v);
                    subs[1].setEnabled(v);
                    applyQuietly();
                });

        subs[0] = switchRow(c,
                I18n.t("解锁后打开指定应用", "Open the specified app after unlock"),
                I18n.t("通过包名打开主界面。与右滑同时开启时，优先使用这种方式。",
                        "Open the main screen by package name. Takes priority over swiping when both are on."),
                Prefs.musicOpenApp(this), v -> {
                    Prefs.setMusicOpenApp(this, v);
                    if (v && Prefs.musicAppPkg(this).isEmpty()) {
                        toast(I18n.t("请先在下面填写应用包名", "Enter the package name below first"));
                    }
                    applyQuietly();
                });

        final EditText pkg = new EditText(this);
        pkg.setHint(I18n.t("应用包名，例如 com.tencent.mobileqq", "Package name, e.g. com.tencent.mobileqq"));
        pkg.setText(Prefs.musicAppPkg(this));
        pkg.setTextSize(13);
        pkg.setSingleLine(true);
        pkg.setInputType(InputType.TYPE_CLASS_TEXT);
        pkg.setPadding(dp(8), dp(6), dp(8), dp(6));
        c.addView(pkg, gap(4));

        Button savePkg = button(I18n.t("保存应用包名", "Save package name"));
        savePkg.setOnClickListener(v -> {
            String p = pkg.getText().toString().trim();
            if (!p.isEmpty() && !p.contains(".")) {
                toast(I18n.t("包名格式看起来不正确", "That does not look like a valid package name"));
                return;
            }
            Prefs.setMusicAppPkg(this, p);
            applyQuietly();
            toast(I18n.t("已保存", "Saved"));
        });
        c.addView(savePkg, gap(6));

        subs[1] = switchRow(c,
                I18n.t("解锁后向右滑动", "Swipe right after unlock"),
                I18n.t("在屏幕中部向右滑动一次，适用于没有填写应用包名的情况。",
                        "Swipe right once across the middle of the screen; useful when no package is set."),
                Prefs.musicSwipe(this), v -> {
                    Prefs.setMusicSwipe(this, v);
                    applyQuietly();
                });

        boolean on = Prefs.musicReturn(this);
        subs[0].setEnabled(on);
        subs[1].setEnabled(on);
        master.setChecked(on);
        return c;
    }

    // ---------- 解锁增强 ----------

    private LinearLayout buildEnhance() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("解锁增强", "Unlock enhancements")));
        switchRow(c,
                I18n.t("SIM 卡 PIN 防呆", "SIM PIN guard"),
                I18n.t("默认关闭。开启后每次亮屏多一次 SIM 状态检查（约 5~10ms）；"
                        + "SIM 卡处于 PIN 锁定状态时会直接跳过本次解锁，避免把手机 PIN 输进 SIM 界面，"
                        + "但也会降低解锁成功率、增加等待。",
                        "Off by default. When on, each screen wake adds a SIM-state check (~5–10 ms); "
                                + "if the SIM is PIN-locked, unlocking is skipped to avoid typing the phone "
                                + "PIN into the SIM screen. This lowers reliability and adds delay."),
                Prefs.simGuard(this), v -> {
                    Prefs.setSimGuard(this, v);
                    applyQuietly();
                });
        return c;
    }

    // ---------- 日志 ----------

    private LinearLayout buildLogs() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("日志", "Logs")));
        c.addView(hint(I18n.t("查看每次亮屏的触发来源、耗时分解与跳过原因。",
                "See the trigger source, timing breakdown and skip reason for every wake.")));
        Button openLog = button(I18n.t("查看运行日志", "View run log"));
        openLog.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        c.addView(openLog, gap(10));
        return c;
    }

    // ---------- 公告栏 ----------

    private LinearLayout buildNotice() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("公告栏", "Announcements")));
        c.addView(hint(I18n.t("使用前请先阅读注意事项与测试版本说明。",
                "Read the notes and the test-build notice before use.")));
        Button open = button(I18n.t("查看公告与注意事项", "View announcements and notes"));
        open.setOnClickListener(v -> startActivity(new Intent(this, AnnounceActivity.class)));
        c.addView(open, gap(10));
        return c;
    }

    // ---------- 关于 ----------

    private LinearLayout buildAbout() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("关于", "About")));
        TextView qq = new TextView(this);
        qq.setText(I18n.t("交流群 QQ：697302419", "QQ group: 697302419"));
        qq.setTextSize(14);
        qq.setTextColor(C_TEXT);
        qq.setPadding(0, dp(8), 0, dp(8));
        qq.setOnClickListener(v -> copyQq());
        c.addView(qq);
        c.addView(hint(I18n.t("点击可复制群号", "Tap to copy the group number")));
        return c;
    }

    private void copyQq() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("QQ", "697302419"));
            toast(I18n.t("已复制群号", "Group number copied"));
        } catch (Throwable t) {
            toast(I18n.t("复制失败", "Copy failed"));
        }
    }

    private void applyQuietly() {
        pool.execute(() -> {
            Scripts.Report r = Scripts.applyConfig(this);
            ui.post(() -> {
                if (!r.ok) {
                    toast(I18n.t("设置已保存，部署后生效（%s）",
                            "Saved; it will apply after deploy (%s)", r.text));
                }
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

    private Switch switchRow(LinearLayout parent, String title, String sub,
                             boolean checked, final OnToggle cb) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
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
