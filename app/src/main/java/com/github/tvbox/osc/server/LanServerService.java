package com.github.tvbox.osc.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LanServerService extends Service {
    private static final String ACTION_START = "com.github.tvbox.osc.action.START_LAN_SERVER";
    private static final String ACTION_STOP = "com.github.tvbox.osc.action.STOP_LAN_SERVER";
    private static final String CHANNEL_ID = "lan_server";
    private static final int NOTIFICATION_ID = 9979;
    private static final int ANDROID_14_API_LEVEL = 34;
    private static final int FOREGROUND_SERVICE_TYPE_DATA_SYNC = 0x00000001;
    private static final long HEALTH_INTERVAL_SECONDS = 10L;
    private static volatile boolean serviceActive;
    private static volatile boolean cpuLockHeld;
    private static volatile boolean wifiLockHeld;
    private static volatile long serviceStartedAt;
    private static volatile long lastHealthCheckAt;
    private static volatile long lastRecoveryAt;
    private static volatile long lastNetworkChangeAt;
    private static volatile int recoveryCount;
    private static volatile int consecutiveHealthFailures;
    private static volatile String lastHealthResult = "等待检查";
    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;
    private ScheduledExecutorService healthExecutor;
    private BroadcastReceiver networkReceiver;

    public static void start(Context context) {
        Intent intent = new Intent(context, LanServerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, LanServerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        promoteToForeground();
        serviceActive = true;
        serviceStartedAt = System.currentTimeMillis();
        lastHealthResult = "服务已启动";
        registerNetworkReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Hawk.put(HawkConfig.LAN_SERVER_ENABLED, false);
            LanServerManager.get().stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!Hawk.get(HawkConfig.LAN_SERVER_ENABLED, false)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!LanServerManager.get().start()) {
            Hawk.put(HawkConfig.LAN_SERVER_ENABLED, false);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        acquireLocks();
        startHealthMonitor();
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopHealthMonitor();
        unregisterNetworkReceiver();
        releaseLocks();
        if (Hawk.get(HawkConfig.LAN_SERVER_ENABLED, false)) {
            LanServerManager.get().stopPreservingSessions();
        } else {
            LanServerManager.get().stop();
        }
        serviceActive = false;
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent stopIntent = new Intent(this, LanServerService.class).setAction(ACTION_STOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, pendingFlags);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String address = LanServerManager.get().getAddress();
        String detail = address.isEmpty()
                ? "后台网络保活中 · 端口 " + LanServerManager.PORT
                : "后台网络保活中 · " + address;
        return builder
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("TVBox 局域网服务")
                .setContentText(detail)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(0, "停止服务", stopPendingIntent)
                .build();
    }

    private void promoteToForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= ANDROID_14_API_LEVEL) {
            startForeground(NOTIFICATION_ID, notification,
                    FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        if (!serviceActive) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "局域网服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持 TVBox 浏览器访问服务在后台运行");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startHealthMonitor() {
        if (healthExecutor != null && !healthExecutor.isShutdown()) return;
        healthExecutor = Executors.newSingleThreadScheduledExecutor();
        healthExecutor.scheduleWithFixedDelay(
                this::runHealthCheck, 2L, HEALTH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHealthMonitor() {
        if (healthExecutor == null) return;
        healthExecutor.shutdownNow();
        healthExecutor = null;
    }

    private void runHealthCheck() {
        if (!Hawk.get(HawkConfig.LAN_SERVER_ENABLED, false)) return;
        acquireLocks();
        boolean responsive = LanServerManager.get().isRunning() && probeLocalServer();
        lastHealthCheckAt = System.currentTimeMillis();
        if (responsive) {
            consecutiveHealthFailures = 0;
            lastHealthResult = "正常";
            return;
        }

        consecutiveHealthFailures++;
        lastHealthResult = "连续失败 " + consecutiveHealthFailures + " 次";
        if (consecutiveHealthFailures < 2) return;

        boolean recovered = LanServerManager.get().restart();
        lastRecoveryAt = System.currentTimeMillis();
        if (recovered) {
            recoveryCount++;
            consecutiveHealthFailures = 0;
            lastHealthResult = "已自动恢复";
            updateNotification();
        } else {
            lastHealthResult = "自动恢复失败，等待重试";
        }
    }

    private boolean probeLocalServer() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", LanServerManager.PORT), 1500);
            socket.setSoTimeout(1500);
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            output.write(("GET /api/health HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return new BufferedInputStream(socket.getInputStream()).read() >= 0;
        } catch (Exception error) {
            LOG.e("LAN health probe failed: " + error.getMessage());
            return false;
        }
    }

    private void registerNetworkReceiver() {
        if (networkReceiver != null) return;
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                lastNetworkChangeAt = System.currentTimeMillis();
                updateNotification();
                ScheduledExecutorService executor = healthExecutor;
                if (executor != null && !executor.isShutdown()) executor.execute(LanServerService.this::runHealthCheck);
            }
        };
        registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    private void unregisterNetworkReceiver() {
        if (networkReceiver == null) return;
        try {
            unregisterReceiver(networkReceiver);
        } catch (Exception ignored) {
        }
        networkReceiver = null;
    }

    private synchronized void acquireLocks() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && (cpuWakeLock == null || !cpuWakeLock.isHeld())) {
                cpuWakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":lan-server");
                cpuWakeLock.setReferenceCounted(false);
                cpuWakeLock.acquire();
            }
            cpuLockHeld = cpuWakeLock != null && cpuWakeLock.isHeld();

            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, getPackageName() + ":lan-server");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
            wifiLockHeld = wifiLock != null && wifiLock.isHeld();
        } catch (Exception error) {
            LOG.e("LAN keep-alive lock failed: " + error.getMessage());
            cpuLockHeld = cpuWakeLock != null && cpuWakeLock.isHeld();
            wifiLockHeld = wifiLock != null && wifiLock.isHeld();
        }
    }

    private synchronized void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release();
        } catch (Exception error) {
            LOG.e("LAN keep-alive lock release failed: " + error.getMessage());
        } finally {
            wifiLock = null;
            cpuWakeLock = null;
            wifiLockHeld = false;
            cpuLockHeld = false;
        }
    }

    public static Status getStatus() {
        return new Status(serviceActive, cpuLockHeld, wifiLockHeld, serviceStartedAt,
                lastHealthCheckAt, lastRecoveryAt, lastNetworkChangeAt, recoveryCount,
                consecutiveHealthFailures, lastHealthResult);
    }

    static JSONObject diagnostics() throws Exception {
        Status status = getStatus();
        return new JSONObject()
                .put("foregroundService", status.serviceActive)
                .put("cpuWakeLock", status.cpuLockHeld)
                .put("wifiLock", status.wifiLockHeld)
                .put("serviceStartedAt", status.serviceStartedAt)
                .put("lastHealthCheckAt", status.lastHealthCheckAt)
                .put("lastRecoveryAt", status.lastRecoveryAt)
                .put("lastNetworkChangeAt", status.lastNetworkChangeAt)
                .put("recoveryCount", status.recoveryCount)
                .put("consecutiveHealthFailures", status.consecutiveHealthFailures)
                .put("health", status.lastHealthResult);
    }

    public static final class Status {
        public final boolean serviceActive;
        public final boolean cpuLockHeld;
        public final boolean wifiLockHeld;
        public final long serviceStartedAt;
        public final long lastHealthCheckAt;
        public final long lastRecoveryAt;
        public final long lastNetworkChangeAt;
        public final int recoveryCount;
        public final int consecutiveHealthFailures;
        public final String lastHealthResult;

        Status(boolean serviceActive, boolean cpuLockHeld, boolean wifiLockHeld,
               long serviceStartedAt, long lastHealthCheckAt, long lastRecoveryAt,
               long lastNetworkChangeAt, int recoveryCount, int consecutiveHealthFailures,
               String lastHealthResult) {
            this.serviceActive = serviceActive;
            this.cpuLockHeld = cpuLockHeld;
            this.wifiLockHeld = wifiLockHeld;
            this.serviceStartedAt = serviceStartedAt;
            this.lastHealthCheckAt = lastHealthCheckAt;
            this.lastRecoveryAt = lastRecoveryAt;
            this.lastNetworkChangeAt = lastNetworkChangeAt;
            this.recoveryCount = recoveryCount;
            this.consecutiveHealthFailures = consecutiveHealthFailures;
            this.lastHealthResult = lastHealthResult;
        }
    }
}
