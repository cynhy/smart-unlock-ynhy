package com.watchunlock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 三级页面：公告栏（注意事项、使用说明、更新日志与开源致谢） */
public class AnnounceActivity extends Activity {

    /** 仓库地址：只作为纯文本展示，长按复制，不做点击跳转 */
    private static final String REPO = "https://github.com/cynhy/smart-unlock-ynhy";

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

        LinearLayout devCard = card();
        devCard.addView(sectionTitle(I18n.t("个人开发 · 欢迎反馈",
                "Indie project · feedback welcome")));
        devCard.addView(body(I18n.t(
                "本应用由个人开发与维护，可能存在使用问题，欢迎把现象、手机型号、"
                        + "系统版本和日志页截图一起反馈。\n\n"
                        + "应用完全在本地运行：不联网、不上传任何数据，因此它没有任何推送通道，"
                        + "无法主动通知你更新。请到下面的仓库查看最新功能、修复与安全说明；"
                        + "如果这个工具对你有帮助，欢迎在仓库点一个 Star / 收藏。",
                "This app is built and maintained by an individual. There may be rough edges; "
                        + "please report the symptom, phone model, OS version and a screenshot of the log page.\n\n"
                        + "The app runs entirely on-device: no network access, no data uploaded, so it has no "
                        + "push channel and cannot tell you about updates. Check the repository below for new "
                        + "features, fixes and security notes. If it helps you, a Star / bookmark is appreciated.")));
        root.addView(devCard, gap(12));

        LinearLayout repoCard = card();
        repoCard.addView(sectionTitle(I18n.t("项目仓库", "Repository")));
        final TextView repo = new TextView(this);
        repo.setText(REPO);
        repo.setTextSize(14);
        repo.setTextColor(C_TEXT);
        repo.setTypeface(android.graphics.Typeface.MONOSPACE);
        repo.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable repoBg = new GradientDrawable();
        repoBg.setColor(0xFFF9FAFB);
        repoBg.setCornerRadius(dp(8));
        repo.setBackground(repoBg);
        repo.setTextIsSelectable(true);
        repo.setOnLongClickListener(v -> {
            copyRepo();
            return true;
        });
        repo.setOnClickListener(v -> toast(I18n.t(
                "不支持点击跳转，请长按这一行复制到浏览器打开",
                "Links are not clickable here. Long-press this line to copy it, then open it in a browser")));
        repoCard.addView(repo, gap(8));
        repoCard.addView(body(I18n.t(
                "不支持点击跳转，请长按复制到浏览器打开。仓库里可以看到最新版本与更新记录。",
                "Not clickable. Long-press to copy the address, then open it in a browser. "
                        + "The repository hosts the latest build and changelog.")));
        root.addView(repoCard, gap(12));

        LinearLayout tip = card();
        tip.addView(sectionTitle(I18n.t("重要提醒", "Important")));
        tip.addView(body(I18n.t(
                "1. 设置改动会在守护运行时自动热重载（写入配置，下一个事件前生效），"
                        + "不需要为了一个开关重启守护；首次使用、切换授权方式、升级或重装后，"
                        + "请回到主页点「部署并启用」。\n\n"
                        + "2. 「部署并启用」会清理上一轮的运行残留（旧守护进程、旧 logcat 监听、"
                        + "跨进程锁、失败计数与冷却状态），但不会清除你的设置、设备勾选与日志。\n\n"
                        + "3. 在线判定缓存 wu.present 不随部署/重启删除：它记录「应用是否正在监听蓝牙事件」，"
                        + "删掉会让每次亮屏都现场查询、慢 200~350ms。只有在应用存活且确实在监听、"
                        + "并且缓存结论为「在线」时才会直接信任它，其余情况一律现场查询。\n\n"
                        + "4. 切换授权方式后同样需要重新部署一次。",
                "1. Setting changes are hot-reloaded while the daemon runs (the config is rewritten and "
                        + "takes effect before the next event), so a toggle no longer restarts the daemon. "
                        + "Return to the home screen and tap “Deploy & enable” for the first deploy, after "
                        + "switching the privilege engine, or after an upgrade / reinstall.\n\n"
                        + "2. Deploy cleans the leftovers of the previous run (old daemon, old logcat "
                        + "listener, cross-process locks, failure counters and cooldown) but never wipes "
                        + "your settings, selected devices or logs.\n\n"
                        + "3. The presence cache (wu.present) is not deleted on deploy/restart: it records "
                        + "whether the app is listening to Bluetooth events, and deleting it would force a "
                        + "live scan on every wake (+200–350 ms). It is trusted only when the app is alive, "
                        + "actually listening, and the cached result is “online”; otherwise the daemon "
                        + "queries the device state live.\n\n"
                        + "4. Switching the privilege engine also requires deploying again.")));
        root.addView(tip, gap(12));

