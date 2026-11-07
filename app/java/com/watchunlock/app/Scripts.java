package com.watchunlock.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手机端脚本的安装 / 启停 / 状态读取。
 * 脚本与 adb 部署版共用同一套路径，二者可以互相接管。
 *
 * 安全约束：
 * 1. 目录必须在 shell 私有区（/data/local/tmp），不能放 /sdcard ——
 *    共享存储任何有「所有文件访问」的 App / 文件管理器 / USB 都能改写脚本，
 *    而脚本以 shell 或 root 执行，等于把提权入口摆在明面上。
 * 2. PIN 单独放在 600 的 pin 文件里，绝不写进 config.conf。
 * 3. config.conf 里的自由文本一律单引号包裹（见 shellQuote），且写入前过滤元字符，
 *    蓝牙名 / WiFi SSID 这类对端可控的字符串不能直接拼进会被 source 的脚本。
 */
public final class Scripts {

    public static final String DIR = "/data/local/tmp/watch-unlock";
    /** 旧版共享存储目录，部署/清理时一并删除，避免明文 PIN 残留 */
    public static final String LEGACY_DIR = "/sdcard/watch-unlock";
    public static final String CONF = DIR + "/config.conf";
    public static final String OPTS = DIR + "/options.conf";
    public static final String PIN = DIR + "/pin";
    /** 延长解锁用的 PIN 副本（600），守护在断开设备时用它写回锁屏密码 */
    public static final String EXT_PIN = DIR + "/extpin";
    /** 延长解锁状态标记：存在表示锁屏密码当前被临时关闭 */
    public static final String EXTENDED = "/data/local/tmp/wu.extended";
    /** 配置重载标记：应用改设置后只 touch 它，常驻守护在下一个事件前重读 config.conf */
    public static final String RELOAD = "/data/local/tmp/wu.reload";
    public static final String DAEMON = DIR + "/wu-daemon.sh";
    public static final String STOP = DIR + "/wu-stop.sh";
    public static final String LOG = DIR + "/wu.log";
    public static final String STATE = "/data/local/tmp/wu.state";
    /** 在线判定缓存。wu.present 故意不在这里：它记录「应用是否在监听蓝牙事件」，
     *  重启时若删掉会永久退化成每次亮屏现查（慢 200~350ms）。 */
    public static final String PRESENT = "/data/local/tmp/wu.present";
    private static final String RUNTIME_CACHE = "/data/local/tmp/wu.cm* /data/local/tmp/wu.kw* "
            + "/data/local/tmp/wu.ident* /data/local/tmp/wu.bt* /data/local/tmp/wu.hit* /data/local/tmp/wu.ui* "
            + "/data/local/tmp/wu.present.tmp*";

    private Scripts() {
    }

    public static class Report {
        public boolean ok;
        public String text = "";

        static Report fail(String msg) {
            Report r = new Report();
            r.ok = false;
            r.text = msg;
            return r;
        }
    }

