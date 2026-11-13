from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import math
import random
import re
import threading
import time
from typing import Any, Callable, Optional
from urllib.parse import parse_qs, quote, unquote, urlparse


OTA_PRIMARY_BASE = "https://haile-bot.pages.dev"
OTA_DEFAULT_APK_URL = f"{OTA_PRIMARY_BASE}/haile-bot-v4.apk"
WECHAT_MINI_APP_ID = "wx7519e26c1d2f9edc"
ALIPAY_MINI_APP_ID = "2021003183687122"
OTA_CHECK_URLS = (
    f"{OTA_PRIMARY_BASE}/ota.json",
    f"{OTA_PRIMARY_BASE}/api/ota",
)

DEFAULT_POSITION_ID = 32577
DEFAULT_POSITION_NAME = "泓园 7 号楼"
DEFAULT_CAMPUS_COORDS = (
    {"label": "浮山校区", "lng": 120.4250, "lat": 36.0716},
    {"label": "崂山校区", "lng": 120.4965, "lat": 36.1185},
)

CAMPUS_PRESETS: tuple[dict[str, Any], ...] = (
    {"name": "青岛大学 · 浮山校区", "subtitle": "山东青岛 · 市南/崂山", "lng": 120.4250, "lat": 36.0716, "keywords": "青大 浮山 泓园 滢园 浩园"},
    {"name": "青岛大学 · 崂山校区", "subtitle": "山东青岛 · 崂山区", "lng": 120.4965, "lat": 36.1185, "keywords": "青大 崂山"},
    {"name": "中国海洋大学 · 崂山校区", "subtitle": "山东青岛 · 松岭路", "lng": 120.4981, "lat": 36.1617, "keywords": "海大 中国海大"},
    {"name": "山东科技大学 · 青岛校区", "subtitle": "山东青岛 · 黄岛区", "lng": 120.1235, "lat": 35.9998, "keywords": "山科大 西海岸 黄岛"},
    {"name": "山东大学 · 中心校区", "subtitle": "山东济南 · 历城区", "lng": 117.0604, "lat": 36.6758, "keywords": "山大 济南 洪家楼 兴隆山 软件园"},
    {"name": "济南长清大学城", "subtitle": "山东济南 · 长清区", "lng": 116.8142, "lat": 36.5446, "keywords": "齐鲁工大 山师 长清"},
    {"name": "北京海淀 · 学院路/中关村高校区", "subtitle": "北京 · 清华/北大/北航/北理/人大", "lng": 116.3264, "lat": 39.9928, "keywords": "北京 清华大学 北京大学 北航 北科大 北邮 人大"},
    {"name": "北京良乡高教园区", "subtitle": "北京 · 房山区", "lng": 116.1736, "lat": 39.7347, "keywords": "良乡 北理工 社科院 北工商"},
    {"name": "北京沙河高教园区", "subtitle": "北京 · 昌平区", "lng": 116.2804, "lat": 40.1578, "keywords": "沙河 昌平 中财 北邮北航沙河"},
    {"name": "上海松江大学城", "subtitle": "上海 · 松江区", "lng": 121.2139, "lat": 31.0528, "keywords": "上海 松江 东华 华政 上外 上经贸"},
    {"name": "上海闵行高校区", "subtitle": "上海 · 交大/华师大闵行", "lng": 121.4368, "lat": 31.0256, "keywords": "上海交大 华东师大 闵行"},
    {"name": "上海杨浦 · 五角场高校区", "subtitle": "上海 · 复旦/同济/上财", "lng": 121.5038, "lat": 31.2982, "keywords": "复旦大学 同济大学 上海财大 杨浦"},
    {"name": "广州大学城", "subtitle": "广东广州 · 番禺小谷围", "lng": 113.3917, "lat": 23.0656, "keywords": "广州 中山大学 华南理工 广外 广大 华师"},
    {"name": "深圳大学城 / 粤海校区", "subtitle": "广东深圳 · 南山区", "lng": 113.9365, "lat": 22.5332, "keywords": "深圳 深大 南科大 哈工深"},
    {"name": "浙江大学 · 紫金港校区", "subtitle": "浙江杭州 · 西湖区", "lng": 120.0864, "lat": 30.3025, "keywords": "杭州 浙大 紫金港 玉泉 西溪"},
    {"name": "杭州下沙高教园区", "subtitle": "浙江杭州 · 钱塘区", "lng": 120.3489, "lat": 30.3156, "keywords": "杭州 下沙 杭电 浙理工 浙工商 计量"},
    {"name": "南京仙林大学城", "subtitle": "江苏南京 · 栖霞区", "lng": 118.9538, "lat": 32.1154, "keywords": "南京 南大 仙林 南师大 南邮 南财"},
    {"name": "南京江宁大学城", "subtitle": "江苏南京 · 江宁区", "lng": 118.8886, "lat": 31.9045, "keywords": "江宁 东南大学 九龙湖 南航 河海 中国药大"},
    {"name": "苏州独墅湖高教区", "subtitle": "江苏苏州 · 工业园区", "lng": 120.7376, "lat": 31.2738, "keywords": "苏州 苏大 西交利物浦 独墅湖"},
    {"name": "武汉大学 / 洪山高校区", "subtitle": "湖北武汉 · 珞珈山/广埠屯", "lng": 114.3655, "lat": 30.5364, "keywords": "武汉 武大 华师 理工大 街道口"},
    {"name": "华中科技大学 / 光谷高校区", "subtitle": "湖北武汉 · 洪山关山", "lng": 114.4138, "lat": 30.5135, "keywords": "华科 中南民大 地大 中南财大 光谷"},
    {"name": "四川大学 · 江安校区", "subtitle": "四川成都 · 双流区", "lng": 103.9986, "lat": 30.5562, "keywords": "成都 川大 江安 望江"},
    {"name": "电子科技大学 · 清水河校区", "subtitle": "四川成都 · 郫都/高新西区", "lng": 103.9278, "lat": 30.7506, "keywords": "成电 电子科大 西南交大 犀浦 温江"},
    {"name": "重庆大学城 · 虎溪校区", "subtitle": "重庆 · 沙坪坝区", "lng": 106.2988, "lat": 29.6032, "keywords": "重庆 重大 重师 虎溪 南岸 西政"},
    {"name": "西安长安大学城", "subtitle": "陕西西安 · 长安区", "lng": 108.8866, "lat": 34.1538, "keywords": "西安 西电 陕师大 西北大学 西工大 长安"},
    {"name": "西安交通大学 · 兴庆/创新港", "subtitle": "陕西西安 · 碑林区", "lng": 108.9845, "lat": 34.2462, "keywords": "西交大 兴庆 长安大学"},
    {"name": "天津海河教育园区 / 八里台", "subtitle": "天津 · 南开/天大", "lng": 117.3168, "lat": 38.9972, "keywords": "天津 南开大学 天津大学 津南 八里台"},
    {"name": "长沙岳麓山大学城", "subtitle": "湖南长沙 · 岳麓区", "lng": 112.9388, "lat": 28.1745, "keywords": "长沙 中南大学 湖南大学 湖师大 岳麓山"},
    {"name": "厦门大学 · 思明/翔安校区", "subtitle": "福建厦门 · 思明/翔安", "lng": 118.1038, "lat": 24.4368, "keywords": "厦门 厦大 集美 翔安"},
    {"name": "福州大学城 · 旗山校区", "subtitle": "福建福州 · 闽侯县", "lng": 119.1972, "lat": 26.0586, "keywords": "福州 福大 福建师大 闽侯"},
    {"name": "中国科学技术大学 / 合肥经开高教区", "subtitle": "安徽合肥 · 包河/蜀山", "lng": 117.2692, "lat": 31.8368, "keywords": "合肥 中科大 合工大 安大"},
    {"name": "郑州龙子湖高校园区", "subtitle": "河南郑州 · 郑东新区", "lng": 113.8065, "lat": 34.7932, "keywords": "郑州 郑大 华水 河南财大 龙子湖"},
    {"name": "哈尔滨高校区", "subtitle": "黑龙江哈尔滨 · 南岗区", "lng": 126.6335, "lat": 45.7432, "keywords": "哈尔滨 哈工大 哈工程 黑大 东北林大"},
    {"name": "长春高校区", "subtitle": "吉林长春 · 朝阳/南关", "lng": 125.2925, "lat": 43.8242, "keywords": "长春 吉林大学 吉大 东北师大"},
    {"name": "沈阳浑南 / 沈北大学城", "subtitle": "辽宁沈阳 · 东北大学/辽大", "lng": 123.4215, "lat": 41.6548, "keywords": "沈阳 东北大学 辽宁大学 沈北"},
    {"name": "大连理工大学 / 凌水高校区", "subtitle": "辽宁大连 · 甘井子区", "lng": 121.5256, "lat": 38.8812, "keywords": "大连 大工 海事 东财"},
    {"name": "南昌红谷滩前湖大学城", "subtitle": "江西南昌 · 南昌大学", "lng": 115.8024, "lat": 28.6628, "keywords": "南昌 南昌大学 江财 江师"},
    {"name": "昆明呈贡大学城", "subtitle": "云南昆明 · 云南大学/昆工", "lng": 102.8482, "lat": 24.8325, "keywords": "昆明 云大 昆工 云师大 呈贡"},
    {"name": "南宁西乡塘 / 五合大学城", "subtitle": "广西南宁 · 广西大学", "lng": 108.2912, "lat": 22.8426, "keywords": "南宁 广西大学 西大"},
    {"name": "贵阳花溪大学城", "subtitle": "贵州贵阳 · 贵州大学", "lng": 106.6632, "lat": 26.4425, "keywords": "贵阳 贵州大学 贵大 花溪"},
    {"name": "太原榆次大学城 / 迎泽", "subtitle": "山西太原 · 太原理工/山大", "lng": 112.7248, "lat": 37.7495, "keywords": "太原 太原理工 山西大学 榆次"},
    {"name": "石家庄高校区", "subtitle": "河北石家庄 · 河北师大/科大", "lng": 114.5218, "lat": 37.9965, "keywords": "石家庄 河北师大 铁道大学"},
    {"name": "兰州榆中 / 安宁高校区", "subtitle": "甘肃兰州 · 兰州大学", "lng": 103.8615, "lat": 36.0468, "keywords": "兰州 兰大 西北师大 兰交大"},
)

