package com.watchunlock.app;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 增强防护二级页面：MAC 严格匹配、WiFi BSSID/安全类型绑定 */
public class ProtectActivity extends Activity {

    private static final int C_BG = 0xFFF3F4F6;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_SUB = 0xFF6B7280;

    private Switch macStrict;
    private Switch macFallback;
    private Switch wifiBind;
    private Switch wifiOpen;
    private Switch presenceLive;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        setTitle(I18n.t("增强防护", "Enhanced protection"));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));
        sv.addView(root, new LinearLayout.LayoutParams(-1, -2));
        setContentView(sv);

        TextView title = new TextView(this);
        title.setText(I18n.t("增强防护", "Enhanced protection"));
        title.setTextSize(21);
        title.setTextColor(C_TEXT);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(I18n.t(
                "这里有三部分：蓝牙按 MAC 认设备、WiFi 按 BSSID 认网络、以及解锁前的在线查询方式。"
                        + "MAC 相关的两个开关暂时停用（正在修复），其余可以正常使用。",
                "Three parts: identify Bluetooth devices by MAC, Wi-Fi networks by BSSID, and choose how "
                        + "presence is checked before unlocking. The two MAC switches are temporarily disabled "
                        + "while being fixed; the rest works normally."));
        sub.setTextSize(12);
        sub.setTextColor(C_SUB);
        LinearLayout.LayoutParams lpSub = new LinearLayout.LayoutParams(-1, -2);
        lpSub.topMargin = dp(4);
        lpSub.bottomMargin = dp(12);
        root.addView(sub, lpSub);

        root.addView(macCard(), gap(dp(12)));
        root.addView(wifiCard(), gap(dp(12)));
        root.addView(speedCard(), gap(dp(12)));
    }

    /** 在线判定方式：缓存（快）还是每次现查（慢但稳） */
    private LinearLayout speedCard() {
        LinearLayout c = card();
        c.addView(section(I18n.t("解锁速度 / 在线判定", "Unlock speed / presence check")));

        presenceLive = switchRow(I18n.t("每次解锁前都现场查询设备状态",
                        "Query device presence live before every unlock"),
                I18n.t("关闭（默认）：用蓝牙连接/断开事件维护的缓存，亮屏解锁更快。\n"
                                + "打开：每次亮屏都现场扫描蓝牙状态，慢约 0.2~0.4 秒；"
                                + "只有在缓存经常不准（系统限制蓝牙广播、App 常被杀）时才需要打开。",
                        "Off (default): use the cache maintained by Bluetooth connect/disconnect events, "
                                + "so waking is faster.\nOn: scan the Bluetooth state live on every wake, "
                                + "about 0.2–0.4 s slower. Only needed when the cache is often wrong (broadcasts "
                                + "restricted or the app is killed frequently)."),
                Prefs.presenceLive(this), null);
        presenceLive.setOnCheckedChangeListener((btn, v) -> {
            Prefs.setPresenceLive(this, v);
            applyQuietly();
        });
        c.addView(presenceLive);

        c.addView(hint(I18n.t(
                "缓存里带着「应用是否在监听蓝牙事件」的标记。本版修好了重启后这个标记被清掉的问题，"
                        + "正常情况下不需要打开现查。",
                "The cache carries a flag saying whether the app is actually listening to Bluetooth events. "
                        + "This build fixes that flag being lost on restarts, so live checks are normally unnecessary.")));
        return c;
    }

    private LinearLayout macCard() {
        LinearLayout c = card();
        c.addView(section(I18n.t("蓝牙身份匹配（暂时停用，正在修复中）",
                "Bluetooth identity (temporarily disabled, being fixed)")));

        macStrict = switchRow(I18n.t("只认 MAC 地址",
                        "Only match by MAC address"),
                I18n.t("打开后，只有 MAC 对得上的设备才算在身边，名称只用来显示。",
                        "When on, a device counts as nearby only if its MAC matches; the name is display-only."),
                false, null);
        macStrict.setEnabled(false);
        c.addView(macStrict);

        macFallback = switchRow(I18n.t("MAC 对不上时，允许同名设备解锁",
                        "Allow a same-name device when the MAC mismatches"),
                I18n.t("只有上面的开关打开时才有意义。放行时界面会标注「身份不可靠」。",
                        "Only meaningful when the switch above is on. The UI marks it as “unreliable identity”."),
                false, null);
        macFallback.setEnabled(false);
        c.addView(macFallback);

        TextView note = hint(I18n.t(
                "为什么停用：蓝牙耳机、手表、手环等设备大多使用随机 MAC，同一个设备每次连接都可能换地址。"
                        + "按 MAC 严格比对，会把正常设备判成「不在线」，结果就是一个设备都解锁不了。"
                        + "因此内核已关闭，配置里始终按旧规则判定：先看 MAC，对不上再看名称。"
                        + "界面保留，修好后重新开放。",
                "Why disabled: earbuds, watches and bands usually use a random MAC that can change on every "
                        + "connection. Strict MAC matching then reports your own device as offline, so nothing "
                        + "unlocks at all. The matching core is therefore off and the old rule is used (MAC first, "
                        + "then name). The UI stays for when it is fixed."));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        c.addView(note, lp);
        return c;
    }

    private LinearLayout wifiCard() {
        LinearLayout c = card();
        c.addView(section(I18n.t("WiFi 身份匹配", "Wi-Fi identity")));

        wifiBind = switchRow(I18n.t("绑定 BSSID + 要求非开放网络",
                        "Bind BSSID and require a non-open network"),
                I18n.t("添加 WiFi 时记录 BSSID；运行时同时比对 SSID 与 BSSID，"
                                + "并拒绝开放（无密码）网络。连接状态用 cmd wifi status 判定。",
                        "Records the BSSID when adding, then matches both SSID and BSSID at runtime and "
                                + "rejects open (unencrypted) networks. Connection state is read via cmd wifi status."),
                Prefs.wifiBindBssid(this), null);
        c.addView(wifiBind);

        wifiOpen = switchRow(I18n.t("允许开放网络也成立",
                        "Also accept open networks"),
                I18n.t("默认关闭。开启后，没有密码的 WiFi 也能满足 WiFi 条件。",
                        "Off by default. When on, an unencrypted Wi-Fi can also satisfy the Wi-Fi condition."),
                Prefs.wifiAllowOpen(this), null);
        c.addView(wifiOpen);

        TextView note = hint(I18n.t(
                "说明：只比对 SSID 时，同名热点可能被伪造。绑定 BSSID 后，"
                        + "同一路由器的不同频段（2.4G/5G）会被视为不同网络，需要分别添加。",
                "Note: matching only the SSID allows a fake hotspot with the same name. With BSSID binding, "
                        + "the 2.4 GHz and 5 GHz bands of one router count as different networks and must be added separately."));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        c.addView(note, lp);

        CompoundButton.OnCheckedChangeListener sync = (btn, v) -> {
            Prefs.setWifiBindBssid(this, wifiBind.isChecked());
            Prefs.setWifiAllowOpen(this, wifiOpen.isChecked());
            applyQuietly();
        };
        wifiBind.setOnCheckedChangeListener(sync);
        wifiOpen.setOnCheckedChangeListener(sync);
        return c;
    }

    private void applyQuietly() {
        new Thread(() -> {
            try {
                Scripts.applyConfig(this);
            } catch (Throwable ignored) {
            }
        }, "protect-apply").start();
    }

    private Switch switchRow(String title, String desc, boolean on, CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(this);
        StringBuilder sb = new StringBuilder(title);
        if (desc != null && !desc.isEmpty()) {
            sb.append("\n").append(desc);
        }
        s.setText(sb.toString());
        s.setTextSize(14);
        s.setTextColor(C_TEXT);
        s.setChecked(on);
        s.setPadding(0, dp(10), 0, dp(10));
        if (l != null) s.setOnCheckedChangeListener(l);
        return s;
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTextColor(C_TEXT);
        t.getPaint().setFakeBoldText(true);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(C_SUB);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(dp(14));
        l.setBackground(d);
        return l;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
