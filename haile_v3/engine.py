from __future__ import annotations

from collections import deque
from datetime import datetime
import json
import os
import queue
import random
import threading
import time
from typing import Any, Optional
import zlib

import haile_bot
from . import APP_VERSION_CODE, __version__
from .api import HaileApi, account_snapshot, summarize_detail, summarize_list_item
from .domain import (
    DEFAULT_POSITION_ID,
    DEFAULT_POSITION_NAME,
    PICKUP_REMINDER_LEAD_SECONDS,
    POLL_PHASE_LABELS,
    classify_device,
    enrich_order,
    find_self_clean_item_id,
    infer_device_name_from_order,
    is_account_order_conflict,
    is_auth_failure,
    is_device_payment_conflict,
    match_mode_by_preference,
    mode_supports_self_clean,
    normalize_device_display_name,
    order_blocks_account,
    order_ready_for_user_action,
    parse_custom_position_input,
    plan_poll,
    seconds_until,
)

INTENTS_FILE = os.path.join("tmp", "v3_intents.json")
HOLDS_FILE = os.path.join("tmp", "v3_holds.json")
POSITION_FILE = os.path.join("tmp", "v4_position.json")

PAYMENT_EXPIRE_FALLBACK_SECONDS = 300
RENEW_BEFORE_SECONDS = 30
POLL_ACTIVE_SECONDS = 1.5
POLL_HELD_SECONDS = 3.0
POLL_RESERVE_SECONDS = 4.0
DEVICE_HOLD_SECONDS = 12.0
ORDER_RETENTION_SECONDS = 7200
RAW_DEVICES_CACHE_SECONDS = 2.0
RUN_INFO_CACHE_SECONDS = 15.0
RUN_INFO_MIN_INTERVAL = 2.0
DEVICE_MONITOR_SECONDS = 4.0
DEVICE_TYPE_LABELS = {
    "washer": "洗衣机",
    "dryer": "烘干机",
}
ORDER_TIMING_KEYS = ("deadline", "expireSeconds", "createdAtLocal")
ACTIVE_ORDER_STAGES = {"awaiting_payment", "reserve_waiting"}
ACCOUNT_MAX_ACTIVE_SLOTS = 2


def safe_server_now(api: Any) -> Optional[float]:
    clock_fn = getattr(api, "server_now", None)
    if callable(clock_fn):
        try:
            val = clock_fn()
            if isinstance(val, (int, float)) and val > 1_000_000_000:
                return float(val)
        except Exception:
            pass
    return None


def safe_clock_offset(api: Any) -> float:
    clock = getattr(api, "clock", None)
    offset = getattr(clock, "offset_seconds", 0.0)
    return float(offset) if isinstance(offset, (int, float)) else 0.0


def pool_key_for_owner(owner_id: str) -> int:
    norm = normalize_owner_id(owner_id)
    if norm == "default":
        return -1
    return -int((zlib.crc32(norm.encode("utf-8")) % 900_000) + 10)