TERMINAL_ORDER_STATES = {401, 411, 1000, 2099}
TERMINAL_PAGE_CODES = {"timeout", "refund_complete", "refund_fail"}
PAYMENT_PAGE_CODES = {"normal_waiting_pay", "waiting_choose_ump", "after_pay_waiting_pay"}
RESERVE_PAGE_CODES = {"appointment_waiting", "waiting_check", "self_clean", "place_clothes"}
ACTIONABLE_RESERVE_PAGE_CODES = {"waiting_check", "place_clothes"}
RUNNING_PAGE_CODES = {"complete", "paid", "normal_fulfilling", "after_pay_fulfilling"}
SELF_CLEAN_SKU_ID = 175
PICKUP_REMINDER_LEAD_SECONDS = 180

KNOWN_DEVICE_NAMES = (
    "04号洗衣机",
    "05号烘干机",
    "06号洗衣机",
    "07号洗衣机",
    "08号洗衣机",
    "09号洗衣机",
)
DEVICE_NAME_PATTERN = re.compile(r"\d{1,2}号(?:洗衣机|烘干机|洗鞋机)")

# 自适应脉冲调度（v4）：消除 T=0s 前脉冲耗尽盲区，并增加超时尾窗平滑探测
POLL_WATCH_SECONDS = 1.5
POLL_OVERTIME_SECONDS = 1.2
POLL_CRUISE_RANGE = (15.0, 30.0)
POLL_APPROACH_RANGE = (2.0, 3.0)
POLL_BURST_RANGE = (0.2, 0.3)
BURST_ATTEMPT_LIMIT = 60
BURST_COOLDOWN_RANGE = (3.5, 6.0)
RELEASE_CRUISE_SECONDS = 60.0
RELEASE_BURST_SECONDS = 10.0
RELEASE_OVERTIME_SECONDS = -12.0

