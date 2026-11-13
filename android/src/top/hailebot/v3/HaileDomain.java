package top.hailebot.v3;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HaileDomain {
    public static final int POSITION_ID = 32577;
    public static final String DEFAULT_POSITION_NAME = "泓园 7 号楼";
    public static final String API_HOST = "https://yshz-user.haier-ioc.com";
    public static final String UPSTREAM_HOST = "yshz-user.haier-ioc.com";
    public static final String UPSTREAM_FALLBACK_IP = "47.93.94.247";
    public static final String WECHAT_MINI_APP_ID = "wx7519e26c1d2f9edc";
    public static final String ALIPAY_MINI_APP_ID = "2021003183687122";
    public static final String HAILE_APP_PACKAGE = "com.yunshang.haile_life";
    public static final String WECHAT_APP_PACKAGE = "com.tencent.mm";
    public static final String ALIPAY_APP_PACKAGE = "com.eg.android.AlipayGphone";

    public static final String OTA_PRIMARY_BASE = "https://haile-bot.pages.dev";
    public static final String OTA_DEFAULT_APK_URL = OTA_PRIMARY_BASE + "/haile-bot-v4.apk";
    public static final String OTA_CHECK_URL = OTA_PRIMARY_BASE + "/ota.json";
    public static final String[] OTA_CHECK_URLS = new String[]{
        OTA_PRIMARY_BASE + "/ota.json",
        OTA_PRIMARY_BASE + "/api/ota"
    };

    public static final int APP_VERSION_CODE = 420;
    public static final String APP_VERSION_NAME = "4.2.0";
    public static final int SELF_CLEAN_SKU_ID = 175;
    public static final int DRYER_DEFAULT_MINUTES = 10;
    public static final int PICKUP_REMINDER_LEAD_SECONDS = 180;

    public static final double POLL_WATCH_SECONDS = 1.5;
    public static final double POLL_OVERTIME_SECONDS = 1.2;
    public static final double RELEASE_CRUISE_SECONDS = 60.0;
    public static final double RELEASE_BURST_SECONDS = 10.0;
    public static final double RELEASE_OVERTIME_SECONDS = -12.0;
    public static final int BURST_ATTEMPT_LIMIT = 60;

    public static final Map<String, String> TARGET_IMEIS = new LinkedHashMap<>();
    public static final Map<String, String> TARGET_DEVICE_CODES = new LinkedHashMap<>();
    public static final Map<String, String> POLL_PHASE_LABELS = new HashMap<>();
    public static final Set<Integer> TERMINAL_ORDER_STATES = new HashSet<>(Arrays.asList(401, 411, 1000, 2099));
    public static final Set<String> TERMINAL_PAGE_CODES = new HashSet<>(Arrays.asList("timeout", "refund_complete", "refund_fail"));
    public static final Set<String> PAYMENT_PAGE_CODES = new HashSet<>(Arrays.asList("normal_waiting_pay", "waiting_choose_ump", "after_pay_waiting_pay"));
    public static final Set<String> RESERVE_PAGE_CODES = new HashSet<>(Arrays.asList("appointment_waiting", "waiting_check", "self_clean", "place_clothes"));
    public static final Set<String> ACTIONABLE_RESERVE_PAGE_CODES = new HashSet<>(Arrays.asList("waiting_check", "place_clothes"));
    public static final Set<String> RUNNING_PAGE_CODES = new HashSet<>(Arrays.asList("complete", "paid", "normal_fulfilling", "after_pay_fulfilling"));

    private static final String[] KNOWN_DEVICE_NAMES = new String[]{
        "04号洗衣机",
        "05号烘干机",
        "06号洗衣机",
        "07号洗衣机",
        "08号洗衣机",
        "09号洗衣机"
    };
    private static final Pattern DEVICE_NAME_PATTERN = Pattern.compile("(\\d{1,2})号(洗衣机|烘干机|洗鞋机)");
    private static final Random RNG = new Random();

    static {
        TARGET_IMEIS.put("865901075430960", "04号洗衣机");
        TARGET_IMEIS.put("865901075488729", "06号洗衣机");
        TARGET_IMEIS.put("865901073935457", "07号洗衣机");
        TARGET_IMEIS.put("865901073916986", "08号洗衣机");
        TARGET_IMEIS.put("865901073920962", "09号洗衣机");
        TARGET_IMEIS.put("869737068397394", "05号烘干机");

        TARGET_DEVICE_CODES.put("865901075488729", "GTB2406241487");
        TARGET_DEVICE_CODES.put("865901073935457", "GTB2406243590");
        TARGET_DEVICE_CODES.put("865901073916986", "GTB2406246124");
        TARGET_DEVICE_CODES.put("865901073920962", "GTB2406243588");
        TARGET_DEVICE_CODES.put("869737068397394", "GYB2407151332");
        TARGET_DEVICE_CODES.put("06号洗衣机", "GTB2406241487");
        TARGET_DEVICE_CODES.put("07号洗衣机", "GTB2406243590");
        TARGET_DEVICE_CODES.put("08号洗衣机", "GTB2406246124");
        TARGET_DEVICE_CODES.put("09号洗衣机", "GTB2406243588");
        TARGET_DEVICE_CODES.put("05号烘干机", "GYB2407151332");

        POLL_PHASE_LABELS.put("watch", "轮询抢占");
        POLL_PHASE_LABELS.put("cruise", "巡航等待");
        POLL_PHASE_LABELS.put("approach", "逼近同步");
        POLL_PHASE_LABELS.put("burst", "脉冲抢占");
        POLL_PHASE_LABELS.put("cooldown", "熔断退避");
        POLL_PHASE_LABELS.put("overtime", "尾窗守候");
    }

    private HaileDomain() {}

    public static final class DeviceState {
        public final String key;
        public final String label;
        public final Integer queueCount;
        public final boolean canOrder;
        public final String orderKind;

        public DeviceState(String key, String label, Integer queueCount, boolean canOrder, String orderKind) {
            this.key = key;
            this.label = label;
            this.queueCount = queueCount;
            this.canOrder = canOrder;
            this.orderKind = orderKind;
        }
    }

    public static final class PollPlan {
        public final String phase;
        public final double delay;
        public final boolean attempt;

        public PollPlan(String phase, double delay, boolean attempt) {
            this.phase = phase;
            this.delay = delay;
            this.attempt = attempt;
        }
    }

    public static double uniform(double min, double max) {
        return min + (max - min) * RNG.nextDouble();
    }

    public static PollPlan planPoll(Double remainingSeconds, int burstAttempts) {
        if (remainingSeconds == null) {
            return new PollPlan("watch", POLL_WATCH_SECONDS, true);
        }
        if (remainingSeconds > RELEASE_CRUISE_SECONDS) {
            return new PollPlan("cruise", uniform(15.0, 30.0), false);
        }
        if (remainingSeconds > RELEASE_BURST_SECONDS) {
            return new PollPlan("approach", uniform(2.0, 3.0), true);
        }
        if (remainingSeconds < RELEASE_OVERTIME_SECONDS) {
            return new PollPlan("overtime", POLL_OVERTIME_SECONDS, true);
        }
        if (burstAttempts >= BURST_ATTEMPT_LIMIT) {
            return new PollPlan("cooldown", uniform(3.5, 6.0), false);
        }
        return new PollPlan("burst", uniform(0.2, 0.3), true);
    }

    public static Long parseLocalDatetimeMillis(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) return null;
        String normalized = text.replace('T', ' ');
        String[] patterns = new String[]{
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.CHINA);
                Date parsed = sdf.parse(normalized);
                if (parsed != null) {
                    return parsed.getTime();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static Double secondsUntil(String value) {
        return secondsUntil(value, 0.0);
    }

    public static Double secondsUntil(String value, double clockOffsetSeconds) {
        Long targetMillis = parseLocalDatetimeMillis(value);
        if (targetMillis == null) return null;
        long refMillis = System.currentTimeMillis() + Math.round(clockOffsetSeconds * 1000.0);
        return (targetMillis - refMillis) / 1000.0;
    }

    public static String normalizeDeviceDisplayName(JSONObject raw, boolean useDefaultTargetMap) {
        if (raw == null) return "";
        String imei = raw.optString("imei", "").trim();
        if (useDefaultTargetMap && TARGET_IMEIS.containsKey(imei)) {
            return TARGET_IMEIS.get(imei);
        }
        String rawName = raw.optString("name", "").trim();
        Matcher matcher = DEVICE_NAME_PATTERN.matcher(rawName);
        if (matcher.find()) {
            String num = matcher.group(1);
            String kind = matcher.group(2);
            return (num.length() == 1 ? "0" + num : num) + "号" + kind;
        }
        if (!rawName.isEmpty()) return rawName;
        int id = raw.optInt("id", 0);
        return id > 0 ? ("设备#" + id) : "";
    }

    public static DeviceState classifyDevice(JSONObject raw, boolean paymentPending) {
        if (paymentPending) {
            return new DeviceState("payment_pending", "有人待支付", null, false, null);
        }
        int state = raw != null ? raw.optInt("state", 0) : 0;
        boolean enableReserve = raw != null && raw.optBoolean("enableReserve", false);
        int reserveState = raw != null ? raw.optInt("reserveState", 0) : 0;

        if (state == 1) {
            return new DeviceState("idle", "空闲", 0, true, "instant");
        }
        if (state == 2 && enableReserve && reserveState == 1) {
            return new DeviceState("busy_reservable", "使用中，可预约", 1, true, "reserve");
        }
        if (state == 2) {
            return new DeviceState("busy_full", "使用中，轮询抢占", 2, true, "poll");
        }
        if (state == 3 || state == 4) {
            return new DeviceState("fault", "故障或离线", null, false, null);
        }
        return new DeviceState("unknown", "状态未知", null, false, null);
    }

    public static String classifyOrder(JSONObject order) {
        if (order == null) return "unknown";
        if (order.optBoolean("trackingConflict", false)) {
            return "tracking_conflict";
        }
        int state = order.optInt("state", 0);
        String pageCode = optNullableString(order, "pageCode");
        boolean canPay = order.optBoolean("canPay", false);
        boolean canCancel = order.optBoolean("canCancel", false);
        int orderType = order.optInt("orderType", 0);

        if (TERMINAL_ORDER_STATES.contains(state) || (pageCode != null && TERMINAL_PAGE_CODES.contains(pageCode))) {
            return "ended";
        }
        if (state == 100 || (pageCode != null && PAYMENT_PAGE_CODES.contains(pageCode)) || canPay) {
            return "awaiting_payment";
        }
        if (state == 50 && canCancel) {
            return "reserve_waiting";
        }
        if (pageCode != null && RESERVE_PAGE_CODES.contains(pageCode)) {
            return "reserve_waiting";
        }
        if (orderType == 300 && canCancel) {
            return "reserve_waiting";
        }
        if (state == 500 || (pageCode != null && RUNNING_PAGE_CODES.contains(pageCode))) {
            return "running";
        }
        return "unknown";
    }

    public static String orderStageLabel(String stage, String pageCode) {
        if ("reserve_waiting".equals(stage)) {
            if ("appointment_waiting".equals(pageCode)) return "已预约候机";
            if ("waiting_check".equals(pageCode)) return "已到号·待验证";
            if ("self_clean".equals(pageCode)) return "筒自洁中";
            if ("place_clothes".equals(pageCode)) return "待放衣物";
        }
        if ("awaiting_payment".equals(stage) && "waiting_choose_ump".equals(pageCode)) {
            return "待选券支付";
        }
        if ("awaiting_payment".equals(stage)) return "待支付";
        if ("reserve_waiting".equals(stage)) return "预约/验证中";
        if ("running".equals(stage)) return "进行中";
        if ("tracking_conflict".equals(stage)) return "待确认";
        if ("ended".equals(stage)) return "已结束";
        return "未知";
    }

    public static boolean orderReadyForUserAction(JSONObject order) {
        if (order == null) return false;
        String stage = classifyOrder(order);
        if ("awaiting_payment".equals(stage)) return true;
        if ("reserve_waiting".equals(stage)) {
            String pageCode = optNullableString(order, "pageCode");
            return order.optBoolean("canPay", false)
                || (pageCode != null && ACTIONABLE_RESERVE_PAGE_CODES.contains(pageCode));
        }
        return false;
    }

    public static boolean orderBlocksAccount(JSONObject order) {
        String stage = classifyOrder(order);
        return "awaiting_payment".equals(stage)
            || "reserve_waiting".equals(stage)
            || "tracking_conflict".equals(stage);
    }

    public static JSONObject enrichOrder(JSONObject order) {
        JSONObject copy = cloneJson(order);
        String stage = classifyOrder(copy);
        String pageCode = optNullableString(copy, "pageCode");
        try {
            copy.put("stage", stage);
            copy.put("stageLabel", orderStageLabel(stage, pageCode));
            copy.put("blocksAccount", orderBlocksAccount(copy));
            copy.put("readyForUserAction", orderReadyForUserAction(copy));
        } catch (Exception ignored) {
        }
        return copy;
    }

    public static boolean modeSupportsSelfClean(JSONObject mode) {
        if (mode == null) return false;
        JSONObject attachMap = mode.optJSONObject("attachMap");
        return attachMap != null && attachMap.optBoolean("SelfClean", false);
    }

    public static Integer findSelfCleanItemId(JSONArray modes) {
        if (modes == null) return null;
        for (int i = 0; i < modes.length(); i++) {
            JSONObject item = modes.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "");
            int skuId = item.optInt("skuId", -1);
            if (skuId == SELF_CLEAN_SKU_ID || name.contains("自洁")) {
                if (item.has("id") && !item.isNull("id")) {
                    int id = item.optInt("id", -1);
                    if (id > 0) return id;
                }
            }
        }
        return null;
    }

    public static JSONObject matchModeByPreference(String deviceName, JSONArray items, String preferredName) {
        if (items == null || items.length() == 0) return null;
        String pref = preferredName != null ? preferredName.trim() : "";
        if (!pref.isEmpty()) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null && pref.equals(it.optString("name", "").trim())) return it;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String n = it.optString("name", "");
                if (!n.isEmpty() && (n.contains(pref) || pref.contains(n))) return it;
            }
        }
        return selectPreferredMode(deviceName, items);
    }

    public static String inferDeviceNameFromOrder(JSONObject order) {
        if (order == null) return "";
        String text = order.optString("goodsName", "");
        if (text.isEmpty()) {
            text = order.optString("projectName", "");
        }
        for (String name : KNOWN_DEVICE_NAMES) {
            if (text.contains(name)) return name;
        }
        Matcher matcher = DEVICE_NAME_PATTERN.matcher(text);
        if (matcher.find()) {
            String num = matcher.group(1);
            String kind = matcher.group(2);
            return (num.length() == 1 ? "0" + num : num) + "号" + kind;
        }
        return "";
    }

    public static boolean isAuthFailure(int code, String message) {
        String text = message == null ? "" : message;
        return code == 2 || code == 401 || code == 100002 || code == 100003
            || text.contains("登录已失效")
            || text.contains("未登录")
            || text.contains("其它设备登录")
            || text.contains("其他设备登录");
    }

    private static final String[] ACCOUNT_CONFLICT_KEYS = new String[]{
        "已有一笔订单",
        "已有订单",
        "已存在订单",
        "账号存在订单",
        "该账号存在订单",
        "未完成订单",
        "存在预约单",
        "订单进行中",
        "您有一笔",
        "您已存在",
        "您的账号"
    };

    private static boolean hasAccountConflictKey(String text) {
        for (String key : ACCOUNT_CONFLICT_KEYS) {
            if (text.contains(key)) return true;
        }
        return false;
    }

    public static boolean isDevicePaymentConflict(String message) {
        String text = message == null ? "" : message;
        if (hasAccountConflictKey(text)) return false;
        String trimmed = text.trim();
        return (text.contains("其他用户") && (text.contains("等待支付") || text.contains("待支付") || text.contains("付款")))
            || text.contains("正在付款")
            || (text.contains("设备") && text.contains("待支付"))
            || "有人待支付".equals(trimmed)
            || "待支付".equals(trimmed);
    }

    public static boolean isAccountOrderConflict(String message) {
        String text = message == null ? "" : message;
        if (isDevicePaymentConflict(text)) return false;
        return hasAccountConflictKey(text);
    }

    public static boolean isDryerDeviceName(String deviceName) {
        return deviceName != null && deviceName.contains("烘干");
    }

    public static String deviceTypeFromName(String deviceName) {
        return isDryerDeviceName(deviceName) ? "dryer" : "washer";
    }

    public static JSONObject buildPurchaseItem(int goodsId, int goodsItemId, int quantity) {
        JSONObject item = new JSONObject();
        try {
            item.put("goodsId", goodsId);
            item.put("goodsItemId", goodsItemId);
            item.put("soldType", 1);
            item.put("amount", quantity);
            item.put("num", quantity);
        } catch (Exception ignored) {
        }
        return item;
    }

    public static JSONObject buildDevicePurchaseItem(String deviceName, int goodsId, int goodsItemId, Integer dryerMinutes) {
        if (isDryerDeviceName(deviceName)) {
            int minutes = dryerMinutes != null && dryerMinutes > 0 ? dryerMinutes : DRYER_DEFAULT_MINUTES;
            JSONObject item = new JSONObject();
            try {
                item.put("goodsId", goodsId);
                item.put("goodsItemId", goodsItemId);
                item.put("soldType", 2);
                item.put("amount", 1);
                item.put("num", Math.max(1, minutes));
            } catch (Exception ignored) {
            }
            return item;
        }
        return buildPurchaseItem(goodsId, goodsItemId, 1);
    }

    public static JSONObject selectPreferredMode(String deviceName, JSONArray items) {
        if (items == null || items.length() == 0) return null;
        String[] priorities = isDryerDeviceName(deviceName)
            ? new String[]{"高温", "中温", "低温", "烘干"}
            : new String[]{"标准", "常规", "大物", "快速", "单脱"};
        for (String p : priorities) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && item.optString("name", "").contains(p)) {
                    return item;
                }
            }
        }
        return items.optJSONObject(0);
    }

    public static Long getTokenExpiry(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            JSONObject payload = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            if (payload.has("exp")) {
                long exp = payload.optLong("exp", 0L);
                return exp > 0 ? exp : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean isTokenExpired(String token) {
        Long exp = getTokenExpiry(token);
        if (exp == null) return true;
        long nowSec = System.currentTimeMillis() / 1000L;
        return exp <= nowSec + 30L;
    }

    public static String formatTimestamp(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) return null;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        return sdf.format(new Date(epochSeconds * 1000L));
    }

    public static String extractProjectName(JSONObject orderItem, String fallback) {
        if (orderItem == null) return fallback;
        String goodsName = optNullableString(orderItem, "goodsName");
        if (goodsName == null || goodsName.isEmpty()) goodsName = fallback;
        String modeName = optNullableString(orderItem, "goodsItemName");
        if (goodsName != null && !goodsName.isEmpty() && modeName != null && !modeName.isEmpty()) {
            return goodsName + "【" + modeName + "】";
        }
        return goodsName != null ? goodsName : fallback;
    }

    public static String optNullableString(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null;
        String val = obj.optString(key, "");
        return "null".equalsIgnoreCase(val) ? null : val;
    }

    public static String deviceCodeFor(String imeiOrName) {
        if (imeiOrName == null) return "";
        for (Map.Entry<String, String> entry : TARGET_DEVICE_CODES.entrySet()) {
            if (imeiOrName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "";
    }

    public static String normalizeOtaApkUrl(String rawUrl) {
        String clean = rawUrl != null ? rawUrl.trim() : "";
        if (clean.isEmpty() || clean.contains("4c666.top") || clean.endsWith("/haile-bot-v3.apk")) {
            return OTA_DEFAULT_APK_URL;
        }
        return clean;
    }

    public static String buildWechatMiniScheme(String imeiOrName) {
        return buildWechatMiniScheme(imeiOrName, true);
    }

    public static String buildWechatMiniScheme(String imeiOrName, boolean forPayment) {
        String base = "weixin://dl/business/?appid=" + WECHAT_MINI_APP_ID + "&path=pages/tabbar/home";
        if (!forPayment) {
            String code = deviceCodeFor(imeiOrName);
            if (code != null && !code.isEmpty()) {
                try {
                    return base + "&query=" + java.net.URLEncoder.encode("N=" + code, "UTF-8");
                } catch (Exception ignored) {}
            }
        }
        return base;
    }

    public static String buildAlipayCashierScheme(String orderNo) {
        String cleanNo = orderNo != null ? orderNo.trim() : "";
        String page = !cleanNo.isEmpty() ? ("pages/pay/cashier?orderNo=" + cleanNo) : "pages/tabbar/home";
        try {
            return "alipays://platformapi/startapp?appId=" + ALIPAY_MINI_APP_ID + "&page=" + java.net.URLEncoder.encode(page, "UTF-8");
        } catch (Exception e) {
            return "alipays://platformapi/startapp?appId=" + ALIPAY_MINI_APP_ID + "&page=pages%2Ftabbar%2Fhome";
        }
    }

    public static JSONObject cloneJson(JSONObject src) {
        if (src == null) return new JSONObject();
        try {
            return new JSONObject(src.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static final double GCJ_A = 6378245.0;
    private static final double GCJ_EE = 0.00669342162296594323;
    private static final Pattern COORD_PAIR_RE = Pattern.compile("^\\s*(-?\\d{2,3}\\.\\d+)\\s*[,，\\s]\\s*(-?\\d{1,2}\\.\\d+)\\s*$");
    private static final Pattern IMEI_15_RE = Pattern.compile("^\\d{14,17}$");
    private static final Pattern POS_ID_RE = Pattern.compile("^#?(\\d{1,10})$");
    private static final Pattern DEV_CODE_RE = Pattern.compile("^(G[A-Z]{1,3}\\d{6,16})$", Pattern.CASE_INSENSITIVE);

    private static final String[][] CAMPUS_PRESETS = new String[][]{
        {"青岛大学 · 浮山校区", "山东青岛 · 市南/崂山", "120.4250", "36.0716", "青大 浮山 泓园 滢园 浩园"},
        {"青岛大学 · 崂山校区", "山东青岛 · 崂山区", "120.4965", "36.1185", "青大 崂山"},
        {"中国海洋大学 · 崂山校区", "山东青岛 · 松岭路", "120.4981", "36.1617", "海大 中国海大"},
        {"山东科技大学 · 青岛校区", "山东青岛 · 黄岛区", "120.1235", "35.9998", "山科大 西海岸 黄岛"},
        {"山东大学 · 中心校区", "山东济南 · 历城区", "117.0604", "36.6758", "山大 济南 洪家楼 兴隆山 软件园"},
        {"济南长清大学城", "山东济南 · 长清区", "116.8142", "36.5446", "齐鲁工大 山师 长清"},
        {"北京海淀 · 学院路/中关村高校区", "北京 · 清华/北大/北航/北理/人大", "116.3264", "39.9928", "北京 清华大学 北京大学 北航 北科大 北邮 人大"},
        {"北京良乡高教园区", "北京 · 房山区", "116.1736", "39.7347", "良乡 北理工 社科院 北工商"},
        {"北京沙河高教园区", "北京 · 昌平区", "116.2804", "40.1578", "沙河 昌平 中财 北邮北航沙河"},
        {"上海松江大学城", "上海 · 松江区", "121.2139", "31.0528", "上海 松江 东华 华政 上外 上经贸"},
        {"上海闵行高校区", "上海 · 交大/华师大闵行", "121.4368", "31.0256", "上海交大 华东师大 闵行"},
        {"上海杨浦 · 五角场高校区", "上海 · 复旦/同济/上财", "121.5038", "31.2982", "复旦大学 同济大学 上海财大 杨浦"},
        {"广州大学城", "广东广州 · 番禺小谷围", "113.3917", "23.0656", "广州 中山大学 华南理工 广外 广大 华师"},
        {"深圳大学城 / 粤海校区", "广东深圳 · 南山区", "113.9365", "22.5332", "深圳 深大 南科大 哈工深"},
        {"浙江大学 · 紫金港校区", "浙江杭州 · 西湖区", "120.0864", "30.3025", "杭州 浙大 紫金港 玉泉 西溪"},
        {"杭州下沙高教园区", "浙江杭州 · 钱塘区", "120.3489", "30.3156", "杭州 下沙 杭电 浙理工 浙工商 计量"},
        {"南京仙林大学城", "江苏南京 · 栖霞区", "118.9538", "32.1154", "南京 南大 仙林 南师大 南邮 南财"},
        {"南京江宁大学城", "江苏南京 · 江宁区", "118.8886", "31.9045", "江宁 东南大学 九龙湖 南航 河海 中国药大"},
        {"苏州独墅湖高教区", "江苏苏州 · 工业园区", "120.7376", "31.2738", "苏州 苏大 西交利物浦 独墅湖"},
        {"武汉大学 / 洪山高校区", "湖北武汉 · 珞珈山/广埠屯", "114.3655", "30.5364", "武汉 武大 华师 理工大 街道口"},
        {"华中科技大学 / 光谷高校区", "湖北武汉 · 洪山关山", "114.4138", "30.5135", "华科 中南民大 地大 中南财大 光谷"},
        {"四川大学 · 江安校区", "四川成都 · 双流区", "103.9986", "30.5562", "成都 川大 江安 望江"},
        {"电子科技大学 · 清水河校区", "四川成都 · 郫都/高新西区", "103.9278", "30.7506", "成电 电子科大 西南交大 犀浦 温江"},
        {"重庆大学城 · 虎溪校区", "重庆 · 沙坪坝区", "106.2988", "29.6032", "重庆 重大 重师 虎溪 南岸 西政"},
        {"西安长安大学城", "陕西西安 · 长安区", "108.8866", "34.1538", "西安 西电 陕师大 西北大学 西工大 长安"},
        {"西安交通大学 · 兴庆/创新港", "陕西西安 · 碑林区", "108.9845", "34.2462", "西交大 兴庆 长安大学"},
        {"天津海河教育园区 / 八里台", "天津 · 南开/天大", "117.3168", "38.9972", "天津 南开大学 天津大学 津南 八里台"},
        {"长沙岳麓山大学城", "湖南长沙 · 岳麓区", "112.9388", "28.1745", "长沙 中南大学 湖南大学 湖师大 岳麓山"},
        {"厦门大学 · 思明/翔安校区", "福建厦门 · 思明/翔安", "118.1038", "24.4368", "厦门 厦大 集美 翔安"},
        {"福州大学城 · 旗山校区", "福建福州 · 闽侯县", "119.1972", "26.0586", "福州 福大 福建师大 闽侯"},
        {"中国科学技术大学 / 合肥经开高教区", "安徽合肥 · 包河/蜀山", "117.2692", "31.8368", "合肥 中科大 合工大 安大"},
        {"郑州龙子湖高校园区", "河南郑州 · 郑东新区", "113.8065", "34.7932", "郑州 郑大 华水 河南财大 龙子湖"},
        {"哈尔滨高校区", "黑龙江哈尔滨 · 南岗区", "126.6335", "45.7432", "哈尔滨 哈工大 哈工程 黑大 东北林大"},
        {"长春高校区", "吉林长春 · 朝阳/南关", "125.2925", "43.8242", "长春 吉林大学 吉大 东北师大"},
        {"沈阳浑南 / 沈北大学城", "辽宁沈阳 · 东北大学/辽大", "123.4215", "41.6548", "沈阳 东北大学 辽宁大学 沈北"},
        {"大连理工大学 / 凌水高校区", "辽宁大连 · 甘井子区", "121.5256", "38.8812", "大连 大工 海事 东财"},
        {"南昌红谷滩前湖大学城", "江西南昌 · 南昌大学", "115.8024", "28.6628", "南昌 南昌大学 江财 江师"},
        {"昆明呈贡大学城", "云南昆明 · 云南大学/昆工", "102.8482", "24.8325", "昆明 云大 昆工 云师大 呈贡"},
        {"南宁西乡塘 / 五合大学城", "广西南宁 · 广西大学", "108.2912", "22.8426", "南宁 广西大学 西大"},
        {"贵阳花溪大学城", "贵州贵阳 · 贵州大学", "106.6632", "26.4425", "贵阳 贵州大学 贵大 花溪"},
        {"太原榆次大学城 / 迎泽", "山西太原 · 太原理工/山大", "112.7248", "37.7495", "太原 太原理工 山西大学 榆次"},
        {"石家庄高校区", "河北石家庄 · 河北师大/科大", "114.5218", "37.9965", "石家庄 河北师大 铁道大学"},
        {"兰州榆中 / 安宁高校区", "甘肃兰州 · 兰州大学", "103.8615", "36.0468", "兰州 兰大 西北师大 兰交大"}
    };

    private static boolean outOfChina(double lng, double lat) {
        return !(lng >= 72.004 && lng <= 137.8347 && lat >= 0.8293 && lat <= 55.8271);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    public static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{Math.round(lng * 1_000_000.0) / 1_000_000.0, Math.round(lat * 1_000_000.0) / 1_000_000.0};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1.0 - GCJ_EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((GCJ_A * (1.0 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (GCJ_A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{
            Math.round((lng + dLng) * 1_000_000.0) / 1_000_000.0,
            Math.round((lat + dLat) * 1_000_000.0) / 1_000_000.0
        };
    }

    public static JSONArray searchCampusPresets(String query, int limit) {
        String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        JSONArray out = new JSONArray();
        if (q.isEmpty()) {
            for (int i = 0; i < Math.min(limit, CAMPUS_PRESETS.length); i++) {
                String[] p = CAMPUS_PRESETS[i];
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("name", p[0]);
                    obj.put("subtitle", p[1]);
                    obj.put("lng", Double.parseDouble(p[2]));
                    obj.put("lat", Double.parseDouble(p[3]));
                    out.put(obj);
                } catch (Exception ignored) {}
            }
            return out;
        }
        String[] qTokens = q.split("[\\s·,，/-]+");
        java.util.List<int[]> scored = new java.util.ArrayList<>();
        for (int idx = 0; idx < CAMPUS_PRESETS.length; idx++) {
            String[] item = CAMPUS_PRESETS[idx];
            String hay = (item[0] + " " + item[1] + " " + item[4]).toLowerCase(Locale.ROOT);
            String hayCompact = hay.replaceAll("[\\s·,，/-]+", "");
            String[] words = hay.split("[\\s·,，/-]+");
            int score = 0;
            if (item[0].toLowerCase(Locale.ROOT).contains(q)) score += 120;
            else if (hay.contains(q) || hayCompact.contains(q)) score += 80;
            for (String tok : qTokens) {
                if (tok.length() >= 2 && (hay.contains(tok) || hayCompact.contains(tok))) score += 30;
            }
            for (String w : words) {
                if (w.length() >= 2 && q.contains(w)) score += 35;
            }
            if (score > 0) scored.add(new int[]{score, idx});
        }
        scored.sort((a, b) -> Integer.compare(b[0], a[0]));
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            String[] p = CAMPUS_PRESETS[scored.get(i)[1]];
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", p[0]);
                obj.put("subtitle", p[1]);
                obj.put("lng", Double.parseDouble(p[2]));
                obj.put("lat", Double.parseDouble(p[3]));
                out.put(obj);
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static JSONObject parseCustomPositionInput(String rawText) {
        JSONObject out = new JSONObject();
        try {
            String text = rawText != null ? rawText.trim() : "";
            if (text.isEmpty()) {
                out.put("kind", "empty");
                return out;
            }
            Matcher cm = COORD_PAIR_RE.matcher(text);
            if (cm.matches()) {
                double a = Double.parseDouble(cm.group(1));
                double b = Double.parseDouble(cm.group(2));
                out.put("kind", "coords");
                if (Math.abs(a) <= 90.0 && Math.abs(b) > 90.0) {
                    out.put("lng", b);
                    out.put("lat", a);
                } else {
                    out.put("lng", a);
                    out.put("lat", b);
                }
                return out;
            }
            if (text.contains("http://") || text.contains("https://") || text.contains("?") || text.contains("/barCode/")) {
                String unq = java.net.URLDecoder.decode(text, "UTF-8");
                if (unq.contains("/barCode/")) {
                    String bar = unq.split("/barCode/", 2)[1].split("[?/]", 2)[0].trim();
                    if (!bar.isEmpty()) {
                        out.put("kind", "scan");
                        out.put("imei", "");
                        out.put("n", bar);
                        return out;
                    }
                }
                int qIdx = unq.indexOf('?');
                String qs = qIdx >= 0 ? unq.substring(qIdx + 1) : unq;
                Map<String, String> flat = new HashMap<>();
                for (String pair : qs.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        flat.put(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT), pair.substring(eq + 1).trim());
                    }
                }
                for (String pk : new String[]{"positionid", "shopid"}) {
                    String pv = flat.get(pk);
                    if (pv != null && pv.matches("^\\d+$")) {
                        out.put("kind", "position_id");
                        out.put("positionId", Integer.parseInt(pv));
                        return out;
                    }
                }
                String imei = flat.getOrDefault("imei", "");
                String nCode = flat.getOrDefault("n", flat.getOrDefault("devid", flat.getOrDefault("deviceld", flat.getOrDefault("deviceid", ""))));
                if (!imei.isEmpty() || !nCode.isEmpty()) {
                    out.put("kind", "scan");
                    out.put("imei", imei);
                    out.put("n", nCode);
                    return out;
                }
            }
            if (IMEI_15_RE.matcher(text).matches()) {
                out.put("kind", "scan");
                out.put("imei", text);
                out.put("n", "");
                return out;
            }
            Matcher dm = DEV_CODE_RE.matcher(text);
            if (dm.matches()) {
                out.put("kind", "scan");
                out.put("imei", "");
                out.put("n", dm.group(1).toUpperCase(Locale.ROOT));
                return out;
            }
            Matcher pm = POS_ID_RE.matcher(text);
            if (pm.matches()) {
                out.put("kind", "position_id");
                out.put("positionId", Integer.parseInt(pm.group(1)));
                return out;
            }
            out.put("kind", "keyword");
            out.put("query", text);
        } catch (Exception ignored) {
            try { out.put("kind", "keyword"); out.put("query", rawText != null ? rawText.trim() : ""); } catch (Exception ignored2) {}
        }
        return out;
    }
}
