from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
import tempfile
import unittest
from unittest.mock import MagicMock, Mock, patch

from haile_v3 import APP_VERSION_CODE, __version__
from haile_v3.api import HaileApi
from haile_v3.domain import (
    BURST_ATTEMPT_LIMIT,
    OTA_DEFAULT_APK_URL,
    POLL_OVERTIME_SECONDS,
    ClockOffsetTracker,
    build_alipay_cashier_scheme,
    build_wechat_mini_scheme,
    match_mode_by_preference,
    normalize_device_display_name,
    normalize_ota_apk_url,
    parse_custom_position_input,
    plan_poll,
    search_campus_presets,
    wgs84_to_gcj02,
)
from haile_v3.engine import Runtime, pool_key_for_owner


def fake_jwt(exp: int = 4_000_000_000) -> str:
    import base64
    import json

    payload = base64.urlsafe_b64encode(json.dumps({"exp": exp}).encode()).rstrip(b"=").decode()
    return f"e.{payload}.s"


class ClockOffsetAndPollV4Test(unittest.TestCase):
    def test_clock_offset_ewma_and_sanity_clamp(self):
        tracker = ClockOffsetTracker(max_abs_offset=600.0)
        base_ts = 1_760_000_000.0
        server_dt = datetime.fromtimestamp(base_ts + 4.0, tz=timezone.utc)
        hdr = format_datetime(server_dt, usegmt=True)

        offset1 = tracker.observe_http_date(hdr, rtt_seconds=0.2, local_now=base_ts)
        self.assertTrue(tracker.calibrated)
        self.assertAlmostEqual(offset1, 4.1, places=2)
        self.assertAlmostEqual(tracker.now(local_now=base_ts), base_ts + 4.1, places=2)

        # Extreme bogus header (>10 min skew) is ignored safely
        bogus_dt = datetime.fromtimestamp(base_ts + 3600.0, tz=timezone.utc)
        self.assertIsNone(tracker.observe_http_date(format_datetime(bogus_dt, usegmt=True), rtt_seconds=0.1, local_now=base_ts))
        self.assertAlmostEqual(tracker.offset_seconds, 4.1, places=2)

    def test_haile_api_observes_clock_from_response_headers(self):
        api = HaileApi()
        server_dt = datetime.now(timezone.utc) + timedelta(seconds=3)
        fake_resp = Mock()
        fake_resp.headers = {"Date": format_datetime(server_dt, usegmt=True)}
        api._observe_clock(fake_resp, rtt_seconds=0.1)
        self.assertTrue(api.clock.calibrated)
        self.assertTrue(1.5 <= api.clock.offset_seconds <= 4.5)

    def test_plan_poll_v4_covers_release_window_and_overtime_tail(self):
        # 50 attempts in [-10s, +2s] should still burst without hitting premature blackout
        plan_mid = plan_poll(0.0, burst_attempts=40)
        self.assertEqual(plan_mid.phase, "burst")
        self.assertTrue(plan_mid.attempt)
        self.assertEqual(BURST_ATTEMPT_LIMIT, 60)

        # Overtime spin-down tail (< -12s) probes steadily without blind cooldown sleep
        plan_tail = plan_poll(-18.0, burst_attempts=60)
        self.assertEqual(plan_tail.phase, "overtime")
        self.assertEqual(plan_tail.delay, POLL_OVERTIME_SECONDS)
        self.assertTrue(plan_tail.attempt)