POLL_PHASE_LABELS = {
    "watch": "轮询抢占",
    "cruise": "巡航等待",
    "approach": "逼近同步",
    "burst": "脉冲抢占",
    "cooldown": "熔断退避",
    "overtime": "尾窗守候",
}

CLOCK_OFFSET_MAX_ABS_SECONDS = 600.0


class ClockOffsetTracker:
    """利用上游 HTTP Date 响应头与半程 RTT 进行零开销服务端时钟偏移 EWMA 校准。"""

    def __init__(self, max_abs_offset: float = CLOCK_OFFSET_MAX_ABS_SECONDS):
        self._lock = threading.Lock()
        self._offset_seconds = 0.0
        self._samples = 0
        self._max_abs = max_abs_offset

    @property
    def offset_seconds(self) -> float:
        with self._lock:
            return self._offset_seconds

    @property
    def calibrated(self) -> bool:
        with self._lock:
            return self._samples > 0

    def observe_http_date(
        self,
        date_header: Optional[str],
        rtt_seconds: float = 0.0,
        local_now: Optional[float] = None,
    ) -> Optional[float]:
        if not date_header:
            return None
        try:
            parsed = parsedate_to_datetime(str(date_header).strip())
            if parsed is None:
                return None
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            server_ts = parsed.timestamp() + max(0.0, float(rtt_seconds)) * 0.5
        except Exception:
            return None

        ref_now = time.time() if local_now is None else float(local_now)
        sample = server_ts - ref_now
        if abs(sample) > self._max_abs:
            return None

        with self._lock:
            if self._samples == 0:
                self._offset_seconds = sample
            else:
                self._offset_seconds = self._offset_seconds * 0.7 + sample * 0.3
            self._samples += 1
            return self._offset_seconds

    def now(self, local_now: Optional[float] = None) -> float:
        base = time.time() if local_now is None else float(local_now)
        with self._lock:
            return base + self._offset_seconds


