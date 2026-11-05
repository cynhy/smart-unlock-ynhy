package com.watchunlock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 三级页面：公告栏（注意事项与测试版本说明） */
public class AnnounceActivity extends Activity {

    private static final int C_BG = 0xFFF3F4F6;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_WARN = 0xFFB45309;
    private static final int C_LINK = 0xFF2563EB;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        setTitle(I18n.t("公告栏", "Announcements"));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        sv.addView(root, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText(I18n.t("公告与注意事项", "Announcements and notes"));
        title.setTextSize(22);
        title.setTextColor(C_TEXT);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        LinearLayout testCard = card();
        TextView test = new TextView(this);
        test.setText(I18n.t("这是测试应用包（2.0）",
                "This is a test build (2.0)"));
        test.setTextSize(16);
        test.setTextColor(C_WARN);
        test.getPaint().setFakeBoldText(true);
        testCard.addView(test);
        testCard.addView(body(I18n.t("本版本使用测试密钥签名，不是正式发布版本。"
                        + "测试包与正式包签名不同，两者无法直接覆盖安装；切换时需要先卸载旧版本，"
                        + "卸载会清除应用内的设置与设备列表，请提前记录。",
                "This build is signed with a test key and is not an official release. "
                        + "The test and official builds use different signatures and cannot overwrite each "
                        + "other; switching requires uninstalling the old build, which clears in-app settings "
                        + "and the device list, so note them down first.")));
        root.addView(testCard, gap(12));

        LinearLayout notes = card();
        notes.addView(sectionTitle(I18n.t("使用注意", "Notes")));
        notes.addView(body(I18n.t(
                "1. 默认授权方式是 Shizuku。Root 需要设备已 root 并在弹窗中授予 su；"
                        + "无线调试需要在开发者选项手动打开，重启后会自动关闭，端口也会变化，需要重新配对。\n\n"
                        + "2. 切换授权方式后会自动重启守护脚本；如果提示授权未就绪，请先完成对应授权再部署。\n\n"
                        + "3. 无线调试的配对端口与连接端口不同：配对端口只在使用配对码弹窗时有效，"
                        + "关闭弹窗后立即失效；连接端口以「无线调试」主页面显示的为准。\n\n"
                        + "4. WiFi 条件只能读取当前已连接的网络，添加的是当前 WiFi，"
                        + "不支持手动输入 SSID。\n\n"
                        + "5. 开启「SIM 卡 PIN 防呆」会增加一次状态检查，解锁成功率和速度都会下降，默认关闭。\n\n"
                        + "6. 音乐锁屏返回：两个方式可单独或同时开启，同时开启时优先按包名打开应用；"
                        + "包名必须填写正确，否则会退回右滑（若已开启）。\n\n"
                        + "7. 部分系统会限制后台启动应用，若打开应用无效，请给本应用授予"
                        + "「后台弹出界面 / 自启动」权限。\n\n"
                        + "8. 解锁依赖屏幕点亮事件，系统省电策略过严时可能延迟或失败；"
                        + "建议把本应用加入电池优化白名单。",
                "1. The default engine is Shizuku. Root requires the device to be rooted and su granted "
                        + "in the prompt; wireless debugging must be enabled manually in Developer options, "
                        + "turns off after a reboot, and its port changes, so pair again.\n\n"
                        + "2. Switching engines restarts the daemon automatically. If it reports that the "
                        + "engine is not ready, finish the authorization first and deploy again.\n\n"
                        + "3. The pairing port and the connect port are different. The pairing port only "
                        + "works while the pairing dialog is open; use the port shown on the main "
                        + "Wireless debugging screen for connection.\n\n"
                        + "4. The Wi-Fi condition can only read the currently connected network; it adds "
                        + "the current Wi-Fi and does not support manual SSID entry.\n\n"
                        + "5. SIM PIN guard adds an extra check and lowers unlock reliability and speed. "
                        + "It is off by default.\n\n"
                        + "6. Music lock screen return: the two methods can be used alone or together. "
                        + "When both are on, opening the app by package name takes priority; if the "
                        + "package is wrong it falls back to the right swipe when enabled.\n\n"
                        + "7. Some ROMs restrict background app launches. If opening the app does not "
                        + "work, grant this app background-popup / auto-start permission.\n\n"
                        + "8. Unlocking relies on screen-on events. Aggressive battery saving may delay "
                        + "or break it; add this app to the battery optimization whitelist.")));
        root.addView(notes, gap(12));

        LinearLayout credits = card();
        TextView creditsTitle = sectionTitle(I18n.t("开源组件（点击查看开源协议）",
                "Open-source components (tap for licenses)"));
        creditsTitle.setTextColor(C_LINK);
        creditsTitle.setOnClickListener(v -> showLicenses());
        credits.addView(creditsTitle);
        credits.addView(body(I18n.t(
                "本应用使用了以下开源项目，在此致谢：\n\n"
                        + "· Shizuku（RikkaApps，Apache-2.0）——免 root 授权框架与 API\n"
                        + "· Kadb（flyfishxu，Apache-2.0）——应用内无线 ADB 客户端与配对流程\n"
                        + "· Bouncy Castle bcprov / bcpkix / bcutil（Bouncy Castle Licence）——证书与密钥生成\n"
                        + "· Okio（Square，Apache-2.0）、Kotlin 与 kotlinx.coroutines（JetBrains，Apache-2.0）\n"
                        + "· hiddenapibypass、spake2——各自遵循其上游开源协议\n\n"
                        + "以上项目的完整许可证文本与版权声明见各自官方仓库。"
                        + "无线调试相关实现参考了社区公开方案（Kadb、ADBeesh、Shizuku 的配对流程）。",
                "This app uses the following open-source projects:\n\n"
                        + "· Shizuku (RikkaApps, Apache-2.0) — privilege framework and API\n"
                        + "· Kadb (flyfishxu, Apache-2.0) — in-app wireless ADB client and pairing\n"
                        + "· Bouncy Castle bcprov / bcpkix / bcutil (Bouncy Castle Licence) — certificates and keys\n"
                        + "· Okio (Square, Apache-2.0), Kotlin and kotlinx.coroutines (JetBrains, Apache-2.0)\n"
                        + "· hiddenapibypass, spake2 — under their respective upstream licenses\n\n"
                        + "Full license texts and copyright notices are in the upstream repositories. "
                        + "The wireless debugging flow references public community implementations "
                        + "(Kadb, ADBeesh, and Shizuku's pairing workflow).")));
        root.addView(credits, gap(12));

        LinearLayout contact = card();
        contact.addView(sectionTitle(I18n.t("反馈", "Feedback")));
        contact.addView(body(I18n.t("遇到问题请带上日志页的截图，并注明手机型号与系统版本。"
                        + "交流群 QQ：697302419",
                "When reporting a problem, include a screenshot of the log page and your phone model "
                        + "and OS version. QQ group: 697302419")));
        root.addView(contact, gap(12));

        setContentView(sv);
    }

