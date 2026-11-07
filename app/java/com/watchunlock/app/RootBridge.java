package com.watchunlock.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Root 引擎：通过 su 以 uid=0 执行命令。
 *
 * 稳定性要点：
 * 1. 复用一条常驻的 su 交互 shell，避免每次操作都重新拉起 su（首次授权、超时与卡顿都更少）；
 *    交互 shell 不可用时自动退回一次性的 su -c。
 * 2. 每条命令写入唯一的脚本文件，绝不共用同一个临时脚本，避免并发调用互相覆盖。
 * 3. 写文件同样使用唯一临时文件，避免两个写入同时发生时内容串台。
 * 4. su 失败/超时的缓存只保留 20 秒，授权成功后缓存 5 分钟；首次检测给 15 秒，
 *    留出 root 管理器弹窗的确认时间，不会出现「刚点允许却仍显示未授权」。
 */
public final class RootBridge {

    private static final Object LOCK = new Object();

    private static final long OK_TTL = 300000L;
    private static final long FAIL_TTL = 20000L;
    private static final int FIRST_TIMEOUT = 15000;

    private static Boolean sOk;
    private static long sCheckedAt;

    private static final AtomicLong FILE_SEQ = new AtomicLong();
    private static final AtomicInteger SHELL_SEQ = new AtomicInteger();

    private static Process sShell;
    private static Writer sShellIn;
    private static BufferedReader sShellOut;
    private static String sSuPath;
    private static final StringBuilder sErrBuf = new StringBuilder();

    private RootBridge() {
    }

    public static synchronized boolean isReady(Context c) {
        long now = System.currentTimeMillis();
        if (sOk != null) {
            long ttl = sOk ? OK_TTL : FAIL_TTL;
            if (now - sCheckedAt < ttl) return sOk;
        }
        int timeout = sOk == null ? FIRST_TIMEOUT : 6000;
        boolean ok = probeNow(c, timeout);
        sOk = ok;
        sCheckedAt = System.currentTimeMillis();
        return ok;
    }

    /** 主动检测一次，不受缓存影响；用于「检测 Root 授权」按钮和部署前的强校验。 */
    public static synchronized boolean probe(Context c, int timeoutMs) {
        boolean ok = probeNow(c, timeoutMs);
        sOk = ok;
        sCheckedAt = System.currentTimeMillis();
        return ok;
    }

    private static boolean probeNow(Context c, int timeoutMs) {
        ShizukuBridge.Result r = run(c, "id", timeoutMs);
        return r.code == 0 && r.out.contains("uid=0");
    }

    public static synchronized void reset() {
        sOk = null;
        sCheckedAt = 0;
        closeShell();
    }