@dataclass(frozen=True)
class DeviceState:
    key: str
    label: str
    queue_count: Optional[int]
    can_order: bool
    order_kind: Optional[str]


@dataclass(frozen=True)
class PollPlan:
    phase: str
    delay: float
    attempt: bool


def plan_poll(
    remaining_seconds: Optional[float],
    burst_attempts: int = 0,
    rng: Callable[[float, float], float] = random.uniform,
) -> PollPlan:
    """按距设备释放的剩余时间返回本 tick 的轮询节奏与是否允许写请求。

    - remaining_seconds 为 None：无释放估计，退化为固定 watch 轮询。
    - remaining_seconds > 60s：cruise 巡航只读。
    - 10s < remaining_seconds <= 60s：approach 逼近尝试。
    - -12s <= remaining_seconds <= 10s：burst 高频脉冲（上限 60 次完整覆盖 T=0s 前后窗口）。
    - remaining_seconds < -12s：overtime 尾窗稳态探测（应对滚筒脱水延时解锁）。
    """
    if remaining_seconds is None:
        return PollPlan("watch", POLL_WATCH_SECONDS, True)
    if remaining_seconds > RELEASE_CRUISE_SECONDS:
        return PollPlan("cruise", rng(*POLL_CRUISE_RANGE), False)
    if remaining_seconds > RELEASE_BURST_SECONDS:
        return PollPlan("approach", rng(*POLL_APPROACH_RANGE), True)
    if remaining_seconds < RELEASE_OVERTIME_SECONDS:
        return PollPlan("overtime", POLL_OVERTIME_SECONDS, True)
    if burst_attempts >= BURST_ATTEMPT_LIMIT:
        return PollPlan("cooldown", rng(*BURST_COOLDOWN_RANGE), False)
    return PollPlan("burst", rng(*POLL_BURST_RANGE), True)


