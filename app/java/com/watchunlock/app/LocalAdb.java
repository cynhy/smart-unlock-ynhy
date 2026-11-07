package com.watchunlock.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 内置 ADB 引擎：直接执行随应用打包的官方 AOSP adb 客户端。
 *
 * 二进制来自 Termux 的 android-tools 37.0.0p1 构建（Apache-2.0），
 * 用法与电脑上的 adb 完全一致：adb pair / adb connect / adb shell / adb push。
 *
 * Android 10+ 只允许在 nativeLibraryDir 里执行二进制，所以可执行文件
 * 由 APK 的 lib/arm64-v8a/libadb.so 解出；它依赖的 .so 首次使用时从
 * assets/adb-libs.zip 解压到 filesDir/adb/lib，并设为只读（Android 14
 * 要求动态加载的库不能可写）。
 */
public final class LocalAdb {

    /** adb server 用非默认端口，避免和用户设备上其它 adb 服务撞端口 */
    private static final int SERVER_PORT = 5039;
    private static final String LIBS_ASSET = "adb-libs.zip";
    private static final String LIBS_VERSION = "37.0.0p1-3";

    private static boolean sPrepared;
    private static String sAdbPath;
    private static File sHomeDir;
    private static File sLibDir;
    private static File sTmpDir;

    private LocalAdb() {
    }

    /** 解压依赖库并确认 adb 可执行文件存在；只在后台线程调用 */
    public static synchronized void prepare(Context c) throws IOException {
        if (sPrepared) return;
        if (c == null) throw new IOException("no context");
        Context app = c.getApplicationContext();
        if (app == null) app = c;
        ApplicationInfo ai = app.getApplicationInfo();
        String nativeDir = ai == null ? null : ai.nativeLibraryDir;
        File adb = nativeDir == null ? null : new File(nativeDir, "libadb.so");
        if (adb == null || !adb.isFile()) {
            throw new IOException(I18n.t(
                    "内置 adb 可执行文件缺失（nativeLibraryDir 里没有 libadb.so）",
                    "Bundled adb is missing (libadb.so not found in nativeLibraryDir)"));
        }
        File base = new File(app.getFilesDir(), "adb");
        sHomeDir = new File(base, "home");
        sLibDir = new File(base, "lib");
        sTmpDir = new File(base, "tmp");
        if (!sHomeDir.isDirectory() && !sHomeDir.mkdirs()) {
            throw new IOException("cannot create " + sHomeDir);
        }
        sTmpDir.mkdirs();
        File stamp = new File(base, "libs.version");
        String have = readText(stamp);
        if (!LIBS_VERSION.equals(have) || countFiles(sLibDir) < 50) {
            extractLibs(app, sLibDir);
            writeText(stamp, LIBS_VERSION);
        }
        sAdbPath = adb.getAbsolutePath();
        sPrepared = true;
    }

