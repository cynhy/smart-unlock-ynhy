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

/** 二级页面：设置（授权方式 / 震感 / 特殊锁屏返回 / 日志 / 公告 / 交流群） */
public class SettingsActivity extends Activity {

    private static final int C_BG = 0xFFF3F4F6;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_PRIMARY = 0xFF2563EB;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_SUB = 0xFF6B7280;

    private LinearLayout musicAppsBox;
    private EditText musicPkgField;

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
        sub.setText(I18n.t("修改后会立即写入配置，守护在下一个事件前自动热重载，无需重启；"
                        + "首次使用或切换授权方式后仍需「部署并启用」。",
                "Changes are written to the config immediately and hot-reloaded before the next event, "
                        + "with no restart; the first deploy or an engine switch still needs “Deploy & enable”."));
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
        // 第 4 项只是「外部工具入口」，不是新的授权引擎：点它会进说明页，不改当前授权方式
        final RadioButton rWeb = radio(I18n.t("网页版 ADB（USB，免回环端口）  ›",
                "Web ADB (USB, no loopback port)  ›"));
        rWeb.setTextColor(C_PRIMARY);
        rWeb.setPadding(0, dp(10), 0, dp(6));
        rg.addView(rShizuku);
        rg.addView(rRoot);
        rg.addView(rAdb);
        rg.addView(rWeb);

        String mode = Prefs.privilegeMode(this);
        rg.check("root".equals(mode) ? rRoot.getId()
                : "adb".equals(mode) ? rAdb.getId() : rShizuku.getId());

        final Button adbSetup = button(I18n.t("无线调试配对与连接", "Wireless debugging setup"));
        final TextView adbHint = hint(I18n.t("无线调试需在开发者选项手动打开，重启后会自动关闭；"
                        + "端口每次开启都会变化，需要重新配对。",
                "Wireless debugging must be enabled manually in Developer options and turns itself off "
                        + "after a reboot. The port changes every time, so pair again when it changes."));
        final Button rootTest = button(I18n.t("检测 Root 授权（会弹出 su 请求）",
                "Test root (shows the su prompt)"));
        final String rootTestLabel = rootTest.getText().toString();
        rootTest.setOnClickListener(v -> {
            status.setText(I18n.t("正在等待 Root 授权…", "Waiting for root authorization…"));
            rootTest.setEnabled(false);
            rootTest.setText(I18n.t("等待授权…", "Waiting…"));
            pool.execute(() -> {
                final boolean ok = Privilege.probeRoot(30000);
                // statusText() 可能再次探测 Root，必须留在后台线程
                final String st = Privilege.statusText();
                ui.post(() -> {
                    rootTest.setEnabled(true);
                    rootTest.setText(rootTestLabel);
                    status.setText(st);
                    toast(ok ? I18n.t("Root 已授权", "Root granted")
                            : I18n.t("Root 未授权或等待超时，请重试并在 su 弹窗中点允许",
                            "Root not granted or timed out; retry and tap Allow in the su prompt"));
                });
            });
        });

        final boolean[] restoringWeb = {false};
        rg.setOnCheckedChangeListener((g, id) -> {
            if (id == rWeb.getId()) {
                // 网页版 ADB 只是入口：立即把选中状态恢复到当前授权引擎，再打开说明页
                restoringWeb[0] = true;
                rg.check("root".equals(Prefs.privilegeMode(this)) ? rRoot.getId()
                        : "adb".equals(Prefs.privilegeMode(this)) ? rAdb.getId() : rShizuku.getId());
                restoringWeb[0] = false;
                startActivity(new Intent(this, WebAdbActivity.class));
                return;
            }
            if (restoringWeb[0]) return;
            String m = id == rRoot.getId() ? Privilege.ROOT
                    : id == rAdb.getId() ? Privilege.ADB : Privilege.SHIZUKU;
            Prefs.setPrivilegeMode(this, m);
            boolean adb = Privilege.ADB.equals(m);
            boolean root = Privilege.ROOT.equals(m);
            adbSetup.setVisibility(adb ? View.VISIBLE : View.GONE);
            adbHint.setVisibility(adb ? View.VISIBLE : View.GONE);
            rootTest.setVisibility(root ? View.VISIBLE : View.GONE);
            if (adb && Prefs.adbPort(this) <= 0) {
                toast(I18n.t("请先完成一次无线调试配对", "Pair wireless debugging first"));
            }
            if (root) {
                // 不因切换单选项而突然弹 su；授权只在用户点「检测」或部署时请求
                status.setText(I18n.t("Root 模式：点下面的「检测 Root 授权」确认；部署时也会自动请求授权。",
                        "Root mode: tap “Test root” below to confirm; deploying also requests authorization."));
            } else {
                refreshStatus(status, m);
            }
            applyQuietly();
        });