def parse_local_datetime(value: Any) -> Optional[datetime]:
    if not value:
        return None
    if isinstance(value, datetime):
        return value
    try:
        return datetime.fromisoformat(str(value).replace(" ", "T"))
    except ValueError:
        return None


def seconds_until(value: Any, now_ts: Optional[float] = None) -> Optional[float]:
    parsed = parse_local_datetime(value)
    if not parsed:
        return None
    ref_dt = datetime.fromtimestamp(now_ts) if now_ts is not None else datetime.now()
    return (parsed - ref_dt).total_seconds()


def normalize_device_display_name(raw_item: dict[str, Any], target_imeis: Optional[dict[str, str]] = None) -> str:
    imei = str(raw_item.get("imei") or "").strip()
    if target_imeis and imei in target_imeis:
        return target_imeis[imei]
    raw_name = str(raw_item.get("name") or "").strip()
    match = DEVICE_NAME_PATTERN.search(raw_name)
    if match:
        token = match.group(0)
        num_part, rest = token.split("号", 1)
        if len(num_part) == 1:
            return f"0{num_part}号{rest}"
        return token
    floor = str(raw_item.get("floorCode") or raw_item.get("floorName") or "").strip()
    if raw_name:
        return raw_name
    if floor:
        return f"{floor}层设备"
    return f"设备#{raw_item.get('id') or '未知'}"


def classify_device(raw: dict[str, Any], payment_pending: bool = False) -> DeviceState:
    if payment_pending:
        return DeviceState("payment_pending", "有人待支付", None, False, None)

    state = raw.get("state")
    enable_reserve = bool(raw.get("enableReserve"))
    reserve_state = raw.get("reserveState")

    if state == 1:
        return DeviceState("idle", "空闲", 0, True, "instant")
    if state == 2 and enable_reserve and reserve_state == 1:
        return DeviceState("busy_reservable", "使用中，可预约", 1, True, "reserve")
    if state == 2:
        return DeviceState("busy_full", "使用中，轮询抢占", 2, True, "poll")
    if state in {3, 4}:
        return DeviceState("fault", "故障或离线", None, False, None)
    return DeviceState("unknown", "状态未知", None, False, None)


def classify_order(order: dict[str, Any]) -> str:
    if order.get("trackingConflict"):
        return "tracking_conflict"

    state = order.get("state")
    page_code = order.get("pageCode")
    can_pay = bool(order.get("canPay"))
    can_cancel = bool(order.get("canCancel"))
    order_type = order.get("orderType")

    if state in TERMINAL_ORDER_STATES or page_code in TERMINAL_PAGE_CODES:
        return "ended"
    if state == 100 or page_code in PAYMENT_PAGE_CODES or can_pay:
        return "awaiting_payment"
    if state == 50 and can_cancel:
        return "reserve_waiting"
    if page_code in RESERVE_PAGE_CODES:
        return "reserve_waiting"
    if order_type == 300 and can_cancel:
        return "reserve_waiting"
    if state == 500 or page_code in RUNNING_PAGE_CODES:
        return "running"
    return "unknown"


def order_stage_label(stage: str, page_code: Optional[str] = None) -> str:
    if stage == "reserve_waiting":
        if page_code == "appointment_waiting":
            return "已预约候机"
        if page_code == "waiting_check":
            return "已到号·待验证"
        if page_code == "self_clean":
            return "筒自洁中"
        if page_code == "place_clothes":
            return "待放衣物"
    if stage == "awaiting_payment" and page_code == "waiting_choose_ump":
        return "待选券支付"
    return {
        "awaiting_payment": "待支付",
        "reserve_waiting": "预约/验证中",
        "running": "进行中",
        "tracking_conflict": "待确认",
        "ended": "已结束",
        "unknown": "未知",
    }.get(stage, "未知")


