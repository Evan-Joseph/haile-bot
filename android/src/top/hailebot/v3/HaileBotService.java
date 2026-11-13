package top.hailebot.v3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;

public final class HaileBotService extends Service implements HaileEngine.StatusListener {
    public static final String EXTRA_JUMP_PAY = "extra_jump_pay";
    public static final String EXTRA_JUMP_DEVICE = "extra_jump_device";
    public static final String EXTRA_JUMP_ORDER_NO = "extra_jump_order_no";
    private static final String PREFS_NAME = "haile_prefs";
    private static final String KEY_ALERTS_MUTED = "alerts_muted";
    private static final String CHANNEL_ONGOING = "haile_ongoing_service";
    private static final String CHANNEL_ALERT = "haile_order_alerts";
    private static final int NOTIF_ID_ONGOING = 1001;

    private static volatile HaileEngine sharedEngine;
    private static volatile HaileServer sharedServer;
    private static volatile int serverPort = 18010;
    private static final Object initLock = new Object();

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String lastSummary = "";
    private long lastTargetBucket = 0L;

    public static boolean isAlertsMuted(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ALERTS_MUTED, false);
    }

    public static void setAlertsMuted(Context context, boolean muted) {
        Context appCtx = context.getApplicationContext();
        SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ALERTS_MUTED, muted).apply();
        if (muted) {
            try {
                Vibrator v = (Vibrator) appCtx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    v.cancel();
                }
            } catch (Exception ignored) {
            }
            try {
                NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.service.notification.StatusBarNotification[] active = nm.getActiveNotifications();
                    if (active != null) {
                        for (android.service.notification.StatusBarNotification sbn : active) {
                            if (sbn != null && sbn.getId() != NOTIF_ID_ONGOING) {
                                nm.cancel(sbn.getId());
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static int ensureRuntimeStarted(Context context) {
        Context appCtx = context.getApplicationContext();
        synchronized (initLock) {
            if (sharedEngine == null) {
                sharedEngine = new HaileEngine(appCtx.getFilesDir());
                sharedEngine.startBackgroundLoops();
            }
            if (sharedServer == null) {
                sharedServer = new HaileServer(sharedEngine, appCtx.getAssets());
                try {
                    serverPort = sharedServer.start();
                } catch (Exception ignored) {}
            }
        }
        try {
            Intent svc = new Intent(appCtx, HaileBotService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(svc);
            } else {
                appCtx.startService(svc);
            }
        } catch (Exception ignored) {
        }
        return serverPort;
    }

    public static int getServerPort() {
        return serverPort;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannels();
        ensureRuntimeStarted(this);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HaileBot:PreemptWakeLock");
            wakeLock.setReferenceCounted(false);
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int lockMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(lockMode, "HaileBot:PreemptWifiLock");
            wifiLock.setReferenceCounted(false);
        }
        Notification ongoing = buildOngoingNotification("本地守护就绪 · 随时可抢占", 0L);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID_ONGOING, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID_ONGOING, ongoing);
        }
        if (sharedEngine != null) {
            sharedEngine.setStatusListener(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (sharedEngine != null) {
            onStateChanged(
                sharedEngine.activeIntentCount(),
                sharedEngine.buildOngoingSummary(),
                sharedEngine.ongoingTargetEpochMillis()
            );
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    private void acquireLocks() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(4 * 3600 * 1000L);
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel ongoing = new NotificationChannel(
            CHANNEL_ONGOING,
            "后台抢占与倒计时守护",
            NotificationManager.IMPORTANCE_LOW
        );
        ongoing.setDescription("保持息屏后台轮询、支付倒计时与收衣提醒不断连");
        ongoing.setShowBadge(false);
        ongoing.enableVibration(false);
        ongoing.setSound(null, null);
        nm.createNotificationChannel(ongoing);

        NotificationChannel alert = new NotificationChannel(
            CHANNEL_ALERT,
            "锁单与收衣提醒",
            NotificationManager.IMPORTANCE_HIGH
        );
        alert.setDescription("抢到洗衣机或洗烘完成时弹出提醒");
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 260, 120, 260});
        nm.createNotificationChannel(alert);
    }

    private PendingIntent contentPendingIntent() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 0, openIntent, flags);
    }

    private PendingIntent jumpPayPendingIntent(String deviceHint, String orderNo) {
        Intent payIntent = new Intent(this, MainActivity.class);
        payIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        payIntent.putExtra(EXTRA_JUMP_PAY, true);
        payIntent.putExtra(EXTRA_JUMP_DEVICE, deviceHint != null ? deviceHint : "");
        payIntent.putExtra(EXTRA_JUMP_ORDER_NO, orderNo != null ? orderNo : "");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 901, payIntent, flags);
    }

    private Notification buildOngoingNotification(String summary, long countdownTargetMillis) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ONGOING)
            : new Notification.Builder(this);
        String posTitle = sharedEngine != null ? sharedEngine.getPositionName() : HaileDomain.DEFAULT_POSITION_NAME;
        builder.setContentTitle(posTitle + " · 海乐抢占助手")
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent());

        long now = System.currentTimeMillis();
        if (countdownTargetMillis > now + 2000L) {
            builder.setWhen(countdownTargetMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true);
        } else {
            builder.setShowWhen(false)
                .setUsesChronometer(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    @Override
    public void onStateChanged(int activeIntentCount, String ongoingSummary, long countdownTargetMillis) {
        if (activeIntentCount > 0) {
            acquireLocks();
        } else {
            releaseLocks();
        }
        long targetBucket = countdownTargetMillis > 0 ? (countdownTargetMillis / 5000L) : 0L;
        if (ongoingSummary != null && (!ongoingSummary.equals(lastSummary) || targetBucket != lastTargetBucket)) {
            lastSummary = ongoingSummary;
            lastTargetBucket = targetBucket;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID_ONGOING, buildOngoingNotification(ongoingSummary, countdownTargetMillis));
            }
        }
    }

    @Override
    public void onOrderAlert(String keyId, String stage, String title, String body) {
        if (isAlertsMuted(this)) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ALERT)
            : new Notification.Builder(this);
        PendingIntent payPending = jumpPayPendingIntent(title, keyId);
        boolean actionable = "awaiting_payment".equals(stage) || "reserve_waiting".equals(stage);
        builder.setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(actionable ? payPending : contentPendingIntent());
        if (actionable) {
            builder.addAction(android.R.drawable.ic_menu_send, "去小程序/App支付", payPending);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_HIGH);
            builder.setVibrate(new long[]{0, 260, 120, 260});
        }
        int id = 2000 + Math.abs((keyId != null ? keyId : title).hashCode() % 5000);
        nm.notify(id, builder.build());
    }
}
