package com.watchunlock.app;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 内置无线调试引擎的适配层。
 *
 * 底层改用官方 AOSP adb 客户端（见 {@link LocalAdb}），不再使用第三方
 * Kadb 实现；接口保持不变，上层（Privilege / Scripts / 界面）无需改动。
 */
public final class AdbBridge {

    private static Context sApp;
    private static boolean sConnected;
    private static String sHost;
    private static int sPort;
    private static String sError = "";

    private AdbBridge() {
    }

    public static synchronized String lastError() {
        return sError;
    }

    public static boolean isConfigured(Context c) {
        return Prefs.adbPort(c) > 0;
    }

    /** 只做连接状态判断，不发起网络连接（可以安全地在界面线程调用） */
    public static synchronized boolean isReady() {
        return sConnected;
    }

    public static synchronized void close() {
        sConnected = false;
        sHost = null;
        sPort = 0;
        final Context c = sApp;
        if (c != null) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    LocalAdb.killServer(c);
                }
            });
            t.setDaemon(true);
            t.start();
        }
    }

    /** 建立连接并验证 shell 可用；会阻塞，只能在后台线程调用 */
    public static synchronized boolean connect(Context c) {
        if (c == null) return false;
        Context app = c.getApplicationContext();
        if (app != null) sApp = app;
        String host = Prefs.adbHost(c);
        int port = Prefs.adbPort(c);
        if (port <= 0) {
            sError = I18n.t("还没有配置无线调试端口", "Wireless debugging port is not configured");
            return false;
        }
        if (sConnected && host.equals(sHost) && port == sPort) {
            return true;
        }
        sConnected = false;
        String err = LocalAdb.connect(c, host, port);
        if (err == null) {
            sHost = host;
            sPort = port;
            sConnected = true;
            sError = "";
            return true;
        }
        sError = err;
        return false;
    }

    public static synchronized ShizukuBridge.Result run(Context c, String cmd, int timeoutMs) {
        ShizukuBridge.Result r = new ShizukuBridge.Result();
        if (!connect(c)) {
            r.code = -1;
            r.error = sError;
            return r;
        }
        r = LocalAdb.shell(c, sHost, sPort, cmd, timeoutMs);
        if (r.code != 0 && LocalAdb.looksDisconnected(r.out)) {
            // 无线调试端口失效（重启/开关被关）：下次调用重新配对连接
            sConnected = false;
            sError = r.out;
        }
        return r;
    }

    public static synchronized ShizukuBridge.Result writeFile(Context c, String path, byte[] data) {
        ShizukuBridge.Result r = new ShizukuBridge.Result();
        if (c == null) {
            r.code = -1;
            r.error = "no context";
            return r;
        }
        File tmp = new File(c.getCacheDir(), "wu-push.tmp");
        try {
            OutputStream os = new FileOutputStream(tmp);
            try {
                os.write(data);
            } finally {
                os.close();
            }
            if (!connect(c)) {
                r.code = -1;
                r.error = sError;
                return r;
            }
            String err = LocalAdb.push(c, sHost, sPort, tmp, path);
            if (err != null) {
                r.code = -1;
                r.error = err;
                return r;
            }
            r.code = 0;
        } catch (Throwable t) {
            sError = t.getClass().getSimpleName() + ": " + t.getMessage();
            r.code = -1;
            r.error = sError;
        }
        return r;
    }

    /**
     * 配对并保存端口。需要用户先在开发者选项里打开「使用配对码配对设备」，
     * 拿到配对端口与 6 位配对码。会阻塞，只能在后台线程调用。
     */
    public static String pair(Context c, String host, int pairPort, String code) {
        if (c == null) return "no context";
        if (pairPort <= 0) return I18n.t("配对端口无效", "Invalid pairing port");
        if (code == null || code.trim().length() != 6) {
            return I18n.t("配对码必须是 6 位数字", "The pairing code must be 6 digits");
        }
        Context app = c.getApplicationContext();
        if (app != null) sApp = app;
        return LocalAdb.pair(c, host, pairPort, code.trim());
    }

    public static String deviceName() {
        String m = Build.MODEL;
        return m == null || m.isEmpty() ? "watch-unlock" : m;
    }
}