def order_ready_for_user_action(order: Optional[dict[str, Any]]) -> bool:
    """判断订单是否已抢到且需要用户立即前往小程序/App验证或支付。"""
    if not isinstance(order, dict):
        return False
    stage = classify_order(order)
    if stage == "awaiting_payment":
        return True
    if stage == "reserve_waiting":
        page_code = order.get("pageCode")
        return bool(order.get("canPay")) or page_code in ACTIONABLE_RESERVE_PAGE_CODES
    return False


def order_blocks_account(order: dict[str, Any]) -> bool:
    return classify_order(order) in {
        "awaiting_payment",
        "reserve_waiting",
        "tracking_conflict",
    }


def enrich_order(order: dict[str, Any]) -> dict[str, Any]:
    stage = classify_order(order)
    page_code = order.get("pageCode")
    return {
        **order,
        "stage": stage,
        "stageLabel": order_stage_label(stage, page_code),
        "blocksAccount": order_blocks_account(order),
        "readyForUserAction": order_ready_for_user_action(order),
    }


def mode_supports_self_clean(mode: Optional[dict[str, Any]]) -> bool:
    if not isinstance(mode, dict):
        return False
    attach_map = mode.get("attachMap")
    return isinstance(attach_map, dict) and bool(attach_map.get("SelfClean"))


def find_self_clean_item_id(
    modes: list[dict[str, Any]],
    attach_value_map: Optional[dict[str, Any]] = None,
) -> Optional[int]:
    if isinstance(attach_value_map, dict):
        sc = attach_value_map.get("SelfClean")
        if isinstance(sc, dict) and sc.get("isOn") and sc.get("selfCleanItemId"):
            try:
                return int(sc["selfCleanItemId"])
            except (TypeError, ValueError):
                pass
    for item in modes or []:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "")
        if item.get("skuId") == SELF_CLEAN_SKU_ID or "自洁" in name:
            item_id = item.get("id")
            if item_id is not None:
                try:
                    return int(item_id)
                except (TypeError, ValueError):
                    pass
    return None


def match_mode_by_preference(
    device_name: str,
    modes: list[dict[str, Any]],
    preferred_name: Optional[str] = None,
) -> Optional[dict[str, Any]]:
    """跨设备按语义模式名匹配套餐（不同洗衣机 modeId 不同但名称一致）。"""
    valid = [m for m in (modes or []) if isinstance(m, dict) and m.get("id") is not None]
    if not valid:
        return None
    pref = str(preferred_name or "").strip()
    if pref:
        for item in valid:
            if str(item.get("name") or "").strip() == pref:
                return item
        for item in valid:
            name = str(item.get("name") or "")
            if pref in name or name in pref:
                return item
    if "烘干" in str(device_name or ""):
        priorities = ("高温", "中温", "低温", "烘干")
    else:
        priorities = ("标准", "常规", "大物", "快速", "单脱")
    for key in priorities:
        for item in valid:
            if key in str(item.get("name") or ""):
                return item
    return valid[0]


def infer_device_name_from_order(order: dict[str, Any]) -> str:
    text = str(order.get("goodsName") or order.get("projectName") or "")
    for name in KNOWN_DEVICE_NAMES:
        if name in text:
            return name
    match = DEVICE_NAME_PATTERN.search(text)
    if match:
        token = match.group(0)
        num_part, rest = token.split("号", 1)
        return f"0{num_part}号{rest}" if len(num_part) == 1 else token
    return ""


def is_auth_failure(code: Any, message: str = "") -> bool:
    text = str(message or "")
    return code in {2, 401, 100002, 100003} or any(
        key in text for key in ("登录已失效", "未登录", "其它设备登录", "其他设备登录")
    )


