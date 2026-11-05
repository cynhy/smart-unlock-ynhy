package com.watchunlock.app;

import android.content.Context;
import android.os.Build;

import com.flyfishxu.kadb.Kadb;
import com.flyfishxu.kadb.cert.KadbCert;
import com.flyfishxu.kadb.shell.AdbShellResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.Pair;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

/**
 * 内置无线调试引擎：手机自己作为 ADB 客户端，连本机的无线调试端口，
 * 命令同样以 shell(uid 2000) 身份执行，不依赖 Shizuku。
 * 首次使用需要在开发者选项里打开「无线调试」，并用配对码完成一次配对。
 */
public final class AdbBridge {

    private static Kadb sKadb;
    private static String sHost;
    private static int sPort;
    private static String sError = "";
    private static boolean sKeysLoaded;

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
        try {
            return sKadb != null && sKadb.connectionCheck();
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized void close() {
        try {
            if (sKadb != null) sKadb.close();
        } catch (Throwable ignored) {
        }
        sKadb = null;
    }

    /** 建立连接并验证 shell 可用；会阻塞，只能在后台线程调用 */
    public static synchronized boolean connect(Context c) {
        if (c == null) return false;
        String host = Prefs.adbHost(c);
        int port = Prefs.adbPort(c);
        if (port <= 0) {
            sError = I18n.t("还没有配置无线调试端口", "Wireless debugging port is not configured");
            return false;
        }
        if (sKadb != null && sHost != null && sHost.equals(host) && sPort == port && sKadb.connectionCheck()) {
            return true;
        }
        close();
        try {
            ensureKeys(c);
            sKadb = Kadb.Companion.create(host, port, 3000, 20000);
            AdbShellResponse r = sKadb.shell("echo wu-ok");
            if (r.getAllOutput().contains("wu-ok")) {
                sHost = host;
                sPort = port;
                sError = "";
                return true;
            }
            sError = I18n.t("端口可以连接，但 shell 不可用", "Port connected but shell is unavailable");
            close();
            return false;
        } catch (Throwable t) {
            sError = t.getClass().getSimpleName() + ": " + t.getMessage();
            close();
            return false;
        }
    }

    public static synchronized ShizukuBridge.Result run(Context c, String cmd, int timeoutMs) {
        ShizukuBridge.Result r = new ShizukuBridge.Result();
        try {
            if (!connect(c)) {
                r.code = -1;
                r.error = sError;
                return r;
            }
            AdbShellResponse resp = sKadb.shell(cmd);
            r.code = resp.getExitCode();
            r.out = resp.getOutput().trim();
            r.err = resp.getErrorOutput().trim();
        } catch (Throwable t) {
            sError = t.getClass().getSimpleName() + ": " + t.getMessage();
            r.code = -1;
            r.error = sError;
            close();
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
            sKadb.push(tmp, path, 0644, System.currentTimeMillis());
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
        try {
            ensureKeys(c);
        } catch (Throwable t) {
            return I18n.t("生成密钥失败：", "Failed to generate key: ")
                    + t.getClass().getSimpleName();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            Kadb.Companion.pair(host, pairPort, code.trim(), deviceName(), new Continuation<Unit>() {
                @Override
                public CoroutineContext getContext() {
                    return EmptyCoroutineContext.INSTANCE;
                }

                @Override
                public void resumeWith(Object result) {
                    try {
                        kotlin.ResultKt.throwOnFailure(result);
                    } catch (Throwable t) {
                        err.set(t);
                    }
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        try {
            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                return I18n.t("配对超时：请确认配对码与端口正确，并保持配对弹窗打开",
                        "Pairing timed out: check the code and port, and keep the pairing dialog open");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return I18n.t("配对被中断", "Pairing interrupted");
        }
        Throwable t = err.get();
        if (t != null) return t.getClass().getSimpleName() + ": " + t.getMessage();
        return null;
    }

    public static String deviceName() {
        String m = Build.MODEL;
        return m == null || m.isEmpty() ? "watch-unlock" : m;
    }

    /** ADB 私钥/证书只生成一次并持久化，否则每次重启都会被系统当成新设备 */
    private static void ensureKeys(Context c) throws IOException {
        if (sKeysLoaded) return;
        File cf = new File(c.getFilesDir(), "adb_cert.pem");
        File kf = new File(c.getFilesDir(), "adb_key.pem");
        if (cf.exists() && kf.exists()) {
            byte[] cert = readAll(cf);
            byte[] key = readAll(kf);
            try {
                KadbCert.INSTANCE.set(cert, key);
                sKeysLoaded = true;
                return;
            } catch (Throwable ignored) {
                // 证书过期或损坏：重新生成（需要重新配对一次）
            }
        }
        long notAfter = System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000;
        Pair<byte[], byte[]> p = KadbCert.INSTANCE.get(
                2048, "WatchUnlock", "WatchUnlock", "WatchUnlock",
                "WatchUnlock", "WatchUnlock", "WatchUnlock",
                notAfter, new BigInteger(64, new SecureRandom()));
        KadbCert.INSTANCE.set(p.getFirst(), p.getSecond());
        write(cf, p.getFirst());
        write(kf, p.getSecond());
        sKeysLoaded = true;
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n <= 0) break;
                off += n;
            }
        } finally {
            in.close();
        }
        return data;
    }

    private static void write(File f, byte[] data) throws IOException {
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(data);
        } finally {
            os.close();
        }
    }
}
