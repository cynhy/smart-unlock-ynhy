package com.watchunlock.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * 发现本机 adbd 广播的无线调试端口。
 *
 * 思路移植自 Shizuku（Apache-2.0）的 AdbMdns，并针对本项目的实测问题做了加固：
 * - 同时监听 _adb-tls-pairing._tcp（配对）与 _adb-tls-connect._tcp（连接）；
 * - 解析失败不会永久卡住：同一服务重试，解析回调丢失后继续解析下一个；
 * - 发现服务启动失败会自动重试（部分 ROM 在停止上一次发现后立刻启动会返回忙）；
 * - 不再用「本地端口能否绑定」过滤：部分机型 adbd 只监听 IPv6，绑定 IPv4 会误判为可用。
 */
public final class AdbMdns {

    public static final String TLS_PAIRING = "_adb-tls-pairing._tcp";
    public static final String TLS_CONNECT = "_adb-tls-connect._tcp";

    private static final int RESOLVE_RETRIES = 2;
    private static final int START_RETRIES = 5;

    public interface Callback {
        void onPort(int port);
    }

    private final NsdManager nsd;
    private final String serviceType;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private final Map<String, Integer> attemptCount = new HashMap<>();

    private volatile boolean running;
    private boolean registered;
    private boolean resolving;
    private int startFailures;
    private int generation;
    private String serviceName;
    private DiscoveryListener discovery;

    public AdbMdns(Context context, String serviceType, Callback callback) {
        this.nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.serviceType = serviceType;
        this.callback = callback;
    }

    public synchronized void start() {
        if (running || nsd == null) return;
        running = true;
        startDiscovery();
    }

    /** 重新开始发现：用于长时间搜不到服务、或发现被系统打断的情景。 */
    public synchronized void restart() {
        if (nsd == null) return;
        stop();
        final int token = generation;
        main.postDelayed(() -> {
            synchronized (AdbMdns.this) {
                if (token == generation) start();
            }
        }, 1200);
    }

    public synchronized void stop() {
        running = false;
        generation++;
        pending.clear();
        resolving = false;
        startFailures = 0;
        if (nsd == null || discovery == null) {
            registered = false;
            return;
        }
        if (registered) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (Throwable ignored) {
            }
        }
        registered = false;
        discovery = null;
    }

    private synchronized void startDiscovery() {
        if (!running || nsd == null || discovery != null) return;
        discovery = new DiscoveryListener();
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery);
            startFailures = 0;
        } catch (Throwable t) {
            discovery = null;
            scheduleStartRetry();
        }
    }

    private void scheduleStartRetry() {
        if (!running) return;
        if (startFailures >= START_RETRIES) return;
        startFailures++;
        main.postDelayed(this::startDiscovery, 1500);
    }

    private synchronized void resolveNext() {
        if (!running || resolving || nsd == null) return;
        NsdServiceInfo info = pending.pollFirst();
        if (info == null) return;
        resolving = true;
        try {
            nsd.resolveService(info, new ResolveListener(info));
        } catch (Throwable t) {
            resolving = false;
            retryOrDrop(info);
        }
    }

    private void retryOrDrop(NsdServiceInfo info) {
        String key = info.getServiceName() == null ? "" : info.getServiceName();
        int n = attemptCount.containsKey(key) ? attemptCount.get(key) : 0;
        if (n < RESOLVE_RETRIES) {
            attemptCount.put(key, n + 1);
            synchronized (this) {
                pending.addFirst(info);
            }
            main.postDelayed(this::resolveNext, 900);
            return;
        }
        attemptCount.remove(key);
        resolveNext();
    }

    private void onResolved(NsdServiceInfo info) {
        boolean deliver = false;
        synchronized (this) {
            resolving = false;
            if (running && info != null && isLocalHost(info.getHost()) && info.getPort() > 0) {
                serviceName = info.getServiceName();
                attemptCount.remove(serviceName == null ? "" : serviceName);
                deliver = true;
            }
        }
        if (deliver) {
            final int port = info.getPort();
            main.post(() -> {
                if (running) callback.onPort(port);
            });
        }
        resolveNext();
    }

    private void onLost(NsdServiceInfo info) {
        if (info == null || info.getServiceName() == null) return;
        if (serviceName != null && info.getServiceName().equals(serviceName)) {
            main.post(() -> {
                if (running) callback.onPort(-1);
            });
        }
    }

    /** 只接受本机网卡地址上的服务，避免把同一局域网里别的设备当成自己。 */
    private boolean isLocalHost(InetAddress host) {
        if (host == null) return true; // 部分 ROM 解析不出 host，交给 adb connect 的失败路径兜底
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.getHostAddress() != null && a.getHostAddress().equals(host.getHostAddress())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private final class DiscoveryListener implements NsdManager.DiscoveryListener {
        @Override
        public void onDiscoveryStarted(String type) {
            registered = true;
            startFailures = 0;
        }

        @Override
        public void onStartDiscoveryFailed(String type, int errorCode) {
            registered = false;
            synchronized (AdbMdns.this) {
                discovery = null;
            }
            scheduleStartRetry();
        }

        @Override
        public void onDiscoveryStopped(String type) {
            registered = false;
        }

        @Override
        public void onStopDiscoveryFailed(String type, int errorCode) {
            registered = false;
        }

        @Override
        public void onServiceFound(NsdServiceInfo info) {
            synchronized (AdbMdns.this) {
                pending.addLast(info);
            }
            resolveNext();
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
            onLost(info);
        }
    }

    private final class ResolveListener implements NsdManager.ResolveListener {
        private final NsdServiceInfo info;

        ResolveListener(NsdServiceInfo info) {
            this.info = info;
        }

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            synchronized (AdbMdns.this) {
                resolving = false;
            }
            retryOrDrop(serviceInfo == null ? info : serviceInfo);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            onResolved(serviceInfo);
        }
    }
}