class Runtime:
    def __init__(self, api: Optional[HaileApi] = None):
        self.api = api or HaileApi()
        self.accounts = haile_bot.AccountManager()
        self.lock = threading.RLock()
        self.logs: deque[str] = deque(maxlen=400)
        self.orders_by_no: dict[str, dict[str, Any]] = {}
        self.device_payment_holds: dict[str, float] = {}
        self.intents: dict[int, PreemptIntent] = {}
        self.mode_cache: dict[int, tuple[float, list[dict[str, Any]]]] = {}
        self.last_order_refresh_at = 0.0
        self._poller_started = False
        self._poller_stop = threading.Event()
        self._subscribers: list[queue.Queue] = []
        self._device_sigs: dict[int, tuple] = {}
        self._raw_devices: list[dict[str, Any]] = []
        self._raw_devices_at = 0.0
        self._run_info_cache: dict[int, tuple[float, str]] = {}
        self._monitor_started = False
        self.position_id: int = DEFAULT_POSITION_ID
        self.position_name: str = DEFAULT_POSITION_NAME
        self.position_lng: float = 120.4250
        self.position_lat: float = 36.0716
        self.position_customized: bool = False
        self.recent_positions: list[dict[str, Any]] = []
        self._position_detail_cache: dict[int, tuple[float, dict[str, Any]]] = {}
        self._goods_detail_cache: dict[int, tuple[float, dict[str, Any]]] = {}
        self._recent_usage_cache: dict[str, tuple[float, dict[str, Any]]] = {}
        self._running_order_nos: set[str] = set()
        self._pickup_soon_sent: set[str] = set()
        self._pickup_done_sent: set[str] = set()

    def now_ts(self) -> Optional[float]:
        return safe_server_now(self.api)

    def _record_recent_position(
        self,
        pid: int,
        pname: str,
        address: str = "",
        lng: Optional[float] = None,
        lat: Optional[float] = None,
    ) -> None:
        entry: dict[str, Any] = {
            "positionId": int(pid),
            "name": str(pname or f"点位 {pid}").strip(),
        }
        if address:
            entry["address"] = str(address).strip()
        if lng is not None and lat is not None:
            entry["lng"] = round(float(lng), 6)
            entry["lat"] = round(float(lat), 6)
        deduped = [entry] + [
            item for item in self.recent_positions
            if isinstance(item, dict) and int(item.get("positionId") or 0) != int(pid)
        ]
        self.recent_positions = deduped[:8]

    def cached_position_detail(self, position_id: int, max_age: float = 600.0) -> Optional[dict[str, Any]]:
        pid = int(position_id or 0)
        if pid <= 0:
            return None
        now = time.time()
        cached = self._position_detail_cache.get(pid)
        if cached and now - cached[0] < max_age:
            return dict(cached[1])
        fn = getattr(self.api, "position_detail", None)
        if not callable(fn):
            return None
        try:
            detail = fn(pid, lng=self.position_lng, lat=self.position_lat)
        except Exception:
            detail = None
        if isinstance(detail, dict) and detail.get("positionId"):
            self._position_detail_cache[pid] = (now, dict(detail))
            return dict(detail)
        return None

    def cached_goods_detail(self, goods_id: int, max_age: float = 20.0) -> dict[str, Any]:
        gid = int(goods_id or 0)
        if gid <= 0:
            return {}
        now = time.time()
        cached = self._goods_detail_cache.get(gid)
        if cached and now - cached[0] < max_age:
            return dict(cached[1])
        fn = getattr(self.api, "goods_detail", None)
        if not callable(fn):
            return {}
        try:
            detail = fn(gid)
        except Exception:
            detail = {}
        if isinstance(detail, dict) and detail:
            self._goods_detail_cache[gid] = (now, dict(detail))
            return dict(detail)
        return {}

    def load_position(self) -> None:
        if not os.path.exists(POSITION_FILE):
            return
        try:
            with open(POSITION_FILE, "r", encoding="utf-8") as handle:
                data = json.load(handle)
            if isinstance(data, dict) and data.get("positionId"):
                self.position_id = int(data["positionId"])
                self.position_name = str(data.get("positionName") or DEFAULT_POSITION_NAME).strip() or DEFAULT_POSITION_NAME
                self.position_customized = bool(data.get("customized", True))
                if data.get("lng") is not None and data.get("lat") is not None:
                    self.position_lng = float(data["lng"])
                    self.position_lat = float(data["lat"])
                raw_recent = data.get("recentPositions")
                if isinstance(raw_recent, list):
                    self.recent_positions = [it for it in raw_recent if isinstance(it, dict) and it.get("positionId")][:8]
        except Exception:
            pass

    def set_position(
        self,
        position_id: int,
        position_name: str = "",
        lng: Optional[float] = None,
        lat: Optional[float] = None,
        address: str = "",
        mark_customized: bool = True,
    ) -> dict[str, Any]:
        pid = int(position_id) if position_id else DEFAULT_POSITION_ID
        pname = str(position_name or "").strip()
        resolved_addr = str(address or "").strip()
        resolved_lng = lng
        resolved_lat = lat
        if not pname or pname == f"点位 {pid}" or resolved_lng is None or resolved_lat is None:
            detail = self.cached_position_detail(pid)
            if detail:
                if not pname or pname == f"点位 {pid}":
                    pname = str(detail.get("name") or "").strip()
                if not resolved_addr:
                    resolved_addr = str(detail.get("address") or "").strip()
                if resolved_lng is None and detail.get("lng") is not None:
                    resolved_lng = float(detail["lng"])
                if resolved_lat is None and detail.get("lat") is not None:
                    resolved_lat = float(detail["lat"])
        if not pname:
            pname = DEFAULT_POSITION_NAME if pid == DEFAULT_POSITION_ID else f"点位 {pid}"

        with self.lock:
            changed = pid != self.position_id
            if changed:
                running_intents = [
                    it for it in self.intents.values()
                    if it.is_alive() and not it.stop_event.is_set()
                ]
                if running_intents:
                    return {"success": False, "message": "请先停止当前楼栋的抢占任务再切换点位"}
            self.position_id = pid
            self.position_name = pname
            if resolved_lng is not None and resolved_lat is not None:
                self.position_lng = float(resolved_lng)
                self.position_lat = float(resolved_lat)
            if mark_customized:
                self.position_customized = True
            self._record_recent_position(pid, pname, address=resolved_addr, lng=resolved_lng, lat=resolved_lat)
            if changed:
                self._raw_devices = []
                self._raw_devices_at = 0.0
                self._device_sigs.clear()
        try:
            os.makedirs(os.path.dirname(POSITION_FILE), exist_ok=True)
            tmp_path = f"{POSITION_FILE}.tmp"
            with open(tmp_path, "w", encoding="utf-8") as handle:
                json.dump(
                    {
                        "positionId": pid,
                        "positionName": pname,
                        "lng": self.position_lng,
                        "lat": self.position_lat,
                        "customized": self.position_customized,
                        "recentPositions": self.recent_positions,
                    },
                    handle,
                    ensure_ascii=False,
                    indent=2,
                )
            os.replace(tmp_path, POSITION_FILE)
        except Exception:
            pass
        self.log(f"当前洗衣房已切换为: {pname} ({pid})")
        self.emit({
            "type": "position",
            "positionId": pid,
            "positionName": pname,
            "lng": self.position_lng,
            "lat": self.position_lat,
        })
        return {
            "success": True,
            "positionId": pid,
            "positionName": pname,
            "lng": self.position_lng,
            "lat": self.position_lat,
        }

    def nearby_positions(
        self,
        lng: Optional[float] = None,
        lat: Optional[float] = None,
        coord_type: str = "gcj02",
        keyword: str = "",
    ) -> list[dict[str, Any]]:
        has_explicit = lng is not None and lat is not None
        q_lng = lng if has_explicit else (self.position_lng if self.position_customized else None)
        q_lat = lat if has_explicit else (self.position_lat if self.position_customized else None)
        try:
            items = self.api.nearby_positions(
                lng=q_lng,
                lat=q_lat,
                coord_type=coord_type if has_explicit else "gcj02",
                keyword=keyword,
            )
        except TypeError:
            items = self.api.nearby_positions(lng=q_lng, lat=q_lat)
        if not has_explicit and not keyword:
            if not any(it.get("positionId") == self.position_id for it in items):
                items.insert(0, {
                    "positionId": self.position_id,
                    "name": self.position_name,
                    "address": "",
                    "state": 1,
                    "lng": self.position_lng,
                    "lat": self.position_lat,
                })
        for item in items[:5]:
            if item.get("lng") is None or item.get("lat") is None:
                det = self.cached_position_detail(int(item["positionId"]))
                if det and det.get("lng") is not None and det.get("lat") is not None:
                    item["lng"] = det["lng"]
                    item["lat"] = det["lat"]
        return items

    def search_positions(
        self,
        query: str,
        owner_id: str = "default",
        lng: Optional[float] = None,
        lat: Optional[float] = None,
    ) -> dict[str, Any]:
        q = str(query or "").strip()
        if not q:
            return {
                "positions": self.nearby_positions(lng=lng, lat=lat),
                "places": getattr(self.api, "geocode_places", lambda _q: [])(""),
            }

        parsed = parse_custom_position_input(q)
        by_id: dict[int, dict[str, Any]] = {}
        places: list[dict[str, Any]] = []

        if parsed["kind"] == "position_id":
            det = self.cached_position_detail(int(parsed["positionId"]))
            if det:
                by_id[det["positionId"]] = det

        if parsed["kind"] == "coords":
            c_lng, c_lat = float(parsed["lng"]), float(parsed["lat"])
            places.append({"name": f"坐标 ({c_lng:.4f}, {c_lat:.4f})", "subtitle": "经纬度直达", "lng": c_lng, "lat": c_lat})
            for item in self.nearby_positions(lng=c_lng, lat=c_lat):
                by_id.setdefault(int(item["positionId"]), item)
            return {"positions": list(by_id.values()), "places": places}

        enabled = self.enabled_accounts(owner_id)
        token = enabled[0].get("token") if enabled else None
        ref_lng = float(lng) if lng is not None else self.position_lng
        ref_lat = float(lat) if lat is not None else self.position_lat

        if token and hasattr(self.api, "position_choose"):
            try:
                shops = self.api.position_choose(token, name=q, lng=ref_lng, lat=ref_lat, page_size=12)
                for shop in shops[:4]:
                    sid = shop.get("id") or shop.get("shopId")
                    if sid and hasattr(self.api, "position_list_by_shop"):
                        for pos in self.api.position_list_by_shop(token, sid):
                            by_id.setdefault(int(pos["positionId"]), pos)
            except Exception:
                pass

        geocode_fn = getattr(self.api, "geocode_places", None)
        if callable(geocode_fn):
            try:
                places = geocode_fn(q, limit=6)
            except Exception:
                places = []

        if not by_id and places:
            top_place = places[0]
            for item in self.nearby_positions(lng=top_place["lng"], lat=top_place["lat"]):
                by_id.setdefault(int(item["positionId"]), item)
        elif lng is not None and lat is not None:
            for item in self.nearby_positions(lng=lng, lat=lat, keyword=q):
                by_id.setdefault(int(item["positionId"]), item)

        return {
            "positions": list(by_id.values())[:40],
            "places": places,
        }

    def recent_usage(self, owner_id: str = "default", force: bool = False) -> dict[str, Any]:
        owner_id = normalize_owner_id(owner_id)
        now = time.time()
        cached = self._recent_usage_cache.get(owner_id)
        if not force and cached and now - cached[0] < 12.0:
            return cached[1]

        pos_by_id: dict[int, dict[str, Any]] = {}
        for item in self.recent_positions:
            pid = int(item.get("positionId") or 0)
            if pid > 0:
                pos_by_id[pid] = {**item, "source": "local"}

        enabled = self.enabled_accounts(owner_id)
        raw_dev_candidates: list[dict[str, Any]] = []
        seen_gids: set[int] = set()

        for acc in enabled[:2]:
            token = acc.get("token") or ""
            if not token:
                continue
            if hasattr(self.api, "use_position_list"):
                try:
                    for p in self.api.use_position_list(token, lng=self.position_lng, lat=self.position_lat):
                        pid = int(p.get("positionId") or 0)
                        if pid > 0:
                            pos_by_id.setdefault(pid, {**p, "source": "account"})
                except Exception:
                    pass
            if hasattr(self.api, "goods_use_list"):
                try:
                    for dev in self.api.goods_use_list(token, page_size=12):
                        gid = int(dev.get("id") or dev.get("goodsId") or 0)
                        if gid > 0 and gid not in seen_gids:
                            seen_gids.add(gid)
                            raw_dev_candidates.append(dev)
                except Exception:
                    pass
            if len(raw_dev_candidates) < 6 and hasattr(self.api, "trade_list"):
                try:
                    t_res = self.api.trade_list(token, page_size=12)
                    if t_res.get("code") == 0:
                        raw_data = t_res.get("data")
                        t_items = raw_data.get("items") if isinstance(raw_data, dict) else raw_data
                        for order in (t_items or []):
                            if not isinstance(order, dict):
                                continue
                            first = (order.get("orderItemList") or [{}])[0]
                            gid = int(first.get("goodsId") or 0)
                            if gid > 0 and gid not in seen_gids:
                                seen_gids.add(gid)
                                raw_dev_candidates.append({
                                    "id": gid,
                                    "name": first.get("goodsName") or order.get("projectName") or "",
                                    "categoryCode": first.get("categoryCode") or "",
                                })
                                if len(raw_dev_candidates) >= 8:
                                    break
                except Exception:
                    pass

        ref_now = self.now_ts()
        recent_devices: list[dict[str, Any]] = []
        for raw_dev in raw_dev_candidates[:6]:
            gid = int(raw_dev.get("id") or raw_dev.get("goodsId") or 0)
            if gid <= 0:
                continue
            detail = self.cached_goods_detail(gid)
            pid = int(detail.get("positionId") or raw_dev.get("positionId") or 0)
            pos_det = self.cached_position_detail(pid) if pid > 0 else None
            pname = (
                (pos_det or {}).get("name")
                or detail.get("positionName")
                or detail.get("shopName")
                or raw_dev.get("address")
                or (self.position_name if pid == self.position_id else (f"点位 {pid}" if pid > 0 else ""))
            )
            pname = str(pname or "").strip()
            if pid > 0 and pid not in pos_by_id:
                pos_by_id[pid] = {
                    "positionId": pid,
                    "name": pname or f"点位 {pid}",
                    "address": str((pos_det or {}).get("address") or detail.get("shopAddress") or raw_dev.get("address") or "").strip(),
                    "lng": (pos_det or {}).get("lng"),
                    "lat": (pos_det or {}).get("lat"),
                    "source": "history",
                }

            merged_raw = {
                **raw_dev,
                "state": raw_dev.get("state") if raw_dev.get("state") is not None else detail.get("deviceState", 1),
                "enableReserve": raw_dev.get("enableReserve") if raw_dev.get("enableReserve") is not None else detail.get("enableReserve", False),
                "reserveState": raw_dev.get("reserveState") if raw_dev.get("reserveState") is not None else detail.get("reserveState", 0),
                "soldState": detail.get("soldState", 1),
                "deviceErrorCode": detail.get("deviceErrorCode"),
                "deviceErrorMsg": detail.get("deviceErrorMsg"),
                "shopClosed": detail.get("shopClosed", False),
            }
            dev_name = normalize_device_display_name(
                {"name": raw_dev.get("name") or detail.get("name"), "imei": raw_dev.get("imei") or detail.get("imei")},
                haile_bot.TARGET_IMEIS if pid == DEFAULT_POSITION_ID else None,
            ) or str(detail.get("name") or raw_dev.get("name") or f"设备 {gid}")
            st = classify_device(merged_raw, payment_pending=False)
            finish_time = str(raw_dev.get("finishTime") or "").strip()
            if not finish_time and st.key in {"busy_Window", "busy", "reservable", "queue_1", "queue_2"} and hasattr(self.api, "run_info"):
                finish_time = self.cached_finish_time(gid)
            dtype = device_type_from_name(dev_name)
            if dtype == "washer" and str(detail.get("categoryCode") or raw_dev.get("categoryCode") or "") in {"02", "03", "13"}:
                dtype = "dryer"
            recent_devices.append({
                "goodsId": gid,
                "name": dev_name,
                "imei": str(raw_dev.get("imei") or detail.get("imei") or ""),
                "deviceType": dtype,
                "deviceTypeLabel": DEVICE_TYPE_LABELS.get(dtype, "设备"),
                "positionId": pid,
                "positionName": pname,
                "state": st.key,
                "stateLabel": st.label,
                "canOrder": st.can_order,
                "orderKind": st.order_kind,
                "finishTime": finish_time,
                "remainingSeconds": seconds_until(finish_time, now_ts=ref_now) if finish_time else None,
            })

        payload = {
            "positions": list(pos_by_id.values())[:10],
            "devices": recent_devices,
            "loggedIn": bool(enabled),
        }
        self._recent_usage_cache[owner_id] = (now, payload)
        return payload

    def resolve_custom_position(
        self,
        raw_input: str,
        owner_id: str = "default",
        auto_switch: bool = True,
    ) -> dict[str, Any]:
        parsed = parse_custom_position_input(raw_input)
        kind = parsed.get("kind")
        if kind == "empty":
            return {"success": False, "message": "请输入点位 ID、机身二维码链接或学校名称"}

        if kind == "position_id":
            pid = int(parsed["positionId"])
            det = self.cached_position_detail(pid)
            try:
                devs = self.api.devices(pid)
            except Exception as exc:
                if not det:
                    return {"success": False, "message": f"未找到点位 #{pid}（{exc}）"}
                devs = []
            if not det and not devs:
                return {"success": False, "message": f"点位 #{pid} 下未查询到可用洗烘设备"}
            pname = str((det or {}).get("name") or f"点位 {pid}").strip()
            lng = (det or {}).get("lng")
            lat = (det or {}).get("lat")
            addr = str((det or {}).get("address") or "").strip()
            if auto_switch:
                sw = self.set_position(pid, pname, lng=lng, lat=lat, address=addr, mark_customized=True)
                if not sw.get("success"):
                    return sw
            return {
                "success": True,
                "positionId": pid,
                "positionName": pname,
                "address": addr,
                "deviceCount": len(devs),
                "lng": lng,
                "lat": lat,
            }

        if kind == "coords":
            near = self.nearby_positions(lng=parsed["lng"], lat=parsed["lat"])
            if not near:
                return {"success": False, "message": "该坐标附近未找到海乐洗衣房点位"}
            top = near[0]
            if auto_switch:
                sw = self.set_position(
                    int(top["positionId"]),
                    str(top.get("name") or ""),
                    lng=top.get("lng"),
                    lat=top.get("lat"),
                    address=str(top.get("address") or ""),
                    mark_customized=True,
                )
                if not sw.get("success"):
                    return sw
            return {
                "success": True,
                "positionId": int(top["positionId"]),
                "positionName": top.get("name"),
                "candidates": near[:15],
            }

        if kind == "scan":
            imei = str(parsed.get("imei") or "").strip()
            n_code = str(parsed.get("n") or "").strip()
            for item in self._fetch_raw_devices(max_age=30):
                item_imei = str(item.get("imei") or "").strip()
                qr_info = haile_bot.get_device_qr_info(item_imei)
                if (imei and item_imei == imei) or (n_code and str(qr_info.get("device_code") or "").upper() == n_code.upper()):
                    gid = int(item.get("id") or 0)
                    dname = self._device_name_for_raw(item) or str(item.get("name") or "")
                    return {
                        "success": True,
                        "positionId": self.position_id,
                        "positionName": self.position_name,
                        "focusGoodsId": gid,
                        "focusDeviceName": dname,
                    }

            enabled = self.enabled_accounts(owner_id)
            token = enabled[0].get("token") if enabled else None
            if not token:
                return {"success": False, "message": "解析设备二维码或机身码需先登录海乐账号，或直接输入数字点位 ID"}
            scan_res = self.api.scan_goods(token, imei=imei, n_code=n_code)
            if scan_res.get("code") != 0 or not isinstance(scan_res.get("data"), dict) or not scan_res["data"].get("goodsId"):
                return {"success": False, "message": scan_res.get("message") or "未能识别该设备二维码或机身编码"}
            gid = int(scan_res["data"]["goodsId"])
            g_det = self.cached_goods_detail(gid)
            pid = int(g_det.get("positionId") or 0)
            if pid <= 0:
                return {"success": False, "message": "已识别设备，但未获取到所属楼栋点位 ID"}
            p_det = self.cached_position_detail(pid)
            pname = str((p_det or {}).get("name") or g_det.get("positionName") or g_det.get("shopName") or f"点位 {pid}").strip()
            if auto_switch:
                sw = self.set_position(
                    pid,
                    pname,
                    lng=(p_det or {}).get("lng"),
                    lat=(p_det or {}).get("lat"),
                    address=str((p_det or {}).get("address") or g_det.get("shopAddress") or ""),
                    mark_customized=True,
                )
                if not sw.get("success"):
                    return sw
            return {
                "success": True,
                "positionId": pid,
                "positionName": pname,
                "focusGoodsId": gid,
                "focusDeviceName": str(g_det.get("name") or ""),
            }

        searched = self.search_positions(str(parsed.get("query") or ""), owner_id=owner_id)
        positions = searched.get("positions") or []
        if not positions:
            return {"success": False, "message": "未找到匹配的洗衣房楼栋，请尝试在地图上定位或输入数字点位 ID"}
        top = positions[0]
        if auto_switch:
            sw = self.set_position(
                int(top["positionId"]),
                str(top.get("name") or ""),
                lng=top.get("lng"),
                lat=top.get("lat"),
                address=str(top.get("address") or ""),
                mark_customized=True,
            )
            if not sw.get("success"):
                return sw
        return {
            "success": True,
            "positionId": int(top["positionId"]),
            "positionName": top.get("name"),
            "candidates": positions[:15],
        }

    def _maybe_auto_discover_position(self, token: Optional[str], owner_id: str = "default") -> Optional[dict[str, Any]]:
        if not token or self.position_customized:
            return None
        with self.lock:
            if any(it.is_alive() and not it.stop_event.is_set() for it in self.intents.values()):
                return None
        self._recent_usage_cache.pop(normalize_owner_id(owner_id), None)
        usage = self.recent_usage(owner_id, force=True)
        for dev in usage.get("devices") or []:
            pid = int(dev.get("positionId") or 0)
            pname = str(dev.get("positionName") or "").strip()
            if pid > 0:
                sw = self.set_position(pid, pname, mark_customized=True)
                if sw.get("success"):
                    return {"positionId": sw["positionId"], "positionName": sw["positionName"]}
                break
        for pos in usage.get("positions") or []:
            if pos.get("source") == "local":
                continue
            pid = int(pos.get("positionId") or 0)
            pname = str(pos.get("name") or "").strip()
            if pid > 0:
                sw = self.set_position(pid, pname, lng=pos.get("lng"), lat=pos.get("lat"), mark_customized=True)
                if sw.get("success"):
                    return {"positionId": sw["positionId"], "positionName": sw["positionName"]}
                break
        return None

    def _call_api_devices(self) -> list[dict[str, Any]]:
        if self.position_id == DEFAULT_POSITION_ID:
            return self.api.devices()
        return self.api.devices(self.position_id)

    def _device_name_for_raw(self, raw_item: dict[str, Any]) -> str:
        if self.position_id == DEFAULT_POSITION_ID:
            mapped = haile_bot.TARGET_IMEIS.get(str(raw_item.get("imei") or ""))
            if mapped:
                return mapped
            if not raw_item.get("name"):
                return ""
        return normalize_device_display_name(raw_item, haile_bot.TARGET_IMEIS if self.position_id == DEFAULT_POSITION_ID else None)

    def subscribe(self) -> queue.Queue:
        subscriber: queue.Queue = queue.Queue(maxsize=200)
        with self.lock:
            self._subscribers.append(subscriber)
        return subscriber

    def unsubscribe(self, subscriber: queue.Queue) -> None:
        with self.lock:
            if subscriber in self._subscribers:
                self._subscribers.remove(subscriber)

    def has_subscribers(self) -> bool:
        with self.lock:
            return bool(self._subscribers)

    def emit(self, event: dict[str, Any]) -> None:
        payload = {"ts": time.time(), **event}
        with self.lock:
            subscribers = list(self._subscribers)
        for subscriber in subscribers:
            try:
                subscriber.put_nowait(payload)
            except queue.Full:
                pass

    def migrate_intents_for_login(self, account: dict[str, Any]) -> int:
        """手机号重新登录后，把该号持有的活跃意图切到新 token 与新 owner。"""
        phone = account.get("phone")
        owner_id = normalize_owner_id(account.get("owner_id"))
        if not phone:
            return 0
        migrated = 0
        with self.lock:
            intents = [
                intent for intent in self.intents.values()
                if intent.account_phone == phone and intent.is_alive() and not intent.stop_event.is_set()
            ]
        for intent in intents:
            previous_owner = normalize_owner_id(intent.owner_id)
            intent.account = account
            intent.account_phone = phone
            if previous_owner != owner_id:
                intent.owner_id = owner_id
                self.log(f"{intent.device_name} 抢占意图随账号 {phone} 转移到当前用户")
            migrated += 1
        if migrated:
            self.save_intents()
            self.emit({"type": "intent", "state": "migrated", "accountPhone": phone})
        return migrated

    def log(self, message: str) -> None:
        entry = f"[{datetime.now().strftime('%H:%M:%S')}] {message}"
        print(entry)
        with self.lock:
            self.logs.append(entry)

    def get_account(self, phone: str) -> Optional[dict[str, Any]]:
        return next((acc for acc in self.accounts.accounts if acc.get("phone") == phone), None)

    def owned_accounts(self, owner_id: str) -> list[dict[str, Any]]:
        owner_id = normalize_owner_id(owner_id)
        return [acc for acc in self.accounts.accounts if normalize_owner_id(acc.get("owner_id")) == owner_id]

    def enabled_accounts(self, owner_id: Optional[str] = None) -> list[dict[str, Any]]:
        accounts = self.accounts.accounts if owner_id is None else self.owned_accounts(owner_id)
        return [
            acc for acc in accounts
            if acc.get("is_active", True) and not haile_bot.is_token_expired(acc.get("token"))
        ]

    def send_code(self, phone: str) -> dict[str, Any]:
        result = self.api.send_code(phone)
        if result.get("code") == 0:
            self.log(f"验证码已发送: {phone}")
        else:
            self.log(f"验证码发送失败: {phone} {result.get('message')}")
        return result

    def login(self, phone: str, code: str, owner_id: str = "default", owner_name: str = "", remark: str = "") -> dict[str, Any]:
        result = self.api.login(phone, code)
        if result.get("code") != 0:
            self.log(f"登录失败: {phone} {result.get('message')}")
            return {"success": False, "message": result.get("message") or "登录失败"}

        data = result.get("data") or {}
        previous = next((acc for acc in self.accounts.accounts if acc.get("phone") == phone), {})
        final_remark = str(remark or previous.get("remark") or "").strip()
        new_token = data.get("token")
        if not final_remark and new_token:
            try:
                info = self.api.account_info(new_token)
                if isinstance(info, dict) and info.get("code") == 0:
                    nick = str((info.get("data") or {}).get("nickname") or "").strip()
                    if nick and nick != phone:
                        final_remark = nick
            except Exception:
                pass
        new_acc = {
            "phone": phone,
            "token": new_token,
            "user_id": data.get("userId"),
            "is_active": True,
            "role": "service",
            "owner_id": normalize_owner_id(owner_id),
            "owner_name": str(owner_name or previous.get("owner_name") or "").strip(),
            "remark": final_remark,
        }
        self.accounts.accounts = [acc for acc in self.accounts.accounts if acc.get("phone") != phone]
        self.accounts.accounts.append(new_acc)
        self.accounts.save_accounts()
        self.log(f"登录成功: {phone}")
        self.migrate_intents_for_login(new_acc)
        self.refresh_account_orders(new_acc)
        self._recent_usage_cache.pop(normalize_owner_id(owner_id), None)
        auto_pos = self._maybe_auto_discover_position(new_token, owner_id=owner_id)
        out: dict[str, Any] = {"success": True}
        if auto_pos:
            out["autoPosition"] = auto_pos
        return out

    def set_account_enabled(self, phone: str, enabled: bool, owner_id: str = "default") -> dict[str, Any]:
        acc = self.get_owned_account(phone, owner_id)
        if not acc:
            return {"success": False, "message": "账号不属于当前用户"}
        acc["is_active"] = bool(enabled)
        self.accounts.save_accounts()
        self.log(f"账号 {'启用' if enabled else '停用'}: {phone}")
        return {"success": True}

    def set_account_remark(self, phone: str, remark: str, owner_id: str = "default") -> dict[str, Any]:
        acc = self.get_owned_account(phone, owner_id)
        if not acc:
            return {"success": False, "message": "账号不属于当前用户"}
        acc["remark"] = str(remark or "").strip()
        self.accounts.save_accounts()
        self.log(f"账号备注已更新: {phone}")
        return {"success": True}

    def get_owned_account(self, phone: str, owner_id: str) -> Optional[dict[str, Any]]:
        owner_id = normalize_owner_id(owner_id)
        return next(
            (
                acc for acc in self.accounts.accounts
                if acc.get("phone") == phone and normalize_owner_id(acc.get("owner_id")) == owner_id
            ),
            None,
        )

    def modes(self, goods_id: int) -> list[dict[str, Any]]:
        now = time.time()
        cached = self.mode_cache.get(goods_id)
        if cached and now - cached[0] < 3600:
            return cached[1]
        modes = self.api.modes(goods_id)
        self.mode_cache[goods_id] = (now, modes)
        return modes

    def select_mode(
        self,
        device_name: str,
        goods_id: int,
        requested_mode_id: Optional[int] = None,
        preferred_mode_name: Optional[str] = None,
    ) -> Optional[dict[str, Any]]:
        modes = self.modes(goods_id)
        if requested_mode_id:
            matched = next((mode for mode in modes if mode.get("id") == requested_mode_id), None)
            if matched:
                return matched
        if preferred_mode_name:
            return match_mode_by_preference(device_name, modes, preferred_mode_name)
        return haile_bot.select_preferred_mode(device_name, modes)

    def resolve_self_clean_item_id(
        self,
        goods_id: int,
        mode: Optional[dict[str, Any]],
        self_clean: bool = False,
    ) -> Optional[int]:
        if not self_clean or not mode_supports_self_clean(mode):
            return None
        try:
            return find_self_clean_item_id(self.modes(goods_id))
        except Exception:
            return None

    def _check_pickup_transitions(self, order: dict[str, Any]) -> None:
        order_no = str(order.get("orderNo") or "")
        if not order_no:
            return
        stage = order.get("stage")
        device_name = infer_device_name_from_order(order) or str(order.get("goodsName") or "洗衣机")
        owner_id = normalize_owner_id(order.get("ownerId"))

        if stage == "running":
            with self.lock:
                self._running_order_nos.add(order_no)
            finish_str = str(order.get("estimatedFinishTime") or "")
            if not finish_str and order.get("goodsId"):
                try:
                    raw = next((it for it in self._raw_devices if it.get("id") == order.get("goodsId")), None)
                    if raw and raw.get("finishTime"):
                        finish_str = str(raw.get("finishTime"))
                except Exception:
                    pass
            if finish_str:
                rem = seconds_until(finish_str, now_ts=self.now_ts())
                if rem is not None and 0 < rem <= PICKUP_REMINDER_LEAD_SECONDS:
                    with self.lock:
                        already = order_no in self._pickup_soon_sent
                        if not already:
                            self._pickup_soon_sent.add(order_no)
                    if not already:
                        self.log(f"{device_name} 预计 {max(1, int(round(rem / 60)))} 分钟内洗烘完成，准备收衣")
                        self.emit({
                            "type": "pickup",
                            "stage": "pickup_soon",
                            "orderNo": order_no,
                            "ownerId": owner_id,
                            "deviceName": device_name,
                            "remainingSeconds": max(0, int(rem)),
                        })
        elif stage == "ended":
            with self.lock:
                was_running = order_no in self._running_order_nos
                already_done = order_no in self._pickup_done_sent
                if was_running and not already_done:
                    self._running_order_nos.discard(order_no)
                    self._pickup_done_sent.add(order_no)
                    fire_done = True
                else:
                    fire_done = False
            if fire_done:
                self.log(f"{device_name} 洗烘已完成，请及时取衣")
                self.emit({
                    "type": "pickup",
                    "stage": "pickup_done",
                    "orderNo": order_no,
                    "ownerId": owner_id,
                    "deviceName": device_name,
                    "remainingSeconds": 0,
                })

    def refresh_account_orders(self, acc: dict[str, Any]) -> list[dict[str, Any]]:
        token = acc.get("token") or ""
        phone = acc.get("phone") or ""
        if not token or haile_bot.is_token_expired(token):
            return []

        found: dict[str, dict[str, Any]] = {}
        try:
            underway = self.api.underway_orders(token)
            if is_auth_failure(underway.get("code"), underway.get("message", "")):
                return []
            if underway.get("code") == 0:
                for item in underway.get("data") or []:
                    order_no = item.get("orderNo")
                    if order_no:
                        found[order_no] = summarize_list_item(item, phone)
        except Exception as exc:
            self.log(f"订单列表读取失败 {phone}: {exc}")

        try:
            trade = self.api.trade_list(token)
            if trade.get("code") == 0:
                for item in trade.get("data", {}).get("items", []) or []:
                    order_no = item.get("orderNo")
                    if order_no:
                        found.setdefault(order_no, summarize_list_item(item, phone))
        except Exception as exc:
            self.log(f"订单历史读取失败 {phone}: {exc}")

        with self.lock:
            tracked_running = [
                no for no in self._running_order_nos
                if no not in found and (self.orders_by_no.get(no) or {}).get("accountPhone") == phone
            ]
        for order_no in tracked_running:
            found[order_no] = dict(self.orders_by_no.get(order_no) or {"orderNo": order_no})

        for order_no in list(found):
            try:
                summary_item = found.get(order_no)
                with self.lock:
                    existing = self.orders_by_no.get(order_no)
                if (
                    summary_item
                    and summary_item.get("stage") == "ended"
                    and (existing is None or existing.get("stage") == "ended")
                ):
                    continue
                detail = self.api.order_detail(token, order_no)
                if detail.get("code") == 0:
                    found[order_no] = stabilize_order_timing(
                        summarize_detail(detail.get("data") or {}, phone),
                        summary_item or existing,
                    )
            except Exception:
                pass

        stored_orders: list[dict[str, Any]] = []
        with self.lock:
            for order in found.values():
                if order.get("orderNo"):
                    order["ownerId"] = normalize_owner_id(acc.get("owner_id"))
                    order = stabilize_order_timing(order, self.orders_by_no.get(order["orderNo"]))
                    self.orders_by_no[order["orderNo"]] = order
                    stored_orders.append(order)

        for order in stored_orders:
            self._check_pickup_transitions(order)

        return list(found.values())

    def refresh_all_orders(self, owner_id: Optional[str] = None) -> None:
        for acc in self.enabled_accounts(owner_id):
            self.refresh_account_orders(acc)
        self.last_order_refresh_at = time.time()
        self.prune_expired_orders()

    def maybe_refresh_all_orders(self, min_interval: float = 15.0) -> None:
        if time.time() - self.last_order_refresh_at < min_interval:
            return
        self.refresh_all_orders()

    def start_background_refresh(self) -> None:
        with self.lock:
            if self._poller_started:
                return
            self._poller_started = True

        def loop() -> None:
            while not self._poller_stop.is_set():
                try:
                    self.refresh_all_orders()
                except Exception as exc:
                    self.log(f"后台订单刷新失败: {exc}")
                self._poller_stop.wait(15)

        thread = threading.Thread(target=loop, daemon=True, name="haile-v4-order-refresh")
        thread.start()

    def _fetch_raw_devices(self, max_age: float = RAW_DEVICES_CACHE_SECONDS) -> list[dict[str, Any]]:
        """设备列表短 TTL 缓存：前端轮询与监控共享一次上游请求，抢单链路用 max_age=0 拿实时数据。"""
        now = time.time()
        with self.lock:
            if max_age > 0 and self._raw_devices and now - self._raw_devices_at < max_age:
                return self._raw_devices
        try:
            items = self._call_api_devices()
        except Exception as exc:
            self.log(f"设备列表读取失败: {exc}")
            with self.lock:
                return list(self._raw_devices)
        with self.lock:
            self._raw_devices = items
            self._raw_devices_at = time.time()
        return items

    def _run_info_dead_time(self, goods_id: int) -> str:
        now = time.time()
        with self.lock:
            cached = self._run_info_cache.get(goods_id)
            if cached and now - cached[0] < RUN_INFO_CACHE_SECONDS:
                return cached[1]
        dead_time = ""
        try:
            result = self.api.run_info(goods_id)
            if result.get("code") == 0:
                candidate = (result.get("data") or {}).get("deadTime") or ""
                if candidate and (seconds_until(candidate, now_ts=self.now_ts()) or 0) > 0:
                    dead_time = candidate
        except Exception:
            pass
        with self.lock:
            self._run_info_cache[goods_id] = (now, dead_time)
        return dead_time

    def _diff_device_states(self, items: list[dict[str, Any]]) -> None:
        sigs: dict[int, tuple] = {}
        events: list[dict[str, Any]] = []
        for item in items:
            name = self._device_name_for_raw(item)
            if not name or item.get("id") is None:
                continue
            goods_id = int(item.get("id"))
            sig = (
                item.get("state"),
                bool(item.get("enableReserve")),
                item.get("reserveState"),
                str(item.get("finishTime") or ""),
            )
            sigs[goods_id] = sig
            previous = self._device_sigs.get(goods_id)
            if previous is not None and previous != sig:
                events.append({
                    "type": "device",
                    "goodsId": goods_id,
                    "name": name,
                    "stateKey": classify_device(item).key,
                })
        with self.lock:
            self._device_sigs = sigs
        for event in events:
            self.emit(event)

    def start_device_monitor(self) -> None:
        with self.lock:
            if self._monitor_started:
                return
            self._monitor_started = True

        def loop() -> None:
            while not self._poller_stop.is_set():
                try:
                    if self.has_subscribers():
                        items = self._fetch_raw_devices(max_age=0)
                        self._diff_device_states(items)
                        self._poller_stop.wait(DEVICE_MONITOR_SECONDS)
                    else:
                        self._poller_stop.wait(10.0)
                except Exception as exc:
                    self.log(f"设备监控失败: {exc}")
                    self._poller_stop.wait(8.0)

        thread = threading.Thread(target=loop, daemon=True, name="haile-v4-device-monitor")
        thread.start()

    def active_intent_specs(self) -> list[dict[str, Any]]:
        with self.lock:
            intents = list(self.intents.values())
        specs = []
        seen_ids = set()
        for intent in intents:
            if id(intent) in seen_ids:
                continue
            seen_ids.add(id(intent))
            if not intent.is_alive() or intent.stop_event.is_set():
                continue
            specs.append(intent.persist_spec())
        return specs

    def save_intents(self) -> None:
        specs = self.active_intent_specs()
        os.makedirs(os.path.dirname(INTENTS_FILE), exist_ok=True)
        tmp_path = f"{INTENTS_FILE}.tmp"
        with open(tmp_path, "w", encoding="utf-8") as handle:
            json.dump(specs, handle, ensure_ascii=False, indent=2)
        os.replace(tmp_path, INTENTS_FILE)

    def rekey_intent(self, intent: "PreemptIntent", new_key: int) -> None:
        with self.lock:
            old_key = getattr(intent, "intent_key", None)
            if old_key is not None and self.intents.get(old_key) is intent:
                self.intents.pop(old_key, None)
            intent.intent_key = new_key
            self.intents[new_key] = intent

    def forget_intent(self, goods_id: int, owner_id: str, intent_ref: Optional["PreemptIntent"] = None) -> None:
        owner_id = normalize_owner_id(owner_id)
        with self.lock:
            keys_to_remove = []
            for key, item in self.intents.items():
                if intent_ref is not None and item is intent_ref:
                    keys_to_remove.append(key)
                elif key == goods_id and normalize_owner_id(item.owner_id) == owner_id:
                    keys_to_remove.append(key)
            for key in keys_to_remove:
                self.intents.pop(key, None)
        self.save_intents()

    def restore_intents(self) -> None:
        if not os.path.exists(INTENTS_FILE):
            return
        try:
            with open(INTENTS_FILE, "r", encoding="utf-8") as handle:
                specs = json.load(handle)
        except Exception as exc:
            self.log(f"抢占恢复文件读取失败: {exc}")
            return
        if not isinstance(specs, list):
            return

        restored = 0
        for spec in specs:
            owner_id = normalize_owner_id(spec.get("ownerId"))
            pool_ids = spec.get("candidateGoodsIds") or spec.get("goodsIds")
            any_washer = bool(spec.get("anyWasher") or (isinstance(pool_ids, list) and len(pool_ids) > 1))
            goods_id = spec.get("goodsId")
            if not goods_id and not any_washer:
                continue
            try:
                self.refresh_all_orders(owner_id)
                result = self.start_intent(
                    int(goods_id or 0),
                    spec.get("modeId"),
                    owner_id,
                    spec.get("dryerMinutes"),
                    self_clean=bool(spec.get("selfClean", False)),
                    goods_ids=[int(x) for x in pool_ids] if isinstance(pool_ids, list) and pool_ids else None,
                    any_washer=any_washer and not pool_ids,
                    preferred_mode_name=spec.get("preferredModeName") or spec.get("modeName"),
                )
                if result.get("success"):
                    restored += 1
                else:
                    self.log(f"抢占恢复失败 {spec.get('deviceName') or goods_id}: {result.get('message')}")
            except Exception as exc:
                self.log(f"抢占恢复异常 {spec.get('deviceName') or goods_id}: {exc}")
        if restored:
            self.log(f"已恢复 {restored} 个抢占意图")

    def save_holds(self) -> None:
        with self.lock:
            active = {name: expiry for name, expiry in self.device_payment_holds.items() if expiry > time.time()}
        os.makedirs(os.path.dirname(HOLDS_FILE), exist_ok=True)
        tmp_path = f"{HOLDS_FILE}.tmp"
        with open(tmp_path, "w", encoding="utf-8") as handle:
            json.dump(active, handle, ensure_ascii=False, indent=2)
        os.replace(tmp_path, HOLDS_FILE)

    def restore_holds(self) -> None:
        if not os.path.exists(HOLDS_FILE):
            return
        try:
            with open(HOLDS_FILE, "r", encoding="utf-8") as handle:
                data = json.load(handle)
        except Exception as exc:
            self.log(f"支付锁恢复文件读取失败: {exc}")
            return
        if not isinstance(data, dict):
            return
        now = time.time()
        with self.lock:
            for name, expiry in data.items():
                try:
                    if float(expiry) > now:
                        self.device_payment_holds[name] = float(expiry)
                except (TypeError, ValueError):
                    pass
        active = sum(1 for expiry in self.device_payment_holds.values() if expiry > now)
        if active:
            self.log(f"已恢复 {active} 个支付锁")

    def prune_expired_orders(self) -> None:
        now = time.time()
        with self.lock:
            expired = [
                order_no for order_no, order in self.orders_by_no.items()
                if order.get("stage") == "ended" and (order.get("updatedAt") or 0) + ORDER_RETENTION_SECONDS < now
            ]
            for order_no in expired:
                self.orders_by_no.pop(order_no, None)
                self._running_order_nos.discard(order_no)
                self._pickup_soon_sent.discard(order_no)
                self._pickup_done_sent.discard(order_no)
        if expired:
            self.log(f"已清理 {len(expired)} 个过期订单")

    def blocking_orders(self, phone: Optional[str] = None, owner_id: Optional[str] = None) -> list[dict[str, Any]]:
        with self.lock:
            orders = list(self.orders_by_no.values())
        if phone:
            orders = [order for order in orders if order.get("accountPhone") == phone]
        if owner_id:
            owner_id = normalize_owner_id(owner_id)
            orders = [order for order in orders if normalize_owner_id(order.get("ownerId")) == owner_id]
        return [order for order in orders if order_blocks_account(order)]

    def order_for_device(
        self,
        device_name: str,
        owner_id: Optional[str] = None,
        goods_id: Optional[int] = None,
    ) -> Optional[dict[str, Any]]:
        with self.lock:
            orders = list(self.orders_by_no.values())
        if owner_id:
            owner_id = normalize_owner_id(owner_id)
            orders = [order for order in orders if normalize_owner_id(order.get("ownerId")) == owner_id]
        candidates = []
        for order in orders:
            if order.get("stage") == "ended":
                continue
            order_goods_id = order.get("goodsId")
            matched_by_id = (
                goods_id is not None
                and order_goods_id is not None
                and int(order_goods_id) == int(goods_id)
            )
            if not matched_by_id and infer_device_name_from_order(order) != device_name:
                continue
            candidates.append(order)
        if not candidates:
            return None
        return max(candidates, key=lambda item: item.get("updatedAt") or 0)

    def device_finish_time(self, item: dict[str, Any]) -> str:
        finish_time = item.get("finishTime") or ""
        if finish_time or item.get("state") == 1:
            return finish_time
        return self._run_info_dead_time(int(item.get("id")))

    def order_with_device_timing(self, order: Optional[dict[str, Any]], device: Optional[dict[str, Any]]) -> Optional[dict[str, Any]]:
        if not order:
            return None
        timed = dict(order)
        stage = timed.get("stage")
        device_finish = (device or {}).get("finishTime") or ""
        if stage == "running" and device_finish:
            timed["estimatedFinishTime"] = device_finish
            timed["estimatedFinishSource"] = "device"
        elif timed.get("estimatedFinishTime"):
            timed["estimatedFinishSource"] = "order"
        else:
            timed["estimatedFinishSource"] = ""

        ref_now = self.now_ts()
        if timed.get("estimatedFinishTime"):
            remaining = seconds_until(timed.get("estimatedFinishTime"), now_ts=ref_now)
            timed["estimatedRemainingSeconds"] = None if remaining is None else max(0, int(remaining))
        else:
            timed["estimatedRemainingSeconds"] = None

        if timed.get("deadline"):
            remaining = seconds_until(timed.get("deadline"), now_ts=ref_now)
            timed["deadlineRemainingSeconds"] = None if remaining is None else max(0, int(remaining))
        else:
            timed["deadlineRemainingSeconds"] = None
        return timed

    def account_usage(self, phone: str, owner_id: str) -> int:
        """账号当前占用的订单/抢占槽位：阻塞订单 + 未持单的活跃意图（多机竞速意图也只占 1 槽）。"""
        owner_id = normalize_owner_id(owner_id)
        blocking = self.blocking_orders(phone, owner_id)
        held = {order.get("orderNo") for order in blocking}
        extra_intents = 0
        seen_ids = set()
        for intent in self.intents.values():
            if id(intent) in seen_ids:
                continue
            seen_ids.add(id(intent))
            if not intent.is_alive() or intent.account_phone != phone:
                continue
            current_no = getattr(intent, "current_order_no", None)
            if isinstance(current_no, str) and current_no and current_no in held:
                continue
            extra_intents += 1
        return len(blocking) + extra_intents

    def choose_account(self, owner_id: str) -> Optional[dict[str, Any]]:
        owner_id = normalize_owner_id(owner_id)
        self.refresh_all_orders(owner_id)
        candidates = [
            acc for acc in self.enabled_accounts(owner_id)
            if self.account_usage(acc.get("phone") or "", owner_id) < ACCOUNT_MAX_ACTIVE_SLOTS
        ]
        return random.choice(candidates) if candidates else None

    def no_account_reason(self, owner_id: str) -> str:
        """choose_account 返回空时给出具体原因，供前端展示可操作信息。"""
        owner_id = normalize_owner_id(owner_id)
        if not self.owned_accounts(owner_id):
            return "当前还没有登录账号"
        enabled = self.enabled_accounts(owner_id)
        if not enabled:
            return "账号登录已失效，请重新接码登录"
        for acc in enabled:
            usage = self.account_usage(acc.get("phone") or "", owner_id)
            if usage >= ACCOUNT_MAX_ACTIVE_SLOTS:
                label = str(acc.get("remark") or acc.get("phone"))
                return f"账号 {label} 已有 {usage} 个占用中的订单/抢占（单账号上限 {ACCOUNT_MAX_ACTIVE_SLOTS}）"
        return "没有可用账号"

    def live_intent(self, goods_id: int) -> Optional["PreemptIntent"]:
        with self.lock:
            intent = self.intents.get(goods_id)
        if not intent or not intent.is_alive() or intent.stop_event.is_set():
            return None
        return intent

    def pool_intent_for_owner(self, owner_id: str) -> Optional["PreemptIntent"]:
        owner_id = normalize_owner_id(owner_id)
        with self.lock:
            intents = list(self.intents.values())
        for intent in intents:
            if not intent.is_alive() or intent.stop_event.is_set():
                continue
            if normalize_owner_id(intent.owner_id) == owner_id and getattr(intent, "is_pool", False):
                return intent
        return None

    def other_user_label(self, intent: Optional["PreemptIntent"]) -> str:
        if not intent:
            return ""
        account = self.get_account(intent.account_phone or "")
        remark = str((account or {}).get("remark") or "").strip()
        if remark:
            return remark
        phone = str(intent.account_phone or (account or {}).get("phone") or "").strip()
        if len(phone) >= 4:
            return f"尾号{phone[-4:]}"
        return phone or "其他用户"

    def devices(self, owner_id: str = "default") -> list[dict[str, Any]]:
        owner_id = normalize_owner_id(owner_id)
        raw_items = self._fetch_raw_devices()
        devices = []
        now = time.time()
        ref_now = self.now_ts()
        owner_pool = self.pool_intent_for_owner(owner_id)
        for item in raw_items:
            if item.get("id") is None:
                continue
            imei = str(item.get("imei") or "")
            name = self._device_name_for_raw(item)
            if not name:
                continue
            goods_id = int(item["id"])
            hold_active = self.device_payment_holds.get(name, 0) > now
            if item.get("state") == 2 and bool(item.get("enableReserve")) and item.get("reserveState") == 1:
                self.device_payment_holds.pop(name, None)
                hold_active = False
            order = self.order_for_device(name, owner_id, goods_id=goods_id)
            if order and order.get("stage") in {"awaiting_payment", "reserve_waiting"}:
                hold_active = False
            state = classify_device(item, payment_pending=hold_active)
            finish_time = self.device_finish_time(item)
            intent = self.live_intent(goods_id)
            visible_intent = intent if intent and normalize_owner_id(intent.owner_id) == owner_id else None
            if visible_intent is None and owner_pool is not None and goods_id in getattr(owner_pool, "pool_goods_ids", []):
                if getattr(owner_pool, "goods_id", None) == goods_id:
                    visible_intent = owner_pool
            occupied_by_other_user = bool(intent and normalize_owner_id(intent.owner_id) != owner_id)
            in_my_pool = bool(owner_pool and goods_id in getattr(owner_pool, "pool_goods_ids", []))
            device_shell = {"finishTime": finish_time}
            active_order = self.order_with_device_timing(order, device_shell)
            state_label, queue_text = device_user_labels(item, state, active_order, visible_intent)
            devices.append({
                "name": name,
                "goodsId": goods_id,
                "imei": imei,
                "deviceType": device_type_from_name(name),
                "deviceTypeLabel": DEVICE_TYPE_LABELS.get(device_type_from_name(name), "设备"),
                "state": state.key,
                "stateLabel": state_label,
                "queueCount": state.queue_count,
                "queueText": queue_text,
                "canOrder": state.can_order,
                "orderKind": state.order_kind,
                "finishTime": finish_time,
                "remainingSeconds": seconds_until(finish_time, now_ts=ref_now),
                "activeOrder": active_order,
                "intent": visible_intent.snapshot() if visible_intent else None,
                "inPool": in_my_pool,
                "occupiedByOtherUser": occupied_by_other_user,
                "occupiedByOtherUserLabel": self.other_user_label(intent) if occupied_by_other_user else "",
            })
        decorate_device_recommendations(devices)
        return sorted(devices, key=lambda item: item["name"])

    def start_intent(
        self,
        goods_id: int = 0,
        mode_id: Optional[int] = None,
        owner_id: str = "default",
        dryer_minutes: Optional[int] = None,
        self_clean: bool = False,
        goods_ids: Optional[list[int]] = None,
        any_washer: bool = False,
        preferred_mode_name: Optional[str] = None,
    ) -> dict[str, Any]:
        owner_id = normalize_owner_id(owner_id)
        raw_list = self._fetch_raw_devices(max_age=0)

        candidate_ids: list[int] = []
        if goods_ids:
            for gid in goods_ids:
                try:
                    val = int(gid)
                    if val > 0 and val not in candidate_ids:
                        candidate_ids.append(val)
                except (TypeError, ValueError):
                    pass
        elif any_washer or int(goods_id or 0) < 0:
            for item in raw_list:
                name = self._device_name_for_raw(item)
                if name and item.get("id") is not None and device_type_from_name(name) == "washer":
                    candidate_ids.append(int(item["id"]))

        if len(candidate_ids) == 1 and not any_washer:
            goods_id = candidate_ids[0]
            candidate_ids = []

        if len(candidate_ids) >= 1 and (any_washer or len(candidate_ids) > 1):
            pool_key = pool_key_for_owner(owner_id)
            with self.lock:
                existing_pool = self.intents.get(pool_key)
                if existing_pool and existing_pool.is_alive() and not existing_pool.stop_event.is_set():
                    return {"success": False, "message": "已在并池抢占最快洗衣机中"}

            raw_candidates = [it for it in raw_list if it.get("id") is not None and int(it["id"]) in candidate_ids]
            if not raw_candidates:
                return {"success": False, "message": "未找到可并池抢占的洗衣机"}

            first_raw = raw_candidates[0]
            first_name = self._device_name_for_raw(first_raw) or first_raw.get("name") or "洗衣机"
            first_gid = int(first_raw["id"])
            mode = self.select_mode(first_name, first_gid, mode_id, preferred_mode_name=preferred_mode_name or "标准")
            if not mode:
                return {"success": False, "message": "没有可用套餐"}
            effective_self_clean = bool(self_clean and mode_supports_self_clean(mode))
            self_clean_item_id = self.resolve_self_clean_item_id(first_gid, mode, effective_self_clean)

            acc = self.choose_account(owner_id)
            if not acc:
                return {"success": False, "message": self.no_account_reason(owner_id)}

            intent = PreemptIntent(
                self,
                first_raw,
                mode,
                acc,
                owner_id=owner_id,
                dryer_minutes=dryer_minutes,
                self_clean=effective_self_clean,
                self_clean_item_id=self_clean_item_id,
                pool_goods_ids=[int(it["id"]) for it in raw_candidates],
                preferred_mode_name=str(preferred_mode_name or mode.get("name") or "标准"),
                intent_key=pool_key,
            )
            with self.lock:
                self.intents[pool_key] = intent
            intent.start()
            self.save_intents()
            self.emit({"type": "intent", "goodsId": pool_key, "deviceName": intent.display_target_name(), "state": "started", "isPool": True})
            return {"success": True, "intentKey": pool_key, "isPool": True}

        with self.lock:
            current = self.intents.get(goods_id)
            if current and current.is_alive() and not current.stop_event.is_set():
                return {"success": False, "message": "这台设备已经在抢占中"}

        raw = next((item for item in raw_list if item.get("id") == goods_id), None)
        if not raw:
            return {"success": False, "message": "设备不存在或状态不可读"}
        name = self._device_name_for_raw(raw) or raw.get("name")
        mode = self.select_mode(name, goods_id, mode_id, preferred_mode_name=preferred_mode_name)
        if not mode:
            return {"success": False, "message": "没有可用套餐"}
        effective_self_clean = bool(self_clean and mode_supports_self_clean(mode))
        self_clean_item_id = self.resolve_self_clean_item_id(goods_id, mode, effective_self_clean)

        existing_order = self.order_for_device(name, owner_id, goods_id=goods_id)
        if existing_order and existing_order.get("stage") in {"awaiting_payment", "reserve_waiting"}:
            acc = self.get_owned_account(existing_order.get("accountPhone") or "", owner_id)
            if acc:
                if self.account_usage(acc.get("phone") or "", owner_id) > ACCOUNT_MAX_ACTIVE_SLOTS:
                    return {"success": False, "message": f"该账号订单/抢占已满（上限 {ACCOUNT_MAX_ACTIVE_SLOTS}）"}
                intent = PreemptIntent(
                    self,
                    raw,
                    mode,
                    acc,
                    owner_id=owner_id,
                    dryer_minutes=dryer_minutes,
                    self_clean=effective_self_clean,
                    self_clean_item_id=self_clean_item_id,
                    resume_order=existing_order,
                    intent_key=goods_id,
                )
                with self.lock:
                    self.intents[goods_id] = intent
                intent.start()
                self.save_intents()
                self.emit({"type": "intent", "goodsId": goods_id, "deviceName": intent.device_name, "state": "started"})
                return {"success": True}

        acc = self.choose_account(owner_id)
        if not acc:
            return {"success": False, "message": self.no_account_reason(owner_id)}

        intent = PreemptIntent(
            self,
            raw,
            mode,
            acc,
            owner_id=owner_id,
            dryer_minutes=dryer_minutes,
            self_clean=effective_self_clean,
            self_clean_item_id=self_clean_item_id,
            intent_key=goods_id,
        )
        with self.lock:
            self.intents[goods_id] = intent
        intent.start()
        self.save_intents()
        self.emit({"type": "intent", "goodsId": goods_id, "deviceName": intent.device_name, "state": "started"})
        return {"success": True}

    def stop_intent(self, goods_id: int, cancel_order: bool = False, owner_id: str = "default") -> dict[str, Any]:
        owner_id = normalize_owner_id(owner_id)
        with self.lock:
            intent = self.intents.get(goods_id)
            if intent is None and goods_id <= 0:
                intent = self.intents.get(pool_key_for_owner(owner_id))
            if intent is None:
                for candidate in self.intents.values():
                    if normalize_owner_id(candidate.owner_id) == owner_id and (
                        candidate.goods_id == goods_id or goods_id in getattr(candidate, "pool_goods_ids", [])
                    ):
                        intent = candidate
                        break
        if not intent:
            return {"success": True, "message": "没有运行中的抢占"}
        if normalize_owner_id(intent.owner_id) != owner_id:
            return {"success": False, "message": "不能停止其他用户的抢占"}
        intent.request_stop(cancel_order=cancel_order)
        self.emit({"type": "intent", "goodsId": intent.intent_key, "deviceName": intent.device_name, "state": "stopping"})
        return {"success": True}

    def cancel_order(self, phone: str, order_no: str, owner_id: str = "default") -> dict[str, Any]:
        acc = self.get_owned_account(phone, owner_id)
        if not acc:
            return {"success": False, "message": "账号不存在或不属于当前用户"}
        result = self.api.cancel_order(acc.get("token") or "", order_no)
        if result.get("code") == 0:
            with self.lock:
                order = self.orders_by_no.get(order_no)
                if order:
                    self.orders_by_no[order_no] = enrich_order({**order, "state": 411, "pageCode": "timeout", "canPay": False, "canCancel": False})
            self.log(f"已取消订单 {order_no}")
            return {"success": True}
        return {"success": False, "message": result.get("message") or "取消失败"}

    def verify_order(self, phone: str, order_no: str, check_code: str, owner_id: str = "default") -> dict[str, Any]:
        acc = self.get_owned_account(phone, owner_id)
        if not acc:
            return {"success": False, "message": "账号不存在或不属于当前用户"}
        code_str = str(check_code or "").strip()
        if not code_str:
            return {"success": False, "message": "请输入预约核验码"}
        result = self.api.verify_order_code(acc.get("token") or "", order_no, code_str)
        if result.get("code") == 0:
            self.log(f"预约订单 {order_no} 核验成功")
            self.refresh_account_orders(acc)
            return {"success": True}
        return {"success": False, "message": result.get("message") or "核验失败"}

    def resend_verify_code(self, phone: str, order_no: str, owner_id: str = "default") -> dict[str, Any]:
        acc = self.get_owned_account(phone, owner_id)
        if not acc:
            return {"success": False, "message": "账号不存在或不属于当前用户"}
        result = self.api.resend_verify_code(acc.get("token") or "", order_no)
        if result.get("code") == 0:
            self.log(f"预约订单 {order_no} 核验码已重发")
            return {"success": True}
        return {"success": False, "message": result.get("message") or "重发核验码失败"}

    def remind_previous_pickup(self, phone: str, order_no: str, owner_id: str = "default") -> dict[str, Any]:
        acc = self.get_owned_account(phone, owner_id)
        if not acc:
            return {"success": False, "message": "账号不存在或不属于当前用户"}
        result = self.api.remind_pickup_clothes(acc.get("token") or "", order_no)
        if result.get("code") == 0:
            self.log(f"已向 {order_no} 前序用户发送取衣提醒")
            return {"success": True}
        return {"success": False, "message": result.get("message") or "提醒前序取衣失败"}

    def confirm_place_clothes(self, phone: str, order_no: str, owner_id: str = "default") -> dict[str, Any]:
        acc = self.get_owned_account(phone, owner_id)
        if not acc:
            return {"success": False, "message": "账号不存在或不属于当前用户"}
        result = self.api.place_clothes_start(acc.get("token") or "", order_no)
        if result.get("code") == 0:
            self.log(f"预约订单 {order_no} 已确认放衣关门")
            self.refresh_account_orders(acc)
            return {"success": True}
        return {"success": False, "message": result.get("message") or "放衣确认失败"}

    def mark_order_expired_locally(self, order_no: str) -> None:
        if not order_no:
            return
        with self.lock:
            order = self.orders_by_no.get(order_no)
            if not order:
                return
            self.orders_by_no[order_no] = enrich_order({
                **order,
                "state": 411,
                "stateDesc": "支付超时",
                "pageCode": "timeout",
                "canPay": False,
                "canCancel": False,
                "updatedAt": time.time(),
            })

    def state(self, owner_id: str = "default") -> dict[str, Any]:
        owner_id = normalize_owner_id(owner_id)
        devices = self.devices(owner_id)
        devices_by_name = {device.get("name"): device for device in devices}
        with self.lock:
            orders = [
                order for order in self.orders_by_no.values()
                if normalize_owner_id(order.get("ownerId")) == owner_id
            ]
            logs = list(self.logs)
            seen_intents = set()
            intents = []
            for intent in self.intents.values():
                if id(intent) in seen_intents:
                    continue
                seen_intents.add(id(intent))
                if normalize_owner_id(intent.owner_id) == owner_id:
                    intents.append(intent.snapshot())
        accounts = []
        for acc in self.owned_accounts(owner_id):
            snap = account_snapshot(acc)
            snap["blockingCount"] = len(self.blocking_orders(acc.get("phone"), owner_id))
            snap["slotUsage"] = self.account_usage(acc.get("phone") or "", owner_id)
            snap["slotLimit"] = ACCOUNT_MAX_ACTIVE_SLOTS
            accounts.append(snap)
        return {
            "version": __version__,
            "versionCode": APP_VERSION_CODE,
            "position": {
                "positionId": self.position_id,
                "positionName": self.position_name,
                "lng": self.position_lng,
                "lat": self.position_lat,
                "customized": self.position_customized,
                "recentPositions": list(self.recent_positions),
            },
            "clockOffsetSeconds": round(safe_clock_offset(self.api), 3),
            "accounts": accounts,
            "devices": devices,
            "orders": sorted(
                (
                    self.order_with_device_timing(
                        order,
                        devices_by_name.get(infer_device_name_from_order(order)),
                    )
                    for order in orders
                ),
                key=lambda item: item.get("updatedAt") or 0,
                reverse=True,
            ),
            "ownerId": owner_id,
            "blockingCount": len(self.blocking_orders(owner_id=owner_id)),
            "intents": intents,
            "logs": logs,
            "recommendations": build_recommendation_groups(devices),
        }