ACCOUNT_CONFLICT_KEYS = (
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
    "您的账号",
)


def is_device_payment_conflict(message: str) -> bool:
    text = str(message or "")
    if any(key in text for key in ACCOUNT_CONFLICT_KEYS):
        return False
    return (
        ("其他用户" in text and ("等待支付" in text or "待支付" in text or "付款" in text))
        or "正在付款" in text
        or ("设备" in text and "待支付" in text)
        or text.strip() in {"有人待支付", "待支付"}
    )


def is_account_order_conflict(message: str) -> bool:
    text = str(message or "")
    if is_device_payment_conflict(text):
        return False
    return any(key in text for key in ACCOUNT_CONFLICT_KEYS)


def normalize_ota_apk_url(raw_url: Any, native: bool = True) -> str:
    url = str(raw_url or "").strip()
    if not url or "4c666.top" in url or url.endswith("/haile-bot-v3.apk"):
        return OTA_DEFAULT_APK_URL if native else "/haile-bot-v4.apk"
    return url


def build_wechat_mini_scheme(device_code: str = "", for_payment: bool = True) -> str:
    """构造海乐生活微信小程序明文白名单 URL Scheme。

    注意：海乐官方仅对 pages/tabbar/home 开放明文 Scheme 白名单（不可使用不存在的
    pages/scan/scanResult，且 path 内严禁携带 ? 参数）。已锁单去支付时直接进入首页
    （再点底部「订单」或顶部预约卡片），不传 N= 设备码以免重复触发扫码下单提示设备忙碌。
    """
    base = f"weixin://dl/business/?appid={WECHAT_MINI_APP_ID}&path=pages/tabbar/home"
    code = str(device_code or "").strip()
    if not for_payment and code:
        return f"{base}&query={quote(f'N={code}', safe='')}"
    return base


def build_alipay_cashier_scheme(order_no: str = "") -> str:
    """构造海乐生活支付宝小程序（2021003183687122）收银台直达 Scheme。"""
    clean_no = str(order_no or "").strip()
    target_page = f"pages/pay/cashier?orderNo={clean_no}" if clean_no else "pages/tabbar/home"
    return f"alipays://platformapi/startapp?appId={ALIPAY_MINI_APP_ID}&page={quote(target_page, safe='')}"


_GCJ_A = 6378245.0
_GCJ_EE = 0.00669342162296594323


def _out_of_china(lng: float, lat: float) -> bool:
    return not (72.004 <= lng <= 137.8347 and 0.8293 <= lat <= 55.8271)