    /** 点击「开源组件」后弹出的协议说明 */
    private void showLicenses() {
        TextView tv = new TextView(this);
        tv.setText(I18n.t(
                "Apache License 2.0\n"
                        + "适用：Shizuku（RikkaApps）、Kadb（flyfishxu）、Okio（Square）、"
                        + "Kotlin 与 kotlinx.coroutines（JetBrains）、hiddenapibypass\n\n"
                        + "· 允许商用、修改与再分发；\n"
                        + "· 须保留版权声明、许可声明与 NOTICE 内容，修改过的文件需注明改动；\n"
                        + "· 不得使用原作者名称或商标为衍生品背书；\n"
                        + "· 以「现状」提供，不附带任何明示或默示担保，责任由使用者承担。\n"
                        + "完整文本：https://www.apache.org/licenses/LICENSE-2.0\n\n\n"
                        + "Bouncy Castle Licence（MIT 式）\n"
                        + "适用：bcprov / bcpkix / bcutil（The Legion of the Bouncy Castle Inc.）\n\n"
                        + "· 允许商用、修改与再分发；\n"
                        + "· 须保留版权声明与许可声明；\n"
                        + "· 以「现状」提供，不附带担保。\n"
                        + "完整文本：https://www.bouncycastle.org/licence.html\n\n\n"
                        + "其他\n"
                        + "· spake2 遵循其上游项目协议；\n"
                        + "· 本应用仅以调用方式使用上述组件，未修改其源码；\n"
                        + "· 无线调试的「跳转系统页面 + 手动输入配对码」流程参考了社区公开实现"
                        + "（Shizuku、Kadb、ADBeesh），特此鸣谢。",
                "Apache License 2.0\n"
                        + "Applies to: Shizuku (RikkaApps), Kadb (flyfishxu), Okio (Square), "
                        + "Kotlin and kotlinx.coroutines (JetBrains), hiddenapibypass\n\n"
                        + "· Commercial use, modification and redistribution are permitted;\n"
                        + "· Keep copyright, license and NOTICE notices; mark modified files;\n"
                        + "· Do not use the authors' names or trademarks to endorse derivatives;\n"
                        + "· Provided \"AS IS\", without warranties; liability lies with the user.\n"
                        + "Full text: https://www.apache.org/licenses/LICENSE-2.0\n\n\n"
                        + "Bouncy Castle Licence (MIT-style)\n"
                        + "Applies to: bcprov / bcpkix / bcutil (The Legion of the Bouncy Castle Inc.)\n\n"
                        + "· Commercial use, modification and redistribution are permitted;\n"
                        + "· Keep the copyright and license notices;\n"
                        + "· Provided \"AS IS\", without warranties.\n"
                        + "Full text: https://www.bouncycastle.org/licence.html\n\n\n"
                        + "Others\n"
                        + "· spake2 follows its upstream license;\n"
                        + "· This app only calls these components and does not modify their source;\n"
                        + "· The wireless debugging flow (jump to system page + manual pairing code) "
                        + "references public community implementations (Shizuku, Kadb, ADBeesh)."));
        tv.setTextSize(13);
        tv.setTextColor(C_TEXT);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(dp(18), dp(14), dp(18), dp(4));
        tv.setTextIsSelectable(true);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle(I18n.t("开源协议", "Open-source licenses"))
                .setView(sv)
                .setPositiveButton(I18n.t("知道了", "OK"), null)
                .show();
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
}