        LinearLayout notes = card();
        notes.addView(sectionTitle(I18n.t("使用说明（按功能）", "Manual (by feature)")));
        notes.addView(body(I18n.t(
                "1. 部署并启用\n"
                        + "把守护脚本、配置与 PIN 写入 /data/local/tmp，并按当前授权方式启动守护。"
                        + "设置改动会自动热重载；首次使用、切换授权方式、升级或重装后才需要重新部署。"
                        + "「停止」只停守护，不清除设置。\n\n"
                        + "2. 授权方式\n"
                        + "· Shizuku（默认，免 root）：先在 Shizuku 里授权本应用，再部署。\n"
                        + "· Root：设备需已 root，并在 su 弹窗里允许本应用；设置页可点「检测 Root 授权」"
                        + "单独确认，部署时也会自动请求授权。\n"
                        + "· 无线调试（内置 ADB，免 root）：手机自己连自己的 adbd。需要在开发者选项打开"
                        + "「无线调试」，重启后会自动关闭、端口也会变化，需要重新配对；"
                        + "配对码在通知里输入，MIUI/澎湃需把通知样式切换为原生样式。"
                        + "若谷歌未来收紧本机回环 ADB，可用同一页底部的「网页版 ADB」作为备用通道。\n\n"
                        + "3. 蓝牙设备\n"
                        + "勾选要识别的设备。应用监听蓝牙连接/断开广播，并把最近一次结论缓存给守护使用；"
                        + "如果系统省电策略把后台清得太狠、状态看起来不准，可以到"
                        + "「增强防护 → 解锁速度 / 在线判定」打开「每次解锁前都现场查询设备状态」——"
                        + "更稳，但每次亮屏多花约 0.2~0.35 秒，耗电也会略微增加（默认关闭）。\n\n"
                        + "4. Wi-Fi 条件\n"
                        + "只能读取当前已连接的 WiFi，添加的是当前网络，不支持手输 SSID；"
                        + "开启「增强防护 → WiFi → 绑定 BSSID + 要求非开放网络」后，"
                        + "开放（无密码）网络默认被拒绝，可在同页的「允许开放网络也成立」放行；"
                        + "「必要条件」要求蓝牙与 WiFi 同时成立，「WiFi 解锁」允许 WiFi 单独触发。\n\n"
                        + "5. 屏幕解锁\n"
                        + "亮屏后由守护脚本自动打开 PIN 界面并输入密码；不同 ROM 的上滑 / 键盘差异由脚本自动切换，"
                        + "无需手动设置。「未锁屏时跳过注入」默认开启，避免重复输入；"
                        + "SIM 卡 PIN 防呆会增加一次检查，成功率和速度都会下降，默认关闭。\n\n"
                        + "6. 特殊锁屏返回及解锁打开指定应用\n"
                        + "部分系统在音乐锁屏输入密码后会停在音乐页，可自动打开指定应用或向右滑动一次。"
                        + "「选择应用」读取全部已安装应用，默认只列桌面可启动应用，分第三方/系统两页并可搜索；"
                        + "已添加的应用为单选，同一时间只打开一个；包名写错时不会打开任何应用（开启右滑则退回右滑）。\n\n"
                        + "7. 暂停与冷却\n"
                        + "连续失败达到上限会进入冷却，避免反复试错；也可以手动暂停一段时间。\n\n"
                        + "8. 日志\n"
                        + "日志页可自动刷新、复制、清空。反馈问题时请附上日志页截图，"
                        + "并注明手机型号与系统版本。\n\n"
                        + "9. 常见问题\n"
                        + "解锁没反应：先看日志是否有 skip 记录（未锁屏、未命中设备、冷却中等）。\n"
                        + "打开应用无效：给本应用授予「后台弹出界面 / 自启动」权限。\n"
                        + "小米 / 澎湃：把本应用电池策略设为「无限制」，通知样式切为原生样式。\n"
                        + "重启后失效：无线调试会随重启关闭，需重新配对；Shizuku 也需重新启动。\n\n"
                        + "10. 延长解锁（实验性，默认关闭）\n"
                        + "先在主页填写与系统一致的锁屏 PIN，再打开「高级 → 延长解锁」，依次通过三级确认。"
                        + "首次成功解锁后，锁屏密码会被临时清除，亮屏直接进入桌面，不再注入 PIN。\n"
                        + "所有启用的蓝牙设备与 WiFi 条件都不再成立后，会立即、+0.5 秒、+1.5 秒各判定一次，"
                        + "三次都离线才写回原 PIN；"
                        + "关闭开关、停止守护或一键清理会立即写回。"
                        + "密码清除期间指纹 / 人脸会被系统作废，恢复后需重新录入；部分 ROM 可能不生效。"
                        + "若出现异常，请点「立即恢复锁屏密码」，或在系统设置里重新设置锁屏。",
                "1. Deploy & enable\n"
                        + "Writes the daemon, config and PIN to /data/local/tmp and starts the daemon with the "
                        + "selected engine. Setting changes are hot-reloaded; redeploy only for the first "
                        + "deploy, after switching the engine, or after an upgrade / reinstall. "
                        + "“Stop” only stops the daemon and keeps your settings.\n\n"
                        + "2. Privilege engine\n"
                        + "· Shizuku (default, no root): authorize this app in Shizuku first, then deploy.\n"
                        + "· Root: the device must be rooted; allow this app in the su prompt. The Settings page "
                        + "has a “Test root” button; deploying also requests authorization automatically.\n"
                        + "· Wireless debugging (built-in ADB, no root): the phone connects to its own adbd. "
                        + "Turn it on in Developer options; it turns off after a reboot and the port changes, "
                        + "so pair again. Enter the pairing code in the notification; on MIUI/HyperOS switch "
                        + "the notification style to native. If Google restricts local loopback ADB in the "
                        + "future, use “Web ADB” at the bottom of the same page as a fallback.\n\n"
                        + "3. Bluetooth devices\n"
                        + "Tick the devices to recognize. The app listens to Bluetooth connect/disconnect "
                        + "broadcasts and hands the latest result to the daemon as a cache. If aggressive "
                        + "power saving makes that state unreliable, enable “query live before every unlock” "
                        + "in Protection → “Unlock speed / presence”: more reliable, but it adds about "
                        + "0.2–0.35 s per wake and slightly more battery use (off by default).\n\n"
                        + "4. Wi-Fi condition\n"
                        + "Only the currently connected Wi-Fi can be read; it adds the current network and does "
                        + "not accept manual SSIDs. With Protection → Wi-Fi → “Bind BSSID + require a secured "
                        + "network”, open (unencrypted) networks are rejected unless “Allow open networks” is "
                        + "enabled on the same page. “Required” needs Bluetooth and Wi-Fi at the same time; "
                        + "“Wi-Fi unlock” lets Wi-Fi trigger alone.\n\n"
                        + "5. Screen unlock\n"
                        + "The daemon opens the PIN screen and enters the code automatically. Swipe-up / keypad "
                        + "differences between ROMs are handled by the script, with no manual setting. "
                        + "“Skip when already unlocked” is on by default. "
                        + "SIM PIN guard adds a check and lowers reliability and speed; it is off by default.\n\n"
                        + "6. Special lock-screen return & open app after unlock\n"
                        + "Some ROMs stay on the music lock screen after the PIN. The app can open a chosen app "
                        + "or swipe right once. “Choose apps” reads all installed apps, lists launcher-launchable "
                        + "ones by default, splits third-party/system into two pages and supports search. Added "
                        + "apps are single-choice: only one opens at a time. A wrong package opens nothing "
                        + "(falls back to swiping when enabled).\n\n"
                        + "7. Pause & cooldown\n"
                        + "Repeated failures trigger a cooldown instead of retrying forever; you can also pause "
                        + "manually for a while.\n\n"
                        + "8. Log\n"
                        + "The log page can auto-refresh, copy and clear. When reporting a problem, attach a "
                        + "screenshot of the log and your phone model and OS version.\n\n"
                        + "9. Common issues\n"
                        + "No unlock: check the log for skip reasons (already unlocked, no device match, "
                        + "cooldown, etc.).\n"
                        + "App does not open: grant background-popup / auto-start permission to this app.\n"
                        + "Xiaomi / HyperOS: set the battery policy to “No restrictions” and use the native "
                        + "notification style.\n"
                        + "Stops working after reboot: wireless debugging turns off on reboot and must be "
                        + "re-paired; Shizuku must also be started again.\n\n"
                        + "10. Extend unlock (experimental, off by default)\n"
                        + "Enter the same PIN as the system lock screen on the home screen, then turn on "
                        + "advanced → Extend unlock and pass the three confirmations. After the first "
                        + "successful unlock the lock-screen PIN is temporarily removed, so screen-on goes "
                        + "straight to the home screen with no PIN injection.\n"
                        + "When every enabled Bluetooth device and Wi-Fi condition no longer holds, presence is "
                        + "checked three times (now, +0.5 s, +1.5 s) and the PIN is written back only if all "
                        + "three are offline; turning the "
                        + "switch off, stopping the daemon or clean-up restores it immediately. While it is "
                        + "cleared, fingerprints / face data are invalidated and must be re-enrolled; some "
                        + "ROMs reject the feature. If anything goes wrong, tap “Restore lock-screen PIN now” "
                        + "or set the lock screen again in system settings.")));
        root.addView(notes, gap(12));

