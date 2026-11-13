from __future__ import annotations

from contextlib import asynccontextmanager
import json
import os
import queue
from typing import Optional

from fastapi import FastAPI, Query
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import APP_VERSION_CODE, __version__
from .domain import OTA_DEFAULT_APK_URL, normalize_ota_apk_url
from .engine import Runtime


runtime = Runtime()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    runtime.load_position()
    runtime.start_background_refresh()
    runtime.start_device_monitor()
    runtime.restore_intents()
    runtime.restore_holds()
    yield


app = FastAPI(title="Haile Bot v4", version=__version__, lifespan=lifespan)


class PhoneAuth(BaseModel):
    phone: str


class LoginAuth(BaseModel):
    phone: str
    code: str
    ownerId: str = "default"
    ownerName: str = ""
    remark: str = ""


class AccountAction(BaseModel):
    phone: str
    ownerId: str = "default"
    enabled: bool


class AccountRemark(BaseModel):
    phone: str
    ownerId: str = "default"
    remark: str = ""


class IntentReq(BaseModel):
    goodsId: int = 0
    goodsIds: Optional[list[int]] = None
    anyWasher: bool = False
    modeId: Optional[int] = None
    preferredModeName: Optional[str] = None
    dryerMinutes: Optional[int] = None
    selfClean: bool = False
    cancelOrder: bool = False
    ownerId: str = "default"


class PositionReq(BaseModel):
    positionId: int
    positionName: str = ""
    lng: Optional[float] = None
    lat: Optional[float] = None
    address: str = ""


class ResolvePositionReq(BaseModel):
    input: str
    ownerId: str = "default"
    autoSwitch: bool = True


class CancelOrderReq(BaseModel):
    phone: str
    orderNo: str
    ownerId: str = "default"


class VerifyOrderReq(BaseModel):
    phone: str
    orderNo: str
    checkCode: str
    ownerId: str = "default"


@app.get("/api/health")
def health():
    return {
        "status": "ok",
        "version": __version__,
        "versionCode": APP_VERSION_CODE,
    }


@app.get("/api/ota")
def ota(clientVersion: Optional[int] = Query(None)):
    ota_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "static", "ota.json")
    payload = {
        "ok": True,
        "hasUpdate": True,
        "versionCode": APP_VERSION_CODE,
        "versionName": __version__,
        "apkUrl": OTA_DEFAULT_APK_URL,
        "releaseNotes": "v4.2 通用选楼与常用设备升级：新增交互式地图拖动选点与全国高校/城市检索、GPS 精准坐标转换、自定义点位 ID/机身码解析，以及常用宿舍楼与最近使用设备一键直达",
    }
    if os.path.exists(ota_path):
        try:
            with open(ota_path, "r", encoding="utf-8") as handle:
                data = json.load(handle)
            if isinstance(data, dict) and int(data.get("versionCode") or 0) >= APP_VERSION_CODE:
                payload.update(data)
                payload["ok"] = True
        except Exception:
            pass
    payload["apkUrl"] = normalize_ota_apk_url(payload.get("apkUrl"), native=True)
    remote_code = int(payload.get("versionCode") or APP_VERSION_CODE)
    if clientVersion is not None and clientVersion > 0:
        payload["hasUpdate"] = remote_code > int(clientVersion)
    else:
        payload.setdefault("hasUpdate", True)
    payload.setdefault("notes", payload.get("releaseNotes", ""))
    return JSONResponse(
        content=payload,
        headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"},
    )


@app.get("/api/state")
def state(ownerId: str = Query("default")):
    return runtime.state(ownerId)


@app.get("/api/devices")
def devices(ownerId: str = Query("default")):
    return runtime.devices(ownerId)


@app.get("/api/devices/{goods_id}/modes")
def modes(goods_id: int):
    return {"modes": runtime.modes(goods_id)}


