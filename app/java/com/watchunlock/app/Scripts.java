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
 * 脚本与 adb 部署版共用同一套路径，二者可以互相接管：
 *   /sdcard/watch-unlock/config.conf / wu-daemon.sh / wu-stop.sh / wu.log
 *   /data/local/tmp/wu.state（失败次数、冷却截止、状态）
 */
public final class Scripts {

    public static final String DIR = "/sdcard/watch-unlock";
    public static final String CONF = DIR + "/config.conf";
    public static final String OPTS = DIR + "/options.conf";
    public static final String DAEMON = DIR + "/wu-daemon.sh";
    public static final String STOP = DIR + "/wu-stop.sh";
    public static final String LOG = DIR + "/wu.log";
    public static final String STATE = "/data/local/tmp/wu.state";
    /** 在线判定缓存。重新部署/重启时必须清掉，否则会沿用上一轮的结论误判。 */
    public static final String PRESENT = "/data/local/tmp/wu.present";
    private static final String RUNTIME_CACHE = PRESENT + " /data/local/tmp/wu.cm /data/local/tmp/wu.kw";

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
                false, "", false);
    }

    public static String configText(String pin, String targets, boolean all,
                                    String wifiList, boolean wifiUnlock, boolean wifiRequired,
                                    boolean simGuard, String rssi,
                                    boolean musicReturn, String musicPkg, boolean musicSwipe) {
        String r = rssi == null ? "0" : rssi.trim();
        if (r.isEmpty()) r = "0";
        String pkg = musicPkg == null ? "" : musicPkg.trim();
        return "PIN_TEXT=\"" + pin + "\"\n"
                + "PIN_KEYS=\"" + pinKeys(pin) + "\"\n"
                + "TARGETS=\"" + (targets == null ? "" : targets) + "\"\n"
                + "WATCH_MODE=\"" + (all ? "all" : "any") + "\"\n"
                + "WIFI_LIST=\"" + (wifiList == null ? "" : wifiList) + "\"\n"
                + "WIFI_UNLOCK=\"" + (wifiUnlock ? "1" : "0") + "\"\n"
                + "WIFI_REQUIRED=\"" + (wifiRequired ? "1" : "0") + "\"\n"
                + "SIM_GUARD=\"" + (simGuard ? "1" : "0") + "\"\n"
                + "RSSI_MIN=\"" + r + "\"\n"
                + "MUSIC_RETURN=\"" + (musicReturn ? "1" : "0") + "\"\n"
                + "MUSIC_OPEN_APP=\"" + (musicReturn && !pkg.isEmpty() ? "1" : "0") + "\"\n"
                + "MUSIC_OPEN_PKG=\"" + pkg + "\"\n"
                + "MUSIC_SWIPE=\"" + (musicReturn && musicSwipe ? "1" : "0") + "\"\n"
                + "DEBOUNCE=6\n"
                + "MAX_FAILS=3\n"
                + "COOLDOWN=600\n";
    }

    /** 用当前 Prefs 里的选择（设备、多设备、WiFi、SIM 防呆、PIN）拼出 config.conf 内容 */
    public static String configFromPrefs(Context ctx) {
        return configText(Prefs.pin(ctx), Prefs.targets(ctx), Prefs.modeAll(ctx),
                Prefs.wifiList(ctx), Prefs.wifiUnlock(ctx), Prefs.wifiRequired(ctx),
                Prefs.simGuard(ctx), Prefs.rssi(ctx),
                Prefs.musicReturn(ctx), Prefs.musicAppPkg(ctx), Prefs.musicSwipe(ctx));
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
        return restart();
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
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
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
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
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
        ShizukuBridge.Result r = Privilege.run("mkdir -p " + DIR, 8000);
        detail.append("mkdir: ").append(r.code).append('\n');
        if (!r.ok()) {
            return Report.fail(I18n.t("无法创建目录 ", "Cannot create directory ") + DIR
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
        return "cd " + DIR + " && (nohup sh " + DAEMON + " >/dev/null 2>&1 &) ; sleep 2 ; "
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
        rep.ok = !r.out.contains("failed");
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

    /**
     * 一键清理：停守护、杀残留进程（含旧版守护与 logcat 监听）、
     * 删除 /sdcard/watch-unlock 与 /data/local/tmp/wu.*。
     * 不动应用本身的设备选择，方便清理后重新部署。
     */
    public static Report cleanAll() {
        if (!shizukuOk()) {
            return Report.fail(I18n.t("授权服务不可用，无法清理脚本残留（当前：%s）",
                    "Privilege engine unavailable; cannot clean up script leftovers (%s)",
                    Privilege.modeLabel()));
        }
        stopHeld();
        StringBuilder sb = new StringBuilder();
        ShizukuBridge.Result r = Privilege.run("sh " + STOP + " 2>/dev/null", 15000);
        sb.append("stop: ").append(r.code).append('\n');

        // 兜底：旧版本可能留下多个守护实例或 logcat 监听
        r = Privilege.run(
                "for p in $(ps -A -o PID,ARGS | grep -E 'wu-daemon\\.sh|logcat -b events' "
                        + "| grep -v grep | awk '{print $1}'); do kill -9 $p 2>/dev/null; done; "
                        + "rm -f /data/local/tmp/wu.lock; echo killed", 15000);
        sb.append("kill: ").append(r.code).append('\n');

        r = Privilege.run("rm -rf " + DIR + " /data/local/tmp/wu.bt /data/local/tmp/wu.hit "
                + "/data/local/tmp/wu.cm /data/local/tmp/wu.kw /data/local/tmp/wu.ui "
                + "/data/local/tmp/wu.present /data/local/tmp/wu.state /data/local/tmp/wu.lock "
                + "/data/local/tmp/wu.bench.ui /data/local/tmp/wu.daemon.d "
                + "/data/local/tmp/wu.unlock.d /data/local/tmp/wu.shot", 15000);
        sb.append("rm: ").append(r.code).append('\n');
        Report rep = new Report();
        rep.ok = r.ok();
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
        // HyperOS / Android 13+ 会把 dumpsys 里的 MAC 脱敏成 XX:XX:XX:XX:EE:FF（只保留最后两段），
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

    /** 当前 WiFi SSID（未连接返回空串） */
    public static String currentSsid() {
        if (!shizukuOk()) return "";
        ShizukuBridge.Result r = Privilege.run(
                "dumpsys wifi 2>/dev/null | grep -m1 -o 'SSID: \"[^\"]*\"' | head -n1 | sed 's/SSID: //; s/\"//g'", 20000);
        String s = r.out.trim();
        if (s.isEmpty()) {
            r = Privilege.run("cmd wifi status 2>/dev/null | grep -m1 -i 'ssid' | sed 's/.*SSID: //; s/\"//g'", 20000);
            s = r.out.trim();
        }
        int nl = s.indexOf('\n');
        return nl > 0 ? s.substring(0, nl).trim() : s;
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
