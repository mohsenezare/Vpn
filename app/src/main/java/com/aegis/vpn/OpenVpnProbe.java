package com.aegis.vpn;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Measures the real TCP handshake from THIS Android device to the OpenVPN port.
 * UDP does not acknowledge an unauthenticated probe, so never show a fake UDP ping.
 * TCP reachability is not proof that TLS/OpenVPN authentication or routing works.
 */
final class OpenVpnProbe {
    final ConcurrentHashMap<String, Long> elapsed = new ConcurrentHashMap<>();
    volatile boolean busy;

    String key(FreeDirectory.Node n) {
        InetSocketAddress address = FreeDirectory.tcpEndpoint(n);
        return address == null ? "" : address.getHostString() + ":" + address.getPort();
    }

    long ms(FreeDirectory.Node n) {
        String key = key(n);
        if (key.isEmpty()) return -3L; // UDP: not measurable via TCP
        Long time = elapsed.get(key);
        return time == null ? -2L : time;
    }

    String label(FreeDirectory.Node n) {
        long delay = ms(n);
        if (delay == -3) return "UDP · unknown";
        if (delay == -2) return "Not tested";
        if (delay < 0) return "Unreachable";
        return delay + " ms TCP";
    }

    void run(List<FreeDirectory.Node> nodes, Runnable finished) {
        if (busy) return;
        busy = true;
        final ArrayList<FreeDirectory.Node> snapshot = new ArrayList<>(nodes);
        new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(10);
            LinkedHashMap<String, InetSocketAddress> endpoints = new LinkedHashMap<>();
            try {
                for (FreeDirectory.Node node : snapshot) {
                    InetSocketAddress ep = FreeDirectory.tcpEndpoint(node);
                    if (ep != null) endpoints.putIfAbsent(key(node), ep);
                }
                // Clear previous values, including removed servers. Results appear as each test finishes.
                elapsed.clear();
                ArrayList<Callable<Void>> work = new ArrayList<>();
                for (Map.Entry<String, InetSocketAddress> entry : endpoints.entrySet()) {
                    work.add(() -> {
                        long delay = -1L;
                        try {
                            InetSocketAddress target = entry.getValue();
                            InetAddress ip = InetAddress.getByName(target.getHostString());
                            if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() ||
                                ip.isLinkLocalAddress() || ip.isSiteLocalAddress() ||
                                ip.isMulticastAddress()) throw new IllegalArgumentException("Private address");
                            long start = SystemClock.elapsedRealtimeNanos();
                            try (Socket socket = new Socket()) {
                                socket.connect(new InetSocketAddress(ip, target.getPort()), 1700);
                                delay = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(
                                    SystemClock.elapsedRealtimeNanos() - start));
                            }
                        } catch (Exception ignored) {
                            delay = -1L;
                        }
                        elapsed.put(entry.getKey(), delay);
                        return null;
                    });
                }
                List<Future<Void>> results = pool.invokeAll(work, 28, TimeUnit.SECONDS);
                int pos = 0;
                for (String key : endpoints.keySet()) {
                    if (results.get(pos++).isCancelled()) elapsed.put(key, -1L);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                pool.shutdownNow();
                busy = false;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (finished != null) finished.run();
                });
            }
        }, "openvpn-real-tcp-probes").start();
    }
}