class MultiMachinePoolPreemptTest(unittest.TestCase):
    def make_runtime(self):
        api = Mock()
        api.server_now.return_value = None
        api.clock = Mock(offset_seconds=0.0)
        api.underway_orders.return_value = {"code": 0, "data": []}
        api.trade_list.return_value = {"code": 0, "data": {"items": []}}
        runtime = Runtime(api=api)
        runtime.save_intents = Mock()
        runtime.save_holds = Mock()
        runtime.accounts.accounts = [
            {"phone": "10000001111", "token": fake_jwt(), "is_active": True, "owner_id": "alice", "remark": "主力号"},
        ]
        return runtime

    def test_pool_intent_consumes_single_slot_and_locks_fastest_washer_with_device_specific_mode(self):
        runtime = self.make_runtime()
        future_25m = (datetime.now() + timedelta(minutes=25)).strftime("%Y-%m-%d %H:%M:%S")
        raw_devices = [
            {"id": 601, "name": "06号洗衣机", "imei": "865901075488729", "state": 2, "enableReserve": False, "reserveState": 0, "finishTime": future_25m},
            {"id": 701, "name": "07号洗衣机", "imei": "865901073935457", "state": 2, "enableReserve": True, "reserveState": 1, "finishTime": future_25m},
            {"id": 801, "name": "08号洗衣机", "imei": "865901073916986", "state": 1, "enableReserve": True, "reserveState": 0, "finishTime": ""},
        ]
        runtime.api.devices.return_value = raw_devices
        runtime.api.modes.side_effect = lambda gid: [
            {"id": gid * 10 + 1, "name": "标准", "attachMap": {"SelfClean": True}},
            {"id": gid * 10 + 9, "name": "桶自洁", "skuId": 175, "attachMap": {"SelfClean": False}},
        ]
        runtime.api.create_order.return_value = {"code": 0, "data": {"orderNo": "POOL-WIN-801", "expireSeconds": 300}}

        with patch("haile_v3.engine.PreemptIntent.start", return_value=None):
            res = runtime.start_intent(
                owner_id="alice",
                any_washer=True,
                preferred_mode_name="标准",
                self_clean=True,
            )

        self.assertTrue(res["success"])
        self.assertTrue(res["isPool"])
        pool_key = pool_key_for_owner("alice")
        intent = runtime.intents[pool_key]

        # Simulate alive thread for slot calculation
        with patch.object(intent, "is_alive", return_value=True):
            self.assertEqual(runtime.account_usage("10000001111", "alice"), 1)

            with patch.object(intent, "sleep", return_value=None):
                intent.acquire_order()

        # Must pick idle 08号洗衣机 (id=801) and use 801's own modeId (8011) + selfCleanId (8019)!
        runtime.api.create_order.assert_called_once_with(
            runtime.accounts.accounts[0]["token"],
            "08号洗衣机",
            801,
            8011,
            dryer_minutes=None,
            self_clean_item_id=8019,
        )
        self.assertFalse(intent.is_pool)
        self.assertEqual(intent.goods_id, 801)
        self.assertEqual(intent.current_order_no, "POOL-WIN-801")
        self.assertIs(runtime.intents.get(801), intent)
        self.assertIsNone(runtime.intents.get(pool_key))

        # If order later times out unpaid, pool re-opens across all washers automatically
        runtime.api.order_detail.return_value = {
            "code": 0,
            "data": {
                "orderNo": "POOL-WIN-801",
                "state": 411,
                "stateDesc": "支付超时",
                "pageCode": "timeout",
                "buttonSwitch": {"canPay": False, "canCancel": False},
                "orderItemList": [{"goodsId": 801, "goodsName": "08号洗衣机", "goodsItemName": "标准"}],
            },
        }
        with patch.object(intent, "sleep", return_value=None):
            intent.maintain_order()

        self.assertTrue(intent.is_pool)
        self.assertIsNone(intent.current_order_no)
        self.assertIs(runtime.intents.get(pool_key), intent)


