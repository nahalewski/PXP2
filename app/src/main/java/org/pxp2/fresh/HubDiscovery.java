package org.pxp2.fresh;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Finds the PXP2 PC Hub (pxp_server.py) on the local network without typing an address.
 * 1. UDP broadcast "PXP2_DISCOVER" to port {@link #DISCOVERY_PORT}; the hub answers "PXP2_HUB <httpPort>".
 * 2. Fallback for hubs without the responder: probe port 8080 on every host of the phone's IPv4 /24 subnets in parallel.
 */
final class HubDiscovery {
    static final int DISCOVERY_PORT = 8081, HTTP_PORT = 8080;
    private HubDiscovery() { }

    /** Returns the hub's base URL ("http://ip:port") or null. Blocking; call off the UI thread (takes up to ~4 s). */
    static String discover() {
        String local = "http://127.0.0.1:" + HTTP_PORT; // a hub forwarded over USB/wireless debugging (adb reverse)
        if (ServerTransferView.probe(local, 400)) return local;
        String viaBroadcast = broadcast();
        return viaBroadcast != null ? viaBroadcast : scanSubnets();
    }

    private static String broadcast() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true); socket.setSoTimeout(400);
            byte[] ask = "PXP2_DISCOVER".getBytes(StandardCharsets.US_ASCII);
            List<InetAddress> targets = new ArrayList<>(); targets.add(InetAddress.getByName("255.255.255.255"));
            for (InterfaceAddress a : localIpv4()) if (a.getBroadcast() != null) targets.add(a.getBroadcast());
            byte[] buf = new byte[256];
            for (int attempt = 0; attempt < 3; attempt++) {
                for (InetAddress t : targets) try { socket.send(new DatagramPacket(ask, ask.length, t, DISCOVERY_PORT)); } catch (Exception ignored) { }
                long until = System.currentTimeMillis() + 400;
                while (System.currentTimeMillis() < until) {
                    DatagramPacket reply = new DatagramPacket(buf, buf.length);
                    try { socket.receive(reply); } catch (SocketTimeoutException e) { break; }
                    String text = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.US_ASCII).trim();
                    if (!text.startsWith("PXP2_HUB")) continue;
                    int port = HTTP_PORT;
                    try { port = Integer.parseInt(text.substring(8).trim()); } catch (Exception ignored) { }
                    String base = "http://" + reply.getAddress().getHostAddress() + ":" + port;
                    if (ServerTransferView.probe(base, 1500)) return base;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String scanSubnets() {
        List<String> hosts = new ArrayList<>();
        for (InterfaceAddress a : localIpv4()) {
            byte[] ip = a.getAddress().getAddress();
            for (int last = 1; last < 255; last++) {
                if (last == (ip[3] & 255)) continue;
                hosts.add((ip[0] & 255) + "." + (ip[1] & 255) + "." + (ip[2] & 255) + "." + last);
            }
        }
        if (hosts.isEmpty()) return null;
        ExecutorService pool = Executors.newFixedThreadPool(48);
        CompletionService<String> done = new ExecutorCompletionService<>(pool);
        for (String h : hosts) done.submit(() -> {
            try (Socket s = new Socket()) { s.connect(new InetSocketAddress(h, HTTP_PORT), 350); } catch (Exception e) { return null; }
            String base = "http://" + h + ":" + HTTP_PORT;
            return ServerTransferView.probe(base, 1500) ? base : null;
        });
        try {
            long deadline = System.currentTimeMillis() + 6000;
            for (int i = 0; i < hosts.size(); i++) {
                Future<String> f = done.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (f == null) break;
                String r = f.get(); if (r != null) return r;
            }
        } catch (Exception ignored) {
        } finally { pool.shutdownNow(); }
        return null;
    }

    /** Site-local IPv4 addresses of the active interfaces (Wi-Fi, Ethernet, USB tethering). */
    private static List<InterfaceAddress> localIpv4() {
        List<InterfaceAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress a : n.getInterfaceAddresses())
                    if (a.getAddress() instanceof Inet4Address && a.getAddress().isSiteLocalAddress() && a.getNetworkPrefixLength() >= 16) out.add(a);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