    private static final int[] KEYCODES = {7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    public static String pinKeys(String pin) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pin.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(KEYCODES[pin.charAt(i) - '0']);
        }
        return sb.toString();
    }

    public static boolean validPin(String pin) {
        if (pin == null) return false;
        int n = pin.length();
        if (n < 4 || n > 16) return false;
        for (int i = 0; i < n; i++) {
            if (pin.charAt(i) < '0' || pin.charAt(i) > '9') return false;
        }
        return true;
    }

    /** 设备列表「启用|MAC|名称;...」里是否有至少一个启用项 */
    public static boolean hasEnabledTarget(String targets) {
        if (targets == null) return false;
        for (String line : targets.split(";")) {
            String[] p = line.split("\\|", -1);
            if (p.length > 0 && "1".equals(p[0].trim())) return true;
        }
        return false;
    }

    public static String configText(String pin, String targets, boolean all,
                                    String wifiList, boolean wifiUnlock, boolean wifiRequired,
                                    boolean simGuard, String rssi) {
        return configText(pin, targets, all, wifiList, wifiUnlock, wifiRequired, simGuard, rssi,
                false, "", false, true, false, true, false, true, false, false, false);
    }

    public static String configText(String pin, String targets, boolean all,
                                    String wifiList, boolean wifiUnlock, boolean wifiRequired,
                                    boolean simGuard, String rssi,
                                    boolean musicReturn, String musicPkg, boolean musicSwipe,
                                    boolean skipWhenUnlocked, boolean protect,
                                    boolean macStrict, boolean macFallback,
                                    boolean wifiBind, boolean wifiAllowOpen,
                                    boolean extendUnlock, boolean presenceLive) {
        String r = rssi == null ? "0" : rssi.trim();
        if (!r.matches("-?[0-9]{1,3}")) r = "0";
        String pkg = musicPkg == null ? "" : musicPkg.trim();
        // 包名只允许 Android 合法字符，防止把命令拼进 am start 参数
        if (!pkg.matches("[A-Za-z0-9._]{1,200}")) pkg = "";
        // PIN 不进配置文件：由 writePin() 写入 600 的 pin 文件
        return "TARGETS=" + shellQuote(cleanTargets(targets)) + "\n"
                + "WATCH_MODE=\"" + (all ? "all" : "any") + "\"\n"
                + "WIFI_LIST=" + shellQuote(cleanWifiList(wifiList)) + "\n"
                + "WIFI_UNLOCK=\"" + (wifiUnlock ? "1" : "0") + "\"\n"
                + "WIFI_REQUIRED=\"" + (wifiRequired ? "1" : "0") + "\"\n"
                + "SIM_GUARD=\"" + (simGuard ? "1" : "0") + "\"\n"
                + "RSSI_MIN=\"" + r + "\"\n"
                + "MUSIC_RETURN=\"" + (musicReturn ? "1" : "0") + "\"\n"
                + "MUSIC_OPEN_APP=\"" + (musicReturn && !pkg.isEmpty() ? "1" : "0") + "\"\n"
                + "MUSIC_OPEN_PKG=" + shellQuote(pkg) + "\n"
                + "MUSIC_SWIPE=\"" + (musicReturn && musicSwipe ? "1" : "0") + "\"\n"
                + "SKIP_WHEN_UNLOCKED=\"" + (skipWhenUnlocked ? "1" : "0") + "\"\n"
                + "PRESENCE_LIVE=\"" + (presenceLive ? "1" : "0") + "\"\n"
                + "PROTECT_ENABLED=\"" + (protect ? "1" : "0") + "\"\n"
                + "MAC_STRICT=\"" + (macStrict ? "1" : "0") + "\"\n"
                + "MAC_NAME_FALLBACK=\"" + (macFallback ? "1" : "0") + "\"\n"
                + "WIFI_BIND_BSSID=\"" + (wifiBind ? "1" : "0") + "\"\n"
                + "WIFI_ALLOW_OPEN=\"" + (wifiAllowOpen ? "1" : "0") + "\"\n"
                + "EXTEND_UNLOCK=\"" + (extendUnlock ? "1" : "0") + "\"\n"
                + "DEBOUNCE=6\n"
                + "MAX_FAILS=3\n"
                + "COOLDOWN=600\n";
    }

    /** 单引号包裹，串内的 ' 变成 '\''；被 shell source 时不做任何展开 */
    static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 自由文本清洗：去掉控制字符和会破坏配置语法 / 触发替换的字符 */
    static String safeText(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;
            if (c == '\'' || c == '"' || c == '\\' || c == '$' || c == '`' || c == ';' || c == '|') continue;
            b.append(c);
        }
        return b.toString().trim();
    }

    /** 设备列表「启用|MAC|名称;...」：逐字段清洗，分隔符由代码生成，不会被值伪造 */
    static String cleanTargets(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (String entry : raw.split(";")) {
            if (entry.trim().isEmpty()) continue;
            String[] p = entry.split("\\|", -1);
            String en = p.length > 0 && "1".equals(p[0].trim()) ? "1" : "0";
            String mac = p.length > 1 ? p[1].replaceAll("[^0-9A-Fa-fXx:]", "") : "";
            if (mac.length() > 17) mac = mac.substring(0, 17);
            String nm = p.length > 2 ? safeText(p[2]) : "";
            if (nm.length() > 96) nm = nm.substring(0, 96);
            if (mac.isEmpty() && nm.isEmpty()) continue;
            if (out.length() > 0) out.append(';');
            out.append(en).append('|').append(mac).append('|').append(nm);
        }
        return out.toString();
    }

    /** WiFi 列表「启用|SSID|BSSID|是否安全网络;...」：逐字段清洗，兼容只有前两字段的旧数据 */
    static String cleanWifiList(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (String entry : raw.split(";")) {
            if (entry.trim().isEmpty()) continue;
            String[] p = entry.split("\\|", -1);
            String en = p.length > 0 && "1".equals(p[0].trim()) ? "1" : "0";
            String ssid = p.length > 1 ? safeText(p[1]) : "";
            if (ssid.length() > 64) ssid = ssid.substring(0, 64);
            if (ssid.isEmpty()) continue;
            String bssid = p.length > 2 ? p[2].replaceAll("[^0-9A-Fa-f:*]", "").toUpperCase() : "";
            if (bssid.length() > 17) bssid = bssid.substring(0, 17);
            // ? = 旧数据未知；0 = 开放网络；1 = 加密网络
            String sec = p.length > 3 ? p[3].trim() : "?";
            if (!"0".equals(sec) && !"1".equals(sec)) sec = "?";
            if (out.length() > 0) out.append(';');
            out.append(en).append('|').append(ssid).append('|')
                    .append(bssid).append('|').append(sec);
        }
        return out.toString();
    }

    /**
     * 升级迁移：旧版把 PIN 明文和脚本放在 /sdcard/watch-unlock。
     * 能自动重新部署就自动做（顺带杀掉旧守护），否则至少把旧的明文目录删掉。
     */
    public static void migrateLegacy(Context ctx) {
        if (!Privilege.ensureReady()) return;
        ShizukuBridge.Result r = Privilege.run("[ -d " + LEGACY_DIR + " ] && echo yes", 8000);
        if (!r.out.contains("yes")) return;
        boolean deployed = Privilege.run("[ -f " + CONF + " ] && echo yes", 8000).out.contains("yes");
        if (!deployed && validPin(Prefs.pin(ctx)) && hasEnabledTarget(Prefs.targets(ctx))) {
            Report rep = deploy(ctx);
            if (rep.ok) return;
        }
        Privilege.run("for p in $(ps -A -o PID,ARGS 2>/dev/null | grep 'wu-daemon\\.sh' "
                + "| grep -v grep | awk '{print $1}'); do kill -9 $p 2>/dev/null; done; "
                + "rm -rf " + LEGACY_DIR, 15000);
    }

    /** 用当前 Prefs 里的选择（设备、多设备、WiFi、SIM 防呆、PIN）拼出 config.conf 内容 */
    public static String configFromPrefs(Context ctx) {
        return configText(Prefs.pin(ctx), Prefs.targets(ctx), Prefs.modeAll(ctx),
                Prefs.wifiList(ctx), Prefs.wifiUnlock(ctx), Prefs.wifiRequired(ctx),
                Prefs.simGuard(ctx), Prefs.rssi(ctx),
                Prefs.musicReturn(ctx), Prefs.musicAppPkg(ctx), Prefs.musicSwipe(ctx),
                Prefs.skipWhenUnlocked(ctx),
                // MAC 严格匹配暂时停用：蓝牙设备普遍使用随机 MAC，按 MAC 比对会把正常设备
                // 判成「不在线」导致完全无法解锁，见 ProtectActivity 里的说明。
                Prefs.protectEnabled(ctx), false, false,
                Prefs.wifiBindBssid(ctx), Prefs.wifiAllowOpen(ctx),
                Prefs.extendUnlock(ctx), Prefs.presenceLive(ctx));
    }

    /** 开关/勾选变化后：只重写配置并重启守护，不重新写脚本 */
    public static Report applyConfig(Context ctx) {
        if (!Privilege.ensureReady()) return Report.fail(noEngine());
        if (!validPin(Prefs.pin(ctx))) {
            return Report.fail(I18n.t("还没有设置 PIN，请先部署", "No PIN set yet. Deploy first."));
        }
        ShizukuBridge.Result r = Privilege.run("[ -f " + CONF + " ] && echo yes", 8000);
        if (!r.out.contains("yes")) {
            return Report.fail(I18n.t("还没有部署，请先点「部署并启用」",
                    "Not deployed yet. Tap Deploy & enable first."));
        }
        r = Privilege.writeFile(CONF, configFromPrefs(ctx));
        if (!r.ok()) {
            return Report.fail(I18n.t("写配置失败", "Failed to write config") + "\n" + r.text());
        }
        r = writePin(Prefs.pin(ctx));
        if (!r.ok()) {
            return Report.fail(I18n.t("写 PIN 失败", "Failed to write PIN") + "\n" + r.text());
        }
        r = writeExtendPin(Prefs.pin(ctx));
        if (!r.ok()) {
            return Report.fail(I18n.t("写延长解锁 PIN 失败", "Failed to write extend-unlock PIN")
                    + "\n" + r.text());
        }
        Privilege.run("chmod 600 " + CONF + " " + PIN + " " + EXT_PIN, 8000);
        // 不再整进程重启：守护在事件循环里看到 wu.reload 就重新 source 配置。
        // 同时用一次 --present 把新条件下的在线缓存刷新掉，避免沿用旧判定。
        Report rep = new Report();
        rep.ok = true;
        rep.text = I18n.t("配置已生效", "Configuration applied");
        if (daemonAlive()) {
            ShizukuBridge.Result rr = Privilege.run("touch " + RELOAD + "; sh " + DAEMON + " --present",
                    60000);
            if (!rr.ok()) {
                rep.ok = false;
                rep.text = rr.text();
            }
        }
        return rep;
    }

    /** PIN 只落在 shell 私有目录的 600 文件里，不写进 config.conf，更不写 /sdcard */
    private static ShizukuBridge.Result writePin(String pin) {
        ShizukuBridge.Result r = Privilege.writeFile(PIN, pin == null ? "" : pin);
        if (!r.ok()) return r;
        return Privilege.run("chmod 600 " + PIN, 8000);
    }

    /** 延长解锁专用副本；永远保留，哪怕功能关闭也要能写回原密码 */
    private static ShizukuBridge.Result writeExtendPin(String pin) {
        ShizukuBridge.Result r = Privilege.writeFile(EXT_PIN, pin == null ? "" : pin);
        if (!r.ok()) return r;
        return Privilege.run("chmod 600 " + EXT_PIN, 8000);
    }

    /** 读取 config.conf，用于回填 PIN / RSSI 等 */
    public static Map<String, String> config() {
        Map<String, String> m = new LinkedHashMap<>();
        if (!shizukuOk()) return m;
        ShizukuBridge.Result r = Privilege.run("cat " + CONF + " 2>/dev/null", 10000);
        for (String line : r.out.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) {
                String v = line.substring(i + 1).trim();
                if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                        || (v.startsWith("'") && v.endsWith("'")))) {
                    v = v.substring(1, v.length() - 1);
                }
                m.put(line.substring(0, i).trim(), v);
            }
        }
        return m;
    }

    /** 可选：运行方式（独立进程 / Shizuku 托管） */
    public static Report saveRunMode(String runMode) {
        if (!Privilege.ensureReady()) return Report.fail(noEngine());
        String rm = "shizuku".equals(runMode) ? "shizuku" : "detached";
        String text = "RUN_MODE=\"" + rm + "\"\nPOCKET_GUARD=\"0\"\nFAST_SETTLE=\"0.1\"\n";
        ShizukuBridge.Result r = Privilege.writeFile(OPTS, text);
        if (!r.ok()) {
            return Report.fail(I18n.t("写 options.conf 失败", "Failed to write options.conf")
                    + "\n" + r.text());
        }
        return restart();
    }

    public static Map<String, String> options() {
        Map<String, String> m = new LinkedHashMap<>();
        if (!shizukuOk()) return m;
        ShizukuBridge.Result r = Privilege.run("cat " + OPTS + " 2>/dev/null", 10000);
        for (String line : r.out.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) {
                String v = line.substring(i + 1).trim();
                if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                        || (v.startsWith("'") && v.endsWith("'")))) {
                    v = v.substring(1, v.length() - 1);
                }
                m.put(line.substring(0, i).trim(), v);
            }
        }
        return m;
    }

    /**
     * 刷新守护的「设备在不在」缓存。
     * tracking=true 表示应用确实在监听蓝牙广播，缓存此后可长期信任。
     */
    public static void presentRefresh(boolean tracking) {
        if (!shizukuOk()) return;
        Privilege.run("sh " + DAEMON + " --present" + (tracking ? " track" : ""), 60000);
    }

    /** 应用监听不了蓝牙广播时调用：撤销「可信任缓存」标记，亮屏改为现查 */
    public static void untrack() {
        if (!shizukuOk()) return;
        Privilege.run("sh " + DAEMON + " --untrack", 15000);
    }

    /**
     * 亮屏广播触发：直接执行一次解锁，不依赖常驻守护是否存活。
     * 脚本内部有跨进程互斥与 1.5 秒防重复注入，应用可以安全地多次重试。
     */
    public static void wakeUnlock() {
        if (!shizukuOk()) return;
        Privilege.run("sh " + DAEMON + " --unlock", 15000);
    }

    public static Report pause(int seconds) {
        if (!shizukuOk()) return Report.fail(noEngine());
        ShizukuBridge.Result r = Privilege.run("sh " + DAEMON + " --pause " + seconds, 20000);
        Report rep = new Report();
        rep.ok = r.out.startsWith("paused");
        rep.text = r.text();
        return rep;
    }

    public static Report resume() {
        if (!shizukuOk()) return Report.fail(noEngine());
        ShizukuBridge.Result r = Privilege.run("sh " + DAEMON + " --resume", 20000);
        Report rep = new Report();
        rep.ok = r.out.startsWith("resumed");
        rep.text = r.text();
        return rep;
    }

    /** 只重启守护，不动配置，也不清冷却状态。 */
    public static Report restart() {
        Privilege.run("sh " + STOP, 15000);
        Privilege.run("rm -rf /data/local/tmp/wu.daemon.d /data/local/tmp/wu.unlock.d; "
                + "rm -f /data/local/tmp/wu.lock /data/local/tmp/wu.shot " + RUNTIME_CACHE, 8000);
        return start();
    }

    /** 首次部署：写配置 + 写脚本 + 重启守护 */
    public static Report deploy(Context ctx) {
        String pin = Prefs.pin(ctx);
        if (!validPin(pin)) {
            return Report.fail(I18n.t("PIN 必须是 4~16 位数字", "PIN must be 4–16 digits"));
        }
        if (!hasEnabledTarget(Prefs.targets(ctx))) {
            return Report.fail(I18n.t("请至少勾选一个蓝牙设备", "Select at least one Bluetooth device"));
        }
        if (!Privilege.ensureReady()) {
            return Report.fail(I18n.t("授权未就绪（当前：%s）：%s",
                    "Privilege not ready (%s): %s", Privilege.modeLabel(), Privilege.statusText()));
        }

        StringBuilder detail = new StringBuilder();
        ShizukuBridge.Result r = Privilege.run("mkdir -p " + DIR + " && chmod 700 " + DIR, 8000);
        detail.append("mkdir: ").append(r.code).append('\n');
        if (!r.ok()) {
            return Report.fail(I18n.t("无法创建目录 ", "Cannot create directory ") + DIR
                    + "\n" + r.text());
        }
        // 旧版把配置和 PIN 明文放在 /sdcard，部署时彻底清掉；顺带杀掉还在跑的旧守护
        Privilege.run("for p in $(ps -A -o PID,ARGS 2>/dev/null | grep 'wu-daemon\\.sh' "
                + "| grep -v grep | awk '{print $1}'); do kill -9 $p 2>/dev/null; done; "
                + "rm -rf " + LEGACY_DIR, 15000);

        r = writePin(pin);
        if (!r.ok()) {
            return Report.fail(I18n.t("写 PIN 文件失败", "Failed to write PIN file") + "\n" + r.text());
        }
        r = writeExtendPin(pin);
        if (!r.ok()) {
            return Report.fail(I18n.t("写延长解锁 PIN 失败", "Failed to write extend-unlock PIN")
                    + "\n" + r.text());
        }

        r = Privilege.writeFile(CONF, configFromPrefs(ctx));
        if (!r.ok()) {
            return Report.fail(I18n.t("写配置失败", "Failed to write config") + "\n" + r.text());
        }

        byte[] daemon = readAsset(ctx, "wu-daemon.sh");
        byte[] stop = readAsset(ctx, "wu-stop.sh");
        if (daemon == null || stop == null) {
            return Report.fail(I18n.t("内置脚本缺失，APK 不完整",
                    "Built-in scripts missing; the APK is incomplete"));
        }
        r = Privilege.writeFile(DAEMON, daemon);
        if (!r.ok()) {
            return Report.fail(I18n.t("写守护脚本失败", "Failed to write daemon script")
                    + "\n" + r.text());
        }
        r = Privilege.writeFile(STOP, stop);
        if (!r.ok()) {
            return Report.fail(I18n.t("写停止脚本失败", "Failed to write stop script")
                    + "\n" + r.text());
        }
        Privilege.run("chmod 700 " + DIR + " " + DAEMON + " " + STOP
                + "; chmod 600 " + CONF + " " + PIN + " " + EXT_PIN, 8000);
        detail.append("scripts: ").append(DAEMON).append(' ').append(daemon.length).append("B\n");

        r = Privilege.run("sh " + STOP, 15000);
        detail.append("stop: ").append(r.code).append('\n');
        r = Privilege.run("rm -rf /data/local/tmp/wu.daemon.d /data/local/tmp/wu.unlock.d; "
                + "rm -f " + STATE + " /data/local/tmp/wu.lock /data/local/tmp/wu.shot " + RUNTIME_CACHE, 8000);
        if (!r.ok()) detail.append("clean: ").append(r.text()).append('\n');
        r = Privilege.run("[ -f " + OPTS + " ] || printf 'RUN_MODE=\"detached\"\\n' > " + OPTS, 8000);
        if (!r.ok()) detail.append("options: ").append(r.text()).append('\n');

        Report srep = start();
        detail.append("start: ").append(srep.text).append('\n');

        Report rep = new Report();
        rep.ok = srep.ok;
        rep.text = detail.toString();
        if (!rep.ok) {
            rep.text += I18n.t("启动失败：请检查授权引擎是否仍然可用（当前：%s）",
                    "Start failed. Check that the privilege engine is still available (%s).",
                    Privilege.modeLabel());
        }
        return rep;
    }

    private static String startCommand() {
        // setsid：让守护进入独立会话，su 交互 shell 退出/被杀时不会连带收走守护进程；
        // 没有 setsid 的旧 ROM 退回 nohup。两者都失败时仍由 start() 的存活检查暴露问题。
        return "cd " + DIR + " && ( if command -v setsid >/dev/null 2>&1; "
                + "then setsid sh " + DAEMON + " >/dev/null 2>&1 & "
                + "else nohup sh " + DAEMON + " >/dev/null 2>&1 & fi ) ; sleep 2 ; "
                + "pid=$(cat /data/local/tmp/wu.lock 2>/dev/null); "
                + "if [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then echo started:$pid; "
                + "else echo failed; fi";
    }

    public static Report start() {
        if (!Privilege.ensureReady()) return Report.fail(noEngine());
        String runMode = options().get("RUN_MODE");
        if ("shizuku".equals(runMode) && Privilege.supportsManaged()) return startHeld();
        ShizukuBridge.Result r = Privilege.run(startCommand(), 20000);
        Report rep = new Report();
        // 必须看到 started:pid 才算成功；命令没执行、su 被拒时 out 可能为空，
        // 旧逻辑只看 "failed" 会把空输出误判成启动成功。
        rep.ok = r.ok() && r.out.contains("started:") && !r.out.contains("failed");
        rep.text = r.text();
        return rep;
    }

    private static Process sHeld;

    /** Shizuku 托管：守护进程挂在 Shizuku 子进程上，随 Shizuku 生命周期存活 */
    private static Report startHeld() {
        stopHeld();
        try {
            sHeld = Privilege.startLongRunning("sh " + DAEMON);
        } catch (Throwable t) {
            return Report.fail(I18n.t("托管启动失败：", "Managed start failed: ")
                    + t.getClass().getSimpleName());
        }
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
        }
        boolean alive = daemonAlive();
        Report rep = new Report();
        rep.ok = alive;
        rep.text = alive
                ? I18n.t("started (shizuku 托管)", "started (managed by Shizuku)")
                : I18n.t("托管进程未运行，请看日志", "Managed process is not running. Check the log.");
        if (!alive) stopHeld();
        return rep;
    }

    public static void stopHeld() {
        if (sHeld != null) {
            try {
                sHeld.destroy();
            } catch (Throwable ignored) {
            }
            sHeld = null;
        }
    }

    public static Report stop() {
        if (!Privilege.ensureReady()) return Report.fail(noEngine());
        stopHeld();
        ShizukuBridge.Result r = Privilege.run("sh " + STOP, 15000);
        Report rep = new Report();
        rep.ok = r.ok();
        rep.text = r.text();
        return rep;
    }

    /** 延长解锁是否处于「密码已临时关闭」状态 */
    public static boolean extendedActive() {
        return shizukuOk()
                && Privilege.run("[ -f " + EXTENDED + " ] && echo yes", 8000).out.contains("yes");
    }

    /** 校验锁屏 PIN 是否与系统一致（延长解锁开启前必须通过） */
    public static boolean verifyLockPin(String pin) {
        if (!shizukuOk() || !validPin(pin)) return false;
        return Privilege.run("locksettings verify --old " + pin + " --user 0 >/dev/null 2>&1 "
                + "&& echo ok", 20000).out.contains("ok");
    }

    /** 立即写回锁屏密码并解除 disabled；幂等，可重复调用 */
    public static Report extendRestore(Context ctx) {
        if (!shizukuOk()) {
            return Report.fail(I18n.t("授权服务不可用，无法恢复锁屏密码（当前：%s）",
                    "Privilege unavailable; cannot restore the lock-screen PIN (%s)",
                    Privilege.modeLabel()));
        }
        String pin = Prefs.pin(ctx);
        if (!validPin(pin)) {
            return Report.fail(I18n.t("应用内没有有效的 PIN，无法自动恢复；请手动解锁并重新部署",
                    "No valid PIN stored; restore manually and deploy again"));
        }
        ShizukuBridge.Result r = Privilege.run(
                "locksettings set-pin " + pin + " --user 0 >/dev/null 2>&1; "
                        + "locksettings set-disabled false --user 0 >/dev/null 2>&1; "
                        + "rm -f " + EXTENDED + "; echo done", 20000);
        Report rep = new Report();
        rep.ok = r.ok();
        rep.text = r.out;
        return rep;
    }

    /**
     * 一键清理：停掉应用内所有常驻服务（状态监控 + 无线调试配对/mDNS）、
     * 结束守护进程与 logcat 监听、删除脚本目录与所有 /data/local/tmp/wu.* 运行时文件。
     * 不动应用本身的设备选择，方便清理后重新部署。
     */
    public static Report cleanAll(Context ctx) {
        // 先停应用侧的服务，否则监控服务的看门狗会把守护重新拉起来
        Prefs.setWantRunning(ctx, false);
        try {
            ctx.stopService(new android.content.Intent(ctx, MonitorService.class));
        } catch (Throwable ignored) {
        }
        try {
            // AdbPairingService.onDestroy 会停止 mDNS 发现、移除配对通知
            ctx.stopService(new android.content.Intent(ctx, AdbPairingService.class));
        } catch (Throwable ignored) {
        }
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancelAll();
        } catch (Throwable ignored) {
        }

        stopHeld();
        StringBuilder sb = new StringBuilder("services: stopped\n");

        // 延长解锁：删 extpin 之前必须先把锁屏密码写回去
        if (extendedActive()) {
            Report rr = extendRestore(ctx);
            sb.append("extend-restore: ").append(rr.ok ? "ok" : "FAILED").append('\n');
        }

        boolean engine = shizukuOk();
        if (engine) {
            ShizukuBridge.Result r = Privilege.run("sh " + STOP + " 2>/dev/null", 15000);
            sb.append("stop: ").append(r.code).append('\n');

            // 兜底：旧版本 / 手动部署可能留下多个实例、以及 logcat 监听。
            // 排除当前 shell 自身与其父进程，避免把执行清理的命令杀掉。
            r = Privilege.run(
                    "me=$$; pp=$PPID; for p in $(ps -A -o PID,ARGS 2>/dev/null "
                            + "| grep -E 'wu-daemon|wu-stop|watch-unlock|logcat -b events' | grep -v grep "
                            + "| awk -v me=$me -v pp=$pp '$1 != me && $1 != pp {print $1}'); "
                            + "do kill -9 $p 2>/dev/null; done; echo killed", 15000);
            sb.append("kill: ").append(r.code).append('\n');
        } else {
            sb.append(I18n.t("授权不可用：应用内服务已停止，脚本残留未清理",
                    "Privilege unavailable: in-app services stopped, script leftovers not cleaned"))
                    .append('\n');
        }

        // wu.* 通配：覆盖 wu.lock / wu.state / wu.present / wu.log 相关缓存以及未来新增的运行时文件
        ShizukuBridge.Result r = Privilege.run("rm -rf " + DIR + " " + LEGACY_DIR
                + " /data/local/tmp/wu.*; echo cleaned", 15000);
        sb.append("rm: ").append(r.code).append('\n');

        Report rep = new Report();
        rep.ok = engine && r.ok();
        rep.text = sb.toString();
        return rep;
    }

    public static boolean daemonAlive() {
        if (!shizukuOk()) return false;
        ShizukuBridge.Result r = Privilege.run(
                "pid=$(cat /data/local/tmp/wu.lock 2>/dev/null); "
                        + "if [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then echo alive; else echo dead; fi",
                10000);
        return r.out.contains("alive");
    }

    /** 读取状态文件，解析 key=value。 */
    public static Map<String, String> state() {
        Map<String, String> m = new LinkedHashMap<>();
        if (!shizukuOk()) return m;
        ShizukuBridge.Result r = Privilege.run("cat " + STATE + " 2>/dev/null", 10000);
        if (r.out.isEmpty()) return m;
        for (String line : r.out.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        return m;
    }

    /** 自检：在线设备 / 锁屏 / 状态 / 冷却 / WiFi 等。 */
    public static Map<String, String> check() {
        Map<String, String> m = new LinkedHashMap<>();
        if (!shizukuOk()) return m;
        ShizukuBridge.Result r = Privilege.run("sh " + DAEMON + " --check", 90000);
        for (String line : r.out.split("\n")) {
            int i = line.indexOf(':');
            if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        if (m.isEmpty() && !r.text().isEmpty()) m.put("error", r.text());
        return m;
    }

    public static String logTail(int lines) {
        if (!shizukuOk()) return "";
        ShizukuBridge.Result r = Privilege.run("tail -n " + lines + " " + LOG + " 2>/dev/null", 10000);
        return r.out;
    }

    public static Report clearLog() {
        if (!shizukuOk()) return Report.fail(noEngine());
        ShizukuBridge.Result r = Privilege.run(": > " + LOG, 8000);
        Report rep = new Report();
        rep.ok = r.ok();
        rep.text = r.text();
        return rep;
    }

    /** 基准测试：测每个关键命令耗时，用来定位慢在哪一步。 */
    public static String bench() {
        if (!shizukuOk()) return noEngine();
        ShizukuBridge.Result r = Privilege.run("sh " + DAEMON + " --bench", 120000);
        return r.text();
    }

    /** 当前已连接的蓝牙设备：同时收集 MAC（可能被系统脱敏）与设备名，供界面判断 */
    public static Set<String> connectedMacs() {
        Set<String> out = new HashSet<>();
        if (!shizukuOk()) return out;
        ShizukuBridge.Result r = Privilege.run("dumpsys bluetooth_manager", 20000);
        // HyperOS / Android 13+ 会把 dumpsys 里的 MAC 脱敏成 XX:XX:XX:XX:4C:D8（只保留最后两段），
        // 所以这里同时接受脱敏形态，匹配时用 MAC 末 5 位（见 matchesConnected）
        Pattern p = Pattern.compile("((?:[0-9A-Fa-fX]{2}:){5}[0-9A-Fa-fX]{2})");
        String cur = null;
        for (String line : r.out.split("\n")) {
            Matcher m = p.matcher(line);
            boolean linked = line.contains("state=Connected") || line.contains("curState=Connected")
                    || line.contains("mConnectionState: STATE_CONNECTED")
                    || (line.contains("mCurrentState:") && line.contains("Connected"));
            if (line.contains("StateMachine for") || line.contains("StateMachine: name=")) {
                String mac = m.find() ? m.group(1).toUpperCase() : null;
                if (mac != null && mac.indexOf('X') >= 0) mac = mac.substring(mac.length() - 5);
                cur = mac;
            } else if (linked && cur != null) {
                out.add(cur);
            } else if (line.contains("ACL BR/EDR:") || line.contains("ACL LE:")) {
                if (!(line.contains("ACL BR/EDR:Y") || line.contains("ACL LE:Y"))) continue;
                if (m.find()) out.add(m.group(1).toUpperCase());
                int idx = line.lastIndexOf(']');
                if (idx > 0 && idx + 1 < line.length()) {
                    String nm = line.substring(idx + 1).trim();
                    if (!nm.isEmpty()) out.add(nm);
                }
            }
        }
        return out;
    }

    /** 当前 WiFi 的 SSID（兼容旧调用） */
    public static String currentSsid() {
        return currentWifiInfo()[0];
    }

    /**
     * 读取当前 WiFi：{SSID, BSSID, 安全标记}。
     * 安全标记：0=开放网络，1=加密网络，?=无法判断。
     * 连接状态优先用 `cmd wifi status`，BSSID/安全类型再退回 dumpsys。
     */
    public static String[] currentWifiInfo() {
        String[] out = new String[]{"", "", "?"};
        if (!shizukuOk()) return out;
        String cmd = "st=$(cmd wifi status 2>/dev/null); "
                + "s=$(printf '%s\\n' \"$st\" | grep -m1 -o 'SSID: \"[^\"]*\"' | head -n1 "
                + "| sed 's/SSID: //; s/\"//g'); "
                + "[ -z \"$s\" ] && s=$(printf '%s\\n' \"$st\" | grep -m1 'Wifi is connected to \"' "
                + "| sed 's/.*connected to \"//; s/\".*//'); "
                + "[ -z \"$s\" ] && s=$(dumpsys wifi 2>/dev/null | grep -m1 -o 'SSID: \"[^\"]*\"' "
                + "| head -n1 | sed 's/SSID: //; s/\"//g'); "
                + "b=$(printf '%s\\n' \"$st\" | grep -m1 -o 'BSSID: [0-9A-Fa-f:]*' | head -n1 "
                + "| sed 's/BSSID: //' | tr 'a-f' 'A-F'); "
                + "[ -z \"$b\" ] && b=$(dumpsys wifi 2>/dev/null | grep -m1 -o 'mBSSID=[0-9A-Fa-f:]*' "
                + "| sed 's/mBSSID=//' | tr 'a-f' 'A-F'); "
                + "case \"$b\" in 02:00:00:00:00:00|00:00:00:00:00:00) b=\"\";; esac; "
                + "sec=?; "
                + "if [ -n \"$s\" ]; then "
                + "k=$(dumpsys wifi 2>/dev/null | grep -m1 -F \"$s\" "
                + "| grep -oE 'WPA3_SAE|WPA2_PSK|WPA_PSK|WPA2_EAP|WPA_EAP|SAE|OWE|NONE|EAP' | head -n1); "
                + "case \"$k\" in NONE) sec=0;; '') ;; *) sec=1;; esac; fi; "
                + "printf 'SSID=%s\\nBSSID=%s\\nSEC=%s\\n' \"$s\" \"$b\" \"$sec\"";
        ShizukuBridge.Result r = Privilege.run(cmd, 25000);
        for (String line : r.out.split("\n")) {
            line = line.trim();
            if (line.startsWith("SSID=")) {
                out[0] = line.substring(5).trim();
            } else if (line.startsWith("BSSID=")) {
                String b = line.substring(6).trim().toUpperCase();
                if (b.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) out[1] = b;
            } else if (line.startsWith("SEC=")) {
                String s = line.substring(4).trim();
                if ("0".equals(s) || "1".equals(s)) out[2] = s;
            }
        }
        return out;
    }

    private static boolean shizukuOk() {
        return Privilege.isReady();
    }

    private static String noEngine() {
        return I18n.t("授权服务不可用（当前：%s）", "Privilege engine unavailable (%s)",
                Privilege.modeLabel());
    }

    private static byte[] readAsset(Context ctx, String name) {
        try {
            InputStream in = ctx.getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
