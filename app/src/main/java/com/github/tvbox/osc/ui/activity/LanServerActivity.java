package com.github.tvbox.osc.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.server.LanServerManager;
import com.github.tvbox.osc.server.LanServerService;
import com.github.tvbox.osc.util.FastClickCheckUtil;

public class LanServerActivity extends BaseActivity {
    private TextView statusText;
    private TextView addressText;
    private TextView actionText;
    private TextView securityText;
    private TextView pairCodeText;
    private TextView pairExpiryText;
    private TextView deviceCountText;
    private TextView keepAliveText;
    private final Runnable refreshSecurityClock = new Runnable() {
        @Override
        public void run() {
            refreshState();
            if (pairExpiryText != null) pairExpiryText.postDelayed(this, 2000L);
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_lan_server;
    }

    @Override
    protected void init() {
        statusText = findViewById(R.id.tvLanStatus);
        addressText = findViewById(R.id.tvLanAddress);
        actionText = findViewById(R.id.tvLanAction);
        securityText = findViewById(R.id.tvLanSecurity);
        pairCodeText = findViewById(R.id.tvLanPairCode);
        pairExpiryText = findViewById(R.id.tvLanPairExpiry);
        deviceCountText = findViewById(R.id.tvLanDeviceCount);
        keepAliveText = findViewById(R.id.tvLanKeepAlive);

        findViewById(R.id.llLanToggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                LanServerManager manager = LanServerManager.get();
                boolean enable = !manager.isRunning();
                if (!manager.setEnabled(enable)) {
                    Toast.makeText(mContext, "启动失败，端口 " + LanServerManager.PORT + " 可能被占用", Toast.LENGTH_LONG).show();
                } else if (enable) {
                    requestBatteryOptimizationExemption();
                }
                refreshState();
            }
        });
        findViewById(R.id.llLanAddress).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                copyAddress();
            }
        });
        findViewById(R.id.llLanSecurity).setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            LanServerManager manager = LanServerManager.get();
            manager.setSecure(!manager.isSecure());
            refreshState();
        });
        findViewById(R.id.llLanPairCode).setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            LanServerManager.get().regenerateCredentials();
            refreshState();
            Toast.makeText(mContext, "已生成新配对码，旧设备需重新配对", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.llLanDevices).setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            refreshState();
            Toast.makeText(mContext, "设备状态已刷新", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.llLanRevokeDevices).setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            LanServerManager.get().regenerateCredentials();
            refreshState();
            Toast.makeText(mContext, "已注销全部配对设备", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.llLanOpen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                openPage();
            }
        });
        findViewById(R.id.tvLanBack).setOnClickListener(view -> finish());
        refreshState();
        if (LanServerManager.get().isRunning()) requestBatteryOptimizationExemption();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        pairExpiryText.removeCallbacks(refreshSecurityClock);
        pairExpiryText.postDelayed(refreshSecurityClock, 2000L);
    }

    @Override
    protected void onPause() {
        if (pairExpiryText != null) pairExpiryText.removeCallbacks(refreshSecurityClock);
        super.onPause();
    }

    private void refreshState() {
        if (statusText == null) return;
        LanServerManager manager = LanServerManager.get();
        boolean running = manager.isRunning();
        statusText.setText(running ? "运行中" : "已关闭");
        actionText.setText(running ? "关闭服务" : "开启服务");
        String address = manager.getAddress();
        addressText.setText(address.isEmpty() ? "未连接局域网" : address);
        securityText.setText(manager.isSecure() ? "已开启" : "未开启");
        pairCodeText.setText(manager.isSecure() ? manager.getPairCode() : "开启访问安全后显示");
        long remaining = manager.isSecure()
                ? Math.max(0L, manager.getPairCodeExpiresAt() - System.currentTimeMillis()) : 0L;
        pairExpiryText.setText(manager.isSecure() ? "约 " + ((remaining + 59999) / 60000) + " 分钟后过期" : "");
        String deviceSummary = manager.getSessionCount() + " 台";
        if (!manager.getSessions().isEmpty()) deviceSummary += " · " + manager.getSessions().get(0).device;
        deviceCountText.setText(deviceSummary + "（点击刷新）");
        refreshKeepAliveState();
        findViewById(R.id.llLanPairCode).setEnabled(manager.isSecure());
        findViewById(R.id.llLanDevices).setEnabled(manager.isSecure());
        findViewById(R.id.llLanRevokeDevices).setEnabled(manager.isSecure() && manager.getSessionCount() > 0);
        findViewById(R.id.llLanOpen).setEnabled(running && !address.isEmpty());
        findViewById(R.id.llLanAddress).setEnabled(running && !address.isEmpty());
    }

    private void refreshKeepAliveState() {
        if (keepAliveText == null) return;
        LanServerService.Status status = LanServerService.getStatus();
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batteryExempt = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName()));
        String checkedAgo = status.lastHealthCheckAt == 0
                ? "待首次检查"
                : Math.max(0L, (System.currentTimeMillis() - status.lastHealthCheckAt) / 1000L) + " 秒前";
        keepAliveText.setText(
                "前台服务：" + state(status.serviceActive)
                        + "  CPU 锁：" + state(status.cpuLockHeld)
                        + "  WiFi 锁：" + state(status.wifiLockHeld)
                        + "\n电池优化：" + (batteryExempt ? "已豁免" : "未豁免")
                        + "  健康检查：" + status.lastHealthResult + "（" + checkedAgo + "）"
                        + "\n自动恢复：" + status.recoveryCount + " 次"
                        + "  连续失败：" + status.consecutiveHealthFailures + " 次");
    }

    private String state(boolean enabled) {
        return enabled ? "正常" : "未运行";
    }

    private void copyAddress() {
        String address = LanServerManager.get().getAddress();
        if (address.isEmpty() || !LanServerManager.get().isRunning()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("TVBox LAN Server", address));
            Toast.makeText(mContext, "地址已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void openPage() {
        String address = LanServerManager.get().getAddress();
        if (address.isEmpty() || !LanServerManager.get().isRunning()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (Exception error) {
            Toast.makeText(mContext, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (manager == null || manager.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception error) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(mContext, "请在系统设置中允许后台运行", Toast.LENGTH_LONG).show();
            }
        }
    }
}
