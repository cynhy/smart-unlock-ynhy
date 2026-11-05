package com.watchunlock.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 桥：以 shell 身份执行命令、写文件。
 * 所有调用都要求 Shizuku 服务在运行且本应用已获授权。
 */
public final class ShizukuBridge {

    public static final int PERMISSION_GRANTED = 0;

    private static Method sNewProcess;

    private ShizukuBridge() {
    }

    public static class Result {
        public int code;
        public String out = "";
        public String err = "";
        public String error;

        public boolean ok() {
            return error == null && code == 0;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            if (out != null && !out.isEmpty()) sb.append(out);
            if (err != null && !err.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(err);
            }
            if (error != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("error: ").append(error);
            }
            return sb.toString();
        }
    }

    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String lastError = "";

    public static boolean hasPermission() {
        try {
            int r = Shizuku.checkSelfPermission();
            if (r != PERMISSION_GRANTED) lastError = "check=" + r;
            return r == PERMISSION_GRANTED;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }

    public static int permissionState() {
        try {
            return Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            return -999;
        }
    }

    public static int version() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void requestPermission(int code) {
        try {
            Shizuku.requestPermission(code);
        } catch (Throwable ignored) {
        }
    }

    public static Result run(String cmd) {
        return run(cmd, null, 60000);
    }

    public static Result run(String cmd, int timeoutMs) {
        return run(cmd, null, timeoutMs);
    }

    public static Result run(String cmd, byte[] stdin, int timeoutMs) {
        return runInternal(new String[]{"sh", "-c", cmd}, stdin, timeoutMs);
    }

    /** 用 shell 写文件（内容走 stdin，避免转义与注入问题）。 */
    public static Result writeFile(String path, String content) {
        return run("cat > " + path, content.getBytes(StandardCharsets.UTF_8), 15000);
    }

    public static Result writeFile(String path, byte[] content) {
        return run("cat > " + path, content, 15000);
    }

    private static Process newProcess(String[] cmd) throws Exception {
        if (sNewProcess == null) {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            sNewProcess = m;
        }
        return (Process) sNewProcess.invoke(null, cmd, null, null);
    }

    /**
     * 启动一个长驻进程并立刻返回（不等待结束）。
     * 进程随 Shizuku 存活，用于「托管」模式；输出实时打进 logcat。
     */
    public static Process startLongRunning(String cmd) throws Exception {
        final Process p = newProcess(new String[]{"sh", "-c", cmd});
        try {
            p.getOutputStream().close();
        } catch (Throwable ignored) {
        }
        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buf = new byte[8192];
                    InputStream in = p.getInputStream();
                    while (in.read(buf) > 0) {
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "wu-long-out");
        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buf = new byte[8192];
                    InputStream in = p.getErrorStream();
                    while (in.read(buf) > 0) {
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "wu-long-err");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        return p;
    }

    private static Result runInternal(String[] cmd, byte[] stdin, int timeoutMs) {
        Result r = new Result();
        Process p = null;
        try {
            p = newProcess(cmd);
            if (stdin != null) {
                OutputStream os = p.getOutputStream();
                os.write(stdin);
                os.flush();
                os.close();
            }
            final InputStream in = p.getInputStream();
            final InputStream errIn = p.getErrorStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread t1 = new Thread(new Runnable() {
                @Override
                public void run() {
                    copy(in, out);
                }
            }, "wu-out");
            Thread t2 = new Thread(new Runnable() {
                @Override
                public void run() {
                    copy(errIn, err);
                }
            }, "wu-err");
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
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Throwable ignored) {
        }
    }
}