@app.get("/api/positions/nearby")
def nearby_positions(
    lng: Optional[float] = Query(None),
    lat: Optional[float] = Query(None),
    coordType: str = Query("gcj02"),
    q: str = Query(""),
):
    return {"positions": runtime.nearby_positions(lng=lng, lat=lat, coord_type=coordType, keyword=q)}


@app.get("/api/positions/search")
def search_positions(
    q: str = Query(""),
    ownerId: str = Query("default"),
    lng: Optional[float] = Query(None),
    lat: Optional[float] = Query(None),
):
    return runtime.search_positions(q, owner_id=ownerId, lng=lng, lat=lat)


@app.get("/api/recent")
def recent_usage(
    ownerId: str = Query("default"),
    force: bool = Query(False),
):
    return runtime.recent_usage(owner_id=ownerId, force=force)


@app.post("/api/position")
def select_position(req: PositionReq):
    return runtime.set_position(
        req.positionId,
        req.positionName,
        lng=req.lng,
        lat=req.lat,
        address=req.address,
    )


@app.post("/api/position/resolve")
def resolve_position(req: ResolvePositionReq):
    return runtime.resolve_custom_position(req.input, owner_id=req.ownerId, auto_switch=req.autoSwitch)


@app.get("/api/events")
def events(ownerId: str = Query("default")):
    subscriber = runtime.subscribe()

    def stream():
        try:
            yield ": connected\n\n"
            while True:
                try:
                    event = subscriber.get(timeout=15)
                    yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
                except queue.Empty:
                    yield ": ping\n\n"
        finally:
            runtime.unsubscribe(subscriber)

    return StreamingResponse(
        stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


@app.post("/api/accounts/code")
def send_code(req: PhoneAuth):
    return runtime.send_code(req.phone)


@app.post("/api/accounts/login")
def login(req: LoginAuth):
    return runtime.login(req.phone, req.code, req.ownerId, req.ownerName, req.remark)


@app.post("/api/accounts/enabled")
def set_enabled(req: AccountAction):
    return runtime.set_account_enabled(req.phone, req.enabled, req.ownerId)


@app.post("/api/accounts/remark")
def set_remark(req: AccountRemark):
    return runtime.set_account_remark(req.phone, req.remark, req.ownerId)


@app.post("/api/accounts/refresh_orders")
def refresh_orders(ownerId: str = Query("default")):
    runtime.refresh_all_orders(ownerId)
    return {"success": True}


@app.post("/api/intents/start")
def start_intent(req: IntentReq):
    return runtime.start_intent(
        req.goodsId,
        req.modeId,
        req.ownerId,
        req.dryerMinutes,
        self_clean=req.selfClean,
        goods_ids=req.goodsIds,
        any_washer=req.anyWasher,
        preferred_mode_name=req.preferredModeName,
    )


@app.post("/api/intents/stop")
def stop_intent(req: IntentReq):
    return runtime.stop_intent(req.goodsId, cancel_order=req.cancelOrder, owner_id=req.ownerId)


@app.post("/api/orders/cancel")
def cancel_order(req: CancelOrderReq):
    return runtime.cancel_order(req.phone, req.orderNo, req.ownerId)


@app.post("/api/orders/verify")
def verify_order(req: VerifyOrderReq):
    return runtime.verify_order(req.phone, req.orderNo, req.checkCode, req.ownerId)


@app.post("/api/orders/resend_verify")
def resend_verify(req: CancelOrderReq):
    return runtime.resend_verify_code(req.phone, req.orderNo, req.ownerId)


@app.post("/api/orders/remind_pickup")
def remind_pickup(req: CancelOrderReq):
    return runtime.remind_previous_pickup(req.phone, req.orderNo, req.ownerId)


@app.post("/api/orders/place_clothes")
def place_clothes(req: CancelOrderReq):
    return runtime.confirm_place_clothes(req.phone, req.orderNo, req.ownerId)


static_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), "static")
app.mount("/", StaticFiles(directory=static_dir, html=True), name="static")
