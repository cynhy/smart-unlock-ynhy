package com.watchunlock.app;

import android.content.Context;

/**
 * 授权引擎选择：Shizuku（默认）/ Root / 内置无线调试。
 * 上层脚本统一通过这里执行命令与写文件，不需要关心底层用的是哪一档。
 */
public final class Privilege {

    public static final String SHIZUKU = "shizuku";
    public static final String ROOT = "root";
    public static final String ADB = "adb";

    private static Context sApp;

    private Privilege() {
    }

    public static void init(Context c) {
        if (c != null) sApp = c.getApplicationContext();
    }

    public static Context app() {
        return sApp;
    }

    public static String mode() {
        return sApp == null ? SHIZUKU : Prefs.privilegeMode(sApp);
    }

    public static String modeLabel() {
        switch (mode()) {
            case ROOT:
                return I18n.t("Root", "Root");
            case ADB:
                return I18n.t("无线调试", "Wireless debugging");
            default:
                return "Shizuku";
        }
    }

    /** 轻量就绪判断；无线调试只查已有连接，不会主动发起网络请求 */
    public static boolean isReady() {
        if (sApp == null) {
            return ShizukuBridge.isRunning() && ShizukuBridge.hasPermission();
        }
        switch (Prefs.privilegeMode(sApp)) {
            case ROOT:
                return RootBridge.isReady(sApp);
            case ADB:
                return AdbBridge.isReady();
            default:
                return ShizukuBridge.isRunning() && ShizukuBridge.hasPermission();
        }
    }

    /** 供后台线程调用：无线调试会主动尝试连接一次 */
    public static boolean ensureReady() {
        if (sApp == null) return false;
        if (ADB.equals(Prefs.privilegeMode(sApp))) return AdbBridge.connect(sApp);
        return isReady();
    }

    public static boolean probeRoot(int timeoutMs) {
        return sApp != null && RootBridge.probe(sApp, timeoutMs);
    }

    public static String statusText() {
        if (sApp == null) return "";
        switch (Prefs.privilegeMode(sApp)) {
            case ROOT:
                return RootBridge.isReady(sApp)
                        ? I18n.t("Root 已授权", "Root granted")
                        : I18n.t("Root 不可用（su 未授权或设备未 root）",
                        "Root unavailable (su denied or device not rooted)");
            case ADB:
                if (!AdbBridge.isConfigured(sApp)) {
                    return I18n.t("无线调试未配置（先在设置里配对）",
                            "Wireless debugging not configured (pair it in Settings first)");
                }
                return AdbBridge.isReady()
                        ? I18n.t("无线调试已连接", "Wireless debugging connected")
                        : I18n.t("无线调试未连接（端口可能已变化，请重新配对）",
                        "Wireless debugging not connected (the port may have changed; pair again)");
            default:
                if (!ShizukuBridge.isRunning()) return I18n.t("Shizuku 未运行", "Shizuku not running");
                if (!ShizukuBridge.hasPermission()) {
                    return I18n.t("Shizuku 未授权（%s）", "Shizuku not authorized (%s)",
                            ShizukuBridge.lastError);
                }
                return I18n.t("Shizuku 已授权", "Shizuku authorized");
        }
    }

    public static ShizukuBridge.Result run(String cmd, int timeoutMs) {
        if (sApp == null) return ShizukuBridge.run(cmd, timeoutMs);
        switch (Prefs.privilegeMode(sApp)) {
            case ROOT:
                return RootBridge.run(sApp, cmd, timeoutMs);
            case ADB:
                return AdbBridge.run(sApp, cmd, timeoutMs);
            default:
                return ShizukuBridge.run(cmd, timeoutMs);
        }
    }

    public static ShizukuBridge.Result writeFile(String path, String content) {
        return writeFile(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static ShizukuBridge.Result writeFile(String path, byte[] content) {
        if (sApp == null) return ShizukuBridge.writeFile(path, content);
        switch (Prefs.privilegeMode(sApp)) {
            case ROOT:
                return RootBridge.writeFile(sApp, path, content);
            case ADB:
                return AdbBridge.writeFile(sApp, path, content);
            default:
                return ShizukuBridge.writeFile(path, content);
        }
    }

    public static Process startLongRunning(String cmd) throws Exception {
        if (sApp == null || SHIZUKU.equals(Prefs.privilegeMode(sApp))) {
            return ShizukuBridge.startLongRunning(cmd);
        }
        throw new IllegalStateException("managed mode is only available with Shizuku");
    }

    public static boolean supportsManaged() {
        return sApp == null || SHIZUKU.equals(Prefs.privilegeMode(sApp));
    }

    public static void requestShizukuPermission() {
        if (ShizukuBridge.isRunning()) ShizukuBridge.requestPermission(0);
    }
}