class PreemptIntent(threading.Thread):
    def __init__(
        self,
        runtime: Runtime,
        device: dict[str, Any],
        mode: dict[str, Any],
        account: dict[str, Any],
        owner_id: str = "default",
        dryer_minutes: Optional[int] = None,
        self_clean: bool = False,
        self_clean_item_id: Optional[int] = None,
        resume_order: Optional[dict[str, Any]] = None,
        pool_goods_ids: Optional[list[int]] = None,
        preferred_mode_name: Optional[str] = None,
        intent_key: Optional[int] = None,
    ):
        super().__init__(daemon=True)
        self.runtime = runtime
        self.device = device
        self.mode = mode
        self.account = account
        self.owner_id = normalize_owner_id(owner_id)
        self.dryer_minutes = normalize_dryer_minutes(dryer_minutes)
        self.self_clean = bool(self_clean)
        self.self_clean_item_id = int(self_clean_item_id) if self_clean_item_id else None
        self.goods_id = int(device["id"])
        self.device_name = haile_bot.TARGET_IMEIS.get(str(device.get("imei") or "")) or device.get("name") or f"设备#{self.goods_id}"
        self.origin_pool_ids: list[int] = list(pool_goods_ids) if pool_goods_ids and len(pool_goods_ids) > 1 else []
        self.pool_goods_ids: list[int] = list(self.origin_pool_ids)
        self.is_pool: bool = bool(self.pool_goods_ids) and resume_order is None
        self.preferred_mode_name: str = str(preferred_mode_name or mode.get("name") or "")
        self.intent_key: int = int(intent_key) if intent_key is not None else (pool_key_for_owner(self.owner_id) if self.is_pool else self.goods_id)
        self.account_phone = account.get("phone")
        self.stop_event = threading.Event()
        self.cancel_on_stop = False
        resume_order = stabilize_order_timing(resume_order) if resume_order else None
        self.state = "holding_payment" if resume_order and resume_order.get("stage") == "awaiting_payment" else "acquiring"
        self.status = "继续维护订单" if resume_order else ("并池盯守最快空机" if self.is_pool else "准备抢占")
        self.current_order = resume_order
        self.current_order_no = (resume_order or {}).get("orderNo")
        self.attempts = 0
        self.started_at = time.time()
        self.last_error = ""
        self.poll_phase = "watch"
        self.release_at: Optional[float] = None
        self.burst_attempts = 0
        self.cruise_ticks = 0
        self.idle_while_reserved_ticks = 0
        self._run_info_at = 0.0
        self.next_delay = POLL_ACTIVE_SECONDS
        self.error_streak = 0

    def display_target_name(self) -> str:
        if self.is_pool:
            return f"任意最快洗衣机（当前盯 {self.device_name}）"
        return str(self.device_name or "")

    def snapshot(self) -> dict[str, Any]:
        return {
            "goodsId": self.intent_key if self.is_pool else self.goods_id,
            "focusGoodsId": self.goods_id,
            "isPool": self.is_pool,
            "candidateGoodsIds": list(self.pool_goods_ids) if self.is_pool else [],
            "candidateCount": len(self.pool_goods_ids) if self.is_pool else 1,
            "deviceName": f"任意最快洗衣机 · 盯 {self.device_name}" if self.is_pool else self.device_name,
            "focusDeviceName": self.device_name,
            "modeId": self.mode.get("id"),
            "modeName": self.preferred_mode_name if self.is_pool else self.mode.get("name"),
            "selfClean": self.self_clean,
            "dryerMinutes": self.dryer_minutes,
            "accountPhone": self.account_phone,
            "ownerId": self.owner_id,
            "state": self.state,
            "status": self.status,
            "orderNo": self.current_order_no,
            "attempts": self.attempts,
            "running": self.is_alive() and not self.stop_event.is_set(),
            "startedAt": self.started_at,
            "lastError": self.last_error,
            "pollPhase": self.poll_phase,
            "pollPhaseLabel": POLL_PHASE_LABELS.get(self.poll_phase, "轮询抢占"),
            "releaseRemainingSeconds": self.release_remaining_seconds(),
        }

    def persist_spec(self) -> dict[str, Any]:
        return {
            "goodsId": self.intent_key if self.is_pool else self.goods_id,
            "anyWasher": bool(self.origin_pool_ids),
            "candidateGoodsIds": list(self.origin_pool_ids) if self.origin_pool_ids else [],
            "preferredModeName": self.preferred_mode_name,
            "deviceName": self.device_name,
            "modeId": self.mode.get("id"),
            "modeName": self.mode.get("name"),
            "selfClean": self.self_clean,
            "ownerId": self.owner_id,
            "accountPhone": self.account_phone,
            "dryerMinutes": self.dryer_minutes,
            "orderNo": self.current_order_no,
            "updatedAt": time.time(),
        }

    def request_stop(self, cancel_order: bool = False) -> None:
        self.cancel_on_stop = cancel_order
        self.stop_event.set()

    def sleep(self, seconds: float) -> None:
        self.error_streak = 0
        self.stop_event.wait(max(0.1, seconds * random.uniform(0.85, 1.15)))

    def sleep_after_error(self, base_seconds: float) -> None:
        """连续异常指数退避（封顶 30s），正常 sleep 会重置计数。"""
        self.error_streak = min(5, self.error_streak + 1)
        delay = min(30.0, base_seconds * (2 ** self.error_streak))
        self.stop_event.wait(max(0.1, delay * random.uniform(0.85, 1.15)))

    def run(self) -> None:
        clean_note = "（+筒自洁）" if self.self_clean else ""
        target_desc = f"并池 {len(self.pool_goods_ids)} 台洗衣机" if self.is_pool else self.device_name
        self.runtime.log(f"{target_desc} 开始抢占，账号 {self.account_phone}，套餐 {self.preferred_mode_name or self.mode.get('name')}{clean_note}")
        try:
            while not self.stop_event.is_set():
                if self.current_order_no:
                    self.maintain_order()
                else:
                    self.acquire_order()
        finally:
            if self.cancel_on_stop and self.current_order_no:
                self.runtime.cancel_order(self.account_phone or "", self.current_order_no, self.owner_id)
            self.state = "stopped"
            self.status = "已停止"
            self.runtime.log(f"{self.device_name} 抢占已停止")
            self.runtime.emit({"type": "intent", "goodsId": self.intent_key, "deviceName": self.device_name, "state": "stopped"})
            self.runtime.forget_intent(self.intent_key, self.owner_id, intent_ref=self)

    def _ref_now(self) -> Optional[float]:
        now_fn = getattr(self.runtime, "now_ts", None)
        if callable(now_fn):
            val = now_fn()
            if isinstance(val, (int, float)):
                return float(val)
        return safe_server_now(getattr(self.runtime, "api", None))

    def release_remaining_seconds(self) -> Optional[float]:
        if self.release_at is None:
            return None
        return self.release_at - time.time()

    def _estimate_raw_remaining(self, raw: dict[str, Any]) -> Optional[float]:
        ref_now = self._ref_now()
        finish_sec = seconds_until(raw.get("finishTime"), now_ts=ref_now)
        if finish_sec is not None:
            return finish_sec
        gid = raw.get("id")
        if gid is not None and hasattr(self.runtime, "_run_info_dead_time"):
            try:
                dead_str = self.runtime._run_info_dead_time(int(gid))
                if isinstance(dead_str, str) and dead_str:
                    return seconds_until(dead_str, now_ts=ref_now)
            except Exception:
                pass
        return None

    def update_release_estimate(self, raw: dict[str, Any]) -> None:
        """从设备列表 finishTime 或 runInfo deadTime（结合服务端时钟校准）估算释放时间。"""
        ref_now = self._ref_now()
        finish_seconds = seconds_until(raw.get("finishTime"), now_ts=ref_now)
        if finish_seconds is not None:
            self.release_at = time.time() + finish_seconds
            return
        now = time.time()
        if now - self._run_info_at < RUN_INFO_MIN_INTERVAL:
            return
        self._run_info_at = now
        try:
            result = self.runtime.api.run_info(self.goods_id)
        except Exception:
            return
        if result.get("code") != 0:
            return
        dead_seconds = seconds_until((result.get("data") or {}).get("deadTime"), now_ts=self._ref_now())
        self.release_at = None if dead_seconds is None else time.time() + dead_seconds

    def phase_status(self) -> str:
        label = POLL_PHASE_LABELS.get(self.poll_phase, "轮询抢占")
        remaining = self.release_remaining_seconds()
        prefix = f"盯 {self.device_name} · " if self.is_pool else ""
        if self.poll_phase in {"approach", "burst"} and remaining is not None:
            return f"{prefix}{label}，预计 {max(0, int(remaining))}s 后释放"
        if self.poll_phase == "overtime":
            return f"{prefix}{label}，滚筒解锁检测中"
        if self.poll_phase == "cooldown":
            return f"{prefix}{label}，稍后重新冲刺"
        return f"{prefix}{label}"

    def _fetch_live_raw_devices(self) -> list[dict[str, Any]]:
        pid = getattr(self.runtime, "position_id", DEFAULT_POSITION_ID)
        if isinstance(pid, int) and pid != DEFAULT_POSITION_ID:
            return self.runtime.api.devices(pid)
        return self.runtime.api.devices()

    def _select_best_pool_target(self, raw_items: list[dict[str, Any]]) -> Optional[dict[str, Any]]:
        now = time.time()
        holds = getattr(self.runtime, "device_payment_holds", {}) or {}
        scored: list[tuple[int, float, int, dict[str, Any]]] = []
        for item in raw_items:
            gid = item.get("id")
            if gid is None or int(gid) not in self.pool_goods_ids:
                continue
            gid_int = int(gid)
            name = haile_bot.TARGET_IMEIS.get(str(item.get("imei") or "")) or str(item.get("name") or f"设备#{gid_int}")
            if holds.get(name, 0) > now:
                continue
            other = self.runtime.live_intent(gid_int) if hasattr(self.runtime, "live_intent") else None
            if isinstance(other, PreemptIntent) and other is not self and normalize_owner_id(other.owner_id) != self.owner_id:
                continue
            d_state = classify_device(item, payment_pending=False)
            if not d_state.can_order:
                continue
            rem = self._estimate_raw_remaining(item)
            rem_sort = float(rem) if rem is not None else 1800.0
            if d_state.key == "idle":
                tier = 0
            elif d_state.key == "busy_reservable":
                tier = 1
            else:
                tier = 2
            scored.append((tier, rem_sort, gid_int, item))
        if not scored:
            return None
        scored.sort(key=lambda t: (t[0], t[1], t[2]))
        return scored[0][3]

    def _bind_focus_device(self, raw: dict[str, Any]) -> bool:
        gid = int(raw["id"])
        name = haile_bot.TARGET_IMEIS.get(str(raw.get("imei") or "")) or str(raw.get("name") or f"设备#{gid}")
        switched = gid != self.goods_id
        self.device = raw
        self.goods_id = gid
        self.device_name = name
        if switched:
            self.release_at = None
            self._run_info_at = 0.0
            self.burst_attempts = 0
        try:
            mode = self.runtime.select_mode(name, gid, None, preferred_mode_name=self.preferred_mode_name)
            if mode:
                self.mode = mode
                effective_sc = bool(self.self_clean and mode_supports_self_clean(mode))
                self.self_clean_item_id = self.runtime.resolve_self_clean_item_id(gid, mode, effective_sc)
        except Exception:
            return False
        return self.mode is not None and self.mode.get("id") is not None

    def acquire_order(self) -> None:
        self.state = "acquiring"
        self.status = "并池寻机中" if self.is_pool else "抢占中"
        self.attempts += 1
        self.next_delay = POLL_ACTIVE_SECONDS
        try:
            if self.adopt_existing_order_for_device():
                return

            raw_items = self._fetch_live_raw_devices()
            if self.is_pool:
                best_raw = self._select_best_pool_target(raw_items)
                if not best_raw:
                    self.poll_phase = "watch"
                    self.status = "候选洗衣机暂均锁定或不可抢，持续盯守中"
                    self.sleep(POLL_ACTIVE_SECONDS)
                    return
                if not self._bind_focus_device(best_raw):
                    self.sleep(POLL_ACTIVE_SECONDS)
                    return
                raw = best_raw
            else:
                raw = next((item for item in raw_items if item.get("id") == self.goods_id), self.device)

            device_state = classify_device(raw, payment_pending=False)
            if not device_state.can_order:
                self.poll_phase = "watch"
                self.status = device_state.label
                self.sleep(POLL_ACTIVE_SECONDS)
                return

            order_kind = device_state.order_kind or "instant"
            if order_kind == "poll":
                prev_phase = self.poll_phase
                prev_remaining = self.release_remaining_seconds()
                self.update_release_estimate(raw)
                new_remaining = self.release_remaining_seconds()
                plan = plan_poll(new_remaining, self.burst_attempts)
                self.poll_phase = plan.phase
                if plan.phase == "burst":
                    self.burst_attempts += 1
                else:
                    self.burst_attempts = 0

                cycle_reset = (
                    prev_phase in {"approach", "burst", "cooldown", "overtime"}
                    or (
                        prev_remaining is not None
                        and new_remaining is not None
                        and new_remaining - prev_remaining > 60.0
                    )
                )
                if plan.phase == "cruise":
                    self.cruise_ticks += 1
                else:
                    self.cruise_ticks = 0

                should_attempt = plan.attempt or cycle_reset or (plan.phase == "cruise" and self.cruise_ticks > 1)
                self.status = self.phase_status()
                if not should_attempt:
                    self.sleep(plan.delay)
                    return
                self.next_delay = plan.delay
            else:
                self.poll_phase = "watch"
                self.burst_attempts = 0
                self.cruise_ticks = 0

            token = self.account.get("token") or ""
            extra_kwargs = {"self_clean_item_id": self.self_clean_item_id} if self.self_clean_item_id else {}
            if order_kind == "instant":
                result = self.runtime.api.create_order(
                    token,
                    self.device_name,
                    self.goods_id,
                    int(self.mode["id"]),
                    dryer_minutes=self.dryer_minutes,
                    **extra_kwargs,
                )
                msg = str(result.get("message") or "")
                if result.get("code") != 0 and any(k in msg for k in ("使用中", "运行中", "占用")):
                    order_kind = "reserve"
                    result = self.runtime.api.reserve_order(
                        token,
                        self.device_name,
                        self.goods_id,
                        int(self.mode["id"]),
                        dryer_minutes=self.dryer_minutes,
                        **extra_kwargs,
                    )
            else:
                result = self.runtime.api.reserve_order(
                    token,
                    self.device_name,
                    self.goods_id,
                    int(self.mode["id"]),
                    dryer_minutes=self.dryer_minutes,
                    **extra_kwargs,
                )
                msg = str(result.get("message") or "")
                if result.get("code") != 0 and "空闲" in msg:
                    order_kind = "instant"
                    result = self.runtime.api.create_order(
                        token,
                        self.device_name,
                        self.goods_id,
                        int(self.mode["id"]),
                        dryer_minutes=self.dryer_minutes,
                        **extra_kwargs,
                    )

            self.handle_order_result(result, order_kind)
            self.sleep(self.next_delay)
        except Exception as exc:
            self.last_error = str(exc)
            self.status = "网络或平台响应异常"
            self.sleep_after_error(POLL_ACTIVE_SECONDS)

    def adopt_existing_order_for_device(self) -> bool:
        if self.is_pool:
            for gid in self.pool_goods_ids:
                order = self.runtime.order_for_device("", self.owner_id, goods_id=gid)
                if order and order.get("stage") in ACTIVE_ORDER_STAGES:
                    self.goods_id = gid
                    inferred = infer_device_name_from_order(order)
                    if inferred:
                        self.device_name = inferred
                    return self.adopt_order(order, "已接管并池设备订单")
            return False
        order = self.runtime.order_for_device(self.device_name, self.owner_id, goods_id=self.goods_id)
        return self.adopt_order(order, "已接管同设备订单")

    def _lock_pool_onto_current_device(self) -> None:
        if self.is_pool:
            self.is_pool = False
            if hasattr(self.runtime, "rekey_intent"):
                self.runtime.rekey_intent(self, self.goods_id)

    def _reopen_pool_if_applicable(self) -> None:
        if self.origin_pool_ids and not self.is_pool:
            self.is_pool = True
            self.pool_goods_ids = list(self.origin_pool_ids)
            if hasattr(self.runtime, "rekey_intent"):
                self.runtime.rekey_intent(self, pool_key_for_owner(self.owner_id))

    def adopt_order(self, order: Optional[dict[str, Any]], reason: str = "已接管订单") -> bool:
        if not order or order.get("stage") not in ACTIVE_ORDER_STAGES:
            return False
        order = stabilize_order_timing(dict(order), self.current_order)
        if order.get("stage") == "awaiting_payment":
            remaining = payment_remaining_seconds(order, now_ts=self._ref_now())
            if remaining is not None and remaining <= 0:
                self.runtime.mark_order_expired_locally(order.get("orderNo") or "")
                return False

        phone = order.get("accountPhone") or self.account_phone
        if phone and phone != self.account_phone:
            acc = self.runtime.get_owned_account(phone, self.owner_id)
            if not acc:
                return False
            self.account = acc
            self.account_phone = phone

        self._lock_pool_onto_current_device()
        self.current_order = order
        self.current_order_no = order.get("orderNo")
        self.state = "holding_payment" if order.get("stage") == "awaiting_payment" else "holding_reserve"
        if order.get("stage") == "awaiting_payment":
            self.status = "待支付维护中"
        elif order_ready_for_user_action(order):
            self.status = f"{order.get('stageLabel') or '已到号'}，请去小程序/App操作"
        else:
            self.status = "已排上预约，等待前序结束"
        self.runtime.log(f"{self.device_name} {reason} {self.current_order_no}，继续维护")
        self.runtime.save_intents()
        self.runtime.emit({"type": "order", "goodsId": self.goods_id, "deviceName": self.device_name, "orderNo": self.current_order_no, "stage": order.get("stage"), "adopted": True})
        return True

    def handle_order_result(self, result: dict[str, Any], order_kind: str) -> None:
        code = result.get("code")
        message = result.get("message") or ""
        if code == 0:
            data = result.get("data") or {}
            order_no = data.get("orderNo")
            stage = "awaiting_payment" if order_kind == "instant" else "reserve_waiting"
            self._lock_pool_onto_current_device()
            order = enrich_order({
                "orderNo": order_no,
                "accountPhone": self.account_phone,
                "ownerId": self.owner_id,
                "goodsId": self.goods_id,
                "goodsName": f"{self.device_name}【{self.mode.get('name')}】",
                "modeName": self.mode.get("name"),
                "selfClean": self.self_clean,
                "state": 100 if order_kind == "instant" else 50,
                "stateDesc": "待支付" if order_kind == "instant" else "预约成功",
                "pageCode": "normal_waiting_pay" if order_kind == "instant" else "appointment_waiting",
                "orderType": 100 if order_kind == "instant" else 300,
                "canPay": order_kind == "instant",
                "canCancel": True,
                "deadline": "",
                "updatedAt": time.time(),
            })
            if order_kind == "instant":
                order["expireSeconds"] = data.get("expireSeconds") or PAYMENT_EXPIRE_FALLBACK_SECONDS
                order["createdAtLocal"] = time.time()
                order = stabilize_order_timing(order, self.current_order)
            with self.runtime.lock:
                self.runtime.orders_by_no[order_no] = order
                self.runtime.device_payment_holds.pop(self.device_name, None)
            self.current_order = order
            self.current_order_no = order_no
            self.idle_while_reserved_ticks = 0
            self.state = "holding_payment" if stage == "awaiting_payment" else "holding_reserve"
            self.status = "待支付维护中" if stage == "awaiting_payment" else "已排上预约，等待前序结束"
            self.next_delay = 1.0
            recovered_note = "（超时反查接管）" if result.get("recovered") else ""
            self.runtime.log(f"{self.device_name} 已创建{'即时单' if order_kind == 'instant' else '预约单'} {order_no}{recovered_note}，账号 {self.account_phone}")
            self.runtime.save_intents()
            self.runtime.emit({"type": "order", "goodsId": self.goods_id, "deviceName": self.device_name, "orderNo": order_no, "stage": stage})
            return

        if is_auth_failure(code, message):
            self.state = "blocked"
            self.status = "账号登录失效"
            self.runtime.emit({"type": "intent", "goodsId": self.intent_key, "deviceName": self.device_name, "state": "blocked"})
            self.stop_event.set()
            return
        if is_device_payment_conflict(message):
            self.runtime.refresh_account_orders(self.account)
            if self.adopt_existing_order_for_device():
                return
            self.runtime.device_payment_holds[self.device_name] = time.time() + DEVICE_HOLD_SECONDS
            self.runtime.save_holds()
            self.status = "其他设备继续寻机中" if self.is_pool else "有人待支付，等待释放"
            self.next_delay = 0.4 if self.is_pool else POLL_ACTIVE_SECONDS
            return
        if is_account_order_conflict(message):
            self.runtime.refresh_account_orders(self.account)
            if self.adopt_existing_order_for_device():
                return
            self.status = "账号已有阻塞订单，刷新后重试"
            self.next_delay = POLL_ACTIVE_SECONDS * 2
            return
        self.last_error = message
        self.status = message or "下单失败，继续重试"

    def maintain_order(self) -> None:
        token = self.account.get("token") or ""
        try:
            result = self.runtime.api.order_detail(token, self.current_order_no or "")
            if result.get("code") != 0:
                if is_auth_failure(result.get("code"), result.get("message", "")):
                    self.state = "blocked"
                    self.status = "账号登录失效"
                    self.runtime.emit({"type": "intent", "goodsId": self.intent_key, "deviceName": self.device_name, "state": "blocked"})
                    self.stop_event.set()
                    return
                self.status = result.get("message") or "订单查询失败"
                self.sleep(POLL_HELD_SECONDS)
                return

            order = stabilize_order_timing(
                summarize_detail(result.get("data") or {}, self.account_phone or ""),
                self.current_order,
            )
            order["ownerId"] = self.owner_id
            with self.runtime.lock:
                self.runtime.orders_by_no[order["orderNo"]] = order
            self.current_order = order
            self.current_order_no = order["orderNo"]

            if order["stage"] == "running":
                if hasattr(self.runtime, "_check_pickup_transitions"):
                    try:
                        self.runtime._check_pickup_transitions(order)
                    except Exception:
                        pass
                self.state = "fulfilled"
                self.status = "已支付，设备进行中（洗完前将自动提醒收衣）"
                self.runtime.log(f"{self.device_name} 已进入进行中，转入后台收衣提醒")
                self.runtime.emit({"type": "order", "goodsId": self.goods_id, "deviceName": self.device_name, "orderNo": self.current_order_no, "stage": "running"})
                self.stop_event.set()
                return
            if order["stage"] == "ended":
                self.runtime.log(f"{self.device_name} 订单 {self.current_order_no} 已结束，继续抢占")
                self.runtime.emit({"type": "order", "goodsId": self.goods_id, "deviceName": self.device_name, "orderNo": self.current_order_no, "stage": "ended"})
                self.current_order = None
                self.current_order_no = None
                self._reopen_pool_if_applicable()
                self.state = "acquiring"
                self.sleep(0.5)
                return
            if order["stage"] == "awaiting_payment":
                self.state = "holding_payment"
                remaining = payment_remaining_seconds(order, now_ts=self._ref_now())
                if remaining is not None and remaining <= 0:
                    self.runtime.log(f"{self.device_name} 待支付单已到期，继续抢占")
                    self.runtime.emit({"type": "order", "goodsId": self.goods_id, "deviceName": self.device_name, "orderNo": self.current_order_no, "stage": "expired"})
                    self.runtime.mark_order_expired_locally(self.current_order_no or "")
                    self.current_order = None
                    self.current_order_no = None
                    self._reopen_pool_if_applicable()
                    self.state = "acquiring"
                    return
                if remaining is not None and remaining <= RENEW_BEFORE_SECONDS:
                    self.state = "renewing"
                    self.status = f"临期续抢准备中，剩余 {int(max(0, remaining))}s"
                    self.sleep(0.5)
                    return
                self.status = f"待支付维护中，剩余 {int(remaining)}s" if remaining is not None else "待支付维护中"
                self.sleep(POLL_HELD_SECONDS)
                return

            if order.get("pageCode") == "appointment_waiting" and not order.get("readyForUserAction"):
                try:
                    raw_list = self.runtime._fetch_raw_devices(max_age=0)
                    if isinstance(raw_list, list):
                        live_dev = next((it for it in raw_list if isinstance(it, dict) and it.get("id") == self.goods_id), None)
                        if live_dev and live_dev.get("state") == 1:
                            self.idle_while_reserved_ticks += 1
                            if self.idle_while_reserved_ticks >= 2:
                                self.runtime.log(f"{self.device_name} 设备已空闲但预约单仍未生效，自动取消旧预约改抢即时单")
                                self.runtime.cancel_order(self.account_phone or "", self.current_order_no or "", self.owner_id)
                                self.current_order = None
                                self.current_order_no = None
                                self.state = "acquiring"
                                self.idle_while_reserved_ticks = 0
                                self.sleep(0.3)
                                return
                        else:
                            self.idle_while_reserved_ticks = 0
                except Exception:
                    pass
            else:
                self.idle_while_reserved_ticks = 0

            self.state = "holding_reserve"
            if order.get("readyForUserAction"):
                self.status = f"{order['stageLabel']}，请去小程序/App操作"
            elif order.get("pageCode") == "self_clean":
                self.status = "筒自洁进行中，请稍候"
            else:
                self.status = "已排上预约，等待前序结束"
            self.sleep(POLL_RESERVE_SECONDS)
        except Exception as exc:
            self.last_error = str(exc)
            self.status = "维护检查异常，继续保持意图"
            self.sleep_after_error(POLL_HELD_SECONDS)