        c.addView(rg);
        // 第 4 项的注释：说明它是「备用入口」，不是授权方式
        TextView webNote = hint(I18n.t(
                "注：第 4 项「网页版 ADB」不是授权引擎，不会改变上面的选择。"
                        + "它只是打开一个说明页，再用浏览器 + USB 数据线调试安卓设备（主机可用除 iOS 外的设备），"
                        + "用于本机回环 ADB 被收紧时的备用通道；点进去后按页面说明操作即可。",
                "Note: option 4 “Web ADB” is not a privilege engine and does not change the selection above. "
                        + "It only opens an instructions page and then debugs an Android device over USB from a browser "
                        + "(the host can be any non-iOS device), as a fallback when local loopback ADB is restricted. "
                        + "Tap it and follow the page."));
        webNote.setTextColor(C_PRIMARY);
        c.addView(webNote, gap(6));
        c.addView(adbHint, gap(8));
        adbSetup.setOnClickListener(v -> startActivity(new Intent(this, AdbActivity.class)));
        c.addView(adbSetup, gap(10));

        boolean adb = Privilege.ADB.equals(mode);
        adbSetup.setVisibility(adb ? View.VISIBLE : View.GONE);
        adbHint.setVisibility(adb ? View.VISIBLE : View.GONE);
        rootTest.setVisibility(Privilege.ROOT.equals(mode) ? View.VISIBLE : View.GONE);
        c.addView(rootTest, gap(6));

        c.addView(hint(I18n.t("切换后会重启守护脚本。Root 与无线调试不需要安装 Shizuku。",
                "Switching restarts the daemon. Root and wireless debugging do not require Shizuku.")));
        if (Privilege.ROOT.equals(mode)) {
            status.setText(I18n.t("Root 模式：点下面的「检测 Root 授权」确认；部署时也会自动请求授权。",
                    "Root mode: tap “Test root” below to confirm; deploying also requests authorization."));
        } else {
            refreshStatus(status, mode);
        }
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

    // ---------- 特殊锁屏返回及解锁打开指定应用 ----------