def _transform_lat(x: float, y: float) -> float:
    ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (160.0 * math.sin(y / 12.0 * math.pi) + 320.0 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return ret


def _transform_lng(x: float, y: float) -> float:
    ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return ret


def wgs84_to_gcj02(lng: float, lat: float) -> tuple[float, float]:
    """将标准 GPS / OpenStreetMap (WGS-84) 经纬度转换为海乐生活与高德地图使用的 GCJ-02 坐标。"""
    lng_f = float(lng)
    lat_f = float(lat)
    if _out_of_china(lng_f, lat_f):
        return round(lng_f, 6), round(lat_f, 6)
    dlat = _transform_lat(lng_f - 105.0, lat_f - 35.0)
    dlng = _transform_lng(lng_f - 105.0, lat_f - 35.0)
    radlat = lat_f / 180.0 * math.pi
    magic = math.sin(radlat)
    magic = 1.0 - _GCJ_EE * magic * magic
    sqrtmagic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((_GCJ_A * (1.0 - _GCJ_EE)) / (magic * sqrtmagic) * math.pi)
    dlng = (dlng * 180.0) / (_GCJ_A / sqrtmagic * math.cos(radlat) * math.pi)
    return round(lng_f + dlng, 6), round(lat_f + dlat, 6)


def search_campus_presets(query: str, limit: int = 8) -> list[dict[str, Any]]:
    q = str(query or "").strip().lower()
    if not q:
        return [
            {"name": p["name"], "subtitle": p["subtitle"], "lng": p["lng"], "lat": p["lat"]}
            for p in CAMPUS_PRESETS[:limit]
        ]
    scored: list[tuple[int, dict[str, Any]]] = []
    q_tokens = [t for t in re.split(r"[\s·,，/-]+", q) if t]
    for item in CAMPUS_PRESETS:
        hay = f"{item['name']} {item['subtitle']} {item.get('keywords', '')}".lower()
        hay_compact = re.sub(r"[\s·,，/-]+", "", hay)
        item_words = [w for w in re.split(r"[\s·,，/-]+", hay) if len(w) >= 2]
        name_l = item["name"].lower()
        score = 0
        if q in name_l:
            score += 120
        elif q in hay or q in hay_compact:
            score += 80
        for tok in q_tokens:
            if len(tok) >= 2 and (tok in hay or tok in hay_compact):
                score += 30
        for word in item_words:
            if word in q:
                score += 35
        if score > 0:
            scored.append((score, item))
    scored.sort(key=lambda pair: -pair[0])
    return [
        {"name": p["name"], "subtitle": p["subtitle"], "lng": p["lng"], "lat": p["lat"]}
        for _, p in scored[:limit]
    ]


_COORD_PAIR_RE = re.compile(r"^\s*(-?\d{2,3}\.\d+)\s*[,，\s]\s*(-?\d{1,2}\.\d+)\s*$")
_IMEI_15_RE = re.compile(r"^\d{14,17}$")
_POS_ID_RE = re.compile(r"^#?(\d{1,10})$")
_DEV_CODE_RE = re.compile(r"^(G[A-Z]{1,3}\d{6,16})$", re.IGNORECASE)


def parse_custom_position_input(raw_text: str) -> dict[str, Any]:
    """解析用户输入的点位 ID、经纬度、机身二维码链接、15 位 IMEI 或 GTB/GYB 设备码。"""
    text = str(raw_text or "").strip()
    if not text:
        return {"kind": "empty"}

    coord_m = _COORD_PAIR_RE.match(text)
    if coord_m:
        a = float(coord_m.group(1))
        b = float(coord_m.group(2))
        if abs(a) <= 90 and abs(b) > 90:
            return {"kind": "coords", "lng": b, "lat": a}
        return {"kind": "coords", "lng": a, "lat": b}

    if "http://" in text or "https://" in text or "?" in text or "/barCode/" in text:
        unq = unquote(text)
        if "/barCode/" in unq:
            bar = unq.split("/barCode/", 1)[1].split("?", 1)[0].split("/", 1)[0].strip()
            if bar:
                return {"kind": "scan", "n": bar}
        parsed = urlparse(unq if "://" in unq else f"https://dummy.local/?{unq.lstrip('?')}")
        qs = parse_qs(parsed.query)
        flat = {k.lower(): (v[0].strip() if v else "") for k, v in qs.items()}
        for pk in ("positionid", "shopid"):
            if flat.get(pk) and flat[pk].isdigit():
                return {"kind": "position_id", "positionId": int(flat[pk])}
        imei = flat.get("imei") or ""
        n_code = flat.get("n") or flat.get("devid") or flat.get("deviceld") or flat.get("deviceid") or ""
        if imei or n_code:
            return {"kind": "scan", "imei": imei, "n": n_code}

    if _IMEI_15_RE.match(text):
        return {"kind": "scan", "imei": text, "n": ""}

    dev_m = _DEV_CODE_RE.match(text)
    if dev_m:
        return {"kind": "scan", "imei": "", "n": dev_m.group(1).upper()}

    pos_m = _POS_ID_RE.match(text)
    if pos_m:
        return {"kind": "position_id", "positionId": int(pos_m.group(1))}

    return {"kind": "keyword", "query": text}


