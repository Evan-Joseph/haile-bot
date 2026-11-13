from __future__ import annotations

import hashlib
import re
import socket
import threading
import time
from typing import Any, Callable, Optional
from urllib.parse import quote, urlparse

import requests
from requests.adapters import HTTPAdapter

import haile_bot
from .domain import (
    ClockOffsetTracker,
    DEFAULT_CAMPUS_COORDS,
    enrich_order,
    search_campus_presets,
    seconds_until,
    wgs84_to_gcj02,
)

UPSTREAM_HOST = urlparse(haile_bot.API_HOST).hostname or "yshz-user.haier-ioc.com"
UPSTREAM_FALLBACK_IP = "47.93.94.247"
DOH_RESOLVE_URL = f"https://223.5.5.5/resolve?name={UPSTREAM_HOST}&type=A"
DNS_CACHE_TTL_SECONDS = 600.0
_IPV4_RE = re.compile(r"^\d{1,3}(?:\.\d{1,3}){3}$")
_dns_lock = threading.Lock()
_dns_cached_ip: str = ""
_dns_cached_at: float = 0.0
_orig_getaddrinfo = socket.getaddrinfo


def resolve_upstream_ip() -> str:
    """优先走阿里公共 DoH（直连 IP 免系统 DNS 阻塞），失败回退已知云盾 WAF 节点 IP。"""
    global _dns_cached_ip, _dns_cached_at
    now = time.time()
    if _dns_cached_ip and now - _dns_cached_at < DNS_CACHE_TTL_SECONDS:
        return _dns_cached_ip
    with _dns_lock:
        now = time.time()
        if _dns_cached_ip and now - _dns_cached_at < DNS_CACHE_TTL_SECONDS:
            return _dns_cached_ip
        resolved = ""
        session = requests.Session()
        session.trust_env = False
        try:
            resp = session.get(DOH_RESOLVE_URL, timeout=1.5)
            if resp.status_code == 200:
                for ans in (resp.json().get("Answer") or []):
                    data = str(ans.get("data") or "").strip()
                    if ans.get("type") == 1 and _IPV4_RE.match(data):
                        resolved = data
                        break
        except Exception:
            pass
        finally:
            session.close()
        _dns_cached_ip = resolved or _dns_cached_ip or UPSTREAM_FALLBACK_IP
        _dns_cached_at = time.time()
        return _dns_cached_ip


def _patched_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    if host == UPSTREAM_HOST:
        host = resolve_upstream_ip()
    return _orig_getaddrinfo(host, port, family, type, proto, flags)


socket.getaddrinfo = _patched_getaddrinfo


def retry_on_network_error(max_retries: int = 2, delay: float = 0.5):
    """仅用于幂等读接口的重试装饰器；写接口禁止走这里，防止超时重放产生重复单。"""

    def decorator(func: Callable[..., dict[str, Any]]) -> Callable[..., dict[str, Any]]:
        def wrapper(*args, **kwargs) -> dict[str, Any]:
            last_error = None
            for attempt in range(max_retries + 1):
                try:
                    return func(*args, **kwargs)
                except requests.exceptions.RequestException as exc:
                    last_error = exc
                    if attempt < max_retries:
                        time.sleep(delay * (2 ** attempt))
                except ValueError as exc:
                    last_error = exc
                    break
            return {"code": -1, "message": f"网络请求失败: {str(last_error)[:100]}" if last_error else "网络请求失败"}
        return wrapper
    return decorator


def network_error_result(exc: BaseException) -> dict[str, Any]:
    return {"code": -1, "message": f"网络请求失败: {str(exc)[:100]}"}