    private LinearLayout buildMusic() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("特殊锁屏返回及解锁打开指定应用",
                "Special: lock-screen return & open app after unlock")));
        c.addView(hint(I18n.t("部分系统在音乐锁屏界面输入密码后会停在音乐页。开启后，"
                        + "解锁成功再执行一次返回主界面的动作；也可以直接打开指定应用。",
                "On some ROMs the music lock screen stays after the PIN is entered. When on, "
                        + "the app performs one extra action after unlocking; it can also open a chosen app.")));

        final Switch[] subs = new Switch[2];
        final Switch master = switchRow(c,
                I18n.t("启用特殊锁屏返回", "Enable special lock-screen return"),
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
                I18n.t("从下面的已添加应用里单选一个；与右滑同时开启时，优先使用这种方式。",
                        "Pick exactly one app from the added list below. Takes priority over swiping when both are on."),
                Prefs.musicOpenApp(this), v -> {
                    Prefs.setMusicOpenApp(this, v);
                    if (v && Prefs.musicAppPkg(this).isEmpty()) {
                        toast(I18n.t("请先在下面选择一个应用", "Choose an app below first"));
                    }
                    applyQuietly();
                });

        c.addView(label(I18n.t("已添加的应用（单选，同一时间只打开一个；右侧可删除）",
                "Added apps (single choice, only one opens; delete on the right)")), gap(10));
        musicAppsBox = new LinearLayout(this);
        musicAppsBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(musicAppsBox, gap(4));

        Button pick = button(I18n.t("选择应用（读取已安装应用）",
                "Choose apps (read installed apps)"));
        pick.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        c.addView(pick, gap(8));

        c.addView(label(I18n.t("高级：手动输入包名", "Advanced: manual package name")), gap(12));
        musicPkgField = new EditText(this);
        musicPkgField.setHint(I18n.t("应用包名，例如 com.tencent.mobileqq",
                "Package name, e.g. com.tencent.mobileqq"));
        musicPkgField.setText(Prefs.musicAppPkg(this));
        musicPkgField.setTextSize(13);
        musicPkgField.setSingleLine(true);
        musicPkgField.setInputType(InputType.TYPE_CLASS_TEXT);
        musicPkgField.setPadding(dp(8), dp(6), dp(8), dp(6));
        c.addView(musicPkgField, gap(4));

        Button savePkg = button(I18n.t("保存并添加应用包名", "Save and add package name"));
        savePkg.setOnClickListener(v -> {
            String p = musicPkgField.getText().toString().trim();
            if (!p.isEmpty() && !p.matches("[A-Za-z0-9._]{1,200}")) {
                toast(I18n.t("包名格式看起来不正确", "That does not look like a valid package name"));
                return;
            }
            if (!p.isEmpty()) Prefs.addMusicApp(this, p);
            Prefs.setMusicAppPkg(this, p);
            applyQuietly();
            refreshMusicApps();
            toast(I18n.t("已保存", "Saved"));
        });
        c.addView(savePkg, gap(6));

        subs[1] = switchRow(c,
                I18n.t("解锁后向右滑动", "Swipe right after unlock"),
                I18n.t("在屏幕中部向右滑动一次，适用于没有选择应用的情况。",
                        "Swipe right once across the middle of the screen; useful when no app is selected."),
                Prefs.musicSwipe(this), v -> {
                    Prefs.setMusicSwipe(this, v);
                    applyQuietly();
                });

        boolean on = Prefs.musicReturn(this);
        subs[0].setEnabled(on);
        subs[1].setEnabled(on);
        master.setChecked(on);
        refreshMusicApps();
        return c;
    }

    /** 重建「已添加的应用」单选列表；从 AppPickerActivity 返回时也会调用 */
    private void refreshMusicApps() {
        if (musicAppsBox == null) return;
        musicAppsBox.removeAllViews();
        String selected = Prefs.musicAppPkg(this);
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        final java.util.List<RadioButton> radios = new java.util.ArrayList<>();
        int count = 0;
        for (String raw : Prefs.musicApps(this).split("\n")) {
            final String pkg = raw.trim();
            if (pkg.isEmpty()) continue;
            count++;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);

            final RadioButton rb = new RadioButton(this);
            // 同一时间只能选一个：这里手动维护互斥（RadioGroup 要求子项带唯一 id，
            // 之前的实现没有 id，导致多个按钮都显示为选中）
            rb.setText(appLabel(pkg) + "\n" + pkg);
            rb.setTextSize(13);
            rb.setTag(pkg);
            rb.setChecked(pkg.equals(selected));
            rb.setOnClickListener(v -> {
                for (RadioButton other : radios) other.setChecked(other == v);
                Prefs.setMusicAppPkg(this, pkg);
                musicPkgField.setText(pkg);
                applyQuietly();
            });
            radios.add(rb);
            item.addView(rb, new LinearLayout.LayoutParams(0, -2, 1));

            Button del = new Button(this);
            del.setText(I18n.t("删除", "Delete"));
            del.setAllCaps(false);
            del.setTextSize(12);
            del.setTextColor(0xFFDC2626);
            del.setBackground(null);
            del.setPadding(dp(10), 0, dp(10), 0);
            del.setOnClickListener(v -> {
                Prefs.removeMusicApp(this, pkg);
                applyQuietly();
                refreshMusicApps();
                musicPkgField.setText(Prefs.musicAppPkg(this));
                toast(I18n.t("已删除：%s", "Removed: %s", appLabel(pkg)));
            });
            item.addView(del);
            group.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        if (count == 0) {
            TextView empty = hint(I18n.t("还没有添加应用。点下面的按钮读取设备上已安装的应用。",
                    "No apps added yet. Tap the button below to read installed apps."));
            musicAppsBox.addView(empty);
        } else {
            musicAppsBox.addView(group);
        }
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMusicApps();
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
        switchRow(c,
                I18n.t("未锁屏时跳过注入", "Skip when already unlocked"),
                I18n.t("默认开启。亮屏时如果系统报告设备并未锁定（例如刚解锁、受信任设备生效），"
                        + "就不再重复输入 PIN，避免干扰正常使用。"
                        + "注意：小米/澎湃等 ROM 可能在熄屏后立即重新上锁，此时仍会正常注入。",
                        "On by default. If the system reports the device is not locked when the screen "
                                + "turns on (e.g. just unlocked or a trusted device is active), the PIN is "
                                + "not typed again. Note: ROMs that re-lock immediately after screen-off "
                                + "will still receive the normal injection."),
                Prefs.skipWhenUnlocked(this), v -> Prefs.setSkipWhenUnlocked(this, v));
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
        c.addView(hint(I18n.t("使用前请先阅读使用说明、更新日志与开源致谢。",
                "Read the manual, changelog and open-source credits before use.")));
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
                    toast(I18n.t("设置已保存，但未写入守护（%s）。请确认已部署且授权可用。",
                            "Saved, but not applied to the daemon (%s). Check deployment and authorization.",
                            r.text));
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

    /** 小节标签（比正文深一点、比标题小一号） */
    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(C_TEXT);
        t.getPaint().setFakeBoldText(true);
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