    private static void extractLibs(Context c, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        InputStream raw = c.getAssets().open(LIBS_ASSET);
        try {
            ZipInputStream zin = new ZipInputStream(raw);
            try {
                ZipEntry e;
                byte[] buf = new byte[8192];
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName();
                    if (e.isDirectory() || name.isEmpty()) continue;
                    if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.startsWith(".")) continue;
                    File out = new File(dir, name);
                    OutputStream os = new FileOutputStream(out);
                    try {
                        int n;
                        while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                    } finally {
                        os.close();
                    }
                    // 只读：Android 14 起动态加载的库不能可写
                    out.setReadOnly();
                }
            } finally {
                zin.close();
            }
        } finally {
            raw.close();
        }
    }

    private static int countFiles(File dir) {
        File[] files = dir.listFiles();
        return files == null ? 0 : files.length;
    }

    static String adbPath() {
        return sAdbPath;
    }

    static String shellSerial(String host, int port) {
        return host + ":" + port;
    }

    /** 执行一条 adb 客户端命令；会阻塞，只能在后台线程调用 */
    static ShizukuBridge.Result exec(Context c, String[] args, int timeoutMs) {
        ShizukuBridge.Result r = new ShizukuBridge.Result();
        Process p = null;
        try {
            prepare(c);
            // 显式指定 server 端口：仅靠 ANDROID_ADB_SERVER_PORT 时
            // start-server 仍可能落到默认 5037，导致后续命令连到另一个 server
            String[] cmd = new String[args.length + 3];
            cmd[0] = sAdbPath;
            cmd[1] = "-P";
            cmd[2] = String.valueOf(SERVER_PORT);
            System.arraycopy(args, 0, cmd, 3, args.length);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(sHomeDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("HOME", sHomeDir.getAbsolutePath());
            env.put("LD_LIBRARY_PATH", sLibDir.getAbsolutePath());
            env.put("TMPDIR", sTmpDir.getAbsolutePath());
            env.put("ANDROID_ADB_SERVER_PORT", String.valueOf(SERVER_PORT));

            p = pb.start();
            final Process fp = p;
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream in = fp.getInputStream();
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0) {
                            synchronized (buf) {
                                buf.write(b, 0, n);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                try {
                    p.waitFor(1000, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {
                }
                r.error = I18n.t("执行超时", "Timed out");
            }
            // adb server 可能继承这个管道的写端，主动关闭避免读线程一直挂着
            try {
                p.getInputStream().close();
            } catch (Throwable ignored) {
            }
            try {
                reader.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (buf) {
                r.out = buf.toString("UTF-8").trim();
            }
            if (finished) {
                try {
                    r.code = p.exitValue();
                } catch (Throwable t) {
                    r.code = -1;
                }
            } else {
                r.code = -1;
            }
        } catch (Throwable t) {
            if (p != null) {
                try {
                    p.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
            r.code = -1;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return r;
    }

    /** 确保 adb server 已启动；失败时返回错误文本，成功返回 null */
    static String startServer(Context c) {
        ShizukuBridge.Result r = exec(c, new String[]{"start-server"}, 25000);
        if (r.code == 0) {
            return null;
        }
        return I18n.t("adb 服务启动失败：", "Failed to start the adb server: ") + summarize(r);
    }

    /**
     * 连接无线调试端口并验证 shell 可用。
     * 成功返回 null，失败返回错误文本。
     */
    static String connect(Context c, String host, int port) {
        String err = startServer(c);
        if (err != null) return err;
        String serial = shellSerial(host, port);
        ShizukuBridge.Result con = exec(c, new String[]{"connect", serial}, 20000);
        ShizukuBridge.Result v = exec(c, new String[]{"-s", serial, "shell", "echo wu-ok"}, 20000);
        if (v.code == 0 && v.out.contains("wu-ok")) {
            return null;
        }
        String detail = summarize(v);
        if (detail.isEmpty()) detail = summarize(con);
        if (detail.isEmpty()) {
            return I18n.t("端口可以连接，但 shell 不可用", "Connected, but the shell is unavailable");
        }
        return I18n.t("shell 不可用：", "Shell unavailable: ") + detail;
    }

    /** 配对；成功返回 null，失败返回错误文本 */
    static String pair(Context c, String host, int pairPort, String code) {
        String err = startServer(c);
        if (err != null) return err;
        String serial = shellSerial(host, pairPort);
        ShizukuBridge.Result r = exec(c, new String[]{"pair", serial, code}, 25000);
        // adb connect/pair 失败时退出码也可能是 0，必须看输出；
        // “Failed: Successfully paired but server returned unknown response” 也算失败
        if (r.code == 0 && r.out.contains("Successfully paired") && !r.out.contains("Failed:")) {
            return null;
        }
        String detail = summarize(r);
        return detail.isEmpty()
                ? I18n.t("配对失败（adb 没有返回原因）", "Pairing failed (no reason from adb)")
                : detail;
    }

    /** 在远端执行一条 shell 命令 */
    static ShizukuBridge.Result shell(Context c, String host, int port, String cmd, int timeoutMs) {
        return exec(c, new String[]{"-s", shellSerial(host, port), "shell", cmd}, timeoutMs);
    }

    /** 把本地文件推到远端；成功返回 null，失败返回错误文本 */
    static String push(Context c, String host, int port, File local, String remote) {
        String serial = shellSerial(host, port);
        int slash = remote.lastIndexOf('/');
        if (slash > 0) {
            ShizukuBridge.Result mk = exec(c,
                    new String[]{"-s", serial, "shell", "mkdir -p " + quote(remote.substring(0, slash))},
                    15000);
            if (mk.code != 0) return summarize(mk);
        }
        ShizukuBridge.Result r = exec(c,
                new String[]{"-s", serial, "push", local.getAbsolutePath(), remote}, 30000);
        if (r.code != 0 || !r.out.contains("pushed")) {
            String d = summarize(r);
            return d.isEmpty() ? I18n.t("推送文件失败", "Failed to push the file") : d;
        }
        exec(c, new String[]{"-s", serial, "shell", "chmod 644 " + quote(remote)}, 10000);
        return null;
    }

    static void killServer(Context c) {
        exec(c, new String[]{"kill-server"}, 10000);
    }

    /** 判断一条 adb 输出是否属于「连接掉了」，用于让上层重新连接 */
    static boolean looksDisconnected(String out) {
        if (out == null) return false;
        String s = out.toLowerCase();
        return s.contains("device offline")
                || s.contains("device not found")
                || s.contains("no devices/emulators found")
                || s.contains("failed to connect")
                || s.contains("connection reset")
                || s.contains("protocol fault")
                || s.contains("cannot connect");
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String summarize(ShizukuBridge.Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.error != null && !r.error.isEmpty()) sb.append(r.error);
        if (r.out != null && !r.out.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            // adb 的报错可能多行，只保留最有用的前几行
            String[] lines = r.out.split("\n");
            int keep = Math.min(lines.length, 4);
            for (int i = 0; i < keep; i++) {
                if (i > 0) sb.append('\n');
                sb.append(lines[i].trim());
            }
        }
        return sb.toString().trim();
    }

    private static String readText(File f) {
        if (!f.isFile()) return "";
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                return out.toString("UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeText(File f, String text) {
        try {
            OutputStream os = new FileOutputStream(f);
            try {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            } finally {
                os.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
