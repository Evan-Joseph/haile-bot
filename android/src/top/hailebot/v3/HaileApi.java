package top.hailebot.v3;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class HaileApi {
    private static final int TIMEOUT_MS = 5000;
    private static final long DNS_CACHE_TTL_MS = 600_000L;
    private static volatile String cachedIp = "";
    private static volatile long cachedIpAt = 0L;

    private final Object clockLock = new Object();
    private double clockOffsetSeconds = 0.0;
    private int clockSamples = 0;

    public HaileApi() {}

    public double getClockOffsetSeconds() {
        synchronized (clockLock) {
            return clockOffsetSeconds;
        }
    }

    private void observeServerDate(long serverDateMillis, long rttMillis) {
        if (serverDateMillis <= 0L) return;
        double serverSec = (serverDateMillis + Math.max(0L, rttMillis) * 0.5) / 1000.0;
        double localSec = System.currentTimeMillis() / 1000.0;
        double sample = serverSec - localSec;
        if (Math.abs(sample) > 600.0) return;
        synchronized (clockLock) {
            if (clockSamples == 0) {
                clockOffsetSeconds = sample;
            } else {
                clockOffsetSeconds = clockOffsetSeconds * 0.7 + sample * 0.3;
            }
            clockSamples++;
        }
    }

    private static String resolveUpstreamIp() {
        long now = System.currentTimeMillis();
        if (!cachedIp.isEmpty() && now - cachedIpAt < DNS_CACHE_TTL_MS) {
            return cachedIp;
        }
        synchronized (HaileApi.class) {
            now = System.currentTimeMillis();
            if (!cachedIp.isEmpty() && now - cachedIpAt < DNS_CACHE_TTL_MS) {
                return cachedIp;
            }
            String resolved = "";
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://223.5.5.5/resolve?name=" + HaileDomain.UPSTREAM_HOST + "&type=A");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                if (conn.getResponseCode() == 200) {
                    String body = readStream(conn.getInputStream(), conn.getContentEncoding());
                    JSONObject json = new JSONObject(body);
                    JSONArray answers = json.optJSONArray("Answer");
                    if (answers != null) {
                        for (int i = 0; i < answers.length(); i++) {
                            JSONObject ans = answers.optJSONObject(i);
                            if (ans != null && ans.optInt("type") == 1) {
                                String data = ans.optString("data", "").trim();
                                if (data.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) {
                                    resolved = data;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (!resolved.isEmpty()) {
                cachedIp = resolved;
            } else if (cachedIp.isEmpty()) {
                cachedIp = HaileDomain.UPSTREAM_FALLBACK_IP;
            }
            cachedIpAt = System.currentTimeMillis();
            return cachedIp;
        }
    }

    private static SSLSocketFactory createSniSocketFactory(final String sniHost, final String targetIp) {
        final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
        return new SSLSocketFactory() {
            private Socket configureSni(Socket socket) {
                if (socket instanceof SSLSocket) {
                    SSLSocket ssl = (SSLSocket) socket;
                    SSLParameters params = ssl.getSSLParameters();
                    params.setServerNames(Collections.singletonList(new SNIHostName(sniHost)));
                    ssl.setSSLParameters(params);
                }
                return socket;
            }

            @Override
            public String[] getDefaultCipherSuites() {
                return delegate.getDefaultCipherSuites();
            }

            @Override
            public String[] getSupportedCipherSuites() {
                return delegate.getSupportedCipherSuites();
            }

            @Override
            public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
                return configureSni(delegate.createSocket(s, sniHost, port, autoClose));
            }

            @Override
            public Socket createSocket(String host, int port) throws IOException {
                return configureSni(delegate.createSocket(targetIp, port));
            }

            @Override
            public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
                return configureSni(delegate.createSocket(targetIp, port, localHost, localPort));
            }

            @Override
            public Socket createSocket(InetAddress host, int port) throws IOException {
                return configureSni(delegate.createSocket(host, port));
            }

            @Override
            public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
                return configureSni(delegate.createSocket(address, port, localAddress, localPort));
            }
        };
    }

    private static String readStream(InputStream stream, String encoding) throws IOException {
        if (stream == null) return "";
        try (InputStream in = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(stream) : stream) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private JSONObject executeHttp(String method, String path, JSONObject body, String token, boolean useIpFallback) throws IOException {
        String host = useIpFallback ? resolveUpstreamIp() : HaileDomain.UPSTREAM_HOST;
        URL url = new URL("https://" + host + path);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        long t0 = System.currentTimeMillis();
        try {
            if (useIpFallback) {
                conn.setSSLSocketFactory(createSniSocketFactory(HaileDomain.UPSTREAM_HOST, host));
                conn.setHostnameVerifier((hostname, session) ->
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(HaileDomain.UPSTREAM_HOST, session)
                );
            }
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "Dart/3.7 (dart:io)");
            conn.setRequestProperty("appversion", "2.2.3");
            conn.setRequestProperty("apptype", "9");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Host", HaileDomain.UPSTREAM_HOST);
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", token);
            }

            if ("POST".equalsIgnoreCase(method)) {
                conn.setDoOutput(true);
                byte[] payload = (body != null ? body.toString() : "{}").getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                    os.flush();
                }
            }

            int status = conn.getResponseCode();
            long rtt = System.currentTimeMillis() - t0;
            observeServerDate(conn.getHeaderFieldDate("Date", 0L), rtt);

            InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readStream(stream, conn.getContentEncoding()).trim();
            if (status == 401) {
                JSONObject res = new JSONObject();
                try {
                    res.put("code", 2);
                    res.put("message", "登录已失效");
                } catch (Exception ignored) {}
                return res;
            }
            if (status < 200 || status >= 300) {
                JSONObject res = new JSONObject();
                try {
                    res.put("code", -3);
                    res.put("message", "上游 HTTP " + status);
                } catch (Exception ignored) {}
                return res;
            }
            try {
                return new JSONObject(text);
            } catch (Exception e) {
                JSONObject res = new JSONObject();
                try {
                    res.put("code", -2);
                    res.put("message", text.isEmpty() ? "平台返回空响应" : "平台返回非 JSON 响应: " + text.substring(0, Math.min(120, text.length())));
                } catch (Exception ignored) {}
                return res;
            }
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }
    }

    private JSONObject requestOnce(String method, String path, JSONObject body, String token) throws IOException {
        try {
            return executeHttp(method, path, body, token, false);
        } catch (UnknownHostException e) {
            return executeHttp(method, path, body, token, true);
        }
    }

    public JSONObject post(String path, JSONObject body, String token) {
        IOException lastErr = null;
        for (int attempt = 0; attempt <= 2; attempt++) {
            try {
                return requestOnce("POST", path, body, token);
            } catch (IOException e) {
                lastErr = e;
                if (attempt < 2) {
                    try {
                        Thread.sleep(300L * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return networkErrorResult(lastErr);
    }

    public JSONObject get(String path, String token) {
        IOException lastErr = null;
        for (int attempt = 0; attempt <= 2; attempt++) {
            try {
                return requestOnce("GET", path, null, token);
            } catch (IOException e) {
                lastErr = e;
                if (attempt < 2) {
                    try {
                        Thread.sleep(300L * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return networkErrorResult(lastErr);
    }

    private static JSONObject networkErrorResult(Exception e) {
        JSONObject res = new JSONObject();
        try {
            res.put("code", -1);
            String msg = e != null && e.getMessage() != null ? e.getMessage() : "连接超时";
            if (msg.length() > 100) msg = msg.substring(0, 100);
            res.put("message", "网络请求失败: " + msg);
        } catch (Exception ignored) {
        }
        return res;
    }

    public JSONObject guardedWrite(String path, JSONObject body, String token, int goodsId, String expect) {
        try {
            return requestOnce("POST", path, body, token);
        } catch (IOException exc) {
            JSONObject recovered = recoverPendingOrder(token, goodsId, expect);
            if (recovered != null) {
                return recovered;
            }
            JSONObject err = networkErrorResult(exc);
            try {
                err.put("ambiguous", true);
            } catch (Exception ignored) {
            }
            return err;
        }
    }

    public JSONObject recoverPendingOrder(String token, int goodsId, String expect) {
        double offset = getClockOffsetSeconds();
        JSONObject underway = underwayOrders(token);
        JSONArray data = underway.optInt("code", -1) == 0 ? underway.optJSONArray("data") : null;
        if (data != null) {
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            JSONArray itemList = item.optJSONArray("orderItemList");
            JSONObject firstItem = (itemList != null && itemList.length() > 0) ? itemList.optJSONObject(0) : null;
            if (firstItem == null || firstItem.optInt("goodsId", -1) != goodsId) continue;

            int state = item.optInt("state", 0);
            int orderType = item.optInt("orderType", 0);
            if ("instant".equals(expect) && state != 100) continue;
            if ("reserve".equals(expect) && !(state == 50 || orderType == 300)) continue;

            String orderNo = HaileDomain.optNullableString(item, "orderNo");
            if (orderNo == null || orderNo.isEmpty()) continue;

            try {
                JSONObject payload = new JSONObject();
                payload.put("orderNo", orderNo);
                String deadTime = HaileDomain.optNullableString(item, "deadTime");
                if (deadTime == null || deadTime.isEmpty()) {
                    deadTime = HaileDomain.optNullableString(item, "invalidTime");
                }
                Double rem = HaileDomain.secondsUntil(deadTime, offset);
                if (rem != null && rem > 0) {
                    payload.put("expireSeconds", (int) Math.floor(rem));
                }
                JSONObject res = new JSONObject();
                res.put("code", 0);
                res.put("data", payload);
                res.put("recovered", true);
                return res;
            } catch (Exception ignored) {
            }
        }
        }

        if ("instant".equals(expect)) {
            JSONObject recent = tradeList(token, 5);
            if (recent.optInt("code", -1) == 0) {
                JSONObject rData = recent.optJSONObject("data");
                JSONArray rItems = rData != null ? rData.optJSONArray("items") : recent.optJSONArray("data");
                if (rItems != null) {
                    for (int i = 0; i < rItems.length(); i++) {
                        JSONObject item = rItems.optJSONObject(i);
                        if (item == null || item.optInt("state", 0) != 100) continue;
                        JSONArray orderItems = item.optJSONArray("orderItemList");
                        JSONObject first = (orderItems != null && orderItems.length() > 0) ? orderItems.optJSONObject(0) : null;
                        if (first == null || first.optInt("goodsId", -1) != goodsId) continue;
                        String orderNo = HaileDomain.optNullableString(item, "orderNo");
                        if (orderNo == null || orderNo.isEmpty()) continue;
                        String deadTime = HaileDomain.optNullableString(item, "deadTime");
                        if (deadTime == null || deadTime.isEmpty()) deadTime = HaileDomain.optNullableString(item, "invalidTime");
                        Double rem = HaileDomain.secondsUntil(deadTime, offset);
                        if (deadTime != null && !deadTime.isEmpty() && rem != null && rem <= 0) continue;
                        try {
                            JSONObject payload = new JSONObject();
                            payload.put("orderNo", orderNo);
                            if (rem != null && rem > 0) payload.put("expireSeconds", (int) Math.floor(rem));
                            JSONObject res = new JSONObject();
                            res.put("code", 0);
                            res.put("data", payload);
                            res.put("recovered", true);
                            return res;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return null;
    }

    public JSONObject sendCode(String phone) {
        JSONObject body = new JSONObject();
        try {
            body.put("target", phone);
            body.put("sendType", 1);
            body.put("method", 1);
        } catch (Exception ignored) {}
        return post("/login/getCode", body, null);
    }

    public JSONObject login(String phone, String code) {
        JSONObject body = new JSONObject();
        try {
            body.put("account", phone);
            body.put("loginType", 2);
            body.put("verificationCode", code);
            body.put("authorizationClientType", "9");
        } catch (Exception ignored) {}
        return post("/login/login", body, null);
    }

    public JSONObject accountInfo(String token) {
        return get("/account/getAccountInfo", token);
    }

    public JSONArray devices() throws IOException {
        return devices(HaileDomain.POSITION_ID);
    }

    public JSONArray devices(int positionId) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("positionId", positionId > 0 ? positionId : HaileDomain.POSITION_ID);
            body.put("page", 1);
            body.put("pageSize", 50);
        } catch (Exception ignored) {}
        JSONObject data = post("/position/deviceDetailPage", body, null);
        if (data.optInt("code", -1) != 0) {
            throw new IOException(data.optString("message", "设备列表获取失败"));
        }
        JSONObject inner = data.optJSONObject("data");
        JSONArray items = inner != null ? inner.optJSONArray("items") : null;
        return items != null ? items : new JSONArray();
    }

    public static JSONObject formatPositionItem(JSONObject p) {
        if (p == null) return null;
        int pid = p.has("id") ? p.optInt("id", 0) : p.optInt("positionId", 0);
        if (pid <= 0) return null;
        try {
            JSONObject item = new JSONObject();
            String name = p.optString("name", p.optString("positionName", "点位 " + pid)).replace("\n", " ").trim();
            String addr = p.optString("address", p.optString("shopAddress", "")).replace("\n", " ").trim();
            item.put("positionId", pid);
            if (p.has("shopId") && !p.isNull("shopId")) item.put("shopId", p.get("shopId"));
            item.put("name", name.isEmpty() ? ("点位 " + pid) : name);
            item.put("address", addr);
            item.put("state", p.optInt("state", 1));
            if (p.has("distance") && !p.isNull("distance")) {
                double dist = p.optDouble("distance", -1.0);
                if (dist >= 0) item.put("distance", (int) Math.round(dist));
            }
            if (p.has("lng") && p.has("lat") && !p.isNull("lng") && !p.isNull("lat")) {
                double lngVal = p.optDouble("lng", Double.NaN);
                double latVal = p.optDouble("lat", Double.NaN);
                if (!Double.isNaN(lngVal) && !Double.isNaN(latVal)) {
                    item.put("lng", Math.round(lngVal * 1_000_000.0) / 1_000_000.0);
                    item.put("lat", Math.round(latVal * 1_000_000.0) / 1_000_000.0);
                }
            }
            JSONArray stats = p.optJSONArray("positionDeviceDetailList");
            if (stats != null) {
                int idleW = 0, totalW = 0, idleD = 0, totalD = 0;
                boolean hasW = false, hasD = false;
                for (int i = 0; i < stats.length(); i++) {
                    JSONObject st = stats.optJSONObject(i);
                    if (st == null) continue;
                    String cat = st.optString("categoryCode", "");
                    String cname = st.optString("categoryName", "");
                    if ("00".equals(cat) || "01".equals(cat) || cname.contains("洗")) {
                        hasW = true;
                        idleW += st.optInt("idleCount", 0);
                        totalW += st.optInt("total", 0);
                    } else if ("02".equals(cat) || "03".equals(cat) || "13".equals(cat) || cname.contains("烘")) {
                        hasD = true;
                        idleD += st.optInt("idleCount", 0);
                        totalD += st.optInt("total", 0);
                    }
                }
                if (hasW) {
                    item.put("idleWasherCount", idleW);
                    item.put("totalWasherCount", totalW);
                }
                if (hasD) {
                    item.put("idleDryerCount", idleD);
                    item.put("totalDryerCount", totalD);
                }
            } else if (p.has("idleCount") && !p.isNull("idleCount") && p.optInt("idleCount", -1) >= 0) {
                item.put("idleCount", p.optInt("idleCount", 0));
            }
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    public JSONObject positionDetail(int positionId, Double lng, Double lat) {
        StringBuilder qs = new StringBuilder("id=").append(positionId);
        if (lng != null && lat != null) {
            qs.append("&lng=").append(lng).append("&lat=").append(lat);
        }
        JSONObject res = get("/position/positionDetail?" + qs, null);
        if (res.optInt("code", -1) != 0) return null;
        return formatPositionItem(res.optJSONObject("data"));
    }

    public JSONArray nearbyPositions(Double lng, Double lat) {
        return nearbyPositions(lng, lat, "gcj02", "");
    }

    public JSONArray nearbyPositions(Double lng, Double lat, String coordType, String keyword) {
        boolean explicit = lng != null && lat != null;
        double[][] coords;
        if (explicit) {
            double qLng = lng;
            double qLat = lat;
            if ("wgs84".equalsIgnoreCase(coordType)) {
                double[] gcj = HaileDomain.wgs84ToGcj02(qLng, qLat);
                qLng = gcj[0];
                qLat = gcj[1];
            }
            coords = new double[][]{{qLng, qLat}};
        } else {
            coords = new double[][]{{120.4250, 36.0716}, {120.4965, 36.1185}};
        }

        Map<Integer, JSONObject> byId = new LinkedHashMap<>();
        String kw = keyword != null ? keyword.trim().toLowerCase(Locale.ROOT) : "";
        for (double[] c : coords) {
            try {
                JSONObject req = new JSONObject();
                req.put("lng", c[0]);
                req.put("lat", c[1]);
                req.put("page", 1);
                req.put("pageSize", 50);
                JSONObject res = post("/position/nearPosition", req, null);
                if (res.optInt("code", -1) != 0) continue;
                JSONObject dataObj = res.optJSONObject("data");
                JSONArray list = dataObj != null ? dataObj.optJSONArray("items") : res.optJSONArray("data");
                if (list == null) continue;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = formatPositionItem(list.optJSONObject(i));
                    if (item == null) continue;
                    int pid = item.optInt("positionId", 0);
                    if (pid <= 0 || byId.containsKey(pid)) continue;
                    if (!kw.isEmpty()) {
                        String n = item.optString("name", "").toLowerCase(Locale.ROOT);
                        String a = item.optString("address", "").toLowerCase(Locale.ROOT);
                        if (!n.contains(kw) && !a.contains(kw) && !String.valueOf(pid).equals(kw)) continue;
                    }
                    byId.put(pid, item);
                }
            } catch (Exception ignored) {}
        }
        List<JSONObject> sorted = new ArrayList<>(byId.values());
        sorted.sort((a, b) -> {
            int da = a.has("distance") ? a.optInt("distance", Integer.MAX_VALUE) : Integer.MAX_VALUE;
            int db = b.has("distance") ? b.optInt("distance", Integer.MAX_VALUE) : Integer.MAX_VALUE;
            if (da != db) return Integer.compare(da, db);
            return a.optString("name", "").compareTo(b.optString("name", ""));
        });
        JSONArray out = new JSONArray();
        for (JSONObject o : sorted) out.put(o);
        return out;
    }

    public JSONObject goodsDetail(int goodsId) {
        JSONObject data = get("/goods/normal/details?goodsId=" + goodsId, null);
        if (data.optInt("code", -1) != 0) return new JSONObject();
        JSONObject inner = data.optJSONObject("data");
        return inner != null ? inner : new JSONObject();
    }

    public JSONArray modes(int goodsId) throws IOException {
        JSONObject data = get("/goods/normal/details?goodsId=" + goodsId, null);
        if (data.optInt("code", -1) != 0) {
            throw new IOException(data.optString("message", "套餐获取失败"));
        }
        JSONObject inner = data.optJSONObject("data");
        JSONArray items = inner != null ? inner.optJSONArray("items") : null;
        return items != null ? items : new JSONArray();
    }

    public JSONArray usePositionList(String token, double lng, double lat) {
        JSONArray out = new JSONArray();
        try {
            JSONObject body = new JSONObject();
            body.put("lng", lng);
            body.put("lat", lat);
            body.put("page", 1);
            body.put("pageSize", 12);
            JSONObject res = post("/position/usePositionList", body, token);
            if (res.optInt("code", -1) != 0) return out;
            JSONObject dataObj = res.optJSONObject("data");
            JSONArray list = dataObj != null ? dataObj.optJSONArray("items") : res.optJSONArray("data");
            if (list == null) return out;
            for (int i = 0; i < list.length(); i++) {
                JSONObject formatted = formatPositionItem(list.optJSONObject(i));
                if (formatted != null) out.put(formatted);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public JSONArray goodsUseList(String token) {
        JSONArray out = new JSONArray();
        try {
            JSONObject body = new JSONObject();
            body.put("page", 1);
            body.put("pageSize", 15);
            body.put("categoryCodeList", new JSONArray());
            JSONObject res = post("/goods/goodsUseList", body, token);
            if (res.optInt("code", -1) != 0) return out;
            JSONObject dataObj = res.optJSONObject("data");
            JSONArray list = dataObj != null ? dataObj.optJSONArray("items") : res.optJSONArray("data");
            return list != null ? list : out;
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject scanGoods(String token, String imei, String nCode) {
        try {
            List<String> qs = new ArrayList<>();
            if (imei != null && !imei.trim().isEmpty()) qs.add("imei=" + URLEncoder.encode(imei.trim(), "UTF-8"));
            if (nCode != null && !nCode.trim().isEmpty()) qs.add("n=" + URLEncoder.encode(nCode.trim(), "UTF-8"));
            if (qs.isEmpty()) {
                JSONObject err = new JSONObject();
                err.put("code", -1);
                err.put("message", "缺少设备标识");
                return err;
            }
            return get("/goods/scan?" + String.join("&", qs), token);
        } catch (Exception e) {
            return networkErrorResult(e);
        }
    }

    public JSONArray positionChoose(String token, String name, double lng, double lat) {
        try {
            JSONObject body = new JSONObject();
            body.put("page", 1);
            body.put("pageSize", 20);
            body.put("lng", lng);
            body.put("lat", lat);
            body.put("name", name != null ? name.trim() : "");
            JSONObject res = post("/position/positionChoose", body, token);
            if (res.optInt("code", -1) != 0) return new JSONArray();
            JSONObject dataObj = res.optJSONObject("data");
            JSONArray list = dataObj != null ? dataObj.optJSONArray("items") : res.optJSONArray("data");
            return list != null ? list : new JSONArray();
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public JSONArray positionListByShop(String token, String shopId) {
        JSONArray out = new JSONArray();
        try {
            JSONObject res = get("/position/getPositionList?shopId=" + URLEncoder.encode(shopId, "UTF-8"), token);
            if (res.optInt("code", -1) != 0) return out;
            JSONObject dataObj = res.optJSONObject("data");
            JSONArray list = dataObj != null ? dataObj.optJSONArray("items") : res.optJSONArray("data");
            if (list == null) return out;
            for (int i = 0; i < list.length(); i++) {
                JSONObject f = formatPositionItem(list.optJSONObject(i));
                if (f != null) out.put(f);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public JSONArray geocodePlaces(String query, int limit) {
        String q = query != null ? query.trim() : "";
        JSONArray places = HaileDomain.searchCampusPresets(q, limit);
        if (q.isEmpty() || places.length() >= limit) return places;
        HttpURLConnection conn = null;
        try {
            URL u = new URL("https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(q, "UTF-8") + "&language=zh&count=6");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("User-Agent", "HaileBot/4.2");
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    JSONObject j = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                    JSONArray res = j.optJSONArray("results");
                    if (res != null) {
                        for (int i = 0; i < res.length() && places.length() < limit; i++) {
                            JSONObject it = res.optJSONObject(i);
                            if (it == null) continue;
                            String cc = it.optString("country_code", "");
                            if (!cc.isEmpty() && !"CN".equals(cc) && !"HK".equals(cc) && !"MO".equals(cc) && !"TW".equals(cc)) continue;
                            if (!it.has("longitude") || !it.has("latitude")) continue;
                            double[] gcj = HaileDomain.wgs84ToGcj02(it.optDouble("longitude"), it.optDouble("latitude"));
                            String a1 = it.optString("admin1", "").trim();
                            String a2 = it.optString("admin2", "").trim();
                            String sub = !a1.isEmpty() && !a2.isEmpty() ? (a1 + " · " + a2) : (!a1.isEmpty() ? a1 : "全国地点检索");
                            JSONObject p = new JSONObject();
                            p.put("name", it.optString("name", q));
                            p.put("subtitle", sub);
                            p.put("lng", gcj[0]);
                            p.put("lat", gcj[1]);
                            places.put(p);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return places;
    }

    public JSONObject createOrder(String token, String deviceName, int goodsId, int modeId, Integer dryerMinutes, Integer selfCleanItemId) {
        JSONObject body = new JSONObject();
        try {
            JSONArray purchaseList = new JSONArray();
            purchaseList.put(HaileDomain.buildDevicePurchaseItem(deviceName, goodsId, modeId, dryerMinutes));
            body.put("purchaseList", purchaseList);
            if (selfCleanItemId != null && selfCleanItemId > 0) {
                JSONArray addition = new JSONArray();
                addition.put(HaileDomain.buildPurchaseItem(goodsId, selfCleanItemId, 1));
                JSONObject optionalInfo = new JSONObject();
                optionalInfo.put("additionSelectInfo", addition);
                body.put("optionalInfo", optionalInfo);
            }
        } catch (Exception ignored) {}
        return guardedWrite("/trade/create", body, token, goodsId, "instant");
    }

    public JSONObject reserveOrder(String token, String deviceName, int goodsId, int modeId, Integer dryerMinutes, Integer selfCleanItemId) {
        JSONObject body = new JSONObject();
        try {
            JSONArray purchaseList = new JSONArray();
            purchaseList.put(HaileDomain.buildDevicePurchaseItem(deviceName, goodsId, modeId, dryerMinutes));
            body.put("purchaseList", purchaseList);
            JSONArray addition = new JSONArray();
            if (selfCleanItemId != null && selfCleanItemId > 0) {
                addition.put(HaileDomain.buildPurchaseItem(goodsId, selfCleanItemId, 1));
            }
            JSONObject optionalInfo = new JSONObject();
            optionalInfo.put("additionSelectInfo", addition);
            body.put("optionalInfo", optionalInfo);
            body.put("reserveMethod", 1);
        } catch (Exception ignored) {}
        return guardedWrite("/trade/reserveCreate", body, token, goodsId, "reserve");
    }

    public JSONObject orderDetail(String token, String orderNo) {
        return get("/trade/order/detail?orderNo=" + orderNo, token);
    }

    public JSONObject cancelOrder(String token, String orderNo) {
        JSONObject body = new JSONObject();
        try {
            body.put("orderNo", orderNo);
        } catch (Exception ignored) {}
        return post("/trade/cancel", body, token);
    }

    public JSONObject verifyOrderCode(String token, String orderNo, String checkCode) {
        JSONObject body = new JSONObject();
        try {
            body.put("orderNo", orderNo);
            body.put("checkCode", checkCode);
        } catch (Exception ignored) {}
        try {
            return requestOnce("POST", "/trade/check/verify", body, token);
        } catch (IOException e) {
            return networkErrorResult(e);
        }
    }

    public JSONObject resendVerifyCode(String token, String orderNo) {
        JSONObject body = new JSONObject();
        try { body.put("orderNo", orderNo); } catch (Exception ignored) {}
        try {
            return requestOnce("POST", "/trade/check/reSendCode", body, token);
        } catch (IOException e) {
            return networkErrorResult(e);
        }
    }

    public JSONObject remindPickupClothes(String token, String orderNo) {
        JSONObject body = new JSONObject();
        try { body.put("orderNo", orderNo); } catch (Exception ignored) {}
        try {
            return requestOnce("POST", "/message/tokeClothes/sendMessage", body, token);
        } catch (IOException e) {
            return networkErrorResult(e);
        }
    }

    public JSONObject placeClothesStart(String token, String orderNo) {
        JSONObject body = new JSONObject();
        try { body.put("orderNo", orderNo); } catch (Exception ignored) {}
        try {
            return requestOnce("POST", "/device/placeClothes", body, token);
        } catch (IOException e) {
            return networkErrorResult(e);
        }
    }

    public JSONObject underwayOrders(String token) {
        return post("/trade/underway/orderList", new JSONObject(), token);
    }

    public JSONObject tradeList(String token, int pageSize) {
        JSONObject body = new JSONObject();
        try {
            body.put("page", 1);
            body.put("pageSize", pageSize);
            body.put("newOrderState", "");
            body.put("categoryCode", "");
        } catch (Exception ignored) {}
        return post("/trade/list", body, token);
    }

    public JSONObject runInfo(int goodsId) {
        JSONObject body = new JSONObject();
        try {
            body.put("goodsId", goodsId);
        } catch (Exception ignored) {}
        return post("/goods/last/runInfo", body, null);
    }

    public static JSONObject accountSnapshot(JSONObject acc) {
        String token = acc.optString("token", "");
        Long expiry = HaileDomain.getTokenExpiry(token);
        String remark = acc.optString("remark", "").trim();
        String phone = acc.optString("phone", "");
        JSONObject snap = new JSONObject();
        try {
            snap.put("phone", phone);
            snap.put("displayName", !remark.isEmpty() ? remark : phone);
            snap.put("remark", remark);
            snap.put("ownerId", acc.optString("owner_id", "default"));
            snap.put("ownerName", acc.optString("owner_name", ""));
            snap.put("enabled", acc.optBoolean("is_active", true));
            snap.put("tokenExpiresAt", expiry != null ? HaileDomain.formatTimestamp(expiry) : JSONObject.NULL);
            snap.put("tokenExpired", token.isEmpty() || HaileDomain.isTokenExpired(token));
        } catch (Exception ignored) {}
        return snap;
    }

    public static boolean inferOrderSelfClean(JSONObject payload) {
        if (payload == null) return false;
        if (payload.optBoolean("selfClean", false) || "self_clean".equals(HaileDomain.optNullableString(payload, "pageCode"))) {
            return true;
        }
        JSONObject uniqueInfo = payload.optJSONObject("uniqueInfo");
        if (uniqueInfo != null) {
            JSONObject scInfo = uniqueInfo.optJSONObject("selfCleanInfo");
            if (scInfo != null && scInfo.optBoolean("selfClean", false)) {
                return true;
            }
        }
        JSONArray items = payload.optJSONArray("orderItemList");
        if (items != null && items.length() > 1) {
            for (int i = 1; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && (item.optBoolean("selfClean", false) || item.optString("goodsItemName", "").contains("自洁"))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static JSONObject summarizeDetail(JSONObject detail, String accountPhone) {
        JSONArray itemList = detail != null ? detail.optJSONArray("orderItemList") : null;
        JSONObject firstItem = (itemList != null && itemList.length() > 0) ? itemList.optJSONObject(0) : new JSONObject();
        JSONObject buttons = detail != null ? detail.optJSONObject("buttonSwitch") : null;
        JSONObject reserveInfo = detail != null ? detail.optJSONObject("reserveInfo") : null;
        JSONObject checkInfo = detail != null ? detail.optJSONObject("checkInfo") : null;
        String goodsName = HaileDomain.extractProjectName(firstItem, "未知设备");

        JSONObject base = new JSONObject();
        try {
            base.put("orderNo", detail != null ? detail.optString("orderNo", "") : "");
            base.put("accountPhone", accountPhone);
            base.put("goodsName", goodsName);
            String modeName = HaileDomain.optNullableString(firstItem, "goodsItemName");
            base.put("modeName", modeName != null ? modeName : JSONObject.NULL);
            base.put("selfClean", inferOrderSelfClean(detail));
            if (detail != null && detail.has("state")) base.put("state", detail.optInt("state"));
            base.put("stateDesc", detail != null ? detail.optString("stateDesc", "") : "");
            String pageCode = detail != null ? HaileDomain.optNullableString(detail, "pageCode") : null;
            base.put("pageCode", pageCode != null ? pageCode : JSONObject.NULL);
            if (detail != null && detail.has("orderType")) base.put("orderType", detail.optInt("orderType"));
            if (firstItem != null && firstItem.has("goodsId")) base.put("goodsId", firstItem.optInt("goodsId"));
            base.put("canPay", buttons != null && buttons.optBoolean("canPay", false));
            base.put("canCancel", buttons != null && buttons.optBoolean("canCancel", false));

            String deadline = detail != null ? HaileDomain.optNullableString(detail, "invalidTime") : null;
            if ((deadline == null || deadline.isEmpty()) && checkInfo != null) {
                deadline = HaileDomain.optNullableString(checkInfo, "invalidTime");
            }
            if ((deadline == null || deadline.isEmpty()) && reserveInfo != null) {
                deadline = HaileDomain.optNullableString(reserveInfo, "estimateEffectiveTime");
            }
            base.put("deadline", deadline != null ? deadline : "");

            String estFinish = firstItem != null ? HaileDomain.optNullableString(firstItem, "finishTime") : null;
            if ((estFinish == null || estFinish.isEmpty()) && detail != null) {
                estFinish = HaileDomain.optNullableString(detail, "finishTime");
            }
            base.put("estimatedFinishTime", estFinish != null ? estFinish : "");

            String amount = detail != null ? HaileDomain.optNullableString(detail, "originPrice") : null;
            if (amount == null || amount.isEmpty()) amount = detail != null ? HaileDomain.optNullableString(detail, "realPrice") : null;
            if (amount == null || amount.isEmpty()) amount = detail != null ? HaileDomain.optNullableString(detail, "payAmount") : null;
            base.put("amount", amount != null ? amount : "");
            base.put("updatedAt", System.currentTimeMillis() / 1000.0);
        } catch (Exception ignored) {}
        return HaileDomain.enrichOrder(base);
    }

    public static JSONObject summarizeListItem(JSONObject item, String accountPhone) {
        JSONArray itemList = item != null ? item.optJSONArray("orderItemList") : null;
        JSONObject firstItem = (itemList != null && itemList.length() > 0) ? itemList.optJSONObject(0) : new JSONObject();

        String projectName = HaileDomain.optNullableString(item, "projectName");
        String goodsName;
        String modeName;
        if (projectName != null && !projectName.isEmpty()) {
            goodsName = projectName;
            if (projectName.contains("【") && projectName.contains("】")) {
                int start = projectName.indexOf("【") + 1;
                int end = projectName.indexOf("】", start);
                modeName = end > start ? projectName.substring(start, end).trim() : null;
            } else {
                modeName = null;
            }
        } else {
            goodsName = HaileDomain.extractProjectName(firstItem, "未知设备");
            modeName = HaileDomain.optNullableString(firstItem, "goodsItemName");
        }

        int state = item != null ? item.optInt("state", 0) : 0;
        JSONObject base = new JSONObject();
        try {
            base.put("orderNo", item != null ? item.optString("orderNo", "") : "");
            base.put("accountPhone", accountPhone);
            base.put("goodsName", goodsName);
            base.put("modeName", modeName != null ? modeName : JSONObject.NULL);
            base.put("selfClean", inferOrderSelfClean(item));
            base.put("state", state);
            base.put("stateDesc", item != null ? item.optString("stateDesc", "") : "");
            String pageCode = item != null ? HaileDomain.optNullableString(item, "pageCode") : null;
            base.put("pageCode", pageCode != null ? pageCode : JSONObject.NULL);
            if (item != null && item.has("orderType")) base.put("orderType", item.optInt("orderType"));
            if (firstItem != null && firstItem.has("goodsId")) base.put("goodsId", firstItem.optInt("goodsId"));
            base.put("canPay", state == 100);
            base.put("canCancel", !HaileDomain.TERMINAL_ORDER_STATES.contains(state));

            String deadline = HaileDomain.optNullableString(item, "deadTime");
            if (deadline == null || deadline.isEmpty()) deadline = HaileDomain.optNullableString(item, "completeTime");
            if (deadline == null || deadline.isEmpty()) deadline = HaileDomain.optNullableString(item, "invalidTime");
            base.put("deadline", deadline != null ? deadline : "");
            base.put("estimatedFinishTime", "");
            String timeDesc = HaileDomain.optNullableString(item, "timeDescription");
            base.put("timeDescription", timeDesc != null ? timeDesc : "");
            String amount = HaileDomain.optNullableString(item, "realPrice");
            base.put("amount", amount != null ? amount : "");
            base.put("updatedAt", System.currentTimeMillis() / 1000.0);
        } catch (Exception ignored) {}

        JSONObject enriched = HaileDomain.enrichOrder(base);
        if ("running".equals(enriched.optString("stage"))) {
            try {
                String est = HaileDomain.optNullableString(item, "deadTime");
                if (est == null || est.isEmpty()) est = HaileDomain.optNullableString(item, "completeTime");
                enriched.put("estimatedFinishTime", est != null ? est : "");
                enriched.put("deadline", "");
            } catch (Exception ignored) {}
        }
        return enriched;
    }
}
