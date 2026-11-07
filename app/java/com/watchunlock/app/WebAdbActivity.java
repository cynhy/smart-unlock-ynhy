package com.watchunlock.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 二级页面：网页版 ADB（USB，免回环端口）。
 *
 * 该页只是说明 + 外部入口：网页属于晨钟酱的《搞机工具箱》Web 版（GPL-3.0），
 * 不打包进 APK，也不修改其代码；应用本身仍不联网、不上传数据。
 */
public class WebAdbActivity extends Activity {

    private static final int C_BG = 0xFFF3F4F6;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_INFO_BG = 0xFFEFF6FF;
    private static final int C_INFO_TEXT = 0xFF1D4ED8;
    private static final int C_WARN_BG = 0xFFFFF7ED;
    private static final int C_WARN_TEXT = 0xFFC2410C;

    private static final String WEB_ADB_URL = "https://czjam.github.io/gjgjxweb/";
    private static final String WEB_ADB_REPO = "https://github.com/CzJam/gjgjxweb";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        setTitle(I18n.t("网页版 ADB", "Web ADB"));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        sv.addView(root, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText(I18n.t("网页版 ADB（USB，免回环端口）",
                "Web ADB (USB, no loopback port)"));
        title.setTextSize(22);
        title.setTextColor(C_TEXT);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(I18n.t("备用通道：不经过手机自己的 adbd 回环端口，用浏览器 + USB 直接调试安卓设备。",
                "Fallback: debug an Android device over USB from a browser, without the phone's own adbd loopback port."));
        sub.setTextSize(12);
        sub.setTextColor(C_SUB);
        LinearLayout.LayoutParams lpSub = new LinearLayout.LayoutParams(-1, -2);
        lpSub.topMargin = dp(4);
        lpSub.bottomMargin = dp(12);
        root.addView(sub, lpSub);

        root.addView(buildWhy(), gap(12));
        root.addView(buildOwner(), gap(12));
        root.addView(buildDevices(), gap(12));
        root.addView(buildSteps(), gap(12));
        root.addView(buildNotes(), gap(12));
        root.addView(buildActions(), gap(12));

        setContentView(sv);
    }

    private LinearLayout buildWhy() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("为什么需要它", "Why this exists")));
        c.addView(body(I18n.t(
                "谷歌可能进一步收紧「本机回环 ADB」（127.0.0.1）的配对与授权。"
                        + "这个方式不走手机自己的 adbd 回环端口，而是用 WebUSB 把目标安卓设备直接连到另一台设备，"
                        + "在浏览器里执行 ADB 命令，可作为无线调试失效时的备用通道。",
                "Google may further restrict pairing/authorization for local loopback ADB (127.0.0.1). "
                        + "This path does not use the phone's own adbd loopback port. Instead, WebUSB connects the "
                        + "target Android device directly to another device and runs ADB commands in a browser, "
                        + "as a fallback when wireless debugging is unavailable.")));
        return c;
    }

    private LinearLayout buildOwner() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("归属与版本", "Owner and version")));
        c.addView(body(I18n.t(
                "网页由 晨钟酱（CzJam）所有并维护，版本 6.3.9，属于开源项目《搞机工具箱》Web 版（GPL-3.0）。\n"
                        + "本应用只提供入口和使用说明，没有内置、也没有修改其网页代码；"
                        + "网页的版权、内容和数据安全由该网页自行负责。",
                "The page is owned and maintained by 晨钟酱 (CzJam), version 6.3.9, "
                        + "part of the open-source “搞机工具箱” Web edition (GPL-3.0).\n"
                        + "This app only provides an entry point and instructions; it does not bundle or modify "
                        + "the page. Copyright, content and data handling are the page's own responsibility.")));
        return c;
    }

    private LinearLayout buildDevices() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("适用设备", "Supported devices")));
        c.addView(body(I18n.t(
                "主机（跑浏览器的那一端）：除 iOS 以外的设备都可以——Windows、macOS、Linux、"
                        + "Android 平板或另一台手机。\n"
                        + "浏览器必须是 Chromium 内核且支持 WebUSB：Chrome、Edge、Brave、Opera 等。\n"
                        + "iOS 不行（iOS 浏览器没有 WebUSB），Firefox 也不行。\n"
                        + "被调试端必须是安卓设备，并打开「USB 调试」。",
                "Host (the device running the browser): any device except iOS — Windows, macOS, Linux, "
                        + "an Android tablet or another phone.\n"
                        + "The browser must be Chromium-based and support WebUSB: Chrome, Edge, Brave, Opera, etc.\n"
                        + "iOS cannot be used (no WebUSB in iOS browsers), and Firefox does not support WebUSB either.\n"
                        + "The device being debugged must be Android with “USB debugging” enabled.")));
        return c;
    }

    private LinearLayout buildSteps() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("使用步骤", "Steps")));
        c.addView(body(I18n.t(
                "1. 目标安卓：开发者选项 → 打开「USB 调试」。\n"
                        + "2. 连接数据线：电脑直连；手机作主机时用 OTG 转接头；两台手机用 C-to-C 数据线。\n"
                        + "3. 在主机上用 Chrome / Edge 打开下面的网页（首次加载需要联网）。\n"
                        + "4. 点网页里的「连接」，选择 USB 设备，并在目标手机上允许 USB 调试授权。\n"
                        + "5. 在网页的 ADB 终端执行命令，例如查看设备信息、临时授权或部署脚本。",
                "1. On the target Android device: Developer options → enable “USB debugging”.\n"
                        + "2. Connect a data cable: a computer directly; an OTG adapter when a phone is the host; "
                        + "a C-to-C cable between two phones.\n"
                        + "3. Open the page below in Chrome / Edge on the host (the first load needs network).\n"
                        + "4. Tap “Connect” on the page, choose the USB device, and allow USB debugging on the target.\n"
                        + "5. Run commands in the page's ADB terminal, e.g. read device info, grant temporary "
                        + "permissions, or deploy a script.")));
        return c;
    }

    private LinearLayout buildNotes() {
        LinearLayout c = card(C_WARN_BG);
        TextView t = new TextView(this);
        t.setText(I18n.t(
                "注意：这是外部页面，首次打开必须联网；本应用本身仍不联网、不上传数据，"
                        + "打开后由浏览器直接与设备通信。\n"
                        + "不要把它当成「不用打开 USB 调试」的魔法：目标端必须开 USB 调试并确认 RSA 授权。\n"
                        + "若手机主机的 USB 口不支持 OTG / 供电不足，会出现连不上或反复掉线，"
                        + "换 OTG 转接头或改用电脑。",
                "Note: this is an external page; the first load needs network. This app itself still makes no "
                        + "network requests and uploads nothing; after opening, the browser talks to the device directly.\n"
                        + "It is not a way to skip USB debugging: USB debugging must be on and the RSA authorization "
                        + "confirmed on the target.\n"
                        + "If the phone-host USB port lacks OTG or power, the connection may fail or drop; use a "
                        + "better OTG adapter or a computer."));
        t.setTextSize(13);
        t.setTextColor(C_WARN_TEXT);
        t.setLineSpacing(dp(3), 1f);
        c.addView(t);
        return c;
    }

    private LinearLayout buildActions() {
        LinearLayout c = card();
        c.addView(sectionTitle(I18n.t("打开网页版", "Open the web page")));
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
                C_INFO_BG, C_INFO_TEXT), gap(10));
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
}
