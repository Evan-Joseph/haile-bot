package top.hailebot.v3;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.CRC32;

public final class HaileEngine {
    public static final int PAYMENT_EXPIRE_FALLBACK_SECONDS = 300;
    public static final int RENEW_BEFORE_SECONDS = 30;
    public static final double POLL_ACTIVE_SECONDS = 1.5;
    public static final double POLL_HELD_SECONDS = 3.0;
    public static final double POLL_RESERVE_SECONDS = 4.0;
    public static final double DEVICE_HOLD_SECONDS = 12.0;
    public static final double ORDER_RETENTION_SECONDS = 7200.0;
    public static final double RAW_DEVICES_CACHE_SECONDS = 2.0;
    public static final double RUN_INFO_CACHE_SECONDS = 15.0;
    public static final double RUN_INFO_MIN_INTERVAL = 2.0;
    public static final double DEVICE_MONITOR_SECONDS = 4.0;
    public static final int ACCOUNT_MAX_ACTIVE_SLOTS = 2;

    public interface StatusListener {
        void onStateChanged(int activeIntentCount, String ongoingSummary, long countdownTargetMillis);
        void onOrderAlert(String keyId, String stage, String title, String body);
    }

    private final File filesDir;
    private final HaileApi api;
    private final Object lock = new Object();
    private final List<JSONObject> accounts = new ArrayList<>();
    private final Deque<String> logs = new ArrayDeque<>();
    private final Map<String, JSONObject> ordersByNo = new LinkedHashMap<>();
    private final Map<String, Double> devicePaymentHolds = new HashMap<>();
    private final Map<Integer, PreemptIntent> intents = new HashMap<>();
    private final Map<Integer, CachedModes> modeCache = new HashMap<>();
    private final Map<Integer, CachedRunInfo> runInfoCache = new HashMap<>();
    private final Map<Integer, String> deviceSigs = new HashMap<>();
    private final List<BlockingQueue<JSONObject>> subscribers = new ArrayList<>();
    private final Set<String> alertedOrders = new HashSet<>();
    private final Set<String> seededOrderAccounts = new HashSet<>();
    private final Set<String> runningOrderNos = new HashSet<>();
    private final Set<String> pickupSoonSent = new HashSet<>();
    private final Set<String> pickupDoneSent = new HashSet<>();
    private final Random rng = new Random();

    private JSONArray rawDevices = new JSONArray();
    private double rawDevicesAt = 0.0;
    private int positionId = HaileDomain.POSITION_ID;
    private String positionName = HaileDomain.DEFAULT_POSITION_NAME;
    private double positionLng = 120.4250;
    private double positionLat = 36.0716;
    private boolean positionCustomized = false;
    private final List<JSONObject> recentPositions = new ArrayList<>();
    private final Map<Integer, CachedJson> positionDetailCache = new HashMap<>();
    private final Map<Integer, CachedJson> goodsDetailCache = new HashMap<>();
    private final Map<String, CachedJson> recentUsageCache = new HashMap<>();
    private boolean pollerStarted = false;
    private boolean monitorStarted = false;
    private volatile boolean stopped = false;
    private volatile StatusListener statusListener;

    private static final class CachedJson {
        final double cachedAt;
        final JSONObject data;
        CachedJson(double cachedAt, JSONObject data) {
            this.cachedAt = cachedAt;
            this.data = data;
        }
    }

    private static final class CachedModes {
        final double cachedAt;
        final JSONArray modes;
        CachedModes(double cachedAt, JSONArray modes) {
            this.cachedAt = cachedAt;
            this.modes = modes;
        }
    }

    private static final class CachedRunInfo {
        final double cachedAt;
        final String deadTime;
        CachedRunInfo(double cachedAt, String deadTime) {
            this.cachedAt = cachedAt;
            this.deadTime = deadTime;
        }
    }

    public HaileEngine(File filesDir) {
        this.filesDir = filesDir;
        this.api = new HaileApi();
        loadPosition();
        loadAccounts();
    }

    public String getPositionName() {
        synchronized (lock) {
            return positionName;
        }
    }

    public static int poolKeyForOwner(String ownerId) {
        String norm = normalizeOwnerId(ownerId);
        if ("default".equals(norm)) return -1;
        CRC32 crc = new CRC32();
        crc.update(norm.getBytes(StandardCharsets.UTF_8));
        return -(int) ((crc.getValue() % 900_000L) + 10L);
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
        notifyStatusListener();
    }

    public void startBackgroundLoops() {
        startBackgroundRefresh();
        startDeviceMonitor();
        restoreHolds();
        restoreIntents();
        notifyStatusListener();
    }

    public static double nowSec() {
        return System.currentTimeMillis() / 1000.0;
    }

    private double clockOffsetSec() {
        return api.getClockOffsetSeconds();
    }

    public static String normalizeOwnerId(String ownerId) {
        if (ownerId == null) return "default";
        String trimmed = ownerId.trim();
        return trimmed.isEmpty() ? "default" : trimmed;
    }

    public static Integer normalizeDryerMinutes(Integer value) {
        if (value == null || value <= 0) return null;
        return Math.min(120, Math.max(1, value));
    }