    public static synchronized ShizukuBridge.Result run(Context c, String cmd, int timeoutMs) {
        ShizukuBridge.Result r = new ShizukuBridge.Result();
        if (c == null) {
            r.code = -1;
            r.error = "no context";
            return r;
        }
        // 每次用独立的脚本文件：并发调用（亮屏解锁、状态刷新、部署）不会互相覆盖内容
        File script = new File(c.getCacheDir(), "wu-root-" + FILE_SEQ.incrementAndGet() + ".sh");
        try {
            write(script, cmd);
        } catch (Throwable t) {
            r.code = -1;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
        try {
            ShizukuBridge.Result sr = runViaShell("sh '" + script.getAbsolutePath() + "'", timeoutMs);
            if (sr != null) return sr;
            return execOneShot("sh '" + script.getAbsolutePath() + "'", timeoutMs);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            script.delete();
        }
    }

    public static synchronized ShizukuBridge.Result writeFile(Context c, String path, byte[] data) {
        ShizukuBridge.Result r = new ShizukuBridge.Result();
        if (c == null) {
            r.code = -1;
            r.error = "no context";
            return r;
        }
        File tmp = new File(c.getCacheDir(), "wu-write-" + FILE_SEQ.incrementAndGet() + ".tmp");
        try {
            write(tmp, data);
            return run(c, "cp '" + tmp.getAbsolutePath() + "' '" + path + "' && chmod 644 '" + path + "'",
                    15000);
        } catch (Throwable t) {
            r.code = -1;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ---------------------------------------------------------------- 常驻 su shell

    /**
     * 在常驻 shell 里执行脚本。返回 null 表示 shell 没能建立（调用方应退回一次性 su -c）；
     * 返回 code=-2 表示命令超时，此时 shell 已被销毁。
     */
    private static ShizukuBridge.Result runViaShell(String command, int timeoutMs) {
        if (!ensureShell(timeoutMs)) return null;

        int id = SHELL_SEQ.incrementAndGet();
        String marker = "__WU_END_" + id + "__";
        clearErr();
        try {
            // 必须先把脚本的退出码存入变量：printf 会覆盖 $?，否则永远返回 0
            sShellIn.write(command + "\n__WU_RC=$?\nprintf '\\n'\necho " + marker + "\"$__WU_RC\"\n");
            sShellIn.flush();
        } catch (Throwable t) {
            closeShell();
            return null;
        }

        StringBuilder out = new StringBuilder();
        boolean found = readUntil(sShellOut, marker, timeoutMs, out);
        int pos;
        synchronized (out) {
            pos = out.lastIndexOf(marker);
        }
        if (!found || pos < 0) {
            closeShell();
            ShizukuBridge.Result r = new ShizukuBridge.Result();
            r.code = -2;
            r.error = "timeout " + timeoutMs + "ms";
            r.out = cleanOut(out);
            r.err = takeErr();
            return r;
        }

        ShizukuBridge.Result r = new ShizukuBridge.Result();
        String before;
        String codeText;
        synchronized (out) {
            before = out.substring(0, pos);
            codeText = out.substring(pos + marker.length()).trim();
        }
        // 命令输出末尾可能没有换行；我们执行了一个 printf '\n'，这里刚好去掉它
        if (before.endsWith("\n")) before = before.substring(0, before.length() - 1);
        r.out = before.trim();
        r.err = takeErr();
        try {
            r.code = Integer.parseInt(codeText);
        } catch (Throwable t) {
            r.code = -1;
            r.error = "bad exit marker: " + codeText;
        }
        return r;
    }

    private static boolean ensureShell(int timeoutMs) {
        if (sShell != null && sShell.isAlive() && sShellIn != null && sShellOut != null) return true;
        closeShell();

        List<String> candidates = suCandidates();
        for (String su : candidates) {
            Process p = null;
            try {
                p = new ProcessBuilder(su).redirectErrorStream(false).start();
                Writer in = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
                BufferedReader out = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                startErrPump(p.getErrorStream());

                int id = SHELL_SEQ.incrementAndGet();
                String marker = "__WU_READY_" + id + "__";
                in.write("stty -echo 2>/dev/null\nprintf '\\n'\necho " + marker + "$?\n");
                in.flush();

                StringBuilder junk = new StringBuilder();
                int wait = sOk == null ? Math.max(timeoutMs, FIRST_TIMEOUT) : Math.max(timeoutMs, 4000);
                if (readUntil(out, marker, wait, junk)) {
                    sShell = p;
                    sShellIn = in;
                    sShellOut = out;
                    sSuPath = su;
                    clearErr();
                    return true;
                }
                // 超时通常是在等 root 管理器弹窗；不要立刻换下一个路径重复弹窗
                closeQuietly(in, out, p);
                return false;
            } catch (IOException e) {
                closeQuietly(p);
                // 这个路径不存在/不可执行，尝试下一个
            } catch (Throwable t) {
                closeQuietly(p);
                return false;
            }
        }
        return false;
    }

    private static List<String> suCandidates() {
        List<String> list = new ArrayList<>();
        if (sSuPath != null) {
            list.add(sSuPath);
            return list;
        }
        list.add("su");
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
                "/data/adb/magisk/su"
        };
        for (String p : paths) {
            if (new File(p).exists() && !list.contains(p)) list.add(p);
        }
        return list;
    }

    private static void startErrPump(final InputStream err) {
        if (err == null) return;
        Thread t = new Thread(() -> {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sErrBuf) {
                        if (sErrBuf.length() > 512 * 1024) sErrBuf.setLength(0);
                        sErrBuf.append(line).append('\n');
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (r != null) r.close();
                } catch (Throwable ignored) {
                }
            }
        }, "wu-root-stderr");
        t.setDaemon(true);
        t.start();
    }

    private static boolean readUntil(BufferedReader in, String marker, int timeoutMs,
                                     final StringBuilder out) {
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] found = {false};
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (out) {
                        out.append(line).append('\n');
                    }
                    if (line.startsWith(marker)) {
                        found[0] = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                done.countDown();
            }
        }, "wu-root-stdout");
        t.setDaemon(true);
        t.start();
        try {
            done.await(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return found[0];
    }

    private static String cleanOut(StringBuilder out) {
        synchronized (out) {
            return out.toString().trim();
        }
    }

    private static void clearErr() {
        synchronized (sErrBuf) {
            sErrBuf.setLength(0);
        }
    }

    private static String takeErr() {
        synchronized (sErrBuf) {
            String s = sErrBuf.toString().trim();
            sErrBuf.setLength(0);
            return s;
        }
    }

    private static void closeShell() {
        Writer in = sShellIn;
        BufferedReader out = sShellOut;
        Process p = sShell;
        sShellIn = null;
        sShellOut = null;
        sShell = null;
        clearErr();
        closeQuietly(in, out, p);
    }

    private static void closeQuietly(Object... things) {
        for (Object o : things) {
            if (o == null) continue;
            try {
                if (o instanceof Process) ((Process) o).destroy();
                else if (o instanceof Writer) ((Writer) o).close();
                else if (o instanceof BufferedReader) ((BufferedReader) o).close();
                else if (o instanceof InputStream) ((InputStream) o).close();
                else if (o instanceof OutputStream) ((OutputStream) o).close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 一次性 su -c 兜底

    /** 单个参数字符串交给 su 自己的 shell 解析；不同 su 实现都支持这种写法 */
    private static ShizukuBridge.Result execOneShot(String command, int timeoutMs) {
        ShizukuBridge.Result r = new ShizukuBridge.Result();
        Process p = null;
        IOException last = null;
        for (String su : suCandidates()) {
            try {
                p = new ProcessBuilder(su, "-c", command).redirectErrorStream(false).start();
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (p == null) {
            r.code = -1;
            r.error = last == null ? "su not found" : last.getClass().getSimpleName() + ": " + last.getMessage();
            return r;
        }
        try {
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
                closeQuietly(p);
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
            closeQuietly(p);
        }
        return r;
    }

    private static void write(File f, String text) throws IOException {
        write(f, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(File f, byte[] data) throws IOException {
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(data);
        } finally {
            os.close();
        }
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