def device_user_labels(
    raw: dict[str, Any],
    state: Any,
    active_order: Optional[dict[str, Any]],
    visible_intent: Optional[Any] = None,
) -> tuple[str, str]:
    """结合当前用户的订单与意图返回不产生歧义的设备状态标签与排队文案。"""
    order_stage = (active_order or {}).get("stage")
    if order_stage == "awaiting_payment":
        return ("已锁单，待支付", "我已锁单")
    if order_stage == "reserve_waiting":
        if order_ready_for_user_action(active_order):
            return ("已到号，请验证/支付", "已轮到我")
        return ("前序运行中 · 我已预约", "我排第 1 位")
    if order_stage == "running":
        reservable = bool(raw.get("enableReserve")) and raw.get("reserveState") == 1
        return ("我的订单运行中", "0 人排队" if reservable else "后序 1 人已约")

    if state.key == "idle":
        return (state.label, "无需排队")
    if state.key == "busy_reservable":
        return ("运行中，可预约", "0 人排队")
    if state.key == "busy_full":
        if visible_intent and getattr(visible_intent, "state", "") == "holding_reserve":
            return ("前序运行中 · 我已预约", "我排第 1 位")
        return ("运行中，1人已约", "1 人排队")
    if state.key == "payment_pending":
        return (state.label, "等待支付释放")
    if state.key == "fault":
        return (state.label, "暂停服务")
    return (state.label, "状态未知")


