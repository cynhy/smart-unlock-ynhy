package com.watchunlock.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.Enumeration;

/**
 * 发现本机 adbd 广播的无线调试端口。
 *
 * 思路移植自 Shizuku（Apache-2.0）的 AdbMdns：
 * - 开启无线调试后，系统会广播 _adb-tls-connect._tcp
 * - 打开“使用配对码配对设备”弹窗后，系统会广播 _adb-tls-pairing._tcp
 * 解析到端口后回调；-1 表示服务消失。
 */
public final class AdbMdns {

    public static final String TLS_PAIRING = "_adb-tls-pairing._tcp";
    public static final String TLS_CONNECT = "_adb-tls-connect._tcp";

    public interface Callback {
        void onPort(int port);
    }

    private final NsdManager nsd;
    private final String serviceType;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private boolean registered;
    private boolean resolving;
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
        if (registered) return;
        discovery = new DiscoveryListener();
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Throwable t) {
            running = false;
            discovery = null;
        }
    }

    public synchronized void stop() {
        running = false;
        if (nsd == null || !registered || discovery == null) return;
        try {
            nsd.stopServiceDiscovery(discovery);
        } catch (Throwable ignored) {
        }
        registered = false;
        discovery = null;
    }

    private void resolve(NsdServiceInfo info) {
        if (!running || nsd == null || resolving) return;
        resolving = true;
        try {
            nsd.resolveService(info, new ResolveListener());
        } catch (Throwable t) {
            resolving = false;
        }
    }

    private void onResolved(NsdServiceInfo info) {
        resolving = false;
        if (!running || info == null) return;
        if (!isLocalHost(info.getHost())) return;
        int port = info.getPort();
        if (port <= 0 || !isPortInUse(port)) return;
        serviceName = info.getServiceName();
        main.post(() -> {
            if (running) callback.onPort(port);
        });
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
        if (host == null) return true; // 部分 ROM 解析不出 host，交给端口占用检查兜底
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

    /** 端口被占用才说明是本机 adbd 在监听；能绑定成功说明是别的设备的端口。 */
    private boolean isPortInUse(int port) {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress("127.0.0.1", port), 1);
            return false;
        } catch (IOException e) {
            return true;
        } catch (Throwable t) {
            return true;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private final class DiscoveryListener implements NsdManager.DiscoveryListener {
        @Override
        public void onDiscoveryStarted(String type) {
            registered = true;
        }

        @Override
        public void onStartDiscoveryFailed(String type, int errorCode) {
            registered = false;
            running = false;
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
            resolve(info);
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
            onLost(info);
        }
    }

    private final class ResolveListener implements NsdManager.ResolveListener {
        @Override
        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
            resolving = false;
        }

        @Override
        public void onServiceResolved(NsdServiceInfo info) {
            onResolved(info);
        }
    }
}