class HaileApi:
    def __init__(self, timeout: float = 5.0):
        self.timeout = timeout
        self.session = self._new_session()
        self._account_sessions: dict[str, requests.Session] = {}
        self._sessions_lock = threading.Lock()
        self.clock = ClockOffsetTracker()

    def server_now(self) -> float:
        return self.clock.now()

    @staticmethod
    def _new_session() -> requests.Session:
        session = requests.Session()
        session.trust_env = False
        adapter = HTTPAdapter(pool_connections=4, pool_maxsize=8)
        session.mount("https://", adapter)
        session.mount("http://", adapter)
        return session

    def session_for(self, token: Optional[str]) -> requests.Session:
        """按账号 token 隔离连接池：不同账号走独立 TCP/TLS 会话，互不复用连接指纹。"""
        if not token:
            return self.session
        key = hashlib.sha1(token.encode("utf-8")).hexdigest()[:16]
        with self._sessions_lock:
            session = self._account_sessions.get(key)
            if session is None:
                session = self._new_session()
                self._account_sessions[key] = session
            return session

    def headers(self, token: Optional[str] = None) -> dict[str, str]:
        headers = haile_bot.HEADERS.copy()
        if token:
            headers["Authorization"] = token
        return headers

    def _observe_clock(self, resp: Any, rtt_seconds: float = 0.0) -> None:
        try:
            headers = getattr(resp, "headers", None)
            if headers and hasattr(headers, "get"):
                date_hdr = headers.get("Date") or headers.get("date")
                if date_hdr:
                    self.clock.observe_http_date(str(date_hdr), rtt_seconds=rtt_seconds)
        except Exception:
            pass

    @staticmethod
    def _parse_json(resp: requests.Response) -> dict[str, Any]:
        status = getattr(resp, "status_code", 200) or 200
        if status == 401:
            return {"code": 2, "message": "登录已失效"}
        if not 200 <= status < 300:
            return {"code": -3, "message": f"上游 HTTP {status}"}
        try:
            return resp.json()
        except ValueError:
            text = resp.text.strip()[:200]
            return {"code": -2, "message": f"平台返回非 JSON 响应: {text}" if text else "平台返回空响应"}

    def _post_once(self, path: str, body: dict[str, Any], token: Optional[str] = None, timeout: Optional[float] = None) -> dict[str, Any]:
        """单次写请求：不自动重试，网络异常直接抛出，由调用方决定如何兜底。"""
        t0 = time.time()
        resp = self.session_for(token).post(
            f"{haile_bot.API_HOST}{path}",
            headers=self.headers(token),
            json=body,
            timeout=timeout or self.timeout,
        )
        self._observe_clock(resp, time.time() - t0)
        return self._parse_json(resp)

    @retry_on_network_error(max_retries=2, delay=0.3)
    def post(self, path: str, body: dict[str, Any], token: Optional[str] = None, timeout: Optional[float] = None) -> dict[str, Any]:
        return self._post_once(path, body, token=token, timeout=timeout)

    @retry_on_network_error(max_retries=2, delay=0.3)
    def get(self, path: str, token: Optional[str] = None, timeout: Optional[float] = None) -> dict[str, Any]:
        t0 = time.time()
        resp = self.session_for(token).get(
            f"{haile_bot.API_HOST}{path}",
            headers=self.headers(token),
            timeout=timeout or self.timeout,
        )
        self._observe_clock(resp, time.time() - t0)
        return self._parse_json(resp)

    def _guarded_write(self, path: str, body: dict[str, Any], token: str, goods_id: int, expect: str) -> dict[str, Any]:
        """写操作单次发送；网络异常后先反查 underway 订单，已生成则直接接管，绝不重放。"""
        try:
            return self._post_once(path, body, token=token)
        except requests.exceptions.RequestException as exc:
            recovered = self.recover_pending_order(token, goods_id, expect)
            if recovered is not None:
                return recovered
            result = network_error_result(exc)
            result["ambiguous"] = True
            return result

    def recover_pending_order(self, token: str, goods_id: int, expect: str) -> Optional[dict[str, Any]]:
        """写超时反查：优先查 underway 列表，即时单额外反查 trade/list 首屏待支付订单，绝不盲目重放。"""
        ref_now = self.server_now()
        try:
            underway = self.underway_orders(token)
        except requests.exceptions.RequestException:
            underway = {}
        if underway.get("code") == 0:
            for item in underway.get("data") or []:
                first_item = (item.get("orderItemList") or [{}])[0]
                if first_item.get("goodsId") != goods_id:
                    continue
                state = item.get("state")
                order_type = item.get("orderType")
                if expect == "instant" and state != 100:
                    continue
                if expect == "reserve" and not (state == 50 or order_type == 300):
                    continue
                order_no = item.get("orderNo")
                if not order_no:
                    continue
                data: dict[str, Any] = {"orderNo": order_no}
                remaining = seconds_until(item.get("deadTime") or item.get("invalidTime"), now_ts=ref_now)
                if remaining and remaining > 0:
                    data["expireSeconds"] = int(remaining)
                return {"code": 0, "data": data, "recovered": True}

        if expect == "instant":
            try:
                recent = self.trade_list(token, page_size=5)
            except requests.exceptions.RequestException:
                recent = {}
            if recent.get("code") == 0:
                raw_data = recent.get("data")
                items = raw_data.get("items") if isinstance(raw_data, dict) else raw_data
                for item in items or []:
                    if not isinstance(item, dict) or item.get("state") != 100:
                        continue
                    first_item = (item.get("orderItemList") or [{}])[0]
                    if first_item.get("goodsId") != goods_id:
                        continue
                    order_no = item.get("orderNo")
                    if not order_no:
                        continue
                    deadline_str = item.get("deadTime") or item.get("invalidTime")
                    remaining = seconds_until(deadline_str, now_ts=ref_now)
                    if deadline_str and remaining is not None and remaining <= 0:
                        continue
                    data = {"orderNo": order_no}
                    if remaining and remaining > 0:
                        data["expireSeconds"] = int(remaining)
                    return {"code": 0, "data": data, "recovered": True}
        return None

    def send_code(self, phone: str) -> dict[str, Any]:
        return self.post("/login/getCode", {"target": phone, "sendType": 1, "method": 1})

    def login(self, phone: str, code: str) -> dict[str, Any]:
        return self.post(
            "/login/login",
            {
                "account": phone,
                "loginType": 2,
                "verificationCode": code,
                "authorizationClientType": "9",
            },
        )

    def account_info(self, token: str) -> dict[str, Any]:
        return self.get("/account/getAccountInfo", token=token)

    def devices(self, position_id: Optional[int] = None) -> list[dict[str, Any]]:
        pid = int(position_id) if position_id else haile_bot.POSITION_ID
        data = self.post("/position/deviceDetailPage", {"positionId": pid, "page": 1, "pageSize": 50})
        if data.get("code") != 0:
            raise RuntimeError(data.get("message") or "设备列表获取失败")
        return data.get("data", {}).get("items", [])

    @staticmethod
    def format_position_item(pos: dict[str, Any]) -> Optional[dict[str, Any]]:
        if not isinstance(pos, dict):
            return None
        raw_id = pos.get("id") if pos.get("id") is not None else pos.get("positionId")
        if raw_id is None:
            return None
        try:
            pid = int(raw_id)
        except (TypeError, ValueError):
            return None
        if pid <= 0:
            return None
        name = str(pos.get("name") or pos.get("positionName") or f"点位 {pid}").replace("\n", " ").strip()
        address = str(pos.get("address") or pos.get("shopAddress") or "").replace("\n", " ").strip()
        dist_raw = pos.get("distance")
        distance: Optional[int] = None
        if isinstance(dist_raw, (int, float)) and dist_raw >= 0:
            distance = int(round(float(dist_raw)))
        lng_val: Optional[float] = None
        lat_val: Optional[float] = None
        try:
            if pos.get("lng") is not None and pos.get("lat") is not None:
                lng_val = round(float(pos["lng"]), 6)
                lat_val = round(float(pos["lat"]), 6)
        except (TypeError, ValueError):
            pass

        idle_washer = None
        total_washer = None
        idle_dryer = None
        total_dryer = None
        dev_stats = pos.get("positionDeviceDetailList")
        if isinstance(dev_stats, list):
            for st in dev_stats:
                if not isinstance(st, dict):
                    continue
                cat = str(st.get("categoryCode") or "")
                cat_name = str(st.get("categoryName") or "")
                if cat in {"00", "01"} or "洗" in cat_name:
                    idle_washer = (idle_washer or 0) + int(st.get("idleCount") or 0)
                    total_washer = (total_washer or 0) + int(st.get("total") or 0)
                elif cat in {"02", "03", "13"} or "烘" in cat_name:
                    idle_dryer = (idle_dryer or 0) + int(st.get("idleCount") or 0)
                    total_dryer = (total_dryer or 0) + int(st.get("total") or 0)

        out: dict[str, Any] = {
            "positionId": pid,
            "shopId": pos.get("shopId"),
            "name": name or f"点位 {pid}",
            "address": address,
            "state": pos.get("state", 1),
        }
        if distance is not None:
            out["distance"] = distance
        if lng_val is not None and lat_val is not None:
            out["lng"] = lng_val
            out["lat"] = lat_val
        if total_washer is not None:
            out["idleWasherCount"] = idle_washer or 0
            out["totalWasherCount"] = total_washer
        elif isinstance(pos.get("idleCount"), int) and pos.get("idleCount") >= 0:
            out["idleCount"] = int(pos["idleCount"])
        if total_dryer is not None:
            out["idleDryerCount"] = idle_dryer or 0
            out["totalDryerCount"] = total_dryer
        return out

    def position_detail(
        self,
        position_id: int,
        lng: Optional[float] = None,
        lat: Optional[float] = None,
    ) -> Optional[dict[str, Any]]:
        qs = [f"id={int(position_id)}"]
        if lng is not None and lat is not None:
            qs.append(f"lng={float(lng)}")
            qs.append(f"lat={float(lat)}")
        res = self.get(f"/position/positionDetail?{'&'.join(qs)}")
        if res.get("code") != 0 or not isinstance(res.get("data"), dict):
            return None
        return self.format_position_item(res["data"])

    def nearby_positions(
        self,
        lng: Optional[float] = None,
        lat: Optional[float] = None,
        coord_type: str = "gcj02",
        keyword: str = "",
    ) -> list[dict[str, Any]]:
        has_explicit_coords = lng is not None and lat is not None
        if has_explicit_coords:
            q_lng, q_lat = float(lng), float(lat)  # type: ignore[arg-type]
            if str(coord_type or "").lower() == "wgs84":
                q_lng, q_lat = wgs84_to_gcj02(q_lng, q_lat)
            coords = [{"lng": q_lng, "lat": q_lat}]
        else:
            coords = list(DEFAULT_CAMPUS_COORDS)

        by_id: dict[int, dict[str, Any]] = {}
        for item in coords:
            res = self.post(
                "/position/nearPosition",
                {"lng": item["lng"], "lat": item["lat"], "page": 1, "pageSize": 50},
            )
            if res.get("code") != 0:
                continue
            raw_list = (res.get("data") or {}).get("items") or res.get("data") or []
            if not isinstance(raw_list, list):
                continue
            for pos in raw_list:
                formatted = self.format_position_item(pos)
                if not formatted:
                    continue
                by_id.setdefault(formatted["positionId"], formatted)

        items = list(by_id.values())
        kw = str(keyword or "").strip().lower()
        if kw:
            items = [
                p for p in items
                if kw in p.get("name", "").lower()
                or kw in p.get("address", "").lower()
                or kw == str(p.get("positionId"))
            ]
        if has_explicit_coords:
            items.sort(key=lambda p: (p.get("distance") if p.get("distance") is not None else 10**9, p["name"]))
        else:
            items.sort(key=lambda p: (p.get("distance") if p.get("distance") is not None else 10**9, p["name"]))
        return items

    def goods_detail(self, goods_id: int) -> dict[str, Any]:
        data = self.get(f"/goods/normal/details?goodsId={int(goods_id)}")
        if data.get("code") != 0 or not isinstance(data.get("data"), dict):
            return {}
        return data["data"]

    def modes(self, goods_id: int) -> list[dict[str, Any]]:
        data = self.get(f"/goods/normal/details?goodsId={goods_id}")
        if data.get("code") != 0:
            raise RuntimeError(data.get("message") or "套餐获取失败")
        return data.get("data", {}).get("items", [])

    def use_position_list(
        self,
        token: str,
        lng: float = 120.4250,
        lat: float = 36.0716,
        page_size: int = 12,
    ) -> list[dict[str, Any]]:
        res = self.post(
            "/position/usePositionList",
            {"lng": float(lng), "lat": float(lat), "page": 1, "pageSize": int(page_size)},
            token=token,
        )
        if res.get("code") != 0:
            return []
        raw_list = (res.get("data") or {}).get("items") or res.get("data") or []
        if not isinstance(raw_list, list):
            return []
        out: list[dict[str, Any]] = []
        for item in raw_list:
            formatted = self.format_position_item(item)
            if formatted:
                out.append(formatted)
        return out

    def goods_use_list(self, token: str, page_size: int = 15) -> list[dict[str, Any]]:
        res = self.post(
            "/goods/goodsUseList",
            {"page": 1, "pageSize": int(page_size), "categoryCodeList": []},
            token=token,
        )
        if res.get("code") != 0:
            return []
        raw_list = (res.get("data") or {}).get("items") or res.get("data") or []
        return [it for it in raw_list if isinstance(it, dict)] if isinstance(raw_list, list) else []

    def scan_goods(self, token: Optional[str], imei: str = "", n_code: str = "") -> dict[str, Any]:
        qs: list[str] = []
        if imei:
            qs.append(f"imei={quote(str(imei).strip(), safe='')}")
        if n_code:
            qs.append(f"n={quote(str(n_code).strip(), safe='')}")
        if not qs:
            return {"code": -1, "message": "缺少设备标识"}
        return self.get(f"/goods/scan?{'&'.join(qs)}", token=token)

    def position_choose(
        self,
        token: str,
        name: str = "",
        lng: float = 120.4250,
        lat: float = 36.0716,
        page_size: int = 25,
    ) -> list[dict[str, Any]]:
        res = self.post(
            "/position/positionChoose",
            {"page": 1, "pageSize": int(page_size), "lng": float(lng), "lat": float(lat), "name": str(name or "").strip()},
            token=token,
        )
        if res.get("code") != 0:
            return []
        raw_data = res.get("data")
        items = raw_data.get("items") if isinstance(raw_data, dict) else raw_data
        return [it for it in (items or []) if isinstance(it, dict)] if isinstance(items, list) else []

    def position_list_by_shop(self, token: str, shop_id: Any) -> list[dict[str, Any]]:
        res = self.get(f"/position/getPositionList?shopId={quote(str(shop_id), safe='')}", token=token)
        if res.get("code") != 0:
            return []
        raw_data = res.get("data")
        items = raw_data.get("items") if isinstance(raw_data, dict) else raw_data
        if not isinstance(items, list):
            return []
        out: list[dict[str, Any]] = []
        for item in items:
            formatted = self.format_position_item(item)
            if formatted:
                out.append(formatted)
        return out

    def geocode_places(self, query: str, limit: int = 6) -> list[dict[str, Any]]:
        q = str(query or "").strip()
        if not q:
            return search_campus_presets("", limit=limit)
        places = search_campus_presets(q, limit=limit)
        if len(places) >= limit:
            return places[:limit]
        try:
            resp = self.session.get(
                f"https://geocoding-api.open-meteo.com/v1/search?name={quote(q, safe='')}&language=zh&count=6",
                headers={"User-Agent": "HaileBot/4.2"},
                timeout=2.5,
            )
            if resp.status_code == 200:
                for item in (resp.json().get("results") or []):
                    if not isinstance(item, dict):
                        continue
                    if item.get("country_code") and item.get("country_code") not in {"CN", "HK", "MO", "TW"}:
                        continue
                    raw_lng = item.get("longitude")
                    raw_lat = item.get("latitude")
                    if raw_lng is None or raw_lat is None:
                        continue
                    gcj_lng, gcj_lat = wgs84_to_gcj02(float(raw_lng), float(raw_lat))
                    admin = " · ".join(
                        str(x).strip()
                        for x in (item.get("admin1"), item.get("admin2"))
                        if x and str(x).strip()
                    )
                    places.append({
                        "name": str(item.get("name") or q).strip(),
                        "subtitle": admin or "全国地点检索",
                        "lng": gcj_lng,
                        "lat": gcj_lat,
                    })
                    if len(places) >= limit:
                        break
        except Exception:
            pass
        return places[:limit]

    def create_order(
        self,
        token: str,
        device_name: str,
        goods_id: int,
        mode_id: int,
        dryer_minutes: Optional[int] = None,
        self_clean_item_id: Optional[int] = None,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {
            "purchaseList": [haile_bot.build_device_purchase_item(device_name, goods_id, mode_id, dryer_minutes=dryer_minutes)],
        }
        if self_clean_item_id:
            body["optionalInfo"] = {
                "additionSelectInfo": [haile_bot.build_purchase_item(goods_id, int(self_clean_item_id))],
            }
        return self._guarded_write(
            "/trade/create",
            body,
            token,
            goods_id,
            "instant",
        )

    def reserve_order(
        self,
        token: str,
        device_name: str,
        goods_id: int,
        mode_id: int,
        dryer_minutes: Optional[int] = None,
        self_clean_item_id: Optional[int] = None,
    ) -> dict[str, Any]:
        addition = (
            [haile_bot.build_purchase_item(goods_id, int(self_clean_item_id))]
            if self_clean_item_id
            else []
        )
        return self._guarded_write(
            "/trade/reserveCreate",
            {
                "purchaseList": [haile_bot.build_device_purchase_item(device_name, goods_id, mode_id, dryer_minutes=dryer_minutes)],
                "optionalInfo": {"additionSelectInfo": addition},
                "reserveMethod": 1,
            },
            token,
            goods_id,
            "reserve",
        )

    def order_detail(self, token: str, order_no: str) -> dict[str, Any]:
        return self.get(f"/trade/order/detail?orderNo={order_no}", token=token)

    def cancel_order(self, token: str, order_no: str) -> dict[str, Any]:
        return self.post("/trade/cancel", {"orderNo": order_no}, token=token)

    def verify_order_code(self, token: str, order_no: str, check_code: str) -> dict[str, Any]:
        return self._post_once("/trade/check/verify", {"orderNo": order_no, "checkCode": check_code}, token=token)

    def resend_verify_code(self, token: str, order_no: str) -> dict[str, Any]:
        return self._post_once("/trade/check/reSendCode", {"orderNo": order_no}, token=token)

    def remind_pickup_clothes(self, token: str, order_no: str) -> dict[str, Any]:
        return self._post_once("/message/tokeClothes/sendMessage", {"orderNo": order_no}, token=token)

    def place_clothes_start(self, token: str, order_no: str) -> dict[str, Any]:
        return self._post_once("/device/placeClothes", {"orderNo": order_no}, token=token)

    def underway_orders(self, token: str) -> dict[str, Any]:
        return self.post("/trade/underway/orderList", {}, token=token)

    def trade_list(self, token: str, page_size: int = 20) -> dict[str, Any]:
        return self.post("/trade/list", {"page": 1, "pageSize": page_size, "newOrderState": "", "categoryCode": ""}, token=token)

    def run_info(self, goods_id: int) -> dict[str, Any]:
        return self.post("/goods/last/runInfo", {"goodsId": goods_id})


def parse_project_name(value: Any) -> tuple[str, Optional[str]]:
    text = str(value or "").strip()
    if "【" in text and "】" in text:
        device, rest = text.split("【", 1)
        return device.strip(), rest.split("】", 1)[0].strip() or None
    return text, None


def item_names(item: dict[str, Any]) -> tuple[str, Optional[str]]:
    first_item = (item.get("orderItemList") or [{}])[0]
    project_name = item.get("projectName")
    if project_name:
        device_name, mode_name = parse_project_name(project_name)
        return project_name, mode_name
    return haile_bot.extract_project_name(first_item), first_item.get("goodsItemName")


def account_snapshot(acc: dict[str, Any]) -> dict[str, Any]:
    token = acc.get("token") or ""
    expiry = haile_bot.get_token_expiry(token)
    remark = str(acc.get("remark") or "").strip()
    phone = acc.get("phone")
    return {
        "phone": phone,
        "displayName": remark or phone,
        "remark": remark,
        "ownerId": acc.get("owner_id") or "default",
        "ownerName": acc.get("owner_name") or "",
        "enabled": bool(acc.get("is_active", True)),
        "tokenExpiresAt": haile_bot.format_timestamp(expiry) if expiry else None,
        "tokenExpired": haile_bot.is_token_expired(token) if token else True,
    }


def infer_order_self_clean(payload: dict[str, Any]) -> bool:
    if payload.get("selfClean") is True or payload.get("pageCode") == "self_clean":
        return True
    unique_info = payload.get("uniqueInfo")
    if isinstance(unique_info, dict):
        sc_info = unique_info.get("selfCleanInfo")
        if isinstance(sc_info, dict) and bool(sc_info.get("selfClean")):
            return True
    items = payload.get("orderItemList")
    if isinstance(items, list) and len(items) > 1:
        for item in items[1:]:
            if isinstance(item, dict) and (item.get("selfClean") or "自洁" in str(item.get("goodsItemName") or "")):
                return True
    return False


def summarize_detail(detail: dict[str, Any], account_phone: str) -> dict[str, Any]:
    first_item = (detail.get("orderItemList") or [{}])[0]
    buttons = detail.get("buttonSwitch") or {}
    reserve_info = detail.get("reserveInfo") or {}
    check_info = detail.get("checkInfo") or {}
    goods_name = haile_bot.extract_project_name(first_item)
    return enrich_order({
        "orderNo": detail.get("orderNo", ""),
        "accountPhone": account_phone,
        "goodsName": goods_name,
        "modeName": first_item.get("goodsItemName"),
        "selfClean": infer_order_self_clean(detail),
        "state": detail.get("state"),
        "stateDesc": detail.get("stateDesc") or "",
        "pageCode": detail.get("pageCode"),
        "orderType": detail.get("orderType"),
        "goodsId": first_item.get("goodsId"),
        "canPay": bool(buttons.get("canPay")),
        "canCancel": bool(buttons.get("canCancel")),
        "deadline": detail.get("invalidTime") or check_info.get("invalidTime") or reserve_info.get("estimateEffectiveTime") or "",
        "estimatedFinishTime": first_item.get("finishTime") or detail.get("finishTime") or "",
        "amount": detail.get("originPrice") or detail.get("realPrice") or detail.get("payAmount") or "",
        "updatedAt": time.time(),
    })


def summarize_list_item(item: dict[str, Any], account_phone: str) -> dict[str, Any]:
    first_item = (item.get("orderItemList") or [{}])[0]
    goods_name, mode_name = item_names(item)
    base = enrich_order({
        "orderNo": item.get("orderNo", ""),
        "accountPhone": account_phone,
        "goodsName": goods_name,
        "modeName": mode_name,
        "selfClean": infer_order_self_clean(item),
        "state": item.get("state"),
        "stateDesc": item.get("stateDesc") or "",
        "pageCode": item.get("pageCode"),
        "orderType": item.get("orderType"),
        "goodsId": first_item.get("goodsId"),
        "canPay": item.get("state") == 100,
        "canCancel": item.get("state") not in haile_bot.TERMINAL_ORDER_STATES,
        "deadline": item.get("deadTime") or item.get("completeTime") or item.get("invalidTime") or "",
        "estimatedFinishTime": "",
        "timeDescription": item.get("timeDescription") or "",
        "amount": item.get("realPrice") or "",
        "updatedAt": time.time(),
    })
    if base["stage"] == "running":
        base["estimatedFinishTime"] = item.get("deadTime") or item.get("completeTime") or ""
        base["deadline"] = ""
    return base
