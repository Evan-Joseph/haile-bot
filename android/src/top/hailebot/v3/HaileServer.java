package top.hailebot.v3;

import android.content.res.AssetManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class HaileServer {
    private final HaileEngine engine;
    private final AssetManager assets;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private int boundPort = 18010;

    public HaileServer(HaileEngine engine, AssetManager assets) {
        this.engine = engine;
        this.assets = assets;
    }

    public synchronized int start() throws IOException {
        if (running && serverSocket != null && !serverSocket.isClosed()) {
            return boundPort;
        }
        try {
            serverSocket = new ServerSocket(18010, 64, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            serverSocket = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        }
        boundPort = serverSocket.getLocalPort();
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "haile-http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return boundPort;
    }

    public int getPort() {
        return boundPort;
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private static String readHeaderLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int prev = -1;
        int cur;
        while ((cur = in.read()) != -1) {
            if (prev == '\r' && cur == '\n') {
                byte[] bytes = bos.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.UTF_8);
            }
            bos.write(cur);
            prev = cur;
            if (bos.size() > 8192) break;
        }
        if (bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq > 0) {
                    String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    params.put(k, v);
                } else if (!pair.isEmpty()) {
                    params.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) {}
        }
        return params;
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(30_000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine = readHeaderLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                socket.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                socket.close();
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String fullTarget = parts[1];
            String path = fullTarget;
            String query = "";
            int qIdx = fullTarget.indexOf('?');
            if (qIdx >= 0) {
                path = fullTarget.substring(0, qIdx);
                query = fullTarget.substring(qIdx + 1);
            }
            Map<String, String> queryParams = parseQuery(query);

            int contentLength = 0;
            String line;
            while ((line = readHeaderLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String v = line.substring(colon + 1).trim();
                    if ("content-length".equals(k)) {
                        try {
                            contentLength = Integer.parseInt(v);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            String bodyText = "";
            if (contentLength > 0 && contentLength < 1024 * 1024) {
                byte[] bodyBytes = new byte[contentLength];
                int offset = 0;
                while (offset < contentLength) {
                    int n = in.read(bodyBytes, offset, contentLength - offset);
                    if (n == -1) break;
                    offset += n;
                }
                bodyText = new String(bodyBytes, 0, offset, StandardCharsets.UTF_8);
            }

            if ("GET".equals(method) && "/api/events".equals(path)) {
                handleSse(socket, out);
                return;
            }

            if (path.startsWith("/api/")) {
                handleApi(method, path, queryParams, bodyText, out);
            } else {
                handleStatic(path, out);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void handleSse(Socket socket, OutputStream out) throws IOException {
        socket.setSoTimeout(0);
        String headers = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: text/event-stream; charset=utf-8\r\n"
            + "Cache-Control: no-cache\r\n"
            + "Connection: keep-alive\r\n"
            + "X-Accel-Buffering: no\r\n\r\n"
            + ": connected\n\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.flush();

        BlockingQueue<JSONObject> sub = engine.subscribe();
        try {
            while (running && !socket.isClosed()) {
                JSONObject ev = sub.poll(15, TimeUnit.SECONDS);
                if (ev != null) {
                    String msg = "data: " + ev.toString() + "\n\n";
                    out.write(msg.getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            engine.unsubscribe(sub);
        }
    }

    private void handleApi(String method, String path, Map<String, String> query, String bodyText, OutputStream out) throws IOException {
        try {
            String ownerId = query.getOrDefault("ownerId", "default");
            if ("GET".equals(method) && "/api/health".equals(path)) {
                JSONObject res = new JSONObject();
                res.put("status", "ok");
                res.put("version", HaileDomain.APP_VERSION_NAME);
                res.put("versionCode", HaileDomain.APP_VERSION_CODE);
                writeJson(out, 200, res.toString());
                return;
            }
            if ("GET".equals(method) && "/api/ota".equals(path)) {
                JSONObject res = fetchOtaInfo(query);
                writeJson(out, 200, res.toString());
                return;
            }
            if ("GET".equals(method) && "/api/state".equals(path)) {
                writeJson(out, 200, engine.state(ownerId).toString());
                return;
            }
            if ("GET".equals(method) && "/api/devices".equals(path)) {
                writeJson(out, 200, engine.devices(ownerId).toString());
                return;
            }
            if ("GET".equals(method) && "/api/positions/nearby".equals(path)) {
                Double lng = null;
                Double lat = null;
                try {
                    if (query.containsKey("lng") && !query.get("lng").isEmpty()) lng = Double.parseDouble(query.get("lng"));
                    if (query.containsKey("lat") && !query.get("lat").isEmpty()) lat = Double.parseDouble(query.get("lat"));
                } catch (Exception ignored) {}
                String coordType = query.getOrDefault("coordType", "gcj02");
                String kw = query.getOrDefault("q", "");
                JSONObject res = new JSONObject();
                res.put("positions", engine.nearbyPositions(lng, lat, coordType, kw));
                writeJson(out, 200, res.toString());
                return;
            }
            if ("GET".equals(method) && "/api/positions/search".equals(path)) {
                Double lng = null;
                Double lat = null;
                try {
                    if (query.containsKey("lng") && !query.get("lng").isEmpty()) lng = Double.parseDouble(query.get("lng"));
                    if (query.containsKey("lat") && !query.get("lat").isEmpty()) lat = Double.parseDouble(query.get("lat"));
                } catch (Exception ignored) {}
                String q = query.getOrDefault("q", "");
                writeJson(out, 200, engine.searchPositions(q, ownerId, lng, lat).toString());
                return;
            }
            if ("GET".equals(method) && "/api/recent".equals(path)) {
                boolean force = "true".equalsIgnoreCase(query.getOrDefault("force", "")) || "1".equals(query.getOrDefault("force", ""));
                writeJson(out, 200, engine.recentUsage(ownerId, force).toString());
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/devices/") && path.endsWith("/modes")) {
                String mid = path.substring("/api/devices/".length(), path.length() - "/modes".length());
                int goodsId = Integer.parseInt(mid);
                JSONArray modes = engine.modes(goodsId);
                JSONObject res = new JSONObject();
                res.put("modes", modes);
                writeJson(out, 200, res.toString());
                return;
            }

            JSONObject req = bodyText.isEmpty() ? new JSONObject() : new JSONObject(bodyText);
            if ("POST".equals(method) && "/api/position".equals(path)) {
                Double lng = req.has("lng") && !req.isNull("lng") ? req.optDouble("lng") : null;
                Double lat = req.has("lat") && !req.isNull("lat") ? req.optDouble("lat") : null;
                JSONObject res = engine.setPosition(
                    req.optInt("positionId", HaileDomain.POSITION_ID),
                    req.optString("positionName", ""),
                    lng,
                    lat,
                    req.optString("address", ""),
                    true
                );
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/position/resolve".equals(path)) {
                JSONObject res = engine.resolveCustomPosition(
                    req.optString("input", ""),
                    req.optString("ownerId", ownerId),
                    req.optBoolean("autoSwitch", true)
                );
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/accounts/code".equals(path)) {
                JSONObject res = engine.sendCode(req.optString("phone", ""));
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/accounts/login".equals(path)) {
                JSONObject res = engine.login(
                    req.optString("phone", ""),
                    req.optString("code", ""),
                    req.optString("ownerId", "default"),
                    req.optString("ownerName", ""),
                    req.optString("remark", "")
                );
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/accounts/enabled".equals(path)) {
                JSONObject res = engine.setAccountEnabled(
                    req.optString("phone", ""),
                    req.optBoolean("enabled", true),
                    req.optString("ownerId", "default")
                );
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/accounts/remark".equals(path)) {
                JSONObject res = engine.setAccountRemark(
                    req.optString("phone", ""),
                    req.optString("remark", ""),
                    req.optString("ownerId", "default")
                );
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/accounts/refresh_orders".equals(path)) {
                engine.refreshAllOrders(ownerId);
                JSONObject res = new JSONObject();
                res.put("success", true);
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/intents/start".equals(path)) {
                int goodsId = req.optInt("goodsId", 0);
                List<Integer> goodsIds = null;
                JSONArray idsArr = req.optJSONArray("goodsIds");
                if (idsArr != null && idsArr.length() > 0) {
                    goodsIds = new ArrayList<>();
                    for (int i = 0; i < idsArr.length(); i++) {
                        int v = idsArr.optInt(i, 0);
                        if (v > 0) goodsIds.add(v);
                    }
                }
                boolean anyWasher = req.optBoolean("anyWasher", false);
                Integer modeId = req.has("modeId") && !req.isNull("modeId") ? req.optInt("modeId") : null;
                String preferredModeName = HaileDomain.optNullableString(req, "preferredModeName");
                Integer dryerMinutes = req.has("dryerMinutes") && !req.isNull("dryerMinutes") ? req.optInt("dryerMinutes") : null;
                boolean selfClean = req.optBoolean("selfClean", false);
                String reqOwner = req.optString("ownerId", "default");
                JSONObject res = engine.startIntent(goodsId, modeId, reqOwner, dryerMinutes, selfClean, goodsIds, anyWasher, preferredModeName);
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/intents/stop".equals(path)) {
                int goodsId = req.optInt("goodsId", 0);
                boolean cancelOrder = req.optBoolean("cancelOrder", false);
                String reqOwner = req.optString("ownerId", "default");
                JSONObject res = engine.stopIntent(goodsId, cancelOrder, reqOwner);
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/orders/cancel".equals(path)) {
                JSONObject res = engine.cancelOrder(
                    req.optString("phone", ""),
                    req.optString("orderNo", ""),
                    req.optString("ownerId", "default")
                );
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/orders/verify".equals(path)) {
                JSONObject res = engine.verifyOrder(
                    req.optString("phone", ""),
                    req.optString("orderNo", ""),
                    req.optString("checkCode", ""),
                    req.optString("ownerId", "default")
                );
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/orders/resend_verify".equals(path)) {
                JSONObject res = engine.resendVerifyCode(
                    req.optString("phone", ""),
                    req.optString("orderNo", ""),
                    req.optString("ownerId", "default")
                );
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/orders/remind_pickup".equals(path)) {
                JSONObject res = engine.remindPreviousPickup(
                    req.optString("phone", ""),
                    req.optString("orderNo", ""),
                    req.optString("ownerId", "default")
                );
                writeJson(out, 200, res.toString());
                return;
            }
            if ("POST".equals(method) && "/api/orders/place_clothes".equals(path)) {
                JSONObject res = engine.confirmPlaceClothes(
                    req.optString("phone", ""),
                    req.optString("orderNo", ""),
                    req.optString("ownerId", "default")
                );
                writeJson(out, 200, res.toString());
                return;
            }

            JSONObject notFound = new JSONObject();
            notFound.put("detail", "Not Found");
            writeJson(out, 404, notFound.toString());
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("message", e.getMessage() != null ? e.getMessage() : "Internal Error");
                writeJson(out, 500, err.toString());
            } catch (Exception ignored) {}
        }
    }

    private void handleStatic(String path, OutputStream out) throws IOException {
        String clean = path;
        if ("/".equals(clean) || clean.isEmpty()) {
            clean = "/index.html";
        }
        if (clean.contains("..")) {
            byte[] msg = "Forbidden".getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nContent-Length: " + msg.length + "\r\nConnection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(msg);
            out.flush();
            return;
        }
        String assetPath = "static" + clean;
        String contentType = guessContentType(clean);
        try (InputStream is = assets.open(assetPath)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();
            String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                + "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
        } catch (IOException e) {
            byte[] msg = "Not Found".getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: " + msg.length + "\r\nConnection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(msg);
            out.flush();
        }
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private JSONObject fetchOtaInfo(Map<String, String> query) {
        JSONObject out = new JSONObject();
        try {
            out.put("localVersionCode", HaileDomain.APP_VERSION_CODE);
            out.put("localVersionName", HaileDomain.APP_VERSION_NAME);
        } catch (Exception ignored) {}

        String customUrl = query != null ? query.getOrDefault("otaUrl", "").trim() : "";
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (!customUrl.isEmpty() && (customUrl.startsWith("https://") || customUrl.startsWith("http://"))) {
            candidates.add(customUrl);
        }
        for (String url : HaileDomain.OTA_CHECK_URLS) {
            if (!candidates.contains(url)) {
                candidates.add(url);
            }
        }

        String lastErr = "";
        for (String baseUrl : candidates) {
            java.net.HttpURLConnection conn = null;
            try {
                String sep = baseUrl.contains("?") ? "&" : "?";
                conn = (java.net.HttpURLConnection) new java.net.URL(
                    baseUrl + sep + "_t=" + System.currentTimeMillis() + "&clientVersion=" + HaileDomain.APP_VERSION_CODE
                ).openConnection();
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                conn.setUseCaches(false);
                conn.setRequestProperty("Cache-Control", "no-cache, no-store");
                int status = conn.getResponseCode();
                if (status == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[2048];
                        int n;
                        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                        JSONObject remote = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                        int remoteCode = remote.optInt("versionCode", 0);
                        if (remoteCode > 0) {
                            String notes = remote.optString("releaseNotes", remote.optString("notes", ""));
                            String rawApk = HaileDomain.normalizeOtaApkUrl(remote.optString("apkUrl", HaileDomain.OTA_DEFAULT_APK_URL));
                            out.put("ok", true);
                            out.put("source", baseUrl);
                            out.put("versionCode", remoteCode);
                            out.put("versionName", remote.optString("versionName", HaileDomain.APP_VERSION_NAME));
                            out.put("apkUrl", rawApk);
                            out.put("releaseNotes", notes);
                            out.put("notes", notes);
                            out.put("hasUpdate", remoteCode > HaileDomain.APP_VERSION_CODE);
                            return out;
                        }
                    }
                } else {
                    lastErr = "HTTP " + status;
                }
            } catch (Exception e) {
                lastErr = e.getClass().getSimpleName();
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }

        try {
            out.put("ok", false);
            out.put("hasUpdate", false);
            out.put("error", lastErr.isEmpty() ? "更新源暂不可达" : "更新源暂不可达 (" + lastErr + ")");
        } catch (Exception ignored) {}
        return out;
    }

    private static void writeJson(OutputStream out, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "200 OK" : (status == 404 ? "404 Not Found" : "500 Internal Server Error");
        String header = "HTTP/1.1 " + statusText + "\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n"
            + "Content-Length: " + bytes.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }
}
