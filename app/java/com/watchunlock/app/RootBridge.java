package com.watchunlock.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Root 引擎：通过 su 以 uid=0 执行命令。
 * 每条命令先写入应用缓存目录的脚本，再交给 su 执行，避免复杂命令的转义问题。
 */
public final class RootBridge {

    private static Boolean sOk;
    private static long sCheckedAt;

    private RootBridge() {
    }

    public static synchronized boolean isReady(Context c) {
        long now = System.currentTimeMillis();
        if (sOk != null && now - sCheckedAt < 120000L) return sOk;
        ShizukuBridge.Result r = exec("id", 2500);
        sOk = r.code == 0 && r.out.contains("uid=0");
        sCheckedAt = now;
        return sOk;
    }

    public static void reset() {
        sOk = null;
        sCheckedAt = 0;
    }

    public static ShizukuBridge.Result run(Context c, String cmd, int timeoutMs) {
        if (c == null) {
            ShizukuBridge.Result r = new ShizukuBridge.Result();
            r.code = -1;
            r.error = "no context";
            return r;
        }
        File script = new File(c.getCacheDir(), "wu-root.sh");
        try {
            write(script, cmd);
        } catch (Throwable t) {
            ShizukuBridge.Result r = new ShizukuBridge.Result();
            r.code = -1;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
        return exec("sh " + script.getAbsolutePath(), timeoutMs);
    }

    public static ShizukuBridge.Result writeFile(Context c, String path, byte[] data) {
        ShizukuBridge.Result r = new ShizukuBridge.Result();
        if (c == null) {
            r.code = -1;
            r.error = "no context";
            return r;
        }
        File tmp = new File(c.getCacheDir(), "wu-write.tmp");
        try {
            write(tmp, data);
        } catch (Throwable t) {
            r.code = -1;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
        return run(c, "cp '" + tmp.getAbsolutePath() + "' '" + path + "' && chmod 644 '" + path + "'", 15000);
    }

    private static void write(File f, String text) throws IOException {
        write(f, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(File f, byte[] data) throws IOException {
        OutputStream os = new java.io.FileOutputStream(f);
        try {
            os.write(data);
        } finally {
            os.close();
        }
    }

    /** su -c 的单个参数字符串："sh /path"；不同 su 实现都会把它交给 shell 解析 */
    private static ShizukuBridge.Result exec(String command, int timeoutMs) {
        ShizukuBridge.Result r = new ShizukuBridge.Result();
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(false).start();
            final InputStream in = p.getInputStream();
            final InputStream errIn = p.getErrorStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread t1 = new Thread(() -> copy(in, out), "wu-root-out");
            Thread t2 = new Thread(() -> copy(errIn, err), "wu-root-err");
            t1.setDaemon(true);
            t2.setDaemon(true);
            t1.start();
            t2.start();
            t1.join(timeoutMs);
            t2.join(3000);
            if (t1.isAlive() || t2.isAlive()) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
                r.code = -2;
                r.error = "timeout " + timeoutMs + "ms";
            } else {
                r.code = p.waitFor();
            }
            r.out = out.toString("UTF-8").trim();
            r.err = err.toString("UTF-8").trim();
        } catch (Throwable t) {
            r.code = -1;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        if (in == null) return;
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Throwable ignored) {
        }
    }
}