def normalize_owner_id(owner_id: Any) -> str:
    value = str(owner_id or "default").strip()
    return value or "default"


def normalize_dryer_minutes(value: Any) -> Optional[int]:
    if value is None or value == "":
        return None
    try:
        minutes = int(value)
    except (TypeError, ValueError):
        return None
    return min(120, max(1, minutes))


def device_type_from_name(name: Any) -> str:
    text = str(name or "")
    return "dryer" if "烘干" in text else "washer"


def recommendation_wait_text(device: dict[str, Any]) -> str:
    seconds = device.get("remainingSeconds")
    try:
        seconds_value = None if seconds is None else max(0.0, float(seconds))
    except (TypeError, ValueError):
        seconds_value = None
    if seconds_value is None:
        finish_time = device.get("finishTime")
        return f"预计 {finish_time} 释放" if finish_time else ""
    if seconds_value <= 0:
        return "预计很快释放"
    if seconds_value < 60:
        return "预计 1 分钟内释放"
    minutes = int((seconds_value + 59) // 60)
    return f"预计 {minutes} 分钟后释放"


def score_device_for_recommendation(device: dict[str, Any]) -> int:
    state = str(device.get("state") or "")
    score = {
        "idle": 1000,
        "busy_reservable": 760,
        "busy_full": 420,
        "payment_pending": 80,
        "unknown": 20,
        "fault": 10,
    }.get(state, 0)

    if device.get("canOrder"):
        score += 40
    if device.get("orderKind") == "instant":
        score += 80
    elif device.get("orderKind") == "reserve":
        score += 30

    queue_count = device.get("queueCount")
    if isinstance(queue_count, int):
        score -= queue_count * 60

    remaining_seconds = device.get("remainingSeconds")
    try:
        if remaining_seconds is not None:
            score -= min(int(max(float(remaining_seconds), 0.0) // 60), 240)
    except (TypeError, ValueError):
        pass

    active_order = device.get("activeOrder") or {}
    if active_order.get("stage") in {"awaiting_payment", "reserve_waiting"}:
        score -= 520
    elif active_order.get("stage") == "running":
        score -= 180

    if device.get("occupiedByOtherUser"):
        score -= 640
    return score


def recommendation_reason(device: dict[str, Any]) -> str:
    if device.get("occupiedByOtherUser"):
        label = device.get("occupiedByOtherUserLabel") or "其他用户"
        return f"{label} 正在抢占"

    active_order = device.get("activeOrder") or {}
    if active_order.get("stage") == "reserve_waiting":
        return "我已排上预约，等待前序结束"

    state = str(device.get("state") or "")
    if state == "idle":
        return "当前空闲，可立即下单"
    if state == "busy_reservable":
        wait_text = recommendation_wait_text(device)
        return f"当前可预约，{wait_text}" if wait_text else "当前可预约"
    if state == "busy_full":
        wait_text = recommendation_wait_text(device)
        return f"当前 1 人已约，轮询抢占，{wait_text}" if wait_text else "当前 1 人已约，轮询抢占"
    if state == "payment_pending":
        return "当前有待支付订单，先等待释放"
    if state == "fault":
        return "设备故障或离线，暂不可用"
    return str(device.get("stateLabel") or "状态未知")


def recommended_tone(device: dict[str, Any], rank: int, positive_cutoff: int) -> str:
    if device.get("occupiedByOtherUser") or device.get("state") in {"payment_pending", "fault"}:
        return "gray"
    if rank == 1 and positive_cutoff >= 1:
        return "green"
    if rank <= positive_cutoff:
        return "yellow"
    if device.get("canOrder"):
        return "gray"
    return "red"


def decorate_device_recommendations(devices: list[dict[str, Any]]) -> None:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for device in devices:
        device_type = device.get("deviceType") or device_type_from_name(device.get("name"))
        device["deviceType"] = device_type
        device["deviceTypeLabel"] = DEVICE_TYPE_LABELS.get(device_type, "设备")
        device["recommendedScore"] = score_device_for_recommendation(device)
        device["recommendedReason"] = recommendation_reason(device)
        grouped.setdefault(device_type, []).append(device)

    for items in grouped.values():
        ranked = sorted(items, key=lambda item: (-int(item.get("recommendedScore") or 0), item.get("name") or ""))
        positive_cutoff = min(2, sum(1 for item in ranked if int(item.get("recommendedScore") or 0) > 0))
        for rank, device in enumerate(ranked, start=1):
            device["recommendedRank"] = rank
            device["isRecommended"] = positive_cutoff > 0 and rank <= positive_cutoff
            device["recommendedTone"] = recommended_tone(device, rank, positive_cutoff)


def build_recommendation_summary(type_label: str, items: list[dict[str, Any]]) -> str:
    if not items:
        return ""
    top = items[0]
    backups = [item for item in items[1:] if item.get("isRecommended")]
    if top.get("occupiedByOtherUser") or top.get("state") == "payment_pending":
        return f"{type_label} 暂无可直接抢占设备，先盯 {top.get('name')}"
    if backups:
        return f"优先 {top.get('name')}，其次 {backups[0].get('name')}"
    return f"优先 {top.get('name')}"


def build_recommendation_groups(devices: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    groups: dict[str, dict[str, Any]] = {}
    device_types = [device.get("deviceType") or "washer" for device in devices]
    for device_type in sorted(set(device_types), key=lambda item: (item != "washer", item)):
        label = DEVICE_TYPE_LABELS.get(device_type, "设备")
        items = sorted(
            [device for device in devices if (device.get("deviceType") or "washer") == device_type],
            key=lambda item: (int(item.get("recommendedRank") or 999), item.get("name") or ""),
        )
        groups[device_type] = {
            "deviceType": device_type,
            "label": label,
            "bestGoodsId": items[0].get("goodsId") if items else None,
            "summary": build_recommendation_summary(label, items),
            "items": [
                {
                    "goodsId": item.get("goodsId"),
                    "name": item.get("name"),
                    "rank": item.get("recommendedRank"),
                    "score": item.get("recommendedScore"),
                    "reason": item.get("recommendedReason"),
                    "tone": item.get("recommendedTone"),
                    "occupiedByOtherUser": item.get("occupiedByOtherUser"),
                    "occupiedByOtherUserLabel": item.get("occupiedByOtherUserLabel"),
                }
                for item in items
            ],
        }
    return groups


def same_order(left: Optional[dict[str, Any]], right: Optional[dict[str, Any]]) -> bool:
    return bool(left and right and left.get("orderNo") and left.get("orderNo") == right.get("orderNo"))


def missing_value(value: Any) -> bool:
    return value is None or value == ""


def stabilize_order_timing(order: Optional[dict[str, Any]], previous: Optional[dict[str, Any]] = None) -> Optional[dict[str, Any]]:
    if not order:
        return order
    stable = dict(order)
    if same_order(stable, previous):
        for key in ORDER_TIMING_KEYS:
            if missing_value(stable.get(key)) and not missing_value(previous.get(key)):
                stable[key] = previous[key]
        if not stable.get("selfClean") and previous.get("selfClean"):
            stable["selfClean"] = True

    if stable.get("stage") == "awaiting_payment" and not stable.get("deadline"):
        if not stable.get("expireSeconds"):
            stable["expireSeconds"] = PAYMENT_EXPIRE_FALLBACK_SECONDS
        if not stable.get("createdAtLocal"):
            stable["createdAtLocal"] = time.time()
    return stable


def payment_remaining_seconds(order: dict[str, Any], now_ts: Optional[float] = None) -> Optional[float]:
    remaining = seconds_until(order.get("deadline"), now_ts=now_ts)
    if remaining is not None:
        return remaining
    if order.get("expireSeconds") and order.get("createdAtLocal"):
        try:
            return float(order["expireSeconds"]) - (time.time() - float(order["createdAtLocal"]))
        except (TypeError, ValueError):
            return None
    return None
