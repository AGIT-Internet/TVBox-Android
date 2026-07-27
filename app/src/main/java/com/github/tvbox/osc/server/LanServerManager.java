package com.github.tvbox.osc.server;

import android.content.Context;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public final class LanServerManager {
    public static final int PORT = 9979;
    private static final long PAIR_CODE_TTL_MS = 5L * 60L * 1000L;
    private static final long FAILURE_WINDOW_MS = 60L * 1000L;
    private static final long FAILURE_BLOCK_MS = 60L * 1000L;
    private static final int MAX_PAIR_FAILURES = 5;

    private static final LanServerManager INSTANCE = new LanServerManager();
    private Context context;
    private LanServer server;
    private long pairCodeExpiresAt;
    private final Map<String, SessionInfo> sessions = new LinkedHashMap<>();
    private final Map<String, PairFailures> pairFailures = new LinkedHashMap<>();

    public static final class SessionInfo {
        public final String token;
        public final String device;
        public final String address;
        public final String userAgent;
        public final long createdAt;
        public long lastSeenAt;

        SessionInfo(String token, String device, String address, String userAgent) {
            this.token = token;
            this.device = device;
            this.address = address;
            this.userAgent = userAgent;
            this.createdAt = System.currentTimeMillis();
            this.lastSeenAt = this.createdAt;
        }
    }

    private static final class PairFailures {
        int count;
        long windowStartedAt;
        long blockedUntil;
    }

    private LanServerManager() {
    }

    public static LanServerManager get() {
        return INSTANCE;
    }

    public synchronized void init(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void startIfEnabled() {
        if (Hawk.get(HawkConfig.LAN_SERVER_ENABLED, false) && context != null) {
            LanServerService.start(context);
        }
    }

    public synchronized boolean setEnabled(boolean enabled) {
        if (enabled) {
            boolean started = start();
            if (started) {
                Hawk.put(HawkConfig.LAN_SERVER_ENABLED, true);
                LanServerService.start(context);
            }
            return started;
        }
        Hawk.put(HawkConfig.LAN_SERVER_ENABLED, false);
        stop();
        if (context != null) LanServerService.stop(context);
        return true;
    }

    public synchronized boolean start() {
        if (isRunning()) return true;
        if (context == null) return false;
        if (server != null) {
            server.stop();
            server = null;
        }
        LanServer candidate = new LanServer(context, PORT);
        try {
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            server = candidate;
            return true;
        } catch (IOException error) {
            LOG.e("LAN server start failed: " + error.getMessage());
            candidate.stop();
            return false;
        }
    }

    public boolean isSecure() {
        return Hawk.get(HawkConfig.LAN_SERVER_SECURE, false);
    }

    public synchronized void setSecure(boolean secure) {
        Hawk.put(HawkConfig.LAN_SERVER_SECURE, secure);
        clearSessions();
        if (secure) generatePairCode();
    }

    public synchronized String getPairCode() {
        ensurePairCode();
        return Hawk.get(HawkConfig.LAN_SERVER_PAIR_CODE, "");
    }

    public synchronized long getPairCodeExpiresAt() {
        ensurePairCode();
        return pairCodeExpiresAt;
    }

    public synchronized void regenerateCredentials() {
        clearSessions();
        generatePairCode();
    }

    private void generatePairCode() {
        SecureRandom random = new SecureRandom();
        Hawk.put(HawkConfig.LAN_SERVER_PAIR_CODE, String.format(Locale.US, "%06d", random.nextInt(1_000_000)));
        pairCodeExpiresAt = System.currentTimeMillis() + PAIR_CODE_TTL_MS;
    }

    private void ensurePairCode() {
        if (pairCodeExpiresAt <= System.currentTimeMillis()
                || Hawk.get(HawkConfig.LAN_SERVER_PAIR_CODE, "").isEmpty()) generatePairCode();
    }

    public synchronized long pairRetryAfter(String address) {
        PairFailures failures = pairFailures.get(address);
        if (failures == null || failures.blockedUntil <= System.currentTimeMillis()) return 0L;
        return failures.blockedUntil - System.currentTimeMillis();
    }

    public synchronized String pair(String code, String address, String device, String userAgent) {
        ensurePairCode();
        if (pairRetryAfter(address) > 0) return "";
        if (!getPairCode().equals(code)) {
            long now = System.currentTimeMillis();
            PairFailures failures = pairFailures.get(address);
            if (failures == null || now - failures.windowStartedAt > FAILURE_WINDOW_MS) {
                failures = new PairFailures();
                failures.windowStartedAt = now;
                pairFailures.put(address, failures);
            }
            failures.count++;
            if (failures.count >= MAX_PAIR_FAILURES) failures.blockedUntil = now + FAILURE_BLOCK_MS;
            return "";
        }
        pairFailures.remove(address);
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte value : bytes) token.append(String.format(Locale.US, "%02x", value & 0xff));
        String name = device == null || device.trim().isEmpty() ? "浏览器设备" : device.trim();
        sessions.put(token.toString(), new SessionInfo(token.toString(), name, address, userAgent == null ? "" : userAgent));
        return token.toString();
    }

    public synchronized boolean authenticate(String token) {
        SessionInfo session = sessions.get(token);
        if (session == null) return false;
        session.lastSeenAt = System.currentTimeMillis();
        return true;
    }

    public synchronized List<SessionInfo> getSessions() {
        return new ArrayList<>(sessions.values());
    }

    public synchronized int getSessionCount() {
        return sessions.size();
    }

    public synchronized void clearSessions() {
        sessions.clear();
        pairFailures.clear();
    }

    public synchronized void stop() {
        stopServer(true);
    }

    synchronized void stopPreservingSessions() {
        stopServer(false);
    }

    synchronized boolean restart() {
        stopServer(false);
        return start();
    }

    private void stopServer(boolean clearSessionState) {
        if (server != null) server.stop();
        server = null;
        if (clearSessionState) clearSessions();
    }

    public synchronized boolean isRunning() {
        return server != null && server.isAlive();
    }

    public String getAddress() {
        String address = findLanAddress();
        return address == null ? "" : "http://" + address + ":" + PORT;
    }

    private String findLanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception error) {
            LOG.e("LAN address lookup failed: " + error.getMessage());
        }
        return null;
    }
}
