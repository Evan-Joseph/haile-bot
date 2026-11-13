package top.hailebot.v3;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HaileRecommendation {
    private HaileRecommendation() {}

    public static String[] deviceUserLabels(
        JSONObject raw,
        HaileDomain.DeviceState state,
        JSONObject activeOrder,
        String visibleIntentState
    ) {
        String orderStage = activeOrder != null ? activeOrder.optString("stage", "") : "";
        if ("awaiting_payment".equals(orderStage)) {
            return new String[]{"已锁单，待支付", "我已锁单"};
        }
        if ("reserve_waiting".equals(orderStage)) {
            if (HaileDomain.orderReadyForUserAction(activeOrder)) {
                return new String[]{"已到号，请验证/支付", "已轮到我"};
            }
            return new String[]{"前序运行中 · 我已预约", "我排第 1 位"};
        }
        if ("running".equals(orderStage)) {
            boolean reservable = raw != null && raw.optBoolean("enableReserve", false) && raw.optInt("reserveState", 0) == 1;
            return new String[]{"我的订单运行中", reservable ? "0 人排队" : "后序 1 人已约"};
        }
        if ("idle".equals(state.key)) {
            return new String[]{state.label, "无需排队"};
        }
        if ("busy_reservable".equals(state.key)) {
            return new String[]{"运行中，可预约", "0 人排队"};
        }
        if ("busy_full".equals(state.key)) {
            if ("holding_reserve".equals(visibleIntentState)) {
                return new String[]{"前序运行中 · 我已预约", "我排第 1 位"};
            }
            return new String[]{"运行中，1人已约", "1 人排队"};
        }
        if ("payment_pending".equals(state.key)) {
            return new String[]{state.label, "等待支付释放"};
        }
        if ("fault".equals(state.key)) {
            return new String[]{state.label, "暂停服务"};
        }
        return new String[]{state.label, "状态未知"};
    }

    public static int scoreDeviceForRecommendation(JSONObject d) {
        String state = d.optString("state", "");
        int score = 0;
        if ("idle".equals(state)) score = 1000;
        else if ("busy_reservable".equals(state)) score = 760;
        else if ("busy_full".equals(state)) score = 420;
        else if ("payment_pending".equals(state)) score = 80;
        else if ("unknown".equals(state)) score = 20;
        else if ("fault".equals(state)) score = 10;

        if (d.optBoolean("canOrder", false)) score += 40;
        String kind = d.optString("orderKind", "");
        if ("instant".equals(kind)) score += 80;
        else if ("reserve".equals(kind)) score += 30;

        if (!d.isNull("queueCount")) {
            score -= d.optInt("queueCount", 0) * 60;
        }
        if (!d.isNull("remainingSeconds")) {
            double rem = Math.max(0.0, d.optDouble("remainingSeconds", 0.0));
            score -= Math.min((int) (rem / 60.0), 240);
        }
        JSONObject activeOrder = d.optJSONObject("activeOrder");
        if (activeOrder != null) {
            String st = activeOrder.optString("stage", "");
            if ("awaiting_payment".equals(st) || "reserve_waiting".equals(st)) score -= 520;
            else if ("running".equals(st)) score -= 180;
        }
        if (d.optBoolean("occupiedByOtherUser", false)) score -= 640;
        return score;
    }

    private static String recommendationWaitText(JSONObject d) {
        if (!d.isNull("remainingSeconds")) {
            double sec = Math.max(0.0, d.optDouble("remainingSeconds", 0.0));
            if (sec <= 0) return "预计很快释放";
            if (sec < 60) return "预计 1 分钟内释放";
            int mins = (int) ((sec + 59) / 60);
            return "预计 " + mins + " 分钟后释放";
        }
        String ft = d.optString("finishTime", "");
        return !ft.isEmpty() ? "预计 " + ft + " 释放" : "";
    }

    private static String recommendationReason(JSONObject d) {
        if (d.optBoolean("occupiedByOtherUser", false)) {
            String lbl = d.optString("occupiedByOtherUserLabel", "其他用户");
            return lbl + " 正在抢占";
        }
        JSONObject activeOrder = d.optJSONObject("activeOrder");
        if (activeOrder != null && "reserve_waiting".equals(activeOrder.optString("stage", ""))) {
            return "我已排上预约，等待前序结束";
        }
        String state = d.optString("state", "");
        if ("idle".equals(state)) return "当前空闲，可立即下单";
        if ("busy_reservable".equals(state)) {
            String w = recommendationWaitText(d);
            return !w.isEmpty() ? "当前可预约，" + w : "当前可预约";
        }
        if ("busy_full".equals(state)) {
            String w = recommendationWaitText(d);
            return !w.isEmpty() ? "当前 1 人已约，轮询抢占，" + w : "当前 1 人已约，轮询抢占";
        }
        if ("payment_pending".equals(state)) return "当前有待支付订单，先等待释放";
        if ("fault".equals(state)) return "设备故障或离线，暂不可用";
        return d.optString("stateLabel", "状态未知");
    }

    public static void decorateDeviceRecommendations(List<JSONObject> devices) {
        Map<String, List<JSONObject>> grouped = new HashMap<>();
        for (JSONObject d : devices) {
            String type = d.optString("deviceType", "washer");
            try {
                d.put("recommendedScore", scoreDeviceForRecommendation(d));
                d.put("recommendedReason", recommendationReason(d));
            } catch (Exception ignored) {}
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(d);
        }
        for (List<JSONObject> items : grouped.values()) {
            items.sort((a, b) -> {
                int cmp = Integer.compare(b.optInt("recommendedScore", 0), a.optInt("recommendedScore", 0));
                if (cmp != 0) return cmp;
                return a.optString("name", "").compareTo(b.optString("name", ""));
            });
            int positiveCount = 0;
            for (JSONObject item : items) {
                if (item.optInt("recommendedScore", 0) > 0) positiveCount++;
            }
            int cutoff = Math.min(2, positiveCount);
            for (int idx = 0; idx < items.size(); idx++) {
                int rank = idx + 1;
                JSONObject item = items.get(idx);
                String st = item.optString("state", "");
                String tone;
                if (item.optBoolean("occupiedByOtherUser", false) || "payment_pending".equals(st) || "fault".equals(st)) {
                    tone = "gray";
                } else if (rank == 1 && cutoff >= 1) {
                    tone = "green";
                } else if (rank <= cutoff) {
                    tone = "yellow";
                } else if (item.optBoolean("canOrder", false)) {
                    tone = "gray";
                } else {
                    tone = "red";
                }
                try {
                    item.put("recommendedRank", rank);
                    item.put("isRecommended", cutoff > 0 && rank <= cutoff);
                    item.put("recommendedTone", tone);
                } catch (Exception ignored) {}
            }
        }
    }

    public static JSONObject buildRecommendationGroups(JSONArray devicesArr) {
        Map<String, List<JSONObject>> grouped = new LinkedHashMap<>();
        grouped.put("washer", new ArrayList<>());
        grouped.put("dryer", new ArrayList<>());
        for (int i = 0; i < devicesArr.length(); i++) {
            JSONObject d = devicesArr.optJSONObject(i);
            if (d == null) continue;
            String t = d.optString("deviceType", "washer");
            grouped.computeIfAbsent(t, k -> new ArrayList<>()).add(d);
        }
        JSONObject groups = new JSONObject();
        for (Map.Entry<String, List<JSONObject>> entry : grouped.entrySet()) {
            List<JSONObject> items = entry.getValue();
            if (items.isEmpty()) continue;
            items.sort(Comparator.comparingInt((JSONObject a) -> a.optInt("recommendedRank", 999))
                .thenComparing(a -> a.optString("name", "")));
            String type = entry.getKey();
            String label = "dryer".equals(type) ? "烘干机" : "洗衣机";
            JSONObject top = items.get(0);
            String summary;
            if (top.optBoolean("occupiedByOtherUser", false) || "payment_pending".equals(top.optString("state"))) {
                summary = label + " 暂无可直接抢占设备，先盯 " + top.optString("name");
            } else if (items.size() > 1 && items.get(1).optBoolean("isRecommended", false)) {
                summary = "优先 " + top.optString("name") + "，其次 " + items.get(1).optString("name");
            } else {
                summary = "优先 " + top.optString("name");
            }
            JSONArray outItems = new JSONArray();
            for (JSONObject item : items) {
                JSONObject o = new JSONObject();
                try {
                    o.put("goodsId", item.optInt("goodsId"));
                    o.put("name", item.optString("name"));
                    o.put("rank", item.optInt("recommendedRank"));
                    o.put("score", item.optInt("recommendedScore"));
                    o.put("reason", item.optString("recommendedReason"));
                    o.put("tone", item.optString("recommendedTone"));
                    o.put("occupiedByOtherUser", item.optBoolean("occupiedByOtherUser"));
                    o.put("occupiedByOtherUserLabel", item.optString("occupiedByOtherUserLabel"));
                } catch (Exception ignored) {}
                outItems.put(o);
            }
            JSONObject grp = new JSONObject();
            try {
                grp.put("deviceType", type);
                grp.put("label", label);
                grp.put("bestGoodsId", top.optInt("goodsId"));
                grp.put("summary", summary);
                grp.put("items", outItems);
                groups.put(type, grp);
            } catch (Exception ignored) {}
        }
        return groups;
    }
}
