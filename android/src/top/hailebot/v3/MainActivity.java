package top.hailebot.v3;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final String PERM_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    private static final String PERM_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION";
    private static final int REQ_LOCATION_PERM = 102;
    private WebView webView;
    private volatile File pendingInstallApk = null;
    private final AtomicBoolean otaDownloading = new AtomicBoolean(false);
    private volatile long lastExternalOpenAt = 0L;
    private volatile boolean pendingNativeLocate = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        int port = HaileBotService.ensureRuntimeStarted(this);
        boolean sysDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        applySystemBarAppearance(sysDark);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor(sysDark ? "#0b101b" : "#f3f5f9"));
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setTextZoom(100);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.addJavascriptInterface(new NativeBridge(), "HaileNative");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (callback != null) {
                    callback.invoke(origin, hasLocationPermission(), false);
                }
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url != null && (url.endsWith(".apk") || "application/vnd.android.package-archive".equalsIgnoreCase(mimetype))) {
                startApkDownloadAndInstall(url);
            } else {
                openUrlInBrowser(url);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    Uri u = request.getUrl();
                    String scheme = u.getScheme();
                    if ("weixin".equalsIgnoreCase(scheme) || "alipays".equalsIgnoreCase(scheme)) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, u);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        } catch (Exception ignored) {}
                        return true;
                    }
                    String path = u.getPath();
                    if (path != null && path.endsWith(".apk")) {
                        startApkDownloadAndInstall(u.toString());
                        return true;
                    }
                }
                return false;
            }
        });

        setContentView(webView);
        webView.loadUrl("http://127.0.0.1:" + port + "/?v=" + HaileDomain.APP_VERSION_CODE);
        maybeRequestPermissions();
        handleJumpPayIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        File pending = pendingInstallApk;
        if (pending != null && pending.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
                pendingInstallApk = null;
                triggerSystemPackageInstaller(pending);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleJumpPayIntent(intent);
    }

    private void handleJumpPayIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(HaileBotService.EXTRA_JUMP_PAY, false)) {
            String hint = intent.getStringExtra(HaileBotService.EXTRA_JUMP_DEVICE);
            String orderNo = intent.getStringExtra(HaileBotService.EXTRA_JUMP_ORDER_NO);
            intent.removeExtra(HaileBotService.EXTRA_JUMP_PAY);
            launchHailePay(hint, "auto", orderNo);
        }
    }

    private void notifyJsOtaProgress(final int pct, final String state, final String message) {
        runOnUiThread(() -> {
            if (webView == null) return;
            try {
                String js = "window.__onHaileOtaProgress && window.__onHaileOtaProgress("
                    + pct + ","
                    + JSONObject.quote(state != null ? state : "") + ","
                    + JSONObject.quote(message != null ? message : "") + ");";
                webView.evaluateJavascript(js, null);
            } catch (Exception ignored) {}
        });
    }

    private void triggerSystemPackageInstaller(final File apkFile) {
        runOnUiThread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                    pendingInstallApk = apkFile;
                    notifyJsOtaProgress(100, "permission_needed", "请授权应用内安装权限");
                    Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                    permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(permIntent);
                    return;
                }
                Uri apkUri = HaileFileProvider.uriForOtaFile(MainActivity.this, apkFile);
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(apkUri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                notifyJsOtaProgress(100, "installing", "");
                startActivity(install);
            } catch (Exception e) {
                notifyJsOtaProgress(0, "error", "无法拉起系统安装器，请点击下方改用系统浏览器下载");
            }
        });
    }

    private void openUrlInBrowser(final String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastExternalOpenAt < 3500L) {
            return;
        }
        lastExternalOpenAt = now;
        final String cleanUrl = rawUrl.trim().endsWith("/haile-bot-v3.apk")
            ? HaileDomain.OTA_DEFAULT_APK_URL
            : rawUrl.trim();
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {}
        });
    }

    private void cleanOtaCacheDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f != null && f.isFile()) {
                try { f.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private void startApkDownloadAndInstall(final String targetUrl) {
        if (!otaDownloading.compareAndSet(false, true)) {
            return;
        }
        final String downloadUrl = HaileDomain.normalizeOtaApkUrl(targetUrl);
        Thread worker = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                notifyJsOtaProgress(3, "downloading", "");
                String currentUrl = downloadUrl + (downloadUrl.contains("?") ? "&" : "?") + "_t=" + System.currentTimeMillis();
                for (int redirects = 0; redirects < 4; redirects++) {
                    conn = (HttpURLConnection) new URL(currentUrl).openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("Cache-Control", "no-cache, no-store");
                    int code = conn.getResponseCode();
                    if (code == 301 || code == 302 || code == 307 || code == 308) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (loc == null || loc.isEmpty()) break;
                        currentUrl = loc;
                        continue;
                    }
                    break;
                }
                if (conn == null || conn.getResponseCode() != 200) {
                    throw new RuntimeException("HTTP " + (conn != null ? conn.getResponseCode() : -1));
                }

                int totalLen = conn.getContentLength();
                File dir = new File(getCacheDir(), "ota");
                if (!dir.exists()) dir.mkdirs();
                pendingInstallApk = null;
                cleanOtaCacheDir(dir);

                File tmpFile = new File(dir, "HaileBot-v4.apk.part");
                File outFile = new File(dir, "HaileBot-v4.apk");

                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(tmpFile)) {
                    byte[] buf = new byte[8192];
                    int readBytes = 0;
                    int lastPct = 3;
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        readBytes += n;
                        if (totalLen > 0) {
                            int pct = Math.min(99, Math.max(5, (int) ((readBytes * 100L) / totalLen)));
                            if (pct >= lastPct + 4) {
                                lastPct = pct;
                                notifyJsOtaProgress(pct, "downloading", "");
                            }
                        }
                    }
                    fos.flush();
                }

                // Verify APK ZIP magic header (PK\x03\x04)
                byte[] magic = new byte[4];
                try (FileInputStream fis = new FileInputStream(tmpFile)) {
                    if (fis.read(magic) != 4 || magic[0] != 0x50 || magic[1] != 0x4B || magic[2] != 0x03 || magic[3] != 0x04) {
                        throw new RuntimeException("安装包校验失败");
                    }
                }
                if (outFile.exists()) outFile.delete();
                if (!tmpFile.renameTo(outFile)) {
                    throw new RuntimeException("保存安装包失败");
                }
                triggerSystemPackageInstaller(outFile);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "网络波动";
                notifyJsOtaProgress(0, "error", "应用内下载受阻（" + msg + "），请点击重试或使用下方浏览器下载");
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
                otaDownloading.set(false);
            }
        }, "haile-ota-downloader");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean isPackageInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        PackageManager pm = getPackageManager();
        if (pm == null) return false;
        try {
            return pm.getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String launchHailePay(String deviceHint, String preferMode, String orderNo) {
        PackageManager pm = getPackageManager();
        String mode = preferMode != null ? preferMode.trim().toLowerCase() : "auto";

        if ("alipay".equals(mode)) {
            try {
                String aliScheme = HaileDomain.buildAlipayCashierScheme(orderNo);
                Intent aliIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(aliScheme));
                aliIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(() -> {
                    try {
                        startActivity(aliIntent);
                    } catch (Exception e) {
                        try {
                            Intent aliApp = pm != null ? pm.getLaunchIntentForPackage(HaileDomain.ALIPAY_APP_PACKAGE) : null;
                            if (aliApp != null) {
                                aliApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(aliApp);
                            }
                        } catch (Exception ignored2) {}
                    }
                });
                return "alipay";
            } catch (Exception ignored) {}
        }

        if ("app".equals(mode) || "auto".equals(mode)) {
            try {
                Intent appIntent = pm != null ? pm.getLaunchIntentForPackage(HaileDomain.HAILE_APP_PACKAGE) : null;
                if (appIntent != null) {
                    appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    runOnUiThread(() -> {
                        try {
                            startActivity(appIntent);
                        } catch (Exception ignored) {
                        }
                    });
                    return "app";
                }
            } catch (Exception ignored) {
            }
        }

        try {
            String scheme = HaileDomain.buildWechatMiniScheme(deviceHint, true);
            Intent wxMini = new Intent(Intent.ACTION_VIEW, Uri.parse(scheme));
            wxMini.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pm == null || wxMini.resolveActivity(pm) != null || pm.getLaunchIntentForPackage(HaileDomain.WECHAT_APP_PACKAGE) != null) {
                runOnUiThread(() -> {
                    try {
                        startActivity(wxMini);
                    } catch (Exception e) {
                        try {
                            Intent wxApp = pm != null ? pm.getLaunchIntentForPackage(HaileDomain.WECHAT_APP_PACKAGE) : null;
                            if (wxApp != null) {
                                wxApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(wxApp);
                            }
                        } catch (Exception ignored2) {
                        }
                    }
                });
                return "weapp";
            }
        } catch (Exception ignored) {
        }

        try {
            Intent wxApp = pm != null ? pm.getLaunchIntentForPackage(HaileDomain.WECHAT_APP_PACKAGE) : null;
            if (wxApp != null) {
                wxApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(() -> {
                    try {
                        startActivity(wxApp);
                    } catch (Exception ignored) {
                    }
                });
                return "wechat";
            }
        } catch (Exception ignored) {
        }
        return "none";
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(PERM_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(PERM_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void notifyJsLocation(boolean ok, double gcjLng, double gcjLat, String message) {
        runOnUiThread(() -> {
            if (webView == null) return;
            try {
                JSONObject payload = new JSONObject();
                payload.put("ok", ok);
                if (ok) {
                    payload.put("lng", gcjLng);
                    payload.put("lat", gcjLat);
                    payload.put("coordType", "gcj02");
                } else {
                    payload.put("message", message != null ? message : "定位失败");
                }
                String js = "window.__onHaileLocation && window.__onHaileLocation(" + payload.toString() + ");";
                webView.evaluateJavascript(js, null);
            } catch (Exception ignored) {}
        });
    }

    private void performNativeLocationLookup() {
        runOnUiThread(() -> {
            if (!hasLocationPermission()) {
                pendingNativeLocate = true;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{PERM_FINE_LOCATION, PERM_COARSE_LOCATION}, REQ_LOCATION_PERM);
                }
                return;
            }
            try {
                final LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm == null) {
                    notifyJsLocation(false, 0, 0, "系统定位服务不可用");
                    return;
                }
                Location best = null;
                long nowMs = System.currentTimeMillis();
                for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                    try {
                        if (!lm.isProviderEnabled(prov) && !LocationManager.PASSIVE_PROVIDER.equals(prov)) continue;
                        Location loc = lm.getLastKnownLocation(prov);
                        if (loc != null && (nowMs - loc.getTime() < 600_000L)) {
                            if (best == null || loc.getAccuracy() < best.getAccuracy()) {
                                best = loc;
                            }
                        }
                    } catch (SecurityException ignored) {}
                }
                if (best != null) {
                    double[] gcj = HaileDomain.wgs84ToGcj02(best.getLongitude(), best.getLatitude());
                    notifyJsLocation(true, gcj[0], gcj[1], "");
                    return;
                }

                final AtomicBoolean delivered = new AtomicBoolean(false);
                final Handler handler = new Handler(Looper.getMainLooper());
                final LocationListener listener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location loc) {
                        if (loc != null && delivered.compareAndSet(false, true)) {
                            try { lm.removeUpdates(this); } catch (Exception ignored) {}
                            double[] gcj = HaileDomain.wgs84ToGcj02(loc.getLongitude(), loc.getLatitude());
                            notifyJsLocation(true, gcj[0], gcj[1], "");
                        }
                    }
                    @Override public void onStatusChanged(String p, int s, Bundle e) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                };

                boolean requested = false;
                for (String prov : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                    try {
                        if (lm.isProviderEnabled(prov)) {
                            lm.requestLocationUpdates(prov, 0L, 0f, listener, Looper.getMainLooper());
                            requested = true;
                        }
                    } catch (Exception ignored) {}
                }
                if (!requested) {
                    notifyJsLocation(false, 0, 0, "请先开启手机定位开关，或直接在上方搜索学校名称");
                    return;
                }
                handler.postDelayed(() -> {
                    if (delivered.compareAndSet(false, true)) {
                        try { lm.removeUpdates(listener); } catch (Exception ignored) {}
                        notifyJsLocation(false, 0, 0, "室内 GPS 信号较弱，可直接拖动地图或搜索学校名称");
                    }
                }, 6500L);
            } catch (Exception e) {
                notifyJsLocation(false, 0, 0, "定位请求失败，请直接搜索学校名称");
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION_PERM && pendingNativeLocate) {
            pendingNativeLocate = false;
            if (hasLocationPermission()) {
                performNativeLocationLookup();
            } else {
                notifyJsLocation(false, 0, 0, "未授予位置权限，可直接拖动地图或输入学校名称搜索");
            }
        }
    }

    private void maybeRequestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(PERM_POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{PERM_POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private void applySystemBarAppearance(boolean isDark) {
        Window window = getWindow();
        if (window == null) return;
        int bg = Color.parseColor(isDark ? "#0b101b" : "#f3f5f9");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(bg);
            window.setNavigationBarColor(bg);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (isDark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                "(function(){var s=document.querySelector('.sheet-wrap.open');if(s){s.classList.remove('open');s.setAttribute('aria-hidden','true');return 'closed';}return '';})()",
                value -> {
                    if (value == null || !value.contains("closed")) {
                        if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            MainActivity.super.onBackPressed();
                        }
                    }
                }
            );
            return;
        }
        super.onBackPressed();
    }

    public final class NativeBridge {
        @JavascriptInterface
        public boolean isNativeApp() {
            return true;
        }

        @JavascriptInterface
        public int getVersionCode() {
            return HaileDomain.APP_VERSION_CODE;
        }

        @JavascriptInterface
        public String getVersionName() {
            return HaileDomain.APP_VERSION_NAME;
        }

        @JavascriptInterface
        public String getPayChannelsJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("haileApp", isPackageInstalled(HaileDomain.HAILE_APP_PACKAGE));
                obj.put("wechat", isPackageInstalled(HaileDomain.WECHAT_APP_PACKAGE));
                obj.put("alipay", isPackageInstalled(HaileDomain.ALIPAY_APP_PACKAGE));
            } catch (Exception ignored) {}
            return obj.toString();
        }

        @JavascriptInterface
        public boolean areNotificationsEnabled() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null && nm.areNotificationsEnabled();
            }
            return true;
        }

        @JavascriptInterface
        public boolean isNotificationsMuted() {
            return HaileBotService.isAlertsMuted(MainActivity.this);
        }

        @JavascriptInterface
        public void setNotificationsMuted(boolean muted) {
            HaileBotService.setAlertsMuted(MainActivity.this, muted);
        }

        @JavascriptInterface
        public void setNotifyEnabled(boolean enabled) {
            HaileBotService.setAlertsMuted(MainActivity.this, !enabled);
        }

        @JavascriptInterface
        public String openHailePay(final String deviceHint, final String preferMode) {
            return launchHailePay(deviceHint, preferMode, "");
        }

        @JavascriptInterface
        public String openHailePayV4(final String deviceHint, final String preferMode, final String orderNo) {
            return launchHailePay(deviceHint, preferMode, orderNo);
        }

        @JavascriptInterface
        public void downloadAndInstallApk(final String url) {
            startApkDownloadAndInstall(url);
        }

        @JavascriptInterface
        public void openExternalUrl(final String url) {
            openUrlInBrowser(url);
        }

        @JavascriptInterface
        public void requestDeviceLocation() {
            performNativeLocationLookup();
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(PERM_POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{PERM_POST_NOTIFICATIONS}, 101);
                    return;
                }
                try {
                    Intent intent = new Intent();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    } else {
                        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.fromParts("package", getPackageName(), null));
                    }
                    startActivity(intent);
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void requestIgnoreBatteryOptimizations() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void setDarkTheme(final boolean isDark) {
            runOnUiThread(() -> {
                applySystemBarAppearance(isDark);
                if (webView != null) {
                    webView.setBackgroundColor(Color.parseColor(isDark ? "#0b101b" : "#f3f5f9"));
                }
            });
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            if (ms <= 0 || HaileBotService.isAlertsMuted(MainActivity.this)) {
                return;
            }
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    int duration = Math.max(8, Math.min(200, ms));
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(duration);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }
}