    private static String readFileText(File file) {
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            if (read <= 0) return "";
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFileAtomic(File file, String content) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private File accountsFile() { return new File(filesDir, "accounts.json"); }
    private File intentsFile() { return new File(filesDir, "v3_intents.json"); }
    private File holdsFile() { return new File(filesDir, "v3_holds.json"); }
    private File positionFile() { return new File(filesDir, "v4_position.json"); }

    private void recordRecentPositionLocked(int pid, String pname, String address, Double lng, Double lat) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("positionId", pid);
            entry.put("name", (pname != null && !pname.trim().isEmpty()) ? pname.trim() : ("点位 " + pid));
            if (address != null && !address.trim().isEmpty()) entry.put("address", address.trim());
            if (lng != null && lat != null) {
                entry.put("lng", Math.round(lng * 1_000_000.0) / 1_000_000.0);
                entry.put("lat", Math.round(lat * 1_000_000.0) / 1_000_000.0);
            }
            recentPositions.removeIf(it -> it == null || it.optInt("positionId", 0) == pid);
            recentPositions.add(0, entry);
            while (recentPositions.size() > 8) {
                recentPositions.remove(recentPositions.size() - 1);
            }
        } catch (Exception ignored) {}
    }

    private JSONObject cachedPositionDetail(int pid, double maxAgeSec) {
        if (pid <= 0) return null;
        double now = nowSec();
        synchronized (lock) {
            CachedJson c = positionDetailCache.get(pid);
            if (c != null && now - c.cachedAt < maxAgeSec) return HaileDomain.cloneJson(c.data);
        }
        double refLng, refLat;
        synchronized (lock) {
            refLng = positionLng;
            refLat = positionLat;
        }
        JSONObject det = api.positionDetail(pid, refLng, refLat);
        if (det != null && det.optInt("positionId", 0) > 0) {
            synchronized (lock) {
                positionDetailCache.put(pid, new CachedJson(now, HaileDomain.cloneJson(det)));
            }
            return det;
        }
        return null;
    }

    private JSONObject cachedGoodsDetail(int gid, double maxAgeSec) {
        if (gid <= 0) return new JSONObject();
        double now = nowSec();
        synchronized (lock) {
            CachedJson c = goodsDetailCache.get(gid);
            if (c != null && now - c.cachedAt < maxAgeSec) return HaileDomain.cloneJson(c.data);
        }
        JSONObject det = api.goodsDetail(gid);
        if (det != null && det.length() > 0) {
            synchronized (lock) {
                goodsDetailCache.put(gid, new CachedJson(now, HaileDomain.cloneJson(det)));
            }
            return det;
        }
        return new JSONObject();
    }

    private void loadPosition() {
        String raw = readFileText(positionFile());
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(raw);
            int pid = obj.optInt("positionId", 0);
            if (pid > 0) {
                synchronized (lock) {
                    positionId = pid;
                    String pname = obj.optString("positionName", HaileDomain.DEFAULT_POSITION_NAME).trim();
                    positionName = pname.isEmpty() ? HaileDomain.DEFAULT_POSITION_NAME : pname;
                    positionCustomized = obj.optBoolean("customized", true);
                    if (obj.has("lng") && obj.has("lat") && !obj.isNull("lng") && !obj.isNull("lat")) {
                        positionLng = obj.optDouble("lng", positionLng);
                        positionLat = obj.optDouble("lat", positionLat);
                    }
                    JSONArray rec = obj.optJSONArray("recentPositions");
                    if (rec != null) {
                        recentPositions.clear();
                        for (int i = 0; i < Math.min(8, rec.length()); i++) {
                            JSONObject it = rec.optJSONObject(i);
                            if (it != null && it.optInt("positionId", 0) > 0) recentPositions.add(it);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public JSONObject setPosition(int newPosId, String newPosName) {
        return setPosition(newPosId, newPosName, null, null, "", true);
    }

    public JSONObject setPosition(int newPosId, String newPosName, Double lng, Double lat, String address, boolean markCustomized) {
        int pid = newPosId > 0 ? newPosId : HaileDomain.POSITION_ID;
        String pname = newPosName != null ? newPosName.trim() : "";
        String addr = address != null ? address.trim() : "";
        Double resLng = lng;
        Double resLat = lat;
        if (pname.isEmpty() || pname.equals("点位 " + pid) || resLng == null || resLat == null) {
            JSONObject det = cachedPositionDetail(pid, 600.0);
            if (det != null) {
                if (pname.isEmpty() || pname.equals("点位 " + pid)) {
                    pname = det.optString("name", "").trim();
                }
                if (addr.isEmpty()) addr = det.optString("address", "").trim();
                if (resLng == null && det.has("lng")) resLng = det.optDouble("lng");
                if (resLat == null && det.has("lat")) resLat = det.optDouble("lat");
            }
        }
        if (pname.isEmpty()) {
            pname = (pid == HaileDomain.POSITION_ID) ? HaileDomain.DEFAULT_POSITION_NAME : ("点位 " + pid);
        }

        double savedLng, savedLat;
        boolean savedCust;
        JSONArray savedRecent = new JSONArray();
        synchronized (lock) {
            if (pid != positionId) {
                for (PreemptIntent it : intents.values()) {
                    if (it != null && it.isRunning()) {
                        JSONObject blocked = new JSONObject();
                        try {
                            blocked.put("success", false);
                            blocked.put("message", "请先停止当前楼栋的抢占任务再切换点位");
                        } catch (Exception ignored) {}
                        return blocked;
                    }
                }
                rawDevices = new JSONArray();
                rawDevicesAt = 0.0;
                deviceSigs.clear();
            }
            positionId = pid;
            positionName = pname;
            if (resLng != null && resLat != null) {
                positionLng = resLng;
                positionLat = resLat;
            }
            if (markCustomized) positionCustomized = true;
            recordRecentPositionLocked(pid, pname, addr, resLng, resLat);
            savedLng = positionLng;
            savedLat = positionLat;
            savedCust = positionCustomized;
            for (JSONObject r : recentPositions) savedRecent.put(r);
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("positionId", pid);
            obj.put("positionName", pname);
            obj.put("lng", savedLng);
            obj.put("lat", savedLat);
            obj.put("customized", savedCust);
            obj.put("recentPositions", savedRecent);
            writeFileAtomic(positionFile(), obj.toString(2));
        } catch (Exception ignored) {}
        log("当前洗衣房已切换为: " + pname + " (" + pid + ")");
        JSONObject ev = new JSONObject();
        try {
            ev.put("type", "position");
            ev.put("positionId", pid);
            ev.put("positionName", pname);
            ev.put("lng", savedLng);
            ev.put("lat", savedLat);
        } catch (Exception ignored) {}
        emit(ev);
        JSONObject res = new JSONObject();
        try {
            res.put("success", true);
            res.put("positionId", pid);
            res.put("positionName", pname);
            res.put("lng", savedLng);
            res.put("lat", savedLat);
        } catch (Exception ignored) {}
        return res;
    }

    public JSONArray nearbyPositions(Double lng, Double lat) {
        return nearbyPositions(lng, lat, "gcj02", "");
    }

    public JSONArray nearbyPositions(Double lng, Double lat, String coordType, String keyword) {
        boolean explicit = lng != null && lat != null;
        Double qLng = lng;
        Double qLat = lat;
        int curPid;
        String curPname;
        double curLng, curLat;
        boolean cust;
        synchronized (lock) {
            curPid = positionId;
            curPname = positionName;
            curLng = positionLng;
            curLat = positionLat;
            cust = positionCustomized;
        }
        if (!explicit && cust) {
            qLng = curLng;
            qLat = curLat;
        }
        JSONArray list = api.nearbyPositions(qLng, qLat, explicit ? coordType : "gcj02", keyword);
        if (!explicit && (keyword == null || keyword.trim().isEmpty())) {
            boolean hasCur = false;
            for (int i = 0; i < list.length(); i++) {
                JSONObject it = list.optJSONObject(i);
                if (it != null && it.optInt("positionId", 0) == curPid) {
                    hasCur = true;
                    break;
                }
            }
            if (!hasCur) {
                JSONArray merged = new JSONArray();
                try {
                    JSONObject cur = new JSONObject();
                    cur.put("positionId", curPid);
                    cur.put("name", curPname);
                    cur.put("address", "");
                    cur.put("state", 1);
                    cur.put("lng", curLng);
                    cur.put("lat", curLat);
                    merged.put(cur);
                } catch (Exception ignored) {}
                for (int i = 0; i < list.length(); i++) merged.put(list.opt(i));
                list = merged;
            }
        }
        for (int i = 0; i < Math.min(5, list.length()); i++) {
            JSONObject it = list.optJSONObject(i);
            if (it != null && (!it.has("lng") || !it.has("lat"))) {
                JSONObject det = cachedPositionDetail(it.optInt("positionId", 0), 600.0);
                if (det != null && det.has("lng") && det.has("lat")) {
                    try {
                        it.put("lng", det.optDouble("lng"));
                        it.put("lat", det.optDouble("lat"));
                    } catch (Exception ignored) {}
                }
            }
        }
        return list;
    }

    public JSONObject searchPositions(String query, String ownerId, Double lng, Double lat) {
        String q = query != null ? query.trim() : "";
        JSONObject out = new JSONObject();
        try {
            if (q.isEmpty()) {
                out.put("positions", nearbyPositions(lng, lat, "gcj02", ""));
                out.put("places", api.geocodePlaces("", 6));
                return out;
            }
            JSONObject parsed = HaileDomain.parseCustomPositionInput(q);
            String kind = parsed.optString("kind", "keyword");
            Map<Integer, JSONObject> byId = new LinkedHashMap<>();
            JSONArray places = new JSONArray();

            if ("position_id".equals(kind)) {
                JSONObject det = cachedPositionDetail(parsed.optInt("positionId", 0), 600.0);
                if (det != null) byId.put(det.optInt("positionId"), det);
            }
            if ("coords".equals(kind)) {
                double cLng = parsed.optDouble("lng");
                double cLat = parsed.optDouble("lat");
                JSONObject p = new JSONObject();
                p.put("name", String.format(Locale.ROOT, "坐标 (%.4f, %.4f)", cLng, cLat));
                p.put("subtitle", "经纬度直达");
                p.put("lng", cLng);
                p.put("lat", cLat);
                places.put(p);
                JSONArray near = nearbyPositions(cLng, cLat, "gcj02", "");
                for (int i = 0; i < near.length(); i++) {
                    JSONObject it = near.optJSONObject(i);
                    if (it != null) byId.putIfAbsent(it.optInt("positionId"), it);
                }
                JSONArray arr = new JSONArray();
                for (JSONObject v : byId.values()) arr.put(v);
                out.put("positions", arr);
                out.put("places", places);
                return out;
            }

            List<JSONObject> enabled = enabledAccounts(ownerId);
            String token = !enabled.isEmpty() ? enabled.get(0).optString("token", "") : "";
            double refLng, refLat;
            synchronized (lock) {
                refLng = lng != null ? lng : positionLng;
                refLat = lat != null ? lat : positionLat;
            }
            if (!token.isEmpty()) {
                JSONArray shops = api.positionChoose(token, q, refLng, refLat);
                for (int i = 0; i < Math.min(4, shops.length()); i++) {
                    JSONObject sh = shops.optJSONObject(i);
                    if (sh == null) continue;
                    String sid = sh.has("id") ? String.valueOf(sh.opt("id")) : String.valueOf(sh.opt("shopId"));
                    if (sid != null && !sid.isEmpty() && !"null".equals(sid)) {
                        JSONArray plist = api.positionListByShop(token, sid);
                        for (int j = 0; j < plist.length(); j++) {
                            JSONObject pos = plist.optJSONObject(j);
                            if (pos != null) byId.putIfAbsent(pos.optInt("positionId"), pos);
                        }
                    }
                }
            }

            places = api.geocodePlaces(q, 6);
            if (byId.isEmpty() && places.length() > 0) {
                JSONObject top = places.optJSONObject(0);
                if (top != null) {
                    JSONArray near = nearbyPositions(top.optDouble("lng"), top.optDouble("lat"), "gcj02", "");
                    for (int i = 0; i < near.length(); i++) {
                        JSONObject it = near.optJSONObject(i);
                        if (it != null) byId.putIfAbsent(it.optInt("positionId"), it);
                    }
                }
            } else if (lng != null && lat != null) {
                JSONArray near = nearbyPositions(lng, lat, "gcj02", q);
                for (int i = 0; i < near.length(); i++) {
                    JSONObject it = near.optJSONObject(i);
                    if (it != null) byId.putIfAbsent(it.optInt("positionId"), it);
                }
            }

            JSONArray posArr = new JSONArray();
            int count = 0;
            for (JSONObject v : byId.values()) {
                if (count++ >= 40) break;
                posArr.put(v);
            }
            out.put("positions", posArr);
            out.put("places", places);
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject recentUsage(String ownerId, boolean force) {
        String norm = normalizeOwnerId(ownerId);
        double now = nowSec();
        if (!force) {
            synchronized (lock) {
                CachedJson c = recentUsageCache.get(norm);
                if (c != null && now - c.cachedAt < 12.0) return HaileDomain.cloneJson(c.data);
            }
        }
        Map<Integer, JSONObject> posById = new LinkedHashMap<>();
        double refLng, refLat;
        int curPid;
        String curPname;
        synchronized (lock) {
            refLng = positionLng;
            refLat = positionLat;
            curPid = positionId;
            curPname = positionName;
            for (JSONObject r : recentPositions) {
                int pid = r.optInt("positionId", 0);
                if (pid > 0) {
                    JSONObject cp = HaileDomain.cloneJson(r);
                    try { cp.put("source", "local"); } catch (Exception ignored) {}
                    posById.put(pid, cp);
                }
            }
        }

        List<JSONObject> enabled = enabledAccounts(norm);
        List<JSONObject> rawCandidates = new ArrayList<>();
        Set<Integer> seenGids = new HashSet<>();

        for (int a = 0; a < Math.min(2, enabled.size()); a++) {
            String token = enabled.get(a).optString("token", "");
            if (token.isEmpty()) continue;
            JSONArray uPos = api.usePositionList(token, refLng, refLat);
            for (int i = 0; i < uPos.length(); i++) {
                JSONObject p = uPos.optJSONObject(i);
                if (p == null) continue;
                int pid = p.optInt("positionId", 0);
                if (pid > 0 && !posById.containsKey(pid)) {
                    try { p.put("source", "account"); } catch (Exception ignored) {}
                    posById.put(pid, p);
                }
            }
            JSONArray gUse = api.goodsUseList(token);
            for (int i = 0; i < gUse.length(); i++) {
                JSONObject d = gUse.optJSONObject(i);
                if (d == null) continue;
                int gid = d.optInt("id", d.optInt("goodsId", 0));
                if (gid > 0 && seenGids.add(gid)) rawCandidates.add(d);
            }
            if (rawCandidates.size() < 6) {
                JSONObject tRes = api.tradeList(token, 12);
                if (tRes.optInt("code", -1) == 0) {
                    JSONObject tData = tRes.optJSONObject("data");
                    JSONArray items = tData != null ? tData.optJSONArray("items") : tRes.optJSONArray("data");
                    if (items != null) {
                        for (int i = 0; i < items.length() && rawCandidates.size() < 8; i++) {
                            JSONObject ord = items.optJSONObject(i);
                            if (ord == null) continue;
                            JSONArray oItems = ord.optJSONArray("orderItemList");
                            JSONObject first = (oItems != null && oItems.length() > 0) ? oItems.optJSONObject(0) : null;
                            if (first == null) continue;
                            int gid = first.optInt("goodsId", 0);
                            if (gid > 0 && seenGids.add(gid)) {
                                JSONObject stub = new JSONObject();
                                try {
                                    stub.put("id", gid);
                                    stub.put("name", first.optString("goodsName", ord.optString("projectName", "")));
                                    stub.put("categoryCode", first.optString("categoryCode", ""));
                                } catch (Exception ignored) {}
                                rawCandidates.add(stub);
                            }
                        }
                    }
                }
            }
        }

        double offset = clockOffsetSec();
        JSONArray recentDevs = new JSONArray();
        for (int i = 0; i < Math.min(6, rawCandidates.size()); i++) {
            JSONObject rawDev = rawCandidates.get(i);
            int gid = rawDev.optInt("id", rawDev.optInt("goodsId", 0));
            if (gid <= 0) continue;
            JSONObject det = cachedGoodsDetail(gid, 20.0);
            int pid = det.optInt("positionId", rawDev.optInt("positionId", 0));
            JSONObject posDet = pid > 0 ? cachedPositionDetail(pid, 600.0) : null;
            String pname = "";
            if (posDet != null) pname = posDet.optString("name", "").trim();
            if (pname.isEmpty()) pname = det.optString("positionName", "").trim();
            if (pname.isEmpty() || "null".equalsIgnoreCase(pname)) pname = det.optString("shopName", "").trim();
            if (pname.isEmpty() || "null".equalsIgnoreCase(pname)) pname = rawDev.optString("address", "").trim();
            if (pname.isEmpty() && pid > 0) pname = (pid == curPid) ? curPname : ("点位 " + pid);

            if (pid > 0 && !posById.containsKey(pid)) {
                try {
                    JSONObject hp = new JSONObject();
                    hp.put("positionId", pid);
                    hp.put("name", pname.isEmpty() ? ("点位 " + pid) : pname);
                    hp.put("address", posDet != null ? posDet.optString("address", "") : det.optString("shopAddress", ""));
                    if (posDet != null && posDet.has("lng")) hp.put("lng", posDet.optDouble("lng"));
                    if (posDet != null && posDet.has("lat")) hp.put("lat", posDet.optDouble("lat"));
                    hp.put("source", "history");
                    posById.put(pid, hp);
                } catch (Exception ignored) {}
            }

            try {
                JSONObject merged = HaileDomain.cloneJson(rawDev);
                if (!merged.has("state") || merged.isNull("state")) merged.put("state", det.optInt("deviceState", 1));
                if (!merged.has("enableReserve") || merged.isNull("enableReserve")) merged.put("enableReserve", det.optBoolean("enableReserve", false));
                if (!merged.has("reserveState") || merged.isNull("reserveState")) merged.put("reserveState", det.optInt("reserveState", 0));
                merged.put("soldState", det.optInt("soldState", 1));
                if (det.has("deviceErrorCode")) merged.put("deviceErrorCode", det.opt("deviceErrorCode"));
                merged.put("shopClosed", det.optBoolean("shopClosed", false));

                JSONObject nameSrc = new JSONObject();
                nameSrc.put("name", !rawDev.optString("name", "").isEmpty() ? rawDev.optString("name") : det.optString("name", ""));
                nameSrc.put("imei", !rawDev.optString("imei", "").isEmpty() ? rawDev.optString("imei") : det.optString("imei", ""));
                String dName = HaileDomain.normalizeDeviceDisplayName(nameSrc, pid == HaileDomain.POSITION_ID);
                if (dName.isEmpty()) dName = det.optString("name", rawDev.optString("name", "设备 " + gid));

                HaileDomain.DeviceState st = HaileDomain.classifyDevice(merged, false);
                String finishTime = rawDev.optString("finishTime", "");
                if ("null".equalsIgnoreCase(finishTime)) finishTime = "";
                if (finishTime.isEmpty() && !"idle".equals(st.key)) {
                    finishTime = runInfoDeadTime(gid);
                }
                String dtype = HaileDomain.deviceTypeFromName(dName);
                String catCode = det.optString("categoryCode", rawDev.optString("categoryCode", ""));
                if ("washer".equals(dtype) && ("02".equals(catCode) || "03".equals(catCode) || "13".equals(catCode))) {
                    dtype = "dryer";
                }
                Double remSec = !finishTime.isEmpty() ? HaileDomain.secondsUntil(finishTime, offset) : null;

                JSONObject item = new JSONObject();
                item.put("goodsId", gid);
                item.put("name", dName);
                item.put("imei", nameSrc.optString("imei", ""));
                item.put("deviceType", dtype);
                item.put("deviceTypeLabel", "dryer".equals(dtype) ? "烘干机" : "洗衣机");
                item.put("positionId", pid);
                item.put("positionName", pname);
                item.put("state", st.key);
                item.put("stateLabel", st.label);
                item.put("canOrder", st.canOrder);
                item.put("orderKind", st.orderKind != null ? st.orderKind : JSONObject.NULL);
                item.put("finishTime", finishTime);
                item.put("remainingSeconds", remSec != null ? remSec : JSONObject.NULL);
                recentDevs.put(item);
            } catch (Exception ignored) {}
        }

        JSONArray posArr = new JSONArray();
        int c = 0;
        for (JSONObject p : posById.values()) {
            if (c++ >= 10) break;
            posArr.put(p);
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("positions", posArr);
            payload.put("devices", recentDevs);
            payload.put("loggedIn", !enabled.isEmpty());
        } catch (Exception ignored) {}
        synchronized (lock) {
            recentUsageCache.put(norm, new CachedJson(now, HaileDomain.cloneJson(payload)));
        }
        return payload;
    }

    public JSONObject resolveCustomPosition(String rawInput, String ownerId, boolean autoSwitch) {
        JSONObject parsed = HaileDomain.parseCustomPositionInput(rawInput);
        String kind = parsed.optString("kind", "empty");
        JSONObject out = new JSONObject();
        try {
            if ("empty".equals(kind)) {
                out.put("success", false);
                out.put("message", "请输入点位 ID、机身二维码链接或学校名称");
                return out;
            }
            if ("position_id".equals(kind)) {
                int pid = parsed.optInt("positionId", 0);
                JSONObject det = cachedPositionDetail(pid, 600.0);
                JSONArray devs = new JSONArray();
                try {
                    devs = api.devices(pid);
                } catch (Exception e) {
                    if (det == null) {
                        out.put("success", false);
                        out.put("message", "未找到点位 #" + pid);
                        return out;
                    }
                }
                if (det == null && devs.length() == 0) {
                    out.put("success", false);
                    out.put("message", "点位 #" + pid + " 下未查询到可用洗烘设备");
                    return out;
                }
                String pname = det != null ? det.optString("name", "点位 " + pid) : ("点位 " + pid);
                Double lng = (det != null && det.has("lng")) ? det.optDouble("lng") : null;
                Double lat = (det != null && det.has("lat")) ? det.optDouble("lat") : null;
                String addr = det != null ? det.optString("address", "") : "";
                if (autoSwitch) {
                    JSONObject sw = setPosition(pid, pname, lng, lat, addr, true);
                    if (!sw.optBoolean("success", false)) return sw;
                }
                out.put("success", true);
                out.put("positionId", pid);
                out.put("positionName", pname);
                out.put("address", addr);
                out.put("deviceCount", devs.length());
                if (lng != null) out.put("lng", lng);
                if (lat != null) out.put("lat", lat);
                return out;
            }
            if ("coords".equals(kind)) {
                JSONArray near = nearbyPositions(parsed.optDouble("lng"), parsed.optDouble("lat"), "gcj02", "");
                if (near.length() == 0) {
                    out.put("success", false);
                    out.put("message", "该坐标附近未找到海乐洗衣房点位");
                    return out;
                }
                JSONObject top = near.optJSONObject(0);
                int pid = top.optInt("positionId");
                String pname = top.optString("name", "点位 " + pid);
                if (autoSwitch) {
                    JSONObject sw = setPosition(pid, pname, top.has("lng") ? top.optDouble("lng") : null, top.has("lat") ? top.optDouble("lat") : null, top.optString("address", ""), true);
                    if (!sw.optBoolean("success", false)) return sw;
                }
                out.put("success", true);
                out.put("positionId", pid);
                out.put("positionName", pname);
                return out;
            }
            if ("scan".equals(kind)) {
                String imei = parsed.optString("imei", "").trim();
                String nCode = parsed.optString("n", "").trim();
                JSONArray curRaw = fetchRawDevices(30.0);
                for (int i = 0; i < curRaw.length(); i++) {
                    JSONObject it = curRaw.optJSONObject(i);
                    if (it == null) continue;
                    String itImei = it.optString("imei", "").trim();
                    String itCode = HaileDomain.deviceCodeFor(itImei);
                    if ((!imei.isEmpty() && imei.equals(itImei)) || (!nCode.isEmpty() && nCode.equalsIgnoreCase(itCode))) {
                        synchronized (lock) {
                            out.put("positionId", positionId);
                            out.put("positionName", positionName);
                        }
                        out.put("success", true);
                        out.put("focusGoodsId", it.optInt("id", 0));
                        out.put("focusDeviceName", deviceNameForRaw(it));
                        return out;
                    }
                }
                List<JSONObject> enabled = enabledAccounts(ownerId);
                String token = !enabled.isEmpty() ? enabled.get(0).optString("token", "") : "";
                if (token.isEmpty()) {
                    out.put("success", false);
                    out.put("message", "解析设备二维码或机身码需先登录海乐账号，或直接输入数字点位 ID");
                    return out;
                }
                JSONObject scanRes = api.scanGoods(token, imei, nCode);
                JSONObject sData = scanRes.optJSONObject("data");
                if (scanRes.optInt("code", -1) != 0 || sData == null || sData.optInt("goodsId", 0) <= 0) {
                    out.put("success", false);
                    out.put("message", scanRes.optString("message", "未能识别该设备二维码或机身编码"));
                    return out;
                }
                int gid = sData.optInt("goodsId");
                JSONObject gDet = cachedGoodsDetail(gid, 20.0);
                int pid = gDet.optInt("positionId", 0);
                if (pid <= 0) {
                    out.put("success", false);
                    out.put("message", "已识别设备，但未获取到所属楼栋点位 ID");
                    return out;
                }
                JSONObject pDet = cachedPositionDetail(pid, 600.0);
                String pname = pDet != null ? pDet.optString("name", "") : gDet.optString("shopName", "点位 " + pid);
                if (autoSwitch) {
                    JSONObject sw = setPosition(pid, pname, pDet != null && pDet.has("lng") ? pDet.optDouble("lng") : null, pDet != null && pDet.has("lat") ? pDet.optDouble("lat") : null, pDet != null ? pDet.optString("address", "") : "", true);
                    if (!sw.optBoolean("success", false)) return sw;
                }
                out.put("success", true);
                out.put("positionId", pid);
                out.put("positionName", pname);
                out.put("focusGoodsId", gid);
                out.put("focusDeviceName", gDet.optString("name", ""));
                return out;
            }

            JSONObject searched = searchPositions(parsed.optString("query", ""), ownerId, null, null);
            JSONArray positions = searched.optJSONArray("positions");
            if (positions == null || positions.length() == 0) {
                out.put("success", false);
                out.put("message", "未找到匹配的洗衣房楼栋，请尝试在地图上定位或输入数字点位 ID");
                return out;
            }
            JSONObject top = positions.optJSONObject(0);
            int pid = top.optInt("positionId");
            String pname = top.optString("name", "点位 " + pid);
            if (autoSwitch) {
                JSONObject sw = setPosition(pid, pname, top.has("lng") ? top.optDouble("lng") : null, top.has("lat") ? top.optDouble("lat") : null, top.optString("address", ""), true);
                if (!sw.optBoolean("success", false)) return sw;
            }
            out.put("success", true);
            out.put("positionId", pid);
            out.put("positionName", pname);
            return out;
        } catch (Exception e) {
            try {
                out.put("success", false);
                out.put("message", "解析点位失败");
            } catch (Exception ignored) {}
            return out;
        }
    }

    private JSONObject maybeAutoDiscoverPosition(String token, String ownerId) {
        if (token == null || token.isEmpty()) return null;
        synchronized (lock) {
            if (positionCustomized) return null;
            for (PreemptIntent it : intents.values()) {
                if (it != null && it.isRunning()) return null;
            }
            recentUsageCache.remove(normalizeOwnerId(ownerId));
        }
        JSONObject usage = recentUsage(ownerId, true);
        JSONArray devs = usage.optJSONArray("devices");
        if (devs != null) {
            for (int i = 0; i < devs.length(); i++) {
                JSONObject d = devs.optJSONObject(i);
                if (d != null && d.optInt("positionId", 0) > 0) {
                    JSONObject sw = setPosition(d.optInt("positionId"), d.optString("positionName", ""), null, null, "", true);
                    if (sw.optBoolean("success", false)) return sw;
                    break;
                }
            }
        }
        JSONArray pos = usage.optJSONArray("positions");
        if (pos != null) {
            for (int i = 0; i < pos.length(); i++) {
                JSONObject p = pos.optJSONObject(i);
                if (p != null && !"local".equals(p.optString("source")) && p.optInt("positionId", 0) > 0) {
                    JSONObject sw = setPosition(p.optInt("positionId"), p.optString("name", ""), p.has("lng") ? p.optDouble("lng") : null, p.has("lat") ? p.optDouble("lat") : null, "", true);
                    if (sw.optBoolean("success", false)) return sw;
                    break;
                }
            }
        }
        return null;
    }

    private String deviceNameForRaw(JSONObject item) {
        int pid;
        synchronized (lock) {
            pid = positionId;
        }
        boolean isDefaultPos = (pid == HaileDomain.POSITION_ID);
        if (isDefaultPos) {
            String mapped = HaileDomain.TARGET_IMEIS.get(item.optString("imei", ""));
            if (mapped != null) return mapped;
            if (item.optString("name", "").trim().isEmpty()) return "";
        }
        return HaileDomain.normalizeDeviceDisplayName(item, isDefaultPos);
    }

    private void loadAccounts() {
        synchronized (lock) {
            accounts.clear();
            String raw = readFileText(accountsFile());
            if (raw == null || raw.trim().isEmpty()) return;
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null) accounts.add(normalizeAccountRecord(obj));
                }
            } catch (Exception ignored) {}
        }
    }

    private void saveAccounts() {
        JSONArray arr = new JSONArray();
        synchronized (lock) {
            for (JSONObject acc : accounts) arr.put(normalizeAccountRecord(acc));
        }
        try { writeFileAtomic(accountsFile(), arr.toString(2)); } catch (Exception ignored) {}
    }

    private static JSONObject normalizeAccountRecord(JSONObject acc) {
        JSONObject out = new JSONObject();
        try {
            String phone = acc.optString("phone", "").trim();
            String token = acc.optString("token", "").trim();
            String ownerId = acc.optString("owner_id", acc.optString("ownerId", "default")).trim();
            if (ownerId.isEmpty()) ownerId = "default";
            out.put("phone", phone);
            out.put("token", token);
            out.put("is_active", acc.optBoolean("is_active", true));
            out.put("role", "service");
            out.put("owner_id", ownerId);
            out.put("owner_name", acc.optString("owner_name", acc.optString("ownerName", "")).trim());
            out.put("remark", acc.optString("remark", acc.optString("name", "")).trim());
            if (acc.has("user_id") && !acc.isNull("user_id")) out.put("user_id", acc.get("user_id"));
        } catch (Exception ignored) {}
        return out;
    }

    public BlockingQueue<JSONObject> subscribe() {
        BlockingQueue<JSONObject> q = new LinkedBlockingQueue<>(200);
        synchronized (lock) { subscribers.add(q); }
        return q;
    }

    public void unsubscribe(BlockingQueue<JSONObject> q) {
        synchronized (lock) { subscribers.remove(q); }
    }

    public boolean hasSubscribers() {
        synchronized (lock) { return !subscribers.isEmpty(); }
    }

    public void emit(JSONObject event) {
        JSONObject payload = HaileDomain.cloneJson(event);
        try { payload.put("ts", nowSec()); } catch (Exception ignored) {}
        List<BlockingQueue<JSONObject>> copy;
        synchronized (lock) { copy = new ArrayList<>(subscribers); }
        for (BlockingQueue<JSONObject> q : copy) q.offer(payload);
        notifyStatusListener();
    }

    public void log(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
        String entry = "[" + sdf.format(new Date()) + "] " + message;
        synchronized (lock) {
            while (logs.size() >= 400) logs.pollFirst();
            logs.addLast(entry);
        }
    }

    public int activeIntentCount() {
        Set<PreemptIntent> unique = new HashSet<>();
        synchronized (lock) {
            for (PreemptIntent intent : intents.values()) {
                if (intent.isRunning()) unique.add(intent);
            }
        }
        return unique.size();
    }

    public long ongoingTargetEpochMillis() {
        double offset = clockOffsetSec();
        synchronized (lock) {
            for (PreemptIntent intent : intents.values()) {
                if (!intent.isRunning()) continue;
                if (intent.currentOrder != null && "awaiting_payment".equals(intent.currentOrder.optString("stage"))) {
                    Double rem = paymentRemainingSeconds(intent.currentOrder, offset);
                    if (rem != null && rem > 1.0) {
                        return System.currentTimeMillis() + Math.round(rem * 1000.0);
                    }
                }
            }
            for (JSONObject ord : ordersByNo.values()) {
                if ("running".equals(ord.optString("stage"))) {
                    String est = ord.optString("estimatedFinishTime", "");
                    Double rem = HaileDomain.secondsUntil(est, offset);
                    if (rem != null && rem > 2.0) {
                        return System.currentTimeMillis() + Math.round(rem * 1000.0);
                    }
                }
            }
        }
        return 0L;
    }

    public String buildOngoingSummary() {
        List<PreemptIntent> active = new ArrayList<>();
        Set<PreemptIntent> seen = new HashSet<>();
        JSONObject firstRunningOrder = null;
        synchronized (lock) {
            for (PreemptIntent intent : intents.values()) {
                if (intent.isRunning() && seen.add(intent)) active.add(intent);
            }
            for (JSONObject ord : ordersByNo.values()) {
                if ("running".equals(ord.optString("stage"))) {
                    firstRunningOrder = ord;
                    break;
                }
            }
        }
        if (active.isEmpty()) {
            if (firstRunningOrder != null) {
                String dName = HaileDomain.inferDeviceNameFromOrder(firstRunningOrder);
                return (dName.isEmpty() ? "洗衣设备" : dName) + " 洗烘运行中 · 结束前自动提醒收衣";
            }
            return "本地守护就绪 · 随时可抢占";
        }
        PreemptIntent first = active.get(0);
        String targetLabel = first.isPool ? ("并池盯 " + first.deviceName) : first.deviceName;
        String extra = active.size() > 1 ? " 等" + active.size() + "项" : "";
        if ("holding_payment".equals(first.state) || ("renewing".equals(first.state) && first.currentOrderNo != null && !first.currentOrderNo.isEmpty())) {
            return "已锁单 " + first.deviceName + extra + " · 请尽快去小程序/App支付";
        }
        if ("renewing".equals(first.state)) {
            return "临期续抢中 " + targetLabel + extra;
        }
        if ("holding_reserve".equals(first.state)) {
            return "已预约 " + first.deviceName + extra + " · " + first.status;
        }
        String phase = HaileDomain.POLL_PHASE_LABELS.getOrDefault(first.pollPhase, "轮询抢占");
        return "抢占中 " + targetLabel + extra + " · " + phase + " (" + first.attempts + "次)";
    }

    private void notifyStatusListener() {
        StatusListener listener = this.statusListener;
        if (listener != null) {
            try {
                listener.onStateChanged(activeIntentCount(), buildOngoingSummary(), ongoingTargetEpochMillis());
            } catch (Exception ignored) {}
        }
    }

    private static String orderAlertKey(JSONObject order) {
        if (order == null) return "";
        String orderNo = order.optString("orderNo", "");
        if (orderNo.isEmpty()) return "";
        String stage = order.optString("stage", "");
        boolean ready = HaileDomain.orderReadyForUserAction(order);
        return orderNo + ":" + stage + ":" + (ready ? "ready" : "wait");
    }

    private void checkAndFireOrderAlert(JSONObject order) {
        if (order == null) return;
        String stage = order.optString("stage", "");
        if (!"awaiting_payment".equals(stage) && !"reserve_waiting".equals(stage)) return;
        if (!HaileDomain.orderReadyForUserAction(order)) return;
        String orderNo = order.optString("orderNo", "");
        String key = orderAlertKey(order);
        if (key.isEmpty()) return;
        synchronized (lock) {
            if (alertedOrders.contains(key)) return;
            alertedOrders.add(key);
        }
        String device = HaileDomain.inferDeviceNameFromOrder(order);
        if (device.isEmpty()) device = order.optString("goodsName", "设备");
        String mode = order.optString("modeName", "");
        String title = "awaiting_payment".equals(stage)
            ? ("已抢到 " + device + (mode.isEmpty() ? "" : " · " + mode))
            : ("已排到 " + device + (mode.isEmpty() ? "" : " · " + mode));
        String body = "awaiting_payment".equals(stage)
            ? "请尽快打开海乐生活小程序 / App 完成支付"
            : "前序已结束，请尽快打开海乐生活验证并放入衣物";
        StatusListener listener = this.statusListener;
        if (listener != null) {
            try { listener.onOrderAlert(orderNo, stage, title, body); } catch (Exception ignored) {}
        }
    }

    private void checkPickupTransitions(JSONObject order) {
        if (order == null) return;
        String orderNo = order.optString("orderNo", "");
        if (orderNo.isEmpty()) return;
        String stage = order.optString("stage", "");
        String device = HaileDomain.inferDeviceNameFromOrder(order);
        if (device.isEmpty()) device = order.optString("goodsName", "洗衣设备");
        String ownerId = normalizeOwnerId(order.optString("ownerId"));

        if ("running".equals(stage)) {
            synchronized (lock) { runningOrderNos.add(orderNo); }
            String finishStr = order.optString("estimatedFinishTime", "");
            if (finishStr.isEmpty() && order.has("goodsId")) {
                int gid = order.optInt("goodsId", 0);
                synchronized (lock) {
                    for (int i = 0; i < rawDevices.length(); i++) {
                        JSONObject d = rawDevices.optJSONObject(i);
                        if (d != null && d.optInt("id", 0) == gid) {
                            finishStr = d.optString("finishTime", "");
                            break;
                        }
                    }
                }
            }
            if (!finishStr.isEmpty()) {
                Double rem = HaileDomain.secondsUntil(finishStr, clockOffsetSec());
                if (rem != null && rem > 0 && rem <= HaileDomain.PICKUP_REMINDER_LEAD_SECONDS) {
                    boolean send;
                    synchronized (lock) { send = pickupSoonSent.add(orderNo); }
                    if (send) {
                        int mins = Math.max(1, (int) Math.round(rem / 60.0));
                        log(device + " 预计 " + mins + " 分钟内洗烘完成，准备收衣");
                        JSONObject ev = new JSONObject();
                        try {
                            ev.put("type", "pickup");
                            ev.put("stage", "pickup_soon");
                            ev.put("orderNo", orderNo);
                            ev.put("ownerId", ownerId);
                            ev.put("deviceName", device);
                            ev.put("remainingSeconds", Math.max(0, (int) Math.floor(rem)));
                        } catch (Exception ignored) {}
                        emit(ev);
                        StatusListener listener = this.statusListener;
                        if (listener != null) {
                            try { listener.onOrderAlert(orderNo + ":soon", "pickup_soon", device + " 即将洗完", "预计 " + mins + " 分钟内结束，可准备前往收衣"); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } else if ("ended".equals(stage)) {
            boolean sendDone = false;
            synchronized (lock) {
                if (runningOrderNos.contains(orderNo) && pickupDoneSent.add(orderNo)) {
                    runningOrderNos.remove(orderNo);
                    sendDone = true;
                }
            }
            if (sendDone) {
                log(device + " 洗烘已完成，请及时取衣");
                JSONObject ev = new JSONObject();
                try {
                    ev.put("type", "pickup");
                    ev.put("stage", "pickup_done");
                    ev.put("orderNo", orderNo);
                    ev.put("ownerId", ownerId);
                    ev.put("deviceName", device);
                    ev.put("remainingSeconds", 0);
                } catch (Exception ignored) {}
                emit(ev);
                StatusListener listener = this.statusListener;
                if (listener != null) {
                    try { listener.onOrderAlert(orderNo + ":done", "pickup_done", device + " 洗烘已完成", "请及时前往洗衣房取回衣物"); } catch (Exception ignored) {}
                }
            }
        }
    }

    public JSONObject getAccount(String phone) {
        synchronized (lock) {
            for (JSONObject acc : accounts) {
                if (phone != null && phone.equals(acc.optString("phone"))) return acc;
            }
        }
        return null;
    }

    public List<JSONObject> ownedAccounts(String ownerId) {
        String norm = normalizeOwnerId(ownerId);
        List<JSONObject> res = new ArrayList<>();
        synchronized (lock) {
            for (JSONObject acc : accounts) {
                if (norm.equals(normalizeOwnerId(acc.optString("owner_id")))) res.add(acc);
            }
        }
        return res;
    }

    public List<JSONObject> enabledAccounts(String ownerId) {
        List<JSONObject> base;
        if (ownerId == null) {
            synchronized (lock) { base = new ArrayList<>(accounts); }
        } else {
            base = ownedAccounts(ownerId);
        }
        List<JSONObject> res = new ArrayList<>();
        for (JSONObject acc : base) {
            if (acc.optBoolean("is_active", true) && !HaileDomain.isTokenExpired(acc.optString("token", ""))) {
                res.add(acc);
            }
        }
        return res;
    }

    public JSONObject getOwnedAccount(String phone, String ownerId) {
        String norm = normalizeOwnerId(ownerId);
        synchronized (lock) {
            for (JSONObject acc : accounts) {
                if (phone != null && phone.equals(acc.optString("phone"))
                    && norm.equals(normalizeOwnerId(acc.optString("owner_id")))) {
                    return acc;
                }
            }
        }
        return null;
    }

    public JSONObject sendCode(String phone) {
        JSONObject res = api.sendCode(phone);
        if (res.optInt("code", -1) == 0) log("验证码已发送: " + phone);
        else log("验证码发送失败: " + phone + " " + res.optString("message"));
        return res;
    }

    public JSONObject login(String phone, String code, String ownerId, String ownerName, String remark) {
        JSONObject result = api.login(phone, code);
        JSONObject resp = new JSONObject();
        try {
            if (result.optInt("code", -1) != 0) {
                log("登录失败: " + phone + " " + result.optString("message"));
                resp.put("success", false);
                resp.put("message", result.optString("message", "登录失败"));
                return resp;
            }
            JSONObject data = result.optJSONObject("data");
            JSONObject prev = getAccount(phone);
            JSONObject newAcc = new JSONObject();
            String newToken = data != null ? data.optString("token", "") : "";
            newAcc.put("phone", phone);
            newAcc.put("token", newToken);
            if (data != null && data.has("userId")) newAcc.put("user_id", data.get("userId"));
            newAcc.put("is_active", true);
            newAcc.put("role", "service");
            newAcc.put("owner_id", normalizeOwnerId(ownerId));
            String finalOwnerName = (ownerName != null && !ownerName.trim().isEmpty())
                ? ownerName.trim() : (prev != null ? prev.optString("owner_name", "") : "");
            String finalRemark = (remark != null && !remark.trim().isEmpty())
                ? remark.trim() : (prev != null ? prev.optString("remark", "") : "");
            if (finalRemark.isEmpty() && !newToken.isEmpty()) {
                try {
                    JSONObject info = api.accountInfo(newToken);
                    if (info.optInt("code", -1) == 0) {
                        JSONObject infoData = info.optJSONObject("data");
                        String nick = infoData != null ? infoData.optString("nickname", "").trim() : "";
                        if (!nick.isEmpty() && !nick.equals(phone)) finalRemark = nick;
                    }
                } catch (Exception ignored) {}
            }
            newAcc.put("owner_name", finalOwnerName);
            newAcc.put("remark", finalRemark);

            synchronized (lock) {
                accounts.removeIf(a -> phone.equals(a.optString("phone")));
                accounts.add(newAcc);
            }
            saveAccounts();
            log("登录成功: " + phone);
            migrateIntentsForLogin(newAcc);
            refreshAccountOrders(newAcc);
            synchronized (lock) { recentUsageCache.remove(normalizeOwnerId(ownerId)); }
            JSONObject autoPos = maybeAutoDiscoverPosition(newToken, ownerId);
            resp.put("success", true);
            if (autoPos != null) resp.put("autoPosition", autoPos);
        } catch (Exception ignored) {}
        return resp;
    }

    public int migrateIntentsForLogin(JSONObject account) {
        String phone = account.optString("phone", "");
        String ownerId = normalizeOwnerId(account.optString("owner_id"));
        if (phone.isEmpty()) return 0;
        Set<PreemptIntent> matching = new HashSet<>();
        synchronized (lock) {
            for (PreemptIntent intent : intents.values()) {
                if (phone.equals(intent.accountPhone) && intent.isRunning()) matching.add(intent);
            }
        }
        int migrated = 0;
        for (PreemptIntent intent : matching) {
            String prevOwner = normalizeOwnerId(intent.ownerId);
            intent.account = account;
            intent.accountPhone = phone;
            if (!prevOwner.equals(ownerId)) {
                intent.ownerId = ownerId;
                log(intent.deviceName + " 抢占意图随账号 " + phone + " 转移到当前用户");
            }
            migrated++;
        }
        if (migrated > 0) {
            saveIntents();
            JSONObject ev = new JSONObject();
            try {
                ev.put("type", "intent");
                ev.put("state", "migrated");
                ev.put("accountPhone", phone);
            } catch (Exception ignored) {}
            emit(ev);
        }
        return migrated;
    }

    public JSONObject setAccountEnabled(String phone, boolean enabled, String ownerId) {
        JSONObject acc = getOwnedAccount(phone, ownerId);
        JSONObject res = new JSONObject();
        try {
            if (acc == null) {
                res.put("success", false);
                res.put("message", "账号不属于当前用户");
                return res;
            }
            acc.put("is_active", enabled);
            saveAccounts();
            log("账号 " + (enabled ? "启用" : "停用") + ": " + phone);
            res.put("success", true);
        } catch (Exception ignored) {}
        return res;
    }

    public JSONObject setAccountRemark(String phone, String remark, String ownerId) {
        JSONObject acc = getOwnedAccount(phone, ownerId);
        JSONObject res = new JSONObject();
        try {
            if (acc == null) {
                res.put("success", false);
                res.put("message", "账号不属于当前用户");
                return res;
            }
            acc.put("remark", remark != null ? remark.trim() : "");
            saveAccounts();
            log("账号备注已更新: " + phone);
            res.put("success", true);
        } catch (Exception ignored) {}
        return res;
    }

    public JSONArray modes(int goodsId) throws Exception {
        double now = nowSec();
        synchronized (lock) {
            CachedModes cached = modeCache.get(goodsId);
            if (cached != null && now - cached.cachedAt < 3600.0) return cached.modes;
        }
        JSONArray fetched = api.modes(goodsId);
        synchronized (lock) { modeCache.put(goodsId, new CachedModes(now, fetched)); }
        return fetched;
    }

    public JSONObject selectMode(String deviceName, int goodsId, Integer requestedModeId, String preferredModeName) throws Exception {
        JSONArray items = modes(goodsId);
        if (requestedModeId != null && requestedModeId > 0) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject m = items.optJSONObject(i);
                if (m != null && m.optInt("id", -1) == requestedModeId) return m;
            }
        }
        if (preferredModeName != null && !preferredModeName.trim().isEmpty()) {
            return HaileDomain.matchModeByPreference(deviceName, items, preferredModeName);
        }
        return HaileDomain.selectPreferredMode(deviceName, items);
    }

    public Integer resolveSelfCleanItemId(int goodsId, JSONObject mode, boolean selfClean) {
        if (!selfClean || !HaileDomain.modeSupportsSelfClean(mode)) return null;
        try { return HaileDomain.findSelfCleanItemId(modes(goodsId)); } catch (Exception e) { return null; }
    }

    public static JSONObject stabilizeOrderTiming(JSONObject order, JSONObject previous) {
        if (order == null) return null;
        JSONObject stable = HaileDomain.cloneJson(order);
        if (previous != null) {
            String leftNo = stable.optString("orderNo", "");
            String rightNo = previous.optString("orderNo", "");
            if (!leftNo.isEmpty() && leftNo.equals(rightNo)) {
                String[] keys = new String[]{"deadline", "expireSeconds", "createdAtLocal"};
                for (String key : keys) {
                    String cur = HaileDomain.optNullableString(stable, key);
                    String prevVal = HaileDomain.optNullableString(previous, key);
                    if ((cur == null || cur.isEmpty()) && prevVal != null && !prevVal.isEmpty()) {
                        try { stable.put(key, previous.get(key)); } catch (Exception ignored) {}
                    }
                }
                if (!stable.optBoolean("selfClean", false) && previous.optBoolean("selfClean", false)) {
                    try { stable.put("selfClean", true); } catch (Exception ignored) {}
                }
            }
        }
        if ("awaiting_payment".equals(stable.optString("stage")) && stable.optString("deadline", "").isEmpty()) {
            try {
                if (stable.optInt("expireSeconds", 0) <= 0) stable.put("expireSeconds", PAYMENT_EXPIRE_FALLBACK_SECONDS);
                if (stable.optDouble("createdAtLocal", 0.0) <= 0.0) stable.put("createdAtLocal", nowSec());
            } catch (Exception ignored) {}
        }
        return stable;
    }

    public static Double paymentRemainingSeconds(JSONObject order, double clockOffsetSec) {
        if (order == null) return null;
        Double rem = HaileDomain.secondsUntil(HaileDomain.optNullableString(order, "deadline"), clockOffsetSec);
        if (rem != null) return rem;
        int expireSec = order.optInt("expireSeconds", 0);
        double createdAt = order.optDouble("createdAtLocal", 0.0);
        if (expireSec > 0 && createdAt > 0.0) {
            return expireSec - (nowSec() - createdAt);
        }
        return null;
    }

    public List<JSONObject> refreshAccountOrders(JSONObject acc) {
        String token = acc.optString("token", "");
        String phone = acc.optString("phone", "");
        if (token.isEmpty() || HaileDomain.isTokenExpired(token)) return Collections.emptyList();

        Map<String, JSONObject> found = new LinkedHashMap<>();
        try {
            JSONObject underway = api.underwayOrders(token);
            if (HaileDomain.isAuthFailure(underway.optInt("code", -1), underway.optString("message", ""))) {
                return Collections.emptyList();
            }
            if (underway.optInt("code", -1) == 0) {
                JSONArray arr = underway.optJSONArray("data");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item != null) {
                            String no = item.optString("orderNo", "");
                            if (!no.isEmpty()) found.put(no, HaileApi.summarizeListItem(item, phone));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("订单列表读取失败 " + phone + ": " + e.getMessage());
        }

        try {
            JSONObject trade = api.tradeList(token, 20);
            if (trade.optInt("code", -1) == 0) {
                JSONObject data = trade.optJSONObject("data");
                JSONArray items = data != null ? data.optJSONArray("items") : null;
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item != null) {
                            String no = item.optString("orderNo", "");
                            if (!no.isEmpty() && !found.containsKey(no)) found.put(no, HaileApi.summarizeListItem(item, phone));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("订单历史读取失败 " + phone + ": " + e.getMessage());
        }

        synchronized (lock) {
            for (String trackedNo : new ArrayList<>(runningOrderNos)) {
                if (!found.containsKey(trackedNo)) {
                    JSONObject old = ordersByNo.get(trackedNo);
                    if (old != null && phone.equals(old.optString("accountPhone"))) {
                        found.put(trackedNo, HaileDomain.cloneJson(old));
                    }
                }
            }
        }

        for (String orderNo : new ArrayList<>(found.keySet())) {
            try {
                JSONObject summaryItem = found.get(orderNo);
                JSONObject existing;
                synchronized (lock) { existing = ordersByNo.get(orderNo); }
                if (summaryItem != null && "ended".equals(summaryItem.optString("stage"))
                    && (existing == null || "ended".equals(existing.optString("stage")))) {
                    continue;
                }
                JSONObject detail = api.orderDetail(token, orderNo);
                if (detail.optInt("code", -1) == 0) {
                    JSONObject prev = summaryItem != null ? summaryItem : existing;
                    JSONObject summarized = HaileApi.summarizeDetail(detail.optJSONObject("data"), phone);
                    found.put(orderNo, stabilizeOrderTiming(summarized, prev));
                }
            } catch (Exception ignored) {}
        }

        List<JSONObject> result = new ArrayList<>();
        synchronized (lock) {
            boolean firstSync = seededOrderAccounts.add(phone);
            for (JSONObject order : found.values()) {
                String no = order.optString("orderNo", "");
                if (!no.isEmpty()) {
                    try { order.put("ownerId", normalizeOwnerId(acc.optString("owner_id"))); } catch (Exception ignored) {}
                    JSONObject stabilized = stabilizeOrderTiming(order, ordersByNo.get(no));
                    ordersByNo.put(no, stabilized);
                    result.add(stabilized);
                    if (firstSync && "running".equals(stabilized.optString("stage"))) {
                        String rk = orderAlertKey(stabilized);
                        if (!rk.isEmpty()) alertedOrders.add(rk);
                    } else {
                        checkAndFireOrderAlert(stabilized);
                    }
                }
            }
        }
        for (JSONObject stabilized : result) {
            checkPickupTransitions(stabilized);
        }
        notifyStatusListener();
        return result;
    }

    public void refreshAllOrders(String ownerId) {
        for (JSONObject acc : enabledAccounts(ownerId)) refreshAccountOrders(acc);
        pruneExpiredOrders();
    }

    public void pruneExpiredOrders() {
        double now = nowSec();
        List<String> expired = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, JSONObject> entry : ordersByNo.entrySet()) {
                JSONObject o = entry.getValue();
                if ("ended".equals(o.optString("stage")) && o.optDouble("updatedAt", 0.0) + ORDER_RETENTION_SECONDS < now) {
                    expired.add(entry.getKey());
                }
            }
            for (String no : expired) {
                ordersByNo.remove(no);
                runningOrderNos.remove(no);
                pickupSoonSent.remove(no);
                pickupDoneSent.remove(no);
            }
        }
    }

    public void startBackgroundRefresh() {
        synchronized (lock) {
            if (pollerStarted) return;
            pollerStarted = true;
        }
        Thread t = new Thread(() -> {
            while (!stopped) {
                try { refreshAllOrders(null); } catch (Exception e) { log("后台订单刷新失败: " + e.getMessage()); }
                try { Thread.sleep(15_000L); } catch (InterruptedException ie) { break; }
            }
        }, "haile-v4-order-refresh");
        t.setDaemon(true);
        t.start();
    }

    private JSONArray fetchRawDevices(double maxAge) {
        double now = nowSec();
        int pid;
        synchronized (lock) {
            if (maxAge > 0 && rawDevices.length() > 0 && now - rawDevicesAt < maxAge) return rawDevices;
            pid = positionId;
        }
        try {
            JSONArray items = api.devices(pid);
            synchronized (lock) {
                rawDevices = items;
                rawDevicesAt = nowSec();
            }
            return items;
        } catch (Exception e) {
            log("设备列表读取失败: " + e.getMessage());
            synchronized (lock) { return rawDevices; }
        }
    }

    private String runInfoDeadTime(int goodsId) {
        double now = nowSec();
        synchronized (lock) {
            CachedRunInfo cached = runInfoCache.get(goodsId);
            if (cached != null && now - cached.cachedAt < RUN_INFO_CACHE_SECONDS) return cached.deadTime;
        }
        String deadTime = "";
        try {
            JSONObject res = api.runInfo(goodsId);
            if (res.optInt("code", -1) == 0) {
                JSONObject data = res.optJSONObject("data");
                String candidate = data != null ? data.optString("deadTime", "") : "";
                Double rem = HaileDomain.secondsUntil(candidate, clockOffsetSec());
                if (!candidate.isEmpty() && rem != null && rem > 0) deadTime = candidate;
            }
        } catch (Exception ignored) {}
        synchronized (lock) { runInfoCache.put(goodsId, new CachedRunInfo(now, deadTime)); }
        return deadTime;
    }

    private void diffDeviceStates(JSONArray items) {
        Map<Integer, String> nextSigs = new HashMap<>();
        List<JSONObject> events = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = deviceNameForRaw(item);
            if (name.isEmpty()) continue;
            int goodsId = item.optInt("id", 0);
            if (goodsId <= 0) continue;
            String sig = item.optInt("state", 0) + "|"
                + item.optBoolean("enableReserve", false) + "|"
                + item.optInt("reserveState", 0) + "|"
                + item.optString("finishTime", "");
            nextSigs.put(goodsId, sig);
            String prev;
            synchronized (lock) { prev = deviceSigs.get(goodsId); }
            if (prev != null && !prev.equals(sig)) {
                JSONObject ev = new JSONObject();
                try {
                    ev.put("type", "device");
                    ev.put("goodsId", goodsId);
                    ev.put("name", name);
                    ev.put("stateKey", HaileDomain.classifyDevice(item, false).key);
                } catch (Exception ignored) {}
                events.add(ev);
            }
        }
        synchronized (lock) {
            deviceSigs.clear();
            deviceSigs.putAll(nextSigs);
        }
        for (JSONObject ev : events) emit(ev);
    }

    public void startDeviceMonitor() {
        synchronized (lock) {
            if (monitorStarted) return;
            monitorStarted = true;
        }
        Thread t = new Thread(() -> {
            while (!stopped) {
                try {
                    if (hasSubscribers()) {
                        JSONArray items = fetchRawDevices(0.0);
                        diffDeviceStates(items);
                        Thread.sleep((long) (DEVICE_MONITOR_SECONDS * 1000));
                    } else {
                        Thread.sleep(10_000L);
                    }
                } catch (Exception e) {
                    try { Thread.sleep(8_000L); } catch (InterruptedException ie) { break; }
                }
            }
        }, "haile-v4-device-monitor");
        t.setDaemon(true);
        t.start();
    }

    public void saveIntents() {
        JSONArray specs = new JSONArray();
        Set<PreemptIntent> seen = new HashSet<>();
        synchronized (lock) {
            for (PreemptIntent intent : intents.values()) {
                if (intent.isRunning() && seen.add(intent)) specs.put(intent.persistSpec());
            }
        }
        try { writeFileAtomic(intentsFile(), specs.toString(2)); } catch (Exception ignored) {}
    }

    public void rekeyIntent(PreemptIntent intent, int newKey) {
        synchronized (lock) {
            int oldKey = intent.intentKey;
            if (intents.get(oldKey) == intent) intents.remove(oldKey);
            intent.intentKey = newKey;
            intents.put(newKey, intent);
        }
    }

    public void forgetIntent(int keyId, String ownerId, PreemptIntent ref) {
        String norm = normalizeOwnerId(ownerId);
        synchronized (lock) {
            List<Integer> toRemove = new ArrayList<>();
            for (Map.Entry<Integer, PreemptIntent> e : intents.entrySet()) {
                if (ref != null && e.getValue() == ref) toRemove.add(e.getKey());
                else if (e.getKey() == keyId && norm.equals(normalizeOwnerId(e.getValue().ownerId))) toRemove.add(e.getKey());
            }
            for (Integer k : toRemove) intents.remove(k);
        }
        saveIntents();
        notifyStatusListener();
    }

    public void restoreIntents() {
        String raw = readFileText(intentsFile());
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            JSONArray specs = new JSONArray(raw);
            int restored = 0;
            for (int i = 0; i < specs.length(); i++) {
                JSONObject spec = specs.optJSONObject(i);
                if (spec == null) continue;
                int goodsId = spec.optInt("goodsId", 0);
                boolean anyWasher = spec.optBoolean("anyWasher", false);
                List<Integer> poolIds = null;
                JSONArray cands = spec.optJSONArray("candidateGoodsIds");
                if (cands != null && cands.length() > 1) {
                    poolIds = new ArrayList<>();
                    for (int j = 0; j < cands.length(); j++) {
                        int v = cands.optInt(j, 0);
                        if (v > 0) poolIds.add(v);
                    }
                }
                if (goodsId == 0 && !anyWasher && poolIds == null) continue;
                String ownerId = normalizeOwnerId(spec.optString("ownerId", "default"));
                Integer modeId = spec.has("modeId") && !spec.isNull("modeId") ? spec.optInt("modeId") : null;
                Integer dryerMins = spec.has("dryerMinutes") && !spec.isNull("dryerMinutes") ? spec.optInt("dryerMinutes") : null;
                boolean selfClean = spec.optBoolean("selfClean", false);
                String prefName = spec.optString("preferredModeName", spec.optString("modeName", ""));
                refreshAllOrders(ownerId);
                JSONObject res = startIntent(goodsId, modeId, ownerId, dryerMins, selfClean, poolIds, anyWasher && poolIds == null, prefName);
                if (res.optBoolean("success", false)) restored++;
            }
            if (restored > 0) log("已恢复 " + restored + " 个抢占意图");
        } catch (Exception e) {
            log("抢占恢复文件读取失败: " + e.getMessage());
        }
    }

    public void saveHolds() {
        JSONObject active = new JSONObject();
        double now = nowSec();
        synchronized (lock) {
            for (Map.Entry<String, Double> e : devicePaymentHolds.entrySet()) {
                if (e.getValue() != null && e.getValue() > now) {
                    try { active.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
                }
            }
        }
        try { writeFileAtomic(holdsFile(), active.toString(2)); } catch (Exception ignored) {}
    }

    public void restoreHolds() {
        String raw = readFileText(holdsFile());
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            JSONObject data = new JSONObject(raw);
            double now = nowSec();
            synchronized (lock) {
                JSONArray names = data.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String name = names.optString(i);
                        double exp = data.optDouble(name, 0.0);
                        if (exp > now) devicePaymentHolds.put(name, exp);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public List<JSONObject> blockingOrders(String phone, String ownerId) {
        List<JSONObject> orders;
        synchronized (lock) { orders = new ArrayList<>(ordersByNo.values()); }
        String normOwner = ownerId != null ? normalizeOwnerId(ownerId) : null;
        List<JSONObject> res = new ArrayList<>();
        for (JSONObject o : orders) {
            if (phone != null && !phone.equals(o.optString("accountPhone"))) continue;
            if (normOwner != null && !normOwner.equals(normalizeOwnerId(o.optString("ownerId")))) continue;
            if (HaileDomain.orderBlocksAccount(o)) res.add(o);
        }
        return res;
    }

    public JSONObject orderForDevice(String deviceName, String ownerId, Integer goodsId) {
        List<JSONObject> orders;
        synchronized (lock) { orders = new ArrayList<>(ordersByNo.values()); }
        String normOwner = ownerId != null ? normalizeOwnerId(ownerId) : null;
        JSONObject best = null;
        double bestUpdated = -1.0;
        for (JSONObject o : orders) {
            if (normOwner != null && !normOwner.equals(normalizeOwnerId(o.optString("ownerId")))) continue;
            if ("ended".equals(o.optString("stage"))) continue;
            int orderGoodsId = o.optInt("goodsId", -1);
            boolean matchedById = goodsId != null && orderGoodsId > 0 && orderGoodsId == goodsId;
            if (!matchedById && (deviceName == null || deviceName.isEmpty() || !deviceName.equals(HaileDomain.inferDeviceNameFromOrder(o)))) continue;
            double u = o.optDouble("updatedAt", 0.0);
            if (best == null || u >= bestUpdated) {
                best = o;
                bestUpdated = u;
            }
        }
        return best;
    }

    public JSONObject orderWithDeviceTiming(JSONObject order, String deviceFinishTime) {
        if (order == null) return null;
        JSONObject timed = HaileDomain.cloneJson(order);
        String stage = timed.optString("stage", "");
        double offset = clockOffsetSec();
        try {
            if ("running".equals(stage) && deviceFinishTime != null && !deviceFinishTime.isEmpty()) {
                timed.put("estimatedFinishTime", deviceFinishTime);
                timed.put("estimatedFinishSource", "device");
            } else if (!timed.optString("estimatedFinishTime", "").isEmpty()) {
                timed.put("estimatedFinishSource", "order");
            } else {
                timed.put("estimatedFinishSource", "");
            }

            String est = timed.optString("estimatedFinishTime", "");
            if (!est.isEmpty()) {
                Double rem = HaileDomain.secondsUntil(est, offset);
                timed.put("estimatedRemainingSeconds", rem == null ? JSONObject.NULL : Math.max(0, (int) Math.floor(rem)));
            } else {
                timed.put("estimatedRemainingSeconds", JSONObject.NULL);
            }

            String dl = timed.optString("deadline", "");
            if (!dl.isEmpty()) {
                Double rem = HaileDomain.secondsUntil(dl, offset);
                timed.put("deadlineRemainingSeconds", rem == null ? JSONObject.NULL : Math.max(0, (int) Math.floor(rem)));
            } else {
                timed.put("deadlineRemainingSeconds", JSONObject.NULL);
            }
        } catch (Exception ignored) {}
        return timed;
    }

    public int accountUsage(String phone, String ownerId) {
        String norm = normalizeOwnerId(ownerId);
        List<JSONObject> blocking = blockingOrders(phone, norm);
        Set<String> held = new HashSet<>();
        for (JSONObject o : blocking) held.add(o.optString("orderNo", ""));
        int extraIntents = 0;
        Set<PreemptIntent> seen = new HashSet<>();
        synchronized (lock) {
            for (PreemptIntent intent : intents.values()) {
                if (!seen.add(intent)) continue;
                if (!intent.isRunning() || !phone.equals(intent.accountPhone)) continue;
                if (intent.currentOrderNo != null && !intent.currentOrderNo.isEmpty() && held.contains(intent.currentOrderNo)) continue;
                extraIntents++;
            }
        }
        return blocking.size() + extraIntents;
    }

    public JSONObject chooseAccount(String ownerId) {
        String norm = normalizeOwnerId(ownerId);
        refreshAllOrders(norm);
        List<JSONObject> candidates = new ArrayList<>();
        for (JSONObject acc : enabledAccounts(norm)) {
            if (accountUsage(acc.optString("phone", ""), norm) < ACCOUNT_MAX_ACTIVE_SLOTS) candidates.add(acc);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    public String noAccountReason(String ownerId) {
        String norm = normalizeOwnerId(ownerId);
        if (ownedAccounts(norm).isEmpty()) return "当前还没有登录账号";
        List<JSONObject> enabled = enabledAccounts(norm);
        if (enabled.isEmpty()) return "账号登录已失效，请重新接码登录";
        for (JSONObject acc : enabled) {
            int usage = accountUsage(acc.optString("phone", ""), norm);
            if (usage >= ACCOUNT_MAX_ACTIVE_SLOTS) {
                String label = acc.optString("remark", "").isEmpty() ? acc.optString("phone") : acc.optString("remark");
                return "账号 " + label + " 已有 " + usage + " 个占用中的订单/抢占（单账号上限 " + ACCOUNT_MAX_ACTIVE_SLOTS + "）";
            }
        }
        return "没有可用账号";
    }

    public PreemptIntent liveIntent(int goodsId) {
        synchronized (lock) {
            PreemptIntent intent = intents.get(goodsId);
            if (intent == null || !intent.isRunning()) return null;
            return intent;
        }
    }

    public PreemptIntent poolIntentForOwner(String ownerId) {
        String norm = normalizeOwnerId(ownerId);
        synchronized (lock) {
            for (PreemptIntent intent : intents.values()) {
                if (intent.isRunning() && intent.isPool && norm.equals(normalizeOwnerId(intent.ownerId))) {
                    return intent;
                }
            }
        }
        return null;
    }

    public String otherUserLabel(PreemptIntent intent) {
        if (intent == null) return "";
        JSONObject acc = getAccount(intent.accountPhone);
        String remark = acc != null ? acc.optString("remark", "").trim() : "";
        if (!remark.isEmpty()) return remark;
        String phone = intent.accountPhone != null ? intent.accountPhone.trim() : "";
        if (phone.length() >= 4) return "尾号" + phone.substring(phone.length() - 4);
        return !phone.isEmpty() ? phone : "其他用户";
    }

    public JSONArray devices(String ownerId) {
        String norm = normalizeOwnerId(ownerId);
        JSONArray rawItems = fetchRawDevices(RAW_DEVICES_CACHE_SECONDS);
        List<JSONObject> deviceList = new ArrayList<>();
        double now = nowSec();
        double offset = clockOffsetSec();
        PreemptIntent ownerPool = poolIntentForOwner(norm);

        for (int i = 0; i < rawItems.length(); i++) {
            JSONObject item = rawItems.optJSONObject(i);
            if (item == null || !item.has("id")) continue;
            String imei = item.optString("imei", "");
            String name = deviceNameForRaw(item);
            if (name.isEmpty()) continue;
            int goodsId = item.optInt("id", 0);

            Double holdExp;
            synchronized (lock) {
                if (item.optInt("state", 0) == 2 && item.optBoolean("enableReserve", false) && item.optInt("reserveState", 0) == 1) {
                    devicePaymentHolds.remove(name);
                    holdExp = null;
                } else {
                    holdExp = devicePaymentHolds.get(name);
                }
            }
            boolean holdActive = holdExp != null && holdExp > now;
            JSONObject order = orderForDevice(name, norm, goodsId);
            if (order != null) {
                String st = order.optString("stage", "");
                if ("awaiting_payment".equals(st) || "reserve_waiting".equals(st)) holdActive = false;
            }
            HaileDomain.DeviceState state = HaileDomain.classifyDevice(item, holdActive);
            String finishTime = item.optString("finishTime", "");
            if ("null".equalsIgnoreCase(finishTime)) finishTime = "";
            if (finishTime.isEmpty() && item.optInt("state", 0) != 1) {
                finishTime = runInfoDeadTime(goodsId);
            }
            PreemptIntent intent = liveIntent(goodsId);
            PreemptIntent visibleIntent = (intent != null && norm.equals(normalizeOwnerId(intent.ownerId))) ? intent : null;
            boolean inMyPool = ownerPool != null && ownerPool.poolGoodsIds.contains(goodsId);
            if (visibleIntent == null && inMyPool && ownerPool.goodsId == goodsId) {
                visibleIntent = ownerPool;
            }
            boolean occupiedByOther = intent != null && !norm.equals(normalizeOwnerId(intent.ownerId));
            JSONObject activeOrder = orderWithDeviceTiming(order, finishTime);
            String devType = HaileDomain.deviceTypeFromName(name);
            Double remSec = HaileDomain.secondsUntil(finishTime, offset);
            String[] userLabels = HaileRecommendation.deviceUserLabels(item, state, activeOrder, visibleIntent != null ? visibleIntent.state : "");

            JSONObject dev = new JSONObject();
            try {
                dev.put("name", name);
                dev.put("goodsId", goodsId);
                dev.put("imei", imei);
                dev.put("deviceType", devType);
                dev.put("deviceTypeLabel", "dryer".equals(devType) ? "烘干机" : "洗衣机");
                dev.put("state", state.key);
                dev.put("stateLabel", userLabels[0]);
                dev.put("queueCount", state.queueCount != null ? state.queueCount : JSONObject.NULL);
                dev.put("queueText", userLabels[1]);
                dev.put("canOrder", state.canOrder);
                dev.put("orderKind", state.orderKind != null ? state.orderKind : JSONObject.NULL);
                dev.put("finishTime", finishTime);
                dev.put("remainingSeconds", remSec != null ? remSec : JSONObject.NULL);
                dev.put("activeOrder", activeOrder != null ? activeOrder : JSONObject.NULL);
                dev.put("intent", visibleIntent != null ? visibleIntent.snapshot() : JSONObject.NULL);
                dev.put("inPool", inMyPool);
                dev.put("occupiedByOtherUser", occupiedByOther);
                dev.put("occupiedByOtherUserLabel", occupiedByOther ? otherUserLabel(intent) : "");
            } catch (Exception ignored) {}
            deviceList.add(dev);
        }

        HaileRecommendation.decorateDeviceRecommendations(deviceList);
        deviceList.sort(Comparator.comparing(d -> d.optString("name", "")));
        JSONArray arr = new JSONArray();
        for (JSONObject d : deviceList) arr.put(d);
        return arr;
    }

    public JSONObject startIntent(int goodsId, Integer modeId, String ownerId, Integer dryerMinutes, boolean selfClean) {
        return startIntent(goodsId, modeId, ownerId, dryerMinutes, selfClean, null, false, null);
    }

    public JSONObject startIntent(
        int goodsId,
        Integer modeId,
        String ownerId,
        Integer dryerMinutes,
        boolean selfClean,
        List<Integer> goodsIds,
        boolean anyWasher,
        String preferredModeName
    ) {
        String norm = normalizeOwnerId(ownerId);
        JSONObject resp = new JSONObject();
        try {
            JSONArray rawList = fetchRawDevices(0.0);
            List<Integer> candIds = new ArrayList<>();
            if (goodsIds != null && !goodsIds.isEmpty()) {
                for (Integer g : goodsIds) {
                    if (g != null && g > 0 && !candIds.contains(g)) candIds.add(g);
                }
            } else if (anyWasher || goodsId < 0) {
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject it = rawList.optJSONObject(i);
                    if (it == null) continue;
                    String n = deviceNameForRaw(it);
                    if (!n.isEmpty() && "washer".equals(HaileDomain.deviceTypeFromName(n))) {
                        int id = it.optInt("id", 0);
                        if (id > 0) candIds.add(id);
                    }
                }
            }

            if (candIds.size() == 1 && !anyWasher) {
                goodsId = candIds.get(0);
                candIds.clear();
            }

            if (!candIds.isEmpty() && (anyWasher || candIds.size() > 1)) {
                int poolKey = poolKeyForOwner(norm);
                synchronized (lock) {
                    PreemptIntent curPool = intents.get(poolKey);
                    if (curPool != null && curPool.isRunning()) {
                        resp.put("success", false);
                        resp.put("message", "已在并池抢占最快洗衣机中");
                        return resp;
                    }
                }
                List<JSONObject> rawCands = new ArrayList<>();
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject it = rawList.optJSONObject(i);
                    if (it != null && candIds.contains(it.optInt("id", -1))) rawCands.add(it);
                }
                if (rawCands.isEmpty()) {
                    resp.put("success", false);
                    resp.put("message", "未找到可并池抢占的洗衣机");
                    return resp;
                }
                JSONObject firstRaw = rawCands.get(0);
                int firstGid = firstRaw.optInt("id");
                String firstName = deviceNameForRaw(firstRaw);
                String pref = (preferredModeName != null && !preferredModeName.trim().isEmpty()) ? preferredModeName.trim() : "标准";
                JSONObject mode = selectMode(firstName, firstGid, modeId, pref);
                if (mode == null) {
                    resp.put("success", false);
                    resp.put("message", "没有可用套餐");
                    return resp;
                }
                boolean effSc = selfClean && HaileDomain.modeSupportsSelfClean(mode);
                Integer scId = resolveSelfCleanItemId(firstGid, mode, effSc);
                JSONObject acc = chooseAccount(norm);
                if (acc == null) {
                    resp.put("success", false);
                    resp.put("message", noAccountReason(norm));
                    return resp;
                }
                List<Integer> finalPoolIds = new ArrayList<>();
                for (JSONObject rc : rawCands) finalPoolIds.add(rc.optInt("id"));
                PreemptIntent intent = new PreemptIntent(firstRaw, mode, acc, norm, dryerMinutes, effSc, scId, null, finalPoolIds, pref, poolKey);
                synchronized (lock) { intents.put(poolKey, intent); }
                intent.start();
                saveIntents();
                JSONObject ev = new JSONObject();
                ev.put("type", "intent");
                ev.put("goodsId", poolKey);
                ev.put("deviceName", intent.deviceName);
                ev.put("state", "started");
                ev.put("isPool", true);
                emit(ev);
                resp.put("success", true);
                resp.put("intentKey", poolKey);
                resp.put("isPool", true);
                return resp;
            }

            synchronized (lock) {
                PreemptIntent cur = intents.get(goodsId);
                if (cur != null && cur.isRunning()) {
                    resp.put("success", false);
                    resp.put("message", "这台设备已经在抢占中");
                    return resp;
                }
            }
            JSONObject raw = null;
            for (int i = 0; i < rawList.length(); i++) {
                JSONObject it = rawList.optJSONObject(i);
                if (it != null && it.optInt("id", -1) == goodsId) {
                    raw = it;
                    break;
                }
            }
            if (raw == null) {
                resp.put("success", false);
                resp.put("message", "设备不存在或状态不可读");
                return resp;
            }
            String name = deviceNameForRaw(raw);
            if (name.isEmpty()) name = raw.optString("name", "未知设备");
            JSONObject mode = selectMode(name, goodsId, modeId, preferredModeName);
            if (mode == null) {
                resp.put("success", false);
                resp.put("message", "没有可用套餐");
                return resp;
            }
            boolean effectiveSelfClean = selfClean && HaileDomain.modeSupportsSelfClean(mode);
            Integer selfCleanItemId = resolveSelfCleanItemId(goodsId, mode, effectiveSelfClean);

            JSONObject existingOrder = orderForDevice(name, norm, goodsId);
            if (existingOrder != null) {
                String st = existingOrder.optString("stage", "");
                if ("awaiting_payment".equals(st) || "reserve_waiting".equals(st)) {
                    JSONObject acc = getOwnedAccount(existingOrder.optString("accountPhone", ""), norm);
                    if (acc != null) {
                        if (accountUsage(acc.optString("phone", ""), norm) > ACCOUNT_MAX_ACTIVE_SLOTS) {
                            resp.put("success", false);
                            resp.put("message", "该账号订单/抢占已满（上限 " + ACCOUNT_MAX_ACTIVE_SLOTS + "）");
                            return resp;
                        }
                        PreemptIntent intent = new PreemptIntent(raw, mode, acc, norm, dryerMinutes, effectiveSelfClean, selfCleanItemId, existingOrder, null, null, goodsId);
                        synchronized (lock) { intents.put(goodsId, intent); }
                        intent.start();
                        saveIntents();
                        JSONObject ev = new JSONObject();
                        ev.put("type", "intent");
                        ev.put("goodsId", goodsId);
                        ev.put("deviceName", intent.deviceName);
                        ev.put("state", "started");
                        emit(ev);
                        resp.put("success", true);
                        return resp;
                    }
                }
            }

            JSONObject acc = chooseAccount(norm);
            if (acc == null) {
                resp.put("success", false);
                resp.put("message", noAccountReason(norm));
                return resp;
            }
            PreemptIntent intent = new PreemptIntent(raw, mode, acc, norm, dryerMinutes, effectiveSelfClean, selfCleanItemId, null, null, preferredModeName, goodsId);
            synchronized (lock) { intents.put(goodsId, intent); }
            intent.start();
            saveIntents();
            JSONObject ev = new JSONObject();
            ev.put("type", "intent");
            ev.put("goodsId", goodsId);
            ev.put("deviceName", intent.deviceName);
            ev.put("state", "started");
            emit(ev);
            resp.put("success", true);
        } catch (Exception e) {
            try {
                resp.put("success", false);
                resp.put("message", e.getMessage() != null ? e.getMessage() : "启动失败");
            } catch (Exception ignored) {}
        }
        return resp;
    }

    public JSONObject stopIntent(int goodsId, boolean cancelOrder, String ownerId) {
        String norm = normalizeOwnerId(ownerId);
        JSONObject resp = new JSONObject();
        try {
            PreemptIntent intent;
            synchronized (lock) {
                intent = intents.get(goodsId);
                if (intent == null && goodsId <= 0) intent = intents.get(poolKeyForOwner(norm));
                if (intent == null) {
                    for (PreemptIntent cand : intents.values()) {
                        if (norm.equals(normalizeOwnerId(cand.ownerId)) && (cand.goodsId == goodsId || cand.poolGoodsIds.contains(goodsId))) {
                            intent = cand;
                            break;
                        }
                    }
                }
            }
            if (intent == null) {
                resp.put("success", true);
                resp.put("message", "没有运行中的抢占");
                return resp;
            }
            if (!norm.equals(normalizeOwnerId(intent.ownerId))) {
                resp.put("success", false);
                resp.put("message", "不能停止其他用户的抢占");
                return resp;
            }
            intent.requestStop(cancelOrder);
            JSONObject ev = new JSONObject();
            ev.put("type", "intent");
            ev.put("goodsId", intent.intentKey);
            ev.put("deviceName", intent.deviceName);
            ev.put("state", "stopping");
            emit(ev);
            resp.put("success", true);
        } catch (Exception ignored) {}
        return resp;
    }

    public JSONObject cancelOrder(String phone, String orderNo, String ownerId) {
        JSONObject acc = getOwnedAccount(phone, ownerId);
        JSONObject resp = new JSONObject();
        try {
            if (acc == null) {
                resp.put("success", false);
                resp.put("message", "账号不存在或不属于当前用户");
                return resp;
            }
            JSONObject res = api.cancelOrder(acc.optString("token", ""), orderNo);
            if (res.optInt("code", -1) == 0) {
                synchronized (lock) {
                    JSONObject order = ordersByNo.get(orderNo);
                    if (order != null) {
                        JSONObject copy = HaileDomain.cloneJson(order);
                        copy.put("state", 411);
                        copy.put("pageCode", "timeout");
                        copy.put("canPay", false);
                        copy.put("canCancel", false);
                        ordersByNo.put(orderNo, HaileDomain.enrichOrder(copy));
                    }
                }
                log("已取消订单 " + orderNo);
                notifyStatusListener();
                resp.put("success", true);
                return resp;
            }
            resp.put("success", false);
            resp.put("message", res.optString("message", "取消失败"));
        } catch (Exception ignored) {}
        return resp;
    }

    public JSONObject verifyOrder(String phone, String orderNo, String checkCode, String ownerId) {
        JSONObject acc = getOwnedAccount(phone, ownerId);
        JSONObject resp = new JSONObject();
        try {
            if (acc == null) {
                resp.put("success", false);
                resp.put("message", "账号不存在或不属于当前用户");
                return resp;
            }
            String codeStr = checkCode != null ? checkCode.trim() : "";
            if (codeStr.isEmpty()) {
                resp.put("success", false);
                resp.put("message", "请输入预约核验码");
                return resp;
            }
            JSONObject res = api.verifyOrderCode(acc.optString("token", ""), orderNo, codeStr);
            if (res.optInt("code", -1) == 0) {
                log("预约订单 " + orderNo + " 核验成功");
                refreshAccountOrders(acc);
                resp.put("success", true);
                return resp;
            }
            resp.put("success", false);
            resp.put("message", res.optString("message", "核验失败"));
        } catch (Exception ignored) {}
        return resp;
    }

    public JSONObject resendVerifyCode(String phone, String orderNo, String ownerId) {
        JSONObject acc = getOwnedAccount(phone, ownerId);
        JSONObject resp = new JSONObject();
        try {
            if (acc == null) {
                resp.put("success", false);
                resp.put("message", "账号不存在或不属于当前用户");
                return resp;
            }
            JSONObject res = api.resendVerifyCode(acc.optString("token", ""), orderNo);
            if (res.optInt("code", -1) == 0) {
                log("预约订单 " + orderNo + " 核验码已重发");
                resp.put("success", true);
                return resp;
            }
            resp.put("success", false);
            resp.put("message", res.optString("message", "重发核验码失败"));
        } catch (Exception ignored) {}
        return resp;
    }

    public JSONObject remindPreviousPickup(String phone, String orderNo, String ownerId) {
        JSONObject acc = getOwnedAccount(phone, ownerId);
        JSONObject resp = new JSONObject();
        try {
            if (acc == null) {
                resp.put("success", false);
                resp.put("message", "账号不存在或不属于当前用户");
                return resp;
            }
            JSONObject res = api.remindPickupClothes(acc.optString("token", ""), orderNo);
            if (res.optInt("code", -1) == 0) {
                log("已向 " + orderNo + " 前序用户发送取衣提醒");
                resp.put("success", true);
                return resp;
            }
            resp.put("success", false);
            resp.put("message", res.optString("message", "提醒前序取衣失败"));
        } catch (Exception ignored) {}
        return resp;
    }

    public JSONObject confirmPlaceClothes(String phone, String orderNo, String ownerId) {
        JSONObject acc = getOwnedAccount(phone, ownerId);
        JSONObject resp = new JSONObject();
        try {
            if (acc == null) {
                resp.put("success", false);
                resp.put("message", "账号不存在或不属于当前用户");
                return resp;
            }
            JSONObject res = api.placeClothesStart(acc.optString("token", ""), orderNo);
            if (res.optInt("code", -1) == 0) {
                log("预约订单 " + orderNo + " 已确认放衣关门");
                refreshAccountOrders(acc);
                resp.put("success", true);
                return resp;
            }
            resp.put("success", false);
            resp.put("message", res.optString("message", "放衣确认失败"));
        } catch (Exception ignored) {}
        return resp;
    }

    public void markOrderExpiredLocally(String orderNo) {
        if (orderNo == null || orderNo.isEmpty()) return;
        synchronized (lock) {
            JSONObject order = ordersByNo.get(orderNo);
            if (order == null) return;
            JSONObject copy = HaileDomain.cloneJson(order);
            try {
                copy.put("state", 411);
                copy.put("stateDesc", "支付超时");
                copy.put("pageCode", "timeout");
                copy.put("canPay", false);
                copy.put("canCancel", false);
                copy.put("updatedAt", nowSec());
            } catch (Exception ignored) {}
            ordersByNo.put(orderNo, HaileDomain.enrichOrder(copy));
        }
    }

    public JSONObject state(String ownerId) {
        String norm = normalizeOwnerId(ownerId);
        JSONArray devicesArr = devices(norm);
        Map<String, String> finishByName = new HashMap<>();
        for (int i = 0; i < devicesArr.length(); i++) {
            JSONObject d = devicesArr.optJSONObject(i);
            if (d != null) finishByName.put(d.optString("name", ""), d.optString("finishTime", ""));
        }
        List<JSONObject> orders = new ArrayList<>();
        List<String> logSnapshot;
        JSONArray intentsArr = new JSONArray();
        Set<PreemptIntent> seenIntents = new HashSet<>();
        int curPid;
        String curPname;
        double curLng, curLat;
        boolean curCust;
        JSONArray recentPosArr = new JSONArray();
        synchronized (lock) {
            curPid = positionId;
            curPname = positionName;
            curLng = positionLng;
            curLat = positionLat;
            curCust = positionCustomized;
            for (JSONObject r : recentPositions) recentPosArr.put(r);
            for (JSONObject o : ordersByNo.values()) {
                if (norm.equals(normalizeOwnerId(o.optString("ownerId")))) orders.add(o);
            }
            logSnapshot = new ArrayList<>(logs);
            for (PreemptIntent intent : intents.values()) {
                if (seenIntents.add(intent) && norm.equals(normalizeOwnerId(intent.ownerId))) {
                    intentsArr.put(intent.snapshot());
                }
            }
        }
        orders.sort((a, b) -> Double.compare(b.optDouble("updatedAt", 0.0), a.optDouble("updatedAt", 0.0)));
        JSONArray ordersArr = new JSONArray();
        for (JSONObject o : orders) {
            String devName = HaileDomain.inferDeviceNameFromOrder(o);
            ordersArr.put(orderWithDeviceTiming(o, finishByName.getOrDefault(devName, "")));
        }

        JSONArray accountsArr = new JSONArray();
        for (JSONObject acc : ownedAccounts(norm)) {
            JSONObject snap = HaileApi.accountSnapshot(acc);
            try {
                String ph = acc.optString("phone", "");
                snap.put("blockingCount", blockingOrders(ph, norm).size());
                snap.put("slotUsage", accountUsage(ph, norm));
                snap.put("slotLimit", ACCOUNT_MAX_ACTIVE_SLOTS);
            } catch (Exception ignored) {}
            accountsArr.put(snap);
        }

        JSONArray logsArr = new JSONArray();
        for (String line : logSnapshot) logsArr.put(line);

        JSONObject posObj = new JSONObject();
        try {
            posObj.put("positionId", curPid);
            posObj.put("positionName", curPname);
            posObj.put("lng", curLng);
            posObj.put("lat", curLat);
            posObj.put("customized", curCust);
            posObj.put("recentPositions", recentPosArr);
        } catch (Exception ignored) {}

        JSONObject stateObj = new JSONObject();
        try {
            stateObj.put("version", HaileDomain.APP_VERSION_NAME);
            stateObj.put("versionCode", HaileDomain.APP_VERSION_CODE);
            stateObj.put("position", posObj);
            stateObj.put("clockOffsetSeconds", Math.round(clockOffsetSec() * 1000.0) / 1000.0);
            stateObj.put("accounts", accountsArr);
            stateObj.put("devices", devicesArr);
            stateObj.put("orders", ordersArr);
            stateObj.put("ownerId", norm);
            stateObj.put("blockingCount", blockingOrders(null, norm).size());
            stateObj.put("intents", intentsArr);
            stateObj.put("logs", logsArr);
            stateObj.put("recommendations", HaileRecommendation.buildRecommendationGroups(devicesArr));
        } catch (Exception ignored) {}
        return stateObj;
    }

    public final class PreemptIntent extends Thread {
        volatile JSONObject device;
        volatile JSONObject mode;
        volatile JSONObject account;
        volatile String ownerId;
        final Integer dryerMinutes;
        final boolean selfClean;
        volatile Integer selfCleanItemId;
        volatile int goodsId;
        volatile String deviceName;
        final List<Integer> originPoolIds;
        volatile List<Integer> poolGoodsIds;
        volatile boolean isPool;
        final String preferredModeName;
        volatile int intentKey;
        volatile String accountPhone;
        volatile boolean stopRequested = false;
        volatile boolean cancelOnStop = false;
        volatile String state;
        volatile String status;
        volatile JSONObject currentOrder;
        volatile String currentOrderNo;
        volatile int attempts = 0;
        final double startedAt;
        volatile String lastError = "";
        volatile String pollPhase = "watch";
        volatile Double releaseAt = null;
        volatile int burstAttempts = 0;
        volatile int cruiseTicks = 0;
        volatile int idleWhileReservedTicks = 0;
        volatile double runInfoAt = 0.0;
        volatile double nextDelay = POLL_ACTIVE_SECONDS;
        volatile int errorStreak = 0;
        private final Object waitLock = new Object();

        PreemptIntent(
            JSONObject device,
            JSONObject mode,
            JSONObject account,
            String ownerId,
            Integer dryerMinutes,
            boolean selfClean,
            Integer selfCleanItemId,
            JSONObject resumeOrder,
            List<Integer> poolIds,
            String preferredModeName,
            int intentKey
        ) {
            super("haile-intent-" + intentKey);
            setDaemon(true);
            this.device = device;
            this.mode = mode;
            this.account = account;
            this.ownerId = normalizeOwnerId(ownerId);
            this.dryerMinutes = normalizeDryerMinutes(dryerMinutes);
            this.selfClean = selfClean;
            this.selfCleanItemId = selfCleanItemId;
            this.goodsId = device.optInt("id");
            String dName = deviceNameForRaw(device);
            this.deviceName = dName.isEmpty() ? device.optString("name", "未知设备") : dName;
            this.originPoolIds = (poolIds != null && poolIds.size() > 1) ? new ArrayList<>(poolIds) : Collections.emptyList();
            this.poolGoodsIds = new ArrayList<>(this.originPoolIds);
            this.isPool = !this.poolGoodsIds.isEmpty() && resumeOrder == null;
            this.preferredModeName = (preferredModeName != null && !preferredModeName.trim().isEmpty())
                ? preferredModeName.trim() : mode.optString("name", "");
            this.intentKey = intentKey;
            this.accountPhone = account.optString("phone", "");
            this.startedAt = nowSec();
            JSONObject resumed = resumeOrder != null ? stabilizeOrderTiming(resumeOrder, null) : null;
            this.state = (resumed != null && "awaiting_payment".equals(resumed.optString("stage"))) ? "holding_payment" : "acquiring";
            this.status = resumed != null ? "继续维护订单" : (this.isPool ? "并池盯守最快空机" : "准备抢占");
            this.currentOrder = resumed;
            this.currentOrderNo = resumed != null ? HaileDomain.optNullableString(resumed, "orderNo") : null;
        }

        public boolean isRunning() {
            return isAlive() && !stopRequested;
        }

        public Double releaseRemainingSeconds() {
            Double rel = this.releaseAt;
            return rel == null ? null : (rel - nowSec());
        }

        public JSONObject snapshot() {
            JSONObject snap = new JSONObject();
            try {
                snap.put("goodsId", isPool ? intentKey : goodsId);
                snap.put("focusGoodsId", goodsId);
                snap.put("isPool", isPool);
                JSONArray cands = new JSONArray();
                if (isPool) {
                    for (Integer id : poolGoodsIds) cands.put(id);
                }
                snap.put("candidateGoodsIds", cands);
                snap.put("candidateCount", isPool ? poolGoodsIds.size() : 1);
                snap.put("deviceName", isPool ? ("任意最快洗衣机 · 盯 " + deviceName) : deviceName);
                snap.put("focusDeviceName", deviceName);
                snap.put("modeId", mode.optInt("id"));
                snap.put("modeName", isPool ? preferredModeName : mode.optString("name"));
                snap.put("selfClean", selfClean);
                snap.put("dryerMinutes", dryerMinutes != null ? dryerMinutes : JSONObject.NULL);
                snap.put("accountPhone", accountPhone);
                snap.put("ownerId", ownerId);
                snap.put("state", state);
                snap.put("status", status);
                snap.put("orderNo", currentOrderNo != null ? currentOrderNo : JSONObject.NULL);
                snap.put("attempts", attempts);
                snap.put("running", isRunning());
                snap.put("startedAt", startedAt);
                snap.put("lastError", lastError);
                snap.put("pollPhase", pollPhase);
                snap.put("pollPhaseLabel", HaileDomain.POLL_PHASE_LABELS.getOrDefault(pollPhase, "轮询抢占"));
                Double rel = releaseRemainingSeconds();
                snap.put("releaseRemainingSeconds", rel != null ? rel : JSONObject.NULL);
            } catch (Exception ignored) {}
            return snap;
        }

        public JSONObject persistSpec() {
            JSONObject spec = new JSONObject();
            try {
                spec.put("goodsId", isPool ? intentKey : goodsId);
                spec.put("anyWasher", !originPoolIds.isEmpty());
                JSONArray cands = new JSONArray();
                for (Integer id : originPoolIds) cands.put(id);
                spec.put("candidateGoodsIds", cands);
                spec.put("preferredModeName", preferredModeName);
                spec.put("deviceName", deviceName);
                spec.put("modeId", mode.optInt("id"));
                spec.put("modeName", mode.optString("name"));
                spec.put("selfClean", selfClean);
                spec.put("ownerId", ownerId);
                spec.put("accountPhone", accountPhone);
                spec.put("dryerMinutes", dryerMinutes != null ? dryerMinutes : JSONObject.NULL);
                spec.put("orderNo", currentOrderNo != null ? currentOrderNo : JSONObject.NULL);
                spec.put("updatedAt", nowSec());
            } catch (Exception ignored) {}
            return spec;
        }

        public void requestStop(boolean cancelOrder) {
            this.cancelOnStop = cancelOrder;
            this.stopRequested = true;
            synchronized (waitLock) { waitLock.notifyAll(); }
        }

        private void sleepJitter(double seconds) {
            this.errorStreak = 0;
            long ms = (long) Math.max(100, seconds * HaileDomain.uniform(0.85, 1.15) * 1000.0);
            synchronized (waitLock) {
                if (stopRequested) return;
                try { waitLock.wait(ms); } catch (InterruptedException ignored) {}
            }
        }

        private void sleepAfterError(double baseSeconds) {
            this.errorStreak = Math.min(5, this.errorStreak + 1);
            double delay = Math.min(30.0, baseSeconds * (1 << this.errorStreak));
            long ms = (long) Math.max(100, delay * HaileDomain.uniform(0.85, 1.15) * 1000.0);
            synchronized (waitLock) {
                if (stopRequested) return;
                try { waitLock.wait(ms); } catch (InterruptedException ignored) {}
            }
        }

        @Override
        public void run() {
            String cleanNote = selfClean ? "（+筒自洁）" : "";
            String desc = isPool ? ("并池 " + poolGoodsIds.size() + " 台洗衣机") : deviceName;
            log(desc + " 开始抢占，账号 " + accountPhone + "，套餐 " + preferredModeName + cleanNote);
            notifyStatusListener();
            try {
                while (!stopRequested) {
                    if (currentOrderNo != null && !currentOrderNo.isEmpty()) {
                        maintainOrder();
                    } else {
                        acquireOrder();
                    }
                    notifyStatusListener();
                }
            } finally {
                if (cancelOnStop && currentOrderNo != null && !currentOrderNo.isEmpty()) {
                    cancelOrder(accountPhone, currentOrderNo, ownerId);
                }
                state = "stopped";
                status = "已停止";
                log(deviceName + " 抢占已停止");
                JSONObject ev = new JSONObject();
                try {
                    ev.put("type", "intent");
                    ev.put("goodsId", intentKey);
                    ev.put("deviceName", deviceName);
                    ev.put("state", "stopped");
                } catch (Exception ignored) {}
                emit(ev);
                forgetIntent(intentKey, ownerId, this);
            }
        }

        private Double estimateRawRemaining(JSONObject raw) {
            Double finishSec = HaileDomain.secondsUntil(HaileDomain.optNullableString(raw, "finishTime"), clockOffsetSec());
            if (finishSec != null) return finishSec;
            int gid = raw.optInt("id", 0);
            if (gid > 0) {
                String dead = runInfoDeadTime(gid);
                if (!dead.isEmpty()) return HaileDomain.secondsUntil(dead, clockOffsetSec());
            }
            return null;
        }

        private JSONObject selectBestPoolTarget(JSONArray rawItems) {
            double now = nowSec();
            JSONObject best = null;
            int bestTier = 99;
            double bestRem = Double.MAX_VALUE;
            int bestGid = Integer.MAX_VALUE;

            for (int i = 0; i < rawItems.length(); i++) {
                JSONObject item = rawItems.optJSONObject(i);
                if (item == null) continue;
                int gid = item.optInt("id", -1);
                if (!poolGoodsIds.contains(gid)) continue;
                String name = deviceNameForRaw(item);
                if (name.isEmpty()) name = item.optString("name", "设备#" + gid);
                synchronized (lock) {
                    Double hold = devicePaymentHolds.get(name);
                    if (hold != null && hold > now) continue;
                }
                PreemptIntent other = liveIntent(gid);
                if (other != null && other != this && !normalizeOwnerId(other.ownerId).equals(ownerId)) continue;
                HaileDomain.DeviceState ds = HaileDomain.classifyDevice(item, false);
                if (!ds.canOrder) continue;
                int tier = "idle".equals(ds.key) ? 0 : ("busy_reservable".equals(ds.key) ? 1 : 2);
                Double remObj = estimateRawRemaining(item);
                double remVal = remObj != null ? remObj : 1800.0;
                if (tier < bestTier || (tier == bestTier && (remVal < bestRem || (remVal == bestRem && gid < bestGid)))) {
                    bestTier = tier;
                    bestRem = remVal;
                    bestGid = gid;
                    best = item;
                }
            }
            return best;
        }

        private boolean bindFocusDevice(JSONObject raw) {
            int gid = raw.optInt("id");
            String name = deviceNameForRaw(raw);
            if (name.isEmpty()) name = raw.optString("name", "设备#" + gid);
            boolean switched = (gid != this.goodsId);
            this.device = raw;
            this.goodsId = gid;
            this.deviceName = name;
            if (switched) {
                this.releaseAt = null;
                this.runInfoAt = 0.0;
                this.burstAttempts = 0;
            }
            try {
                JSONObject m = selectMode(name, gid, null, preferredModeName);
                if (m == null) return false;
                this.mode = m;
                boolean effSc = selfClean && HaileDomain.modeSupportsSelfClean(m);
                this.selfCleanItemId = resolveSelfCleanItemId(gid, m, effSc);
                return m.optInt("id", 0) > 0;
            } catch (Exception e) {
                return false;
            }
        }

        private void lockPoolOntoCurrentDevice() {
            if (isPool) {
                isPool = false;
                rekeyIntent(this, goodsId);
            }
        }

        private void reopenPoolIfApplicable() {
            if (!originPoolIds.isEmpty() && !isPool) {
                isPool = true;
                poolGoodsIds = new ArrayList<>(originPoolIds);
                rekeyIntent(this, poolKeyForOwner(ownerId));
            }
        }

        private void updateReleaseEstimate(JSONObject raw) {
            Double finishSec = HaileDomain.secondsUntil(HaileDomain.optNullableString(raw, "finishTime"), clockOffsetSec());
            if (finishSec != null) {
                this.releaseAt = nowSec() + finishSec;
                return;
            }
            double now = nowSec();
            if (now - this.runInfoAt < RUN_INFO_MIN_INTERVAL) return;
            this.runInfoAt = now;
            try {
                JSONObject res = api.runInfo(goodsId);
                if (res.optInt("code", -1) != 0) return;
                JSONObject data = res.optJSONObject("data");
                Double deadSec = HaileDomain.secondsUntil(data != null ? HaileDomain.optNullableString(data, "deadTime") : null, clockOffsetSec());
                this.releaseAt = deadSec == null ? null : nowSec() + deadSec;
            } catch (Exception ignored) {}
        }

        private String phaseStatus() {
            String label = HaileDomain.POLL_PHASE_LABELS.getOrDefault(pollPhase, "轮询抢占");
            Double rem = releaseRemainingSeconds();
            String prefix = isPool ? ("盯 " + deviceName + " · ") : "";
            if (("approach".equals(pollPhase) || "burst".equals(pollPhase)) && rem != null) {
                return prefix + label + "，预计 " + Math.max(0, (int) Math.floor(rem)) + "s 后释放";
            }
            if ("overtime".equals(pollPhase)) {
                return prefix + label + "，滚筒解锁检测中";
            }
            if ("cooldown".equals(pollPhase)) {
                return prefix + label + "，稍后重新冲刺";
            }
            return prefix + label;
        }

        private boolean adoptExistingOrderForDevice() {
            if (isPool) {
                for (Integer gid : poolGoodsIds) {
                    JSONObject order = orderForDevice("", ownerId, gid);
                    if (order != null) {
                        String st = order.optString("stage", "");
                        if ("awaiting_payment".equals(st) || "reserve_waiting".equals(st)) {
                            this.goodsId = gid;
                            String inf = HaileDomain.inferDeviceNameFromOrder(order);
                            if (!inf.isEmpty()) this.deviceName = inf;
                            return adoptOrder(order, "已接管并池设备订单");
                        }
                    }
                }
                return false;
            }
            JSONObject order = orderForDevice(deviceName, ownerId, goodsId);
            return adoptOrder(order, "已接管同设备订单");
        }

        private boolean adoptOrder(JSONObject order, String reason) {
            if (order == null) return false;
            String st = order.optString("stage", "");
            if (!"awaiting_payment".equals(st) && !"reserve_waiting".equals(st)) return false;
            JSONObject stabilized = stabilizeOrderTiming(order, currentOrder);
            if ("awaiting_payment".equals(st)) {
                Double rem = paymentRemainingSeconds(stabilized, clockOffsetSec());
                if (rem != null && rem <= 0) {
                    markOrderExpiredLocally(stabilized.optString("orderNo", ""));
                    return false;
                }
            }
            String phone = stabilized.optString("accountPhone", accountPhone);
            if (!phone.isEmpty() && !phone.equals(accountPhone)) {
                JSONObject acc = getOwnedAccount(phone, ownerId);
                if (acc == null) return false;
                this.account = acc;
                this.accountPhone = phone;
            }
            lockPoolOntoCurrentDevice();
            this.currentOrder = stabilized;
            this.currentOrderNo = stabilized.optString("orderNo", "");
            this.state = "awaiting_payment".equals(st) ? "holding_payment" : "holding_reserve";
            if ("awaiting_payment".equals(st)) {
                this.status = "待支付维护中";
            } else if (HaileDomain.orderReadyForUserAction(stabilized)) {
                this.status = stabilized.optString("stageLabel", "已到号") + "，请去小程序/App操作";
            } else {
                this.status = "已排上预约，等待前序结束";
            }
            log(deviceName + " " + reason + " " + currentOrderNo + "，继续维护");
            saveIntents();
            checkAndFireOrderAlert(stabilized);
            JSONObject ev = new JSONObject();
            try {
                ev.put("type", "order");
                ev.put("goodsId", goodsId);
                ev.put("deviceName", deviceName);
                ev.put("orderNo", currentOrderNo);
                ev.put("stage", st);
                ev.put("adopted", true);
            } catch (Exception ignored) {}
            emit(ev);
            return true;
        }

        private void acquireOrder() {
            this.state = "acquiring";
            this.status = isPool ? "并池寻机中" : "抢占中";
            this.attempts += 1;
            this.nextDelay = POLL_ACTIVE_SECONDS;
            try {
                if (adoptExistingOrderForDevice()) return;

                int pid;
                synchronized (lock) { pid = positionId; }
                JSONArray items = api.devices(pid);
                JSONObject raw;
                if (isPool) {
                    JSONObject best = selectBestPoolTarget(items);
                    if (best == null) {
                        this.pollPhase = "watch";
                        this.status = "候选洗衣机暂均锁定或不可抢，持续盯守中";
                        sleepJitter(POLL_ACTIVE_SECONDS);
                        return;
                    }
                    if (!bindFocusDevice(best)) {
                        sleepJitter(POLL_ACTIVE_SECONDS);
                        return;
                    }
                    raw = best;
                } else {
                    raw = this.device;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it != null && it.optInt("id", -1) == goodsId) {
                            raw = it;
                            break;
                        }
                    }
                }

                HaileDomain.DeviceState devState = HaileDomain.classifyDevice(raw, false);
                if (!devState.canOrder) {
                    this.pollPhase = "watch";
                    this.status = devState.label;
                    sleepJitter(POLL_ACTIVE_SECONDS);
                    return;
                }

                String orderKind = devState.orderKind != null ? devState.orderKind : "instant";
                if ("poll".equals(orderKind)) {
                    String prevPhase = this.pollPhase;
                    Double prevRemaining = releaseRemainingSeconds();
                    updateReleaseEstimate(raw);
                    Double newRemaining = releaseRemainingSeconds();
                    HaileDomain.PollPlan plan = HaileDomain.planPoll(newRemaining, burstAttempts);
                    this.pollPhase = plan.phase;
                    if ("burst".equals(plan.phase)) {
                        this.burstAttempts += 1;
                    } else {
                        this.burstAttempts = 0;
                    }
                    boolean cycleReset = "approach".equals(prevPhase)
                        || "burst".equals(prevPhase)
                        || "cooldown".equals(prevPhase)
                        || "overtime".equals(prevPhase)
                        || (prevRemaining != null && newRemaining != null && newRemaining - prevRemaining > 60.0);
                    if ("cruise".equals(plan.phase)) {
                        this.cruiseTicks += 1;
                    } else {
                        this.cruiseTicks = 0;
                    }
                    boolean shouldAttempt = plan.attempt || cycleReset || ("cruise".equals(plan.phase) && this.cruiseTicks > 1);
                    this.status = phaseStatus();
                    if (!shouldAttempt) {
                        sleepJitter(plan.delay);
                        return;
                    }
                    this.nextDelay = plan.delay;
                } else {
                    this.pollPhase = "watch";
                    this.burstAttempts = 0;
                    this.cruiseTicks = 0;
                }

                String token = account.optString("token", "");
                JSONObject result;
                if ("instant".equals(orderKind)) {
                    result = api.createOrder(token, deviceName, goodsId, mode.optInt("id"), dryerMinutes, selfCleanItemId);
                    String msg = result.optString("message", "");
                    if (result.optInt("code", -1) != 0 && (msg.contains("使用中") || msg.contains("运行中") || msg.contains("占用"))) {
                        orderKind = "reserve";
                        result = api.reserveOrder(token, deviceName, goodsId, mode.optInt("id"), dryerMinutes, selfCleanItemId);
                    }
                } else {
                    result = api.reserveOrder(token, deviceName, goodsId, mode.optInt("id"), dryerMinutes, selfCleanItemId);
                    String msg = result.optString("message", "");
                    if (result.optInt("code", -1) != 0 && msg.contains("空闲")) {
                        orderKind = "instant";
                        result = api.createOrder(token, deviceName, goodsId, mode.optInt("id"), dryerMinutes, selfCleanItemId);
                    }
                }
                handleOrderResult(result, orderKind);
                sleepJitter(this.nextDelay);
            } catch (Exception e) {
                this.lastError = e.getMessage() != null ? e.getMessage() : "请求异常";
                this.status = "网络或平台响应异常";
                sleepAfterError(POLL_ACTIVE_SECONDS);
            }
        }

        private void handleOrderResult(JSONObject result, String orderKind) {
            int code = result.optInt("code", -1);
            String message = result.optString("message", "");
            if (code == 0) {
                JSONObject data = result.optJSONObject("data");
                String orderNo = data != null ? data.optString("orderNo", "") : "";
                String stage = "instant".equals(orderKind) ? "awaiting_payment" : "reserve_waiting";
                lockPoolOntoCurrentDevice();
                JSONObject rawOrder = new JSONObject();
                try {
                    rawOrder.put("orderNo", orderNo);
                    rawOrder.put("accountPhone", accountPhone);
                    rawOrder.put("ownerId", ownerId);
                    rawOrder.put("goodsId", goodsId);
                    rawOrder.put("goodsName", deviceName + "【" + mode.optString("name") + "】");
                    rawOrder.put("modeName", mode.optString("name"));
                    rawOrder.put("selfClean", selfClean);
                    rawOrder.put("state", "instant".equals(orderKind) ? 100 : 50);
                    rawOrder.put("stateDesc", "instant".equals(orderKind) ? "待支付" : "预约成功");
                    rawOrder.put("pageCode", "instant".equals(orderKind) ? "normal_waiting_pay" : "appointment_waiting");
                    rawOrder.put("orderType", "instant".equals(orderKind) ? 100 : 300);
                    rawOrder.put("canPay", "instant".equals(orderKind));
                    rawOrder.put("canCancel", true);
                    rawOrder.put("deadline", "");
                    rawOrder.put("updatedAt", nowSec());
                    if ("instant".equals(orderKind)) {
                        int exp = data != null ? data.optInt("expireSeconds", PAYMENT_EXPIRE_FALLBACK_SECONDS) : PAYMENT_EXPIRE_FALLBACK_SECONDS;
                        rawOrder.put("expireSeconds", exp > 0 ? exp : PAYMENT_EXPIRE_FALLBACK_SECONDS);
                        rawOrder.put("createdAtLocal", nowSec());
                    }
                } catch (Exception ignored) {}

                JSONObject order = stabilizeOrderTiming(HaileDomain.enrichOrder(rawOrder), currentOrder);
                synchronized (lock) {
                    ordersByNo.put(orderNo, order);
                    devicePaymentHolds.remove(deviceName);
                }
                this.currentOrder = order;
                this.currentOrderNo = orderNo;
                this.idleWhileReservedTicks = 0;
                this.state = "awaiting_payment".equals(stage) ? "holding_payment" : "holding_reserve";
                this.status = "awaiting_payment".equals(stage) ? "待支付维护中" : "已排上预约，等待前序结束";
                this.nextDelay = 1.0;
                String recNote = result.optBoolean("recovered", false) ? "（超时反查接管）" : "";
                log(deviceName + " 已创建" + ("instant".equals(orderKind) ? "即时单 " : "预约单 ") + orderNo + recNote + "，账号 " + accountPhone);
                saveIntents();
                checkAndFireOrderAlert(order);
                JSONObject ev = new JSONObject();
                try {
                    ev.put("type", "order");
                    ev.put("goodsId", goodsId);
                    ev.put("deviceName", deviceName);
                    ev.put("orderNo", orderNo);
                    ev.put("stage", stage);
                } catch (Exception ignored) {}
                emit(ev);
                return;
            }

            if (HaileDomain.isAuthFailure(code, message)) {
                this.state = "blocked";
                this.status = "账号登录失效";
                JSONObject ev = new JSONObject();
                try {
                    ev.put("type", "intent");
                    ev.put("goodsId", intentKey);
                    ev.put("deviceName", deviceName);
                    ev.put("state", "blocked");
                } catch (Exception ignored) {}
                emit(ev);
                this.stopRequested = true;
                return;
            }
            if (HaileDomain.isDevicePaymentConflict(message)) {
                refreshAccountOrders(account);
                if (adoptExistingOrderForDevice()) return;
                synchronized (lock) {
                    devicePaymentHolds.put(deviceName, nowSec() + DEVICE_HOLD_SECONDS);
                }
                saveHolds();
                this.status = isPool ? "切换其他候选洗衣机寻机中" : "有人待支付，等待释放";
                this.nextDelay = isPool ? 0.4 : POLL_ACTIVE_SECONDS;
                return;
            }
            if (HaileDomain.isAccountOrderConflict(message)) {
                refreshAccountOrders(account);
                if (adoptExistingOrderForDevice()) return;
                this.status = "账号已有阻塞订单，刷新后重试";
                this.nextDelay = POLL_ACTIVE_SECONDS * 2.0;
                return;
            }
            this.lastError = message;
            this.status = !message.isEmpty() ? message : "下单失败，继续重试";
        }

        private void maintainOrder() {
            String token = account.optString("token", "");
            try {
                JSONObject res = api.orderDetail(token, currentOrderNo != null ? currentOrderNo : "");
                if (res.optInt("code", -1) != 0) {
                    if (HaileDomain.isAuthFailure(res.optInt("code", -1), res.optString("message", ""))) {
                        this.state = "blocked";
                        this.status = "账号登录失效";
                        JSONObject ev = new JSONObject();
                        try {
                            ev.put("type", "intent");
                            ev.put("goodsId", intentKey);
                            ev.put("deviceName", deviceName);
                            ev.put("state", "blocked");
                        } catch (Exception ignored) {}
                        emit(ev);
                        this.stopRequested = true;
                        return;
                    }
                    this.status = res.optString("message", "订单查询失败");
                    sleepJitter(POLL_HELD_SECONDS);
                    return;
                }

                JSONObject summarized = HaileApi.summarizeDetail(res.optJSONObject("data"), accountPhone);
                JSONObject order = stabilizeOrderTiming(summarized, currentOrder);
                try { order.put("ownerId", ownerId); } catch (Exception ignored) {}
                String orderNo = order.optString("orderNo", currentOrderNo);
                synchronized (lock) { ordersByNo.put(orderNo, order); }
                this.currentOrder = order;
                this.currentOrderNo = orderNo;
                checkAndFireOrderAlert(order);

                String stage = order.optString("stage", "");
                if ("running".equals(stage)) {
                    checkPickupTransitions(order);
                    this.state = "fulfilled";
                    this.status = "已支付，设备进行中（洗完前自动提醒收衣）";
                    log(deviceName + " 已进入进行中，转入后台收衣提醒");
                    JSONObject ev = new JSONObject();
                    try {
                        ev.put("type", "order");
                        ev.put("goodsId", goodsId);
                        ev.put("deviceName", deviceName);
                        ev.put("orderNo", currentOrderNo);
                        ev.put("stage", "running");
                    } catch (Exception ignored) {}
                    emit(ev);
                    this.stopRequested = true;
                    return;
                }
                if ("ended".equals(stage)) {
                    log(deviceName + " 订单 " + currentOrderNo + " 已结束，继续抢占");
                    JSONObject ev = new JSONObject();
                    try {
                        ev.put("type", "order");
                        ev.put("goodsId", goodsId);
                        ev.put("deviceName", deviceName);
                        ev.put("orderNo", currentOrderNo);
                        ev.put("stage", "ended");
                    } catch (Exception ignored) {}
                    emit(ev);
                    this.currentOrder = null;
                    this.currentOrderNo = null;
                    reopenPoolIfApplicable();
                    this.state = "acquiring";
                    sleepJitter(0.5);
                    return;
                }
                if ("awaiting_payment".equals(stage)) {
                    this.state = "holding_payment";
                    Double rem = paymentRemainingSeconds(order, clockOffsetSec());
                    if (rem != null && rem <= 0) {
                        log(deviceName + " 待支付单已到期，继续抢占");
                        JSONObject ev = new JSONObject();
                        try {
                            ev.put("type", "order");
                            ev.put("goodsId", goodsId);
                            ev.put("deviceName", deviceName);
                            ev.put("orderNo", currentOrderNo);
                            ev.put("stage", "expired");
                        } catch (Exception ignored) {}
                        emit(ev);
                        markOrderExpiredLocally(currentOrderNo);
                        this.currentOrder = null;
                        this.currentOrderNo = null;
                        reopenPoolIfApplicable();
                        this.state = "acquiring";
                        return;
                    }
                    if (rem != null && rem <= RENEW_BEFORE_SECONDS) {
                        this.state = "renewing";
                        this.status = "临期续抢准备中，剩余 " + Math.max(0, (int) Math.floor(rem)) + "s";
                        sleepJitter(0.5);
                        return;
                    }
                    this.status = rem != null ? ("待支付维护中，剩余 " + (int) Math.floor(rem) + "s") : "待支付维护中";
                    sleepJitter(POLL_HELD_SECONDS);
                    return;
                }

                if ("appointment_waiting".equals(order.optString("pageCode")) && !HaileDomain.orderReadyForUserAction(order)) {
                    try {
                        JSONArray rawList = fetchRawDevices(0.0);
                        JSONObject liveDev = null;
                        for (int i = 0; i < rawList.length(); i++) {
                            JSONObject it = rawList.optJSONObject(i);
                            if (it != null && it.optInt("id", -1) == goodsId) {
                                liveDev = it;
                                break;
                            }
                        }
                        if (liveDev != null && liveDev.optInt("state", 0) == 1) {
                            this.idleWhileReservedTicks += 1;
                            if (this.idleWhileReservedTicks >= 2) {
                                log(deviceName + " 设备已空闲但预约单仍未生效，自动取消旧预约改抢即时单");
                                cancelOrder(accountPhone, currentOrderNo, ownerId);
                                this.currentOrder = null;
                                this.currentOrderNo = null;
                                this.state = "acquiring";
                                this.idleWhileReservedTicks = 0;
                                sleepJitter(0.3);
                                return;
                            }
                        } else {
                            this.idleWhileReservedTicks = 0;
                        }
                    } catch (Exception ignored) {}
                } else {
                    this.idleWhileReservedTicks = 0;
                }

                this.state = "holding_reserve";
                if (HaileDomain.orderReadyForUserAction(order)) {
                    this.status = order.optString("stageLabel", "已到号") + "，请去小程序/App操作";
                } else if ("self_clean".equals(order.optString("pageCode"))) {
                    this.status = "筒自洁进行中，请稍候";
                } else {
                    this.status = "已排上预约，等待前序结束";
                }
                sleepJitter(POLL_RESERVE_SECONDS);
            } catch (Exception e) {
                this.lastError = e.getMessage() != null ? e.getMessage() : "维护异常";
                this.status = "维护检查异常，继续保持意图";
                sleepAfterError(POLL_HELD_SECONDS);
            }
        }
    }
}