        LinearLayout changelog = card();
        changelog.addView(sectionTitle(I18n.t("更新日志", "Changelog")));
        changelog.addView(body(I18n.t(
                "6.3.6\n"
                        + "· 修复：StateMachine 头部的 MAC 字段取错（把 for 当成 MAC），"
                        + "部分机型因此一直判定「不在线」、完全无法解锁；现在改为取「StateMachine for」之后的地址\n"
                        + "· 修复：配对通知正文不再打开应用。点通知正文会离开系统「无线调试」弹窗，"
                        + "导致配对端口和 6 位码失效；请下拉展开通知，点「输入配对码」\n"
                        + "· 版本号提升到 6.3.6，避免与已安装的 6.3.3 同版本无法覆盖\n\n"
                        + "6.3.3\n"
                        + "· 修复：延长解锁在 clear / restore 之间来回震荡（每 10 秒左右写回一次密码）\n"
                        + "　· 恢复密码前连续 3 次独立判定（立即 → +0.5 秒 → +1.5 秒），"
                        + "任何一次在线或无法判定都放弃本次恢复\n"
                        + "　· presence 临时文件改为按进程独立，并发时不会读到半截 dumpsys；"
                        + "空或异常输出记为「未知」，不再当作离线\n"
                        + "　· clear / restore 增加互斥锁，清除前再复核一次在线，消除同秒「在线 / 离线」矛盾\n\n"
                        + "3.9 / 6.3（两份安装包内容一致，仅版本号不同）\n"
                        + "· 修复：重启守护 / 改设置后「应用正在监听蓝牙」的登记丢失，在线判定退化成每次亮屏现查（+200~350ms）；"
                        + "现在重启不再清掉该缓存，守护拉起后会自动补登记\n"
                        + "· 优化：改设置不再重启守护（配置热重载），避免连续改开关时 2~3 秒一次的重启风暴和解锁排队\n"
                        + "· 新增：增强防护 →「解锁速度 / 在线判定」，可手动切换「每次解锁前都现场查询」（默认关闭）\n"
                        + "· 修复：开了 RSSI 距离门槛时每次解锁会重复现查两遍；部分 ROM 的 StateMachine MAC 解析字段取错\n"
                        + "· 优化：解锁与守护重启使用独立队列；升级迁移每次启动服务只做一次\n"
                        + "· 新增：延长解锁（激进手段近似版，实验性、默认关闭）\n"
                        + "　· 在身边时用 locksettings clear + set-disabled true 临时关闭锁屏密码，亮屏不再注入 PIN\n"
                        + "　· 受信任设备 / WiFi 全部断开、关闭开关、停止守护、一键清理时，立即 set-pin 写回原密码\n"
                        + "　· 开启需先校验系统 PIN，并连续通过三级确认（5 秒 / 2 秒 / 2 秒强制等待）\n"
                        + "　· 风险：生物识别会被系统作废，需重新录入；部分 ROM 可能直接失败；守护被系统杀死时可能无法自动恢复\n"
                        + "　· 主页高级区显示当前状态，并提供「立即恢复锁屏密码」\n"
                        + "· 调整：MAC 严格匹配暂时停用——蓝牙设备普遍使用随机 MAC，开启后会把正常设备判成「不在线」，"
                        + "导致无法解锁。界面保留但不可操作，并注明正在修复；判定恢复为「先看 MAC，对不上再看名称」\n\n"
                        + "3.8.6 / 3.8.8（两份安装包内容一致，仅版本号不同）\n"
                        + "· 修复：解锁后打开指定应用会弹出「没有应用可执行此操作」——不再只按包名解析，改为按 MAIN + LAUNCHER 解析真实启动组件\n"
                        + "· 修复：已添加的应用显示为单选却能同时选中多个；现在严格单选，每个应用右侧可直接删除\n"
                        + "· 新增：增强防护（主页右侧卡片总开关 + 二级页面）\n"
                        + "　· MAC 严格匹配：有 MAC 时只认 MAC，不匹配判为不在线；可允许同名回退，并标注「身份不可靠」\n"
                        + "　· WiFi 绑定 BSSID：添加 WiFi 时保存 BSSID 与安全类型，运行时比对 SSID + BSSID，并要求加密网络；可允许开放网络\n"
                        + "· 优化：「WiFi 独立解锁」更名为「WiFi 解锁」\n"
                        + "· 新增：开机与打开应用时主动请求通知、附近设备权限；被系统永久拒绝时引导去设置授权\n"
                        + "· 修复：无线调试配对通知改为直接进入「输入配对码」页面，输入正确也不会再跳回无线调试页\n"
                        + "· 优化：选择应用页首次进入授权后自动加载列表，并显示加载状态与「重新读取」\n"
                        + "· 优化：主页守护状态卡压缩，右侧新增增强防护卡片\n"
                        + "· 优化：主页与设置页提示「更改任何选项后请重新部署」\n"
                        + "· 优化：一键清理会停止监控服务、无线调试配对服务与 mDNS 发现，并删除全部 /data/local/tmp/wu.* 文件\n\n"
                        + "3.8\n"
                        + "· 新增：选择应用页面，读取全部已安装应用（第三方/系统分页、可搜索，默认只列桌面可启动）\n"
                        + "· 新增：解锁后打开指定应用（单选）；「音乐锁屏返回」更名为「特殊锁屏返回及解锁打开指定应用」\n"
                        + "· 新增：开发者选项跳转兼容小米/澎湃、华为、荣耀、OPPO/realme/一加、vivo/iQOO 与原生 Android\n"
                        + "· 新增：配对成功后可用内置 ADB 尝试启动 Shizuku（默认关闭）\n"
                        + "· 新增：未锁屏时跳过注入开关（默认开启）\n"
                        + "· 优化：打开应用改为解析组件 + monkey 兜底，提升各 ROM 兼容性\n"
                        + "· 公告栏改版：个人开发说明、仓库地址（长按复制）、完整使用说明与开源致谢\n"
                        + "· 说明：本版不包含设备管理员（Device Admin / Device Owner）相关功能\n\n"
                        + "3.6\n"
                        + "· 无线调试改为 Shizuku 式引导：通知内直接输入配对码，自动发现端口并连接\n"
                        + "· 跳转开发者选项时尝试把「无线调试」滚动到可见位置\n"
                        + "· 增加 MIUI / 澎湃通知样式提示\n\n"
                        + "3.5\n"
                        + "· 无线调试配对流程稳定性改进，失败提示更明确\n\n"
                        + "3.4\n"
                        + "· 细节修复与稳定性改进\n\n"
                        + "3.3.3\n"
                        + "· 修复：重新部署或重启后仍沿用上一轮的在线缓存，可能出现「设备已断开却仍解锁」\n"
                        + "· 优化：部署与重启时一并清理在线缓存\n"
                        + "· 新增：设置页提示「改动任何选项后请重新部署并启用」\n"
                        + "· 新增：公告栏「重要提醒」与「更新日志」\n\n"
                        + "3.3\n"
                        + "· 正式密钥签名版本，功能与 3.0 一致\n\n"
                        + "3.0\n"
                        + "· 新增内置无线调试（不依赖 Shizuku、不需要 root）与 Root 授权档\n"
                        + "· 新增音乐锁屏返回、震动反馈、公告栏、内置 ADB 命令行\n"
                        + "· 修复：无线调试缺少 bcutil 导致的崩溃\n\n"
                        + "2.0\n"
                        + "· 三档授权框架定型，设置页改为卡片式布局（未正式分发）\n\n"
                        + "1.1\n"
                        + "· WiFi 解锁与必要条件、SIM 卡 PIN 防呆、暂停时长自定义\n"
                        + "· 三级设置页与日志页、中英双语\n"
                        + "· 修复 Android 12 兼容问题\n\n"
                        + "1.0\n"
                        + "· 首个可用版本：Shizuku 授权、亮屏蓝牙解锁、Wi-Fi 解锁、日志",
                "6.3.6\n"
                        + "· Fix: the MAC field of the StateMachine header was parsed from the wrong column "
                        + "(it took “for” as the MAC), so some devices were always treated as offline and "
                        + "could never unlock; the address after “StateMachine for” is used now\n"
                        + "· Fix: tapping the pairing notification body no longer opens the app. Leaving the "
                        + "system Wireless debugging dialog invalidates the pairing port and 6-digit code; "
                        + "pull down and use the “Enter pairing code” action\n"
                        + "· Version bumped to 6.3.6 so it can replace an installed 6.3.3 with the same signature\n\n"
                        + "6.3.3\n"
                        + "· Fix: extend unlock oscillated between clear and restore (writing the PIN back about "
                        + "every 10 seconds)\n"
                        + "　· Before restoring, presence is checked three independent times (now → +0.5 s → "
                        + "+1.5 s); any online or unknown result cancels the restore\n"
                        + "　· Presence temp files are now per-process, so concurrent runs can no longer read a "
                        + "half-written dumpsys; empty or invalid output counts as “unknown”, not offline\n"
                        + "　· clear / restore now share a mutex and presence is re-checked before clearing, "
                        + "removing the same-second online/offline contradiction\n\n"
                        + "3.9 / 6.3 (both APKs share the same content; version number only)\n"
                        + "· Fix: the “app is listening to Bluetooth events” flag was lost on daemon restarts / "
                        + "setting changes, so presence degraded to a live scan on every wake (+200–350 ms). "
                        + "Restarts no longer clear that cache and the flag is restored after a restart\n"
                        + "· Optimization: changing settings no longer restarts the daemon (config hot-reload), "
                        + "removing the 2–3 s restart storm and unlock queueing caused by toggling switches\n"
                        + "· New: Enhanced protection → “Unlock speed / presence check”, with an optional "
                        + "“query live before every unlock” switch (off by default)\n"
                        + "· Fix: with an RSSI threshold the presence was queried twice per unlock; the "
                        + "StateMachine MAC field was parsed from the wrong column on some ROMs\n"
                        + "· Optimization: unlocks and daemon restarts use separate queues; legacy migration runs "
                        + "once per process\n"
                        + "· New: extend unlock (aggressive workaround; experimental, off by default)\n"
                        + "　· While nearby, the lock-screen PIN is temporarily removed with "
                        + "locksettings clear + set-disabled true, so screen-on no longer injects a PIN\n"
                        + "　· The original PIN is written back (set-pin) as soon as every trusted device / "
                        + "Wi-Fi is gone, the switch is turned off, the daemon stops, or clean-up runs\n"
                        + "· Enabling verifies the system PIN first and requires three consecutive confirmations "
                        + "(forced 5 s / 2 s / 2 s waits)\n"
                        + "· Risks: biometrics are invalidated and must be re-enrolled; some ROMs reject it; "
                        + "if the daemon is killed the PIN may not be restored automatically\n"
                        + "· The Advanced section shows the live state and offers “Restore lock-screen PIN now”\n"
                        + "· Change: strict MAC matching is temporarily disabled — Bluetooth devices commonly use "
                        + "a random MAC, so enabling it reported normal devices as offline and nothing could "
                        + "unlock. The UI is kept but not operable, marked as being fixed; matching is back to "
                        + "“MAC first, then name”\n\n"
                        + "3.8.6 / 3.8.8 (both APKs share the same content; version number only)\n"
                        + "· Fix: “open app after unlock” showed “No app can perform this action” — the launcher "
                        + "component is now resolved with MAIN + LAUNCHER instead of package name only\n"
                        + "· Fix: added apps behaved as single-choice but several could be checked; now strictly "
                        + "single-choice, and every app has a Delete button\n"
                        + "· New: Protection (master switch card on the home screen + secondary page)\n"
                        + "　· Strict MAC: when a MAC exists only the MAC counts; mismatches are treated as offline; "
                        + "same-name fallback can be allowed and is marked “identity unreliable”\n"
                        + "　· Wi-Fi BSSID binding: stores BSSID and security type, compares SSID + BSSID at runtime, "
                        + "requires a secured network; open networks can be allowed explicitly\n"
                        + "· Tweak: “Wi-Fi standalone unlock” renamed to “Wi-Fi unlock”\n"
                        + "· New: actively request notification / nearby-devices permissions on boot and app start; "
                        + "guide to system settings when permanently denied\n"
                        + "· Fix: the wireless-debugging pairing notification now opens the pairing-code page "
                        + "directly and no longer jumps back to the wireless-debugging screen after a correct code\n"
                        + "· Tweak: the app picker reloads automatically after the first permission grant, with a "
                        + "loading state and a Reload button\n"
                        + "· Tweak: the home status card is compact and the Protection card sits to its right\n"
                        + "· Tweak: home and settings remind you to deploy again after changing any option\n"
                        + "· Tweak: “Clean up” stops the monitor service, the pairing service and mDNS discovery, "
                        + "and removes every /data/local/tmp/wu.* file\n\n"
                        + "3.8\n"
                        + "· New: app picker reads all installed apps (third-party/system pages, search, "
                        + "launcher-launchable by default)\n"
                        + "· New: open a chosen app after unlock (single choice); the old “music lock-screen "
                        + "return” section is now “special lock-screen return & open app after unlock”\n"
                        + "· New: Developer options jump now supports Xiaomi/HyperOS, Huawei, Honor, "
                        + "OPPO/realme/OnePlus, vivo/iQOO and stock Android\n"
                        + "· New: optional Shizuku start over the built-in ADB after pairing (off by default)\n"
                        + "· New: “skip injection when already unlocked” switch (on by default)\n"
                        + "· Tweak: app launch resolves the component and falls back to monkey for better ROM support\n"
                        + "· Announcements reworked: indie-project note, repository address (long-press to copy), "
                        + "full manual and open-source credits\n"
                        + "· Note: no Device Admin / Device Owner features in this build\n\n"
                        + "3.6\n"
                        + "· Wireless debugging now uses a Shizuku-style flow: enter the pairing code in the "
                        + "notification, ports are discovered and connected automatically\n"
                        + "· The Developer options jump tries to scroll Wireless debugging into view\n"
                        + "· MIUI / HyperOS notification style hint\n\n"
                        + "3.5\n"
                        + "· Stability fixes in the wireless pairing flow with clearer failure messages\n\n"
                        + "3.4\n"
                        + "· Minor fixes and stability improvements\n\n"
                        + "3.3.3\n"
                        + "· Fix: deploy/restart reused the previous online cache, which could unlock "
                        + "while the target device was actually disconnected\n"
                        + "· Tweak: clear the online cache on deploy and restart\n"
                        + "· New: settings hint to deploy again after changing any option\n"
                        + "· New: Important notices and this changelog\n\n"
                        + "3.3\n"
                        + "· Officially signed build with the same features as 3.0\n\n"
                        + "3.0\n"
                        + "· Added built-in wireless debugging (no Shizuku, no root) and the Root engine\n"
                        + "· Added music lock-screen return, haptic feedback, announcements, ADB console\n"
                        + "· Fix: crash caused by the missing bcutil dependency\n\n"
                        + "2.0\n"
                        + "· Three-engine framework and card-based settings (not officially released)\n\n"
                        + "1.1\n"
                        + "· Standalone Wi-Fi unlock and Wi-Fi requirement, SIM PIN guard, custom pause\n"
                        + "· Three-level settings and log screens, Chinese/English UI\n"
                        + "· Fix: Android 12 compatibility\n\n"
                        + "1.0\n"
                        + "· First release: Shizuku engine, screen-on Bluetooth unlock, Wi-Fi unlock, logs")));
        root.addView(changelog, gap(12));

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
                        + "· hiddenapibypass、spake2——各自遵循其上游开源协议\n"
                        + "· 《搞机工具箱》Web 版 6.3.9（晨钟酱 / CzJam，GPL-3.0）——"
                        + "「网页版 ADB」入口指向的外部网页；仅链接，未打包其代码\n\n"
                        + "以上项目的完整许可证文本与版权声明见各自官方仓库。"
                        + "无线调试相关实现参考了社区公开方案（Kadb、ADBeesh、Shizuku 的配对流程）。",
                "This app uses the following open-source projects:\n\n"
                        + "· Shizuku (RikkaApps, Apache-2.0) — privilege framework and API\n"
                        + "· Kadb (flyfishxu, Apache-2.0) — in-app wireless ADB client and pairing\n"
                        + "· Bouncy Castle bcprov / bcpkix / bcutil (Bouncy Castle Licence) — certificates and keys\n"
                        + "· Okio (Square, Apache-2.0), Kotlin and kotlinx.coroutines (JetBrains, Apache-2.0)\n"
                        + "· hiddenapibypass, spake2 — under their respective upstream licenses\n"
                        + "· “搞机工具箱” Web edition 6.3.9 (晨钟酱 / CzJam, GPL-3.0) — the external page "
                        + "linked from “Web ADB”; linked only, not bundled\n\n"
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
                        + "GNU General Public License v3.0\n"
                        + "适用：《搞机工具箱》Web 版 6.3.9（晨钟酱 / CzJam）\n\n"
                        + "· 该网页是独立开源项目，本应用仅提供链接，未打包、未修改其代码；\n"
                        + "· 如再分发该网页或其修改版，须遵循 GPL-3.0：提供对应源码、保留版权与许可声明；\n"
                        + "· 以「现状」提供，不附带任何担保。\n"
                        + "完整文本：https://www.gnu.org/licenses/gpl-3.0.html\n"
                        + "项目地址：https://github.com/CzJam/gjgjxweb\n\n\n"
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
                        + "GNU General Public License v3.0\n"
                        + "Applies to: “搞机工具箱” Web edition 6.3.9 (晨钟酱 / CzJam)\n\n"
                        + "· This page is an independent open-source project; this app only links to it and "
                        + "does not bundle or modify its code;\n"
                        + "· Redistributing the page or a modified version requires GPL-3.0 compliance: provide "
                        + "the corresponding source and keep copyright/license notices;\n"
                        + "· Provided \"AS IS\", without warranties.\n"
                        + "Full text: https://www.gnu.org/licenses/gpl-3.0.html\n"
                        + "Project: https://github.com/CzJam/gjgjxweb\n\n\n"
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

    /** 长按仓库地址：只复制，不跳转 */
    private void copyRepo() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("repository", REPO));
                toast(I18n.t("已复制到剪贴板，请粘贴到浏览器打开",
                        "Copied. Paste it into your browser"));
                return;
            }
        } catch (Throwable ignored) {
        }
        toast(I18n.t("复制失败，请手动抄写地址", "Copy failed, please type the address manually"));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
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