class PickupReminderAndDynamicPositionTest(unittest.TestCase):
    def test_pickup_soon_and_done_events_fire_once_for_running_orders(self):
        api = Mock()
        api.server_now.return_value = None
        api.clock = Mock(offset_seconds=0.0)
        runtime = Runtime(api=api)
        sub = runtime.subscribe()
        acc = {"phone": "10000001111", "token": fake_jwt(), "is_active": True, "owner_id": "alice"}

        soon_finish = (datetime.now() + timedelta(seconds=120)).strftime("%Y-%m-%d %H:%M:%S")
        api.underway_orders.return_value = {
            "code": 0,
            "data": [
                {
                    "orderNo": "RUN-PICKUP-1",
                    "projectName": "06号洗衣机【标准】",
                    "state": 500,
                    "deadTime": soon_finish,
                    "orderItemList": [{"goodsId": 85575623, "goodsName": "06号洗衣机", "goodsItemName": "标准"}],
                }
            ],
        }
        api.trade_list.return_value = {"code": 0, "data": {"items": []}}
        api.order_detail.return_value = {
            "code": 0,
            "data": {
                "orderNo": "RUN-PICKUP-1",
                "state": 500,
                "pageCode": "normal_fulfilling",
                "finishTime": soon_finish,
                "buttonSwitch": {"canPay": False, "canCancel": False},
                "orderItemList": [{"goodsId": 85575623, "goodsName": "06号洗衣机", "goodsItemName": "标准", "finishTime": soon_finish}],
            },
        }

        runtime.refresh_account_orders(acc)
        runtime.refresh_account_orders(acc)  # duplicate refresh must not double-emit

        events = []
        while not sub.empty():
            events.append(sub.get_nowait())
        pickup_soon = [e for e in events if e.get("type") == "pickup" and e.get("stage") == "pickup_soon"]
        self.assertEqual(len(pickup_soon), 1)
        self.assertEqual(pickup_soon[0]["deviceName"], "06号洗衣机")

        # Now order transitions to ended (even if vanished from underway & trade_list!) -> fires pickup_done once
        api.underway_orders.return_value = {"code": 0, "data": []}
        api.trade_list.return_value = {"code": 0, "data": {"items": []}}
        api.order_detail.return_value = {
            "code": 0,
            "data": {
                "orderNo": "RUN-PICKUP-1",
                "state": 1000,
                "pageCode": "complete",
                "buttonSwitch": {"canPay": False, "canCancel": False},
                "orderItemList": [{"goodsId": 85575623, "goodsName": "06号洗衣机", "goodsItemName": "标准"}],
            },
        }
        runtime.refresh_account_orders(acc)
        events2 = []
        while not sub.empty():
            events2.append(sub.get_nowait())
        pickup_done = [e for e in events2 if e.get("type") == "pickup" and e.get("stage") == "pickup_done"]
        self.assertEqual(len(pickup_done), 1)

    def test_dynamic_position_switch_and_name_normalization(self):
        self.assertEqual(normalize_device_display_name({"name": "泓园9号楼 3号洗衣机", "id": 99}), "03号洗衣机")
        self.assertEqual(match_mode_by_preference("06号洗衣机", [{"id": 1, "name": "大物"}, {"id": 2, "name": "标准"}], "标准")["id"], 2)

        api = Mock()
        api.server_now.return_value = None
        api.clock = Mock(offset_seconds=0.0)
        api.devices.return_value = [
            {"id": 9001, "name": "泓园9号楼 2号洗衣机", "imei": "999", "state": 1, "enableReserve": True, "reserveState": 0},
        ]
        runtime = Runtime(api=api)
        with tempfile.TemporaryDirectory() as tmpdir:
            with patch("haile_v3.engine.POSITION_FILE", f"{tmpdir}/pos.json"):
                runtime.set_position(32599, "泓园 9 号楼")
                st = runtime.state("alice")
        self.assertEqual(st["version"], __version__)
        self.assertEqual(st["versionCode"], APP_VERSION_CODE)
        self.assertEqual(st["position"]["positionId"], 32599)
        self.assertEqual(st["devices"][0]["name"], "02号洗衣机")
        api.devices.assert_called_with(32599)

    def test_v4_ota_default_points_to_permanent_pages_dev_and_v4_apk(self):
        self.assertEqual(OTA_DEFAULT_APK_URL, "https://haile-bot.pages.dev/haile-bot-v4.apk")
        self.assertEqual(normalize_ota_apk_url("https://haile-bot.pages.dev/haile-bot-v3.apk"), OTA_DEFAULT_APK_URL)
        self.assertEqual(normalize_ota_apk_url("https://haile-bot.4c666.top/haile-bot-v3.apk"), OTA_DEFAULT_APK_URL)
        self.assertEqual(normalize_ota_apk_url("", native=False), "/haile-bot-v4.apk")
        self.assertEqual(APP_VERSION_CODE, 420)
        self.assertEqual(__version__, "4.2.0")

    def test_payment_schemes_follow_whitelisted_wechat_and_alipay_cashier_protocols(self):
        # Payment mode must use clean whitelisted path without non-existent scanResult or ? inside path
        wx_pay = build_wechat_mini_scheme("GTB2406241487", for_payment=True)
        self.assertEqual(wx_pay, "weixin://dl/business/?appid=wx7519e26c1d2f9edc&path=pages/tabbar/home")
        self.assertNotIn("scanResult", wx_pay)
        self.assertNotIn("%3F", wx_pay)

        # Device scan jump puts N= inside separate query parameter
        wx_scan = build_wechat_mini_scheme("GTB2406241487", for_payment=False)
        self.assertEqual(wx_scan, "weixin://dl/business/?appid=wx7519e26c1d2f9edc&path=pages/tabbar/home&query=N%3DGTB2406241487")

        # Alipay direct cashier jump
        ali = build_alipay_cashier_scheme("1020261113200001")
        self.assertEqual(
            ali,
            "alipays://platformapi/startapp?appId=2021003183687122&page=pages%2Fpay%2Fcashier%3ForderNo%3D1020261113200001",
        )

    def test_instant_order_timeout_recovery_falls_back_to_trade_list(self):
        api = HaileApi()
        future_str = (datetime.now() + timedelta(seconds=240)).strftime("%Y-%m-%d %H:%M:%S")
        with patch.object(api, "underway_orders", return_value={"code": 0, "data": []}), patch.object(
            api,
            "trade_list",
            return_value={
                "code": 0,
                "data": {
                    "items": [
                        {
                            "orderNo": "INST-100-OK",
                            "state": 100,
                            "invalidTime": future_str,
                            "orderItemList": [{"goodsId": 85575623, "goodsName": "06号洗衣机"}],
                        }
                    ]
                },
            },
        ):
            recovered = api.recover_pending_order("tok", 85575623, "instant")
        self.assertIsNotNone(recovered)
        self.assertTrue(recovered["recovered"])
        self.assertEqual(recovered["data"]["orderNo"], "INST-100-OK")

    def test_position_switch_blocked_while_intent_active_and_reserve_actions_work(self):
        api = Mock()
        api.server_now.return_value = None
        api.clock = Mock(offset_seconds=0.0)
        api.verify_order_code.return_value = {"code": 0, "data": True}
        api.resend_verify_code.return_value = {"code": 0, "data": True}
        api.remind_pickup_clothes.return_value = {"code": 0, "data": True}
        api.place_clothes_start.return_value = {"code": 0, "data": True}
        api.underway_orders.return_value = {"code": 0, "data": []}
        api.trade_list.return_value = {"code": 0, "data": {"items": []}}

        runtime = Runtime(api=api)
        runtime.accounts.accounts = [
            {"phone": "10000001111", "token": fake_jwt(), "is_active": True, "owner_id": "alice"},
        ]

        fake_intent = Mock()
        fake_intent.is_alive.return_value = True
        fake_intent.stop_event.is_set.return_value = False
        runtime.intents[85575623] = fake_intent

        blocked = runtime.set_position(32599, "泓园 9 号楼")
        self.assertFalse(blocked["success"])
        self.assertIn("停止", blocked["message"])

        self.assertTrue(runtime.verify_order("10000001111", "ORD-1", "8866", "alice")["success"])
        self.assertTrue(runtime.resend_verify_code("10000001111", "ORD-1", "alice")["success"])
        self.assertTrue(runtime.remind_previous_pickup("10000001111", "ORD-1", "alice")["success"])
        self.assertTrue(runtime.confirm_place_clothes("10000001111", "ORD-1", "alice")["success"])

    def test_wgs84_to_gcj02_and_custom_input_parser(self):
        # Mainland China WGS-84 shifts ~300-600m east/north in GCJ-02
        gcj_lng, gcj_lat = wgs84_to_gcj02(120.4200, 36.0700)
        self.assertGreater(gcj_lng, 120.4240)
        self.assertGreater(gcj_lat, 36.0701)
        # Overseas coordinates pass through unchanged
        self.assertEqual(wgs84_to_gcj02(-122.4194, 37.7749), (-122.4194, 37.7749))

        self.assertEqual(parse_custom_position_input("#32577"), {"kind": "position_id", "positionId": 32577})
        qr = parse_custom_position_input("https://api.sharewash.cn/mrhi/wechat.html?imei=865901075488729&deviceld=GTB2406241487")
        self.assertEqual(qr["kind"], "scan")
        self.assertEqual(qr["imei"], "865901075488729")
        self.assertEqual(qr["n"], "GTB2406241487")
        self.assertEqual(parse_custom_position_input("GTB2406243590"), {"kind": "scan", "imei": "", "n": "GTB2406243590"})
        self.assertEqual(parse_custom_position_input("120.4250, 36.0716"), {"kind": "coords", "lng": 120.4250, "lat": 36.0716})

        presets = search_campus_presets("浙大紫金港")
        self.assertTrue(any("浙江大学" in p["name"] for p in presets))

    def test_recent_usage_and_first_login_auto_position_discovery(self):
        api = Mock()
        api.server_now.return_value = None
        api.clock = Mock(offset_seconds=0.0)
        api.login.return_value = {"code": 0, "data": {"token": fake_jwt(), "userId": 1001}}
        api.account_info.return_value = {"code": 0, "data": {"nickname": "测试同学"}}
        api.underway_orders.return_value = {"code": 0, "data": []}
        api.trade_list.return_value = {"code": 0, "data": {"items": []}}
        api.use_position_list.return_value = [
            {"positionId": 538, "name": "中心17号楼", "address": "山大中心校区", "lng": 117.0604, "lat": 36.6758},
        ]
        api.goods_use_list.return_value = [
            {"id": 99001, "name": "02号洗衣机", "imei": "860000000000001", "state": 1, "enableReserve": True, "reserveState": 0, "categoryCode": "00"},
        ]
        api.goods_detail.return_value = {
            "id": 99001,
            "name": "02号洗衣机",
            "positionId": 538,
            "shopName": "山大中心校区",
            "deviceState": 1,
            "enableReserve": True,
            "reserveState": 0,
            "categoryCode": "00",
        }
        api.position_detail.return_value = {
            "positionId": 538,
            "name": "中心17号楼",
            "address": "济南市历城区山大南路27号",
            "lng": 117.0604,
            "lat": 36.6758,
        }
        api.scan_goods.return_value = {"code": 0, "data": {"goodsId": 99001}}
        api.devices.return_value = [
            {"id": 99001, "name": "02号洗衣机", "imei": "860000000000001", "state": 1, "enableReserve": True, "reserveState": 0},
        ]

        runtime = Runtime(api=api)
        runtime.accounts.accounts = []
        runtime.accounts.save_accounts = Mock()

        with tempfile.TemporaryDirectory() as tmpdir:
            with patch("haile_v3.engine.POSITION_FILE", f"{tmpdir}/pos.json"):
                res = runtime.login("10000002222", "123456", owner_id="bob")
                self.assertTrue(res["success"])
                self.assertEqual(res.get("autoPosition", {}).get("positionId"), 538)
                self.assertEqual(runtime.position_id, 538)
                self.assertEqual(runtime.position_name, "中心17号楼")

                recent = runtime.recent_usage("bob", force=True)
                self.assertTrue(recent["loggedIn"])
                self.assertEqual(recent["devices"][0]["goodsId"], 99001)
                self.assertEqual(recent["devices"][0]["positionId"], 538)
                self.assertEqual(recent["devices"][0]["state"], "idle")

                resolved = runtime.resolve_custom_position(
                    "https://api.sharewash.cn/mrhi/wechat.html?imei=860000000000001&deviceld=GTB999999",
                    owner_id="bob",
                )
                self.assertTrue(resolved["success"])
                self.assertEqual(resolved["positionId"], 538)
                self.assertEqual(resolved["focusGoodsId"], 99001)


if __name__ == "__main__":
    unittest.main()
