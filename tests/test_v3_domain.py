import base64
import json
import os
import tempfile
import time
import unittest
from datetime import datetime, timedelta
from unittest.mock import MagicMock, Mock, patch

from haile_v3.api import HaileApi, summarize_detail, summarize_list_item
from haile_v3.domain import (
    classify_device,
    classify_order,
    enrich_order,
    find_self_clean_item_id,
    infer_device_name_from_order,
    is_account_order_conflict,
    is_auth_failure,
    is_device_payment_conflict,
    mode_supports_self_clean,
    order_blocks_account,
    order_ready_for_user_action,
)
from haile_v3.engine import PreemptIntent, Runtime, normalize_dryer_minutes


def fake_jwt(exp: int = 4_000_000_000) -> str:
    payload = base64.urlsafe_b64encode(json.dumps({"exp": exp}).encode()).rstrip(b"=").decode()
    return f"e.{payload}.s"


class DomainRulesTest(unittest.TestCase):
    def test_running_order_is_display_only_and_does_not_block(self):
        order = {
            "state": 500,
            "stateDesc": "进行中",
            "pageCode": "complete",
            "canPay": False,
            "canCancel": False,
        }

        self.assertEqual(classify_order(order), "running")
        self.assertFalse(order_blocks_account(order))

    def test_waiting_check_state_50_blocks_account(self):
        order = {
            "state": 50,
            "stateDesc": "待验证",
            "pageCode": "waiting_check",
            "canPay": False,
            "canCancel": True,
        }

        self.assertEqual(classify_order(order), "reserve_waiting")
        self.assertTrue(order_blocks_account(order))

    def test_device_queue_states(self):
        idle = classify_device({"state": 1, "enableReserve": True, "reserveState": 0})
        reservable = classify_device({"state": 2, "enableReserve": True, "reserveState": 1})
        full = classify_device({"state": 2, "enableReserve": False, "reserveState": 0})
        paying = classify_device({"state": 1}, payment_pending=True)
        fault = classify_device({"state": 3})

        self.assertEqual((idle.key, idle.queue_count, idle.order_kind), ("idle", 0, "instant"))
        self.assertEqual((reservable.key, reservable.queue_count, reservable.order_kind), ("busy_reservable", 1, "reserve"))
        self.assertEqual((full.key, full.queue_count, full.can_order, full.order_kind), ("busy_full", 2, True, "poll"))
        self.assertEqual((paying.key, paying.can_order), ("payment_pending", False))
        self.assertEqual((fault.key, fault.label, fault.can_order), ("fault", "故障或离线", False))

    def test_infer_device_name_supports_04_washer_and_regex_fallback(self):
        self.assertEqual(
            infer_device_name_from_order({"goodsName": "04号洗衣机【常规】"}),
            "04号洗衣机",
        )
        self.assertEqual(
            infer_device_name_from_order({"projectName": "青岛大学泓园 12号洗衣机【快洗】"}),
            "12号洗衣机",
        )

    def test_order_for_device_matches_by_goods_id_when_name_missing(self):
        runtime = Runtime(api=Mock())
        runtime.orders_by_no = {
            "ORD-04": {
                "orderNo": "ORD-04",
                "ownerId": "alice",
                "goodsId": 85578163,
                "goodsName": "滚筒洗烘一体【常规】",
                "stage": "awaiting_payment",
                "updatedAt": 100.0,
            }
        }
        matched = runtime.order_for_device("04号洗衣机", "alice", goods_id=85578163)
        self.assertIsNotNone(matched)
        self.assertEqual(matched["orderNo"], "ORD-04")

    def test_two_person_queue_is_pollable(self):
        state = classify_device({"state": 2, "enableReserve": False, "reserveState": 0})

        self.assertTrue(state.can_order)
        self.assertEqual(state.order_kind, "poll")

    def test_extended_page_codes_from_miniapp_state_machine(self):
        self_clean_order = enrich_order({"state": 50, "pageCode": "self_clean", "canCancel": True})
        place_clothes_order = enrich_order({"state": 50, "pageCode": "place_clothes", "canCancel": True})
        choose_ump_order = enrich_order({"state": 100, "pageCode": "waiting_choose_ump", "canPay": True})
        after_pay_wait = enrich_order({"state": 100, "pageCode": "after_pay_waiting_pay", "canPay": True})
        fulfilling_order = enrich_order({"state": 300, "pageCode": "normal_fulfilling"})
        after_fulfilling = enrich_order({"state": 300, "pageCode": "after_pay_fulfilling"})
        refund_done = enrich_order({"state": 600, "pageCode": "refund_complete"})
        refund_failed = enrich_order({"state": 601, "pageCode": "refund_fail"})

        self.assertEqual((self_clean_order["stage"], self_clean_order["stageLabel"], self_clean_order["blocksAccount"]), ("reserve_waiting", "筒自洁中", True))
        self.assertEqual((place_clothes_order["stage"], place_clothes_order["stageLabel"], place_clothes_order["blocksAccount"]), ("reserve_waiting", "待放衣物", True))
        self.assertEqual((choose_ump_order["stage"], choose_ump_order["stageLabel"], choose_ump_order["blocksAccount"]), ("awaiting_payment", "待选券支付", True))
        self.assertEqual((after_pay_wait["stage"], after_pay_wait["blocksAccount"]), ("awaiting_payment", True))
        self.assertEqual((fulfilling_order["stage"], fulfilling_order["blocksAccount"]), ("running", False))
        self.assertEqual((after_fulfilling["stage"], after_fulfilling["blocksAccount"]), ("running", False))
        self.assertEqual((refund_done["stage"], refund_done["blocksAccount"]), ("ended", False))
        self.assertEqual((refund_failed["stage"], refund_failed["blocksAccount"]), ("ended", False))

    def test_self_clean_mode_detection_and_item_id_lookup(self):
        washer_modes = [
            {"id": 2000763282, "name": "大物", "skuId": 95, "attachMap": {"SelfClean": True}},
            {"id": 2000763284, "name": "桶自洁", "skuId": 175, "attachMap": {"SelfClean": False}},
        ]
        self.assertTrue(mode_supports_self_clean(washer_modes[0]))
        self.assertFalse(mode_supports_self_clean(washer_modes[1]))
        self.assertEqual(find_self_clean_item_id(washer_modes), 2000763284)


class OrderSummaryTest(unittest.TestCase):
    def test_underway_running_order_uses_project_name_and_dead_time(self):
        summary = summarize_list_item(
            {
                "orderNo": "RUN-1",
                "projectName": "08号洗衣机【大物】",
                "state": 500,
                "stateDesc": "洗衣中",
                "deadTime": "2099-01-01 12:30:00",
            },
            "10000000001",
        )

        self.assertEqual(summary["goodsName"], "08号洗衣机【大物】")
        self.assertEqual(summary["modeName"], "大物")
        self.assertEqual(summary["stage"], "running")
        self.assertEqual(summary["estimatedFinishTime"], "2099-01-01 12:30:00")
        self.assertEqual(summary["deadline"], "")

    def test_underway_payment_order_uses_dead_time_as_deadline(self):
        summary = summarize_list_item(
            {
                "orderNo": "PAY-1",
                "projectName": "05号烘干机【高温】",
                "state": 100,
                "stateDesc": "待支付",
                "deadTime": "2099-01-01 12:05:00",
            },
            "10000000001",
        )

        self.assertEqual(summary["stage"], "awaiting_payment")
        self.assertEqual(summary["modeName"], "高温")
        self.assertEqual(summary["deadline"], "2099-01-01 12:05:00")

    def test_running_order_timing_prefers_device_finish_time(self):
        runtime = Runtime(api=Mock())
        timed = runtime.order_with_device_timing(
            {
                "orderNo": "RUN-1",
                "stage": "running",
                "estimatedFinishTime": "2099-01-01 12:00:00",
            },
            {"finishTime": "2099-01-01 12:30:00"},
        )

        self.assertEqual(timed["estimatedFinishTime"], "2099-01-01 12:30:00")
        self.assertEqual(timed["estimatedFinishSource"], "device")


class PaymentExpiryIntentTest(unittest.TestCase):
    def make_runtime(self):
        runtime = Mock()
        runtime.lock = MagicMock()
        runtime.lock.__enter__.return_value = None
        runtime.lock.__exit__.return_value = None
        runtime.orders_by_no = {}
        runtime.device_payment_holds = {}
        runtime.save_intents = Mock()
        runtime.log = Mock()
        runtime.get_owned_account = Mock(return_value=None)
        runtime.order_for_device = Mock(return_value=None)
        runtime.refresh_account_orders = Mock(return_value=[])
        return runtime

    def test_timeout_clears_order_but_keeps_intent_running(self):
        runtime = self.make_runtime()
        runtime.api.order_detail.return_value = {
            "code": 0,
            "data": {
                "orderNo": "PAY-1",
                "orderItemList": [{"goodsName": "07号洗衣机", "goodsItemName": "大物"}],
                "state": 411,
                "stateDesc": "支付超时",
                "pageCode": "timeout",
                "buttonSwitch": {"canPay": False, "canCancel": False},
                "orderType": 100,
            },
        }
        intent = PreemptIntent(
            runtime,
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机"},
            {"id": 2000763282, "name": "大物"},
            {"phone": "10000000000", "token": "token"},
        )
        intent.current_order_no = "PAY-1"
        intent.current_order = {"orderNo": "PAY-1"}

        with patch.object(intent, "sleep", return_value=None):
            intent.maintain_order()

        self.assertFalse(intent.stop_event.is_set())
        self.assertIsNone(intent.current_order_no)
        self.assertEqual(intent.state, "acquiring")

    def test_created_payment_order_gets_fallback_countdown_when_expire_missing(self):
        runtime = self.make_runtime()
        intent = PreemptIntent(
            runtime,
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机"},
            {"id": 2000763282, "name": "大物"},
            {"phone": "10000000000", "token": "token"},
        )

        with patch("haile_v3.engine.time.time", return_value=1000.0):
            intent.handle_order_result({"code": 0, "data": {"orderNo": "PAY-2"}}, "instant")

        self.assertEqual(intent.current_order_no, "PAY-2")
        self.assertEqual(intent.current_order["expireSeconds"], 300)
        self.assertEqual(intent.current_order["createdAtLocal"], 1000.0)

    def test_detail_refresh_preserves_local_payment_countdown(self):
        runtime = self.make_runtime()
        runtime.api.order_detail.return_value = {
            "code": 0,
            "data": {
                "orderNo": "PAY-3",
                "orderItemList": [{"goodsName": "07号洗衣机", "goodsItemName": "大物"}],
                "state": 100,
                "stateDesc": "待支付",
                "pageCode": "normal_waiting_pay",
                "buttonSwitch": {"canPay": True, "canCancel": True},
                "orderType": 100,
            },
        }
        intent = PreemptIntent(
            runtime,
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机"},
            {"id": 2000763282, "name": "大物"},
            {"phone": "10000000000", "token": "token"},
        )
        intent.current_order_no = "PAY-3"
        intent.current_order = {"orderNo": "PAY-3", "expireSeconds": 300, "createdAtLocal": 1000.0}

        with patch("haile_v3.engine.time.time", return_value=1010.0), patch.object(intent, "sleep", return_value=None):
            intent.maintain_order()

        self.assertEqual(intent.current_order["expireSeconds"], 300)
        self.assertEqual(intent.current_order["createdAtLocal"], 1000.0)
        self.assertEqual(intent.state, "holding_payment")
        self.assertIn("剩余 290s", intent.status)

    def test_account_conflict_adopts_refreshed_same_device_order(self):
        runtime = self.make_runtime()
        adopted = {
            "orderNo": "PAY-4",
            "accountPhone": "10000000000",
            "ownerId": "default",
            "goodsName": "07号洗衣机【大物】",
            "modeName": "大物",
            "stage": "awaiting_payment",
            "stageLabel": "待支付",
            "expireSeconds": 300,
            "createdAtLocal": 2000.0,
        }
        runtime.refresh_account_orders.return_value = [adopted]
        runtime.order_for_device.return_value = adopted
        intent = PreemptIntent(
            runtime,
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机"},
            {"id": 2000763282, "name": "大物"},
            {"phone": "10000000000", "token": "token"},
        )

        with patch("haile_v3.engine.time.time", return_value=2010.0), patch.object(intent, "sleep", return_value=None):
            intent.handle_order_result({"code": 100000, "message": "该账号已有订单"}, "instant")

        runtime.refresh_account_orders.assert_called_once_with(intent.account)
        self.assertEqual(intent.current_order_no, "PAY-4")
        self.assertEqual(intent.state, "holding_payment")
        self.assertEqual(intent.status, "待支付维护中")


class AccountOwnershipTest(unittest.TestCase):
    def test_state_only_returns_owner_accounts_and_prefers_remark(self):
        runtime = Runtime(api=Mock())
        runtime.accounts.accounts = [
            {"phone": "111", "token": "", "is_active": True, "owner_id": "alice", "remark": "主号"},
            {"phone": "222", "token": "", "is_active": True, "owner_id": "bob", "remark": "室友"},
        ]
        runtime.devices = Mock(return_value=[])

        state = runtime.state("alice")

        self.assertEqual([acc["phone"] for acc in state["accounts"]], ["111"])
        self.assertEqual(state["accounts"][0]["displayName"], "主号")

    def test_choose_account_allows_account_below_two_slot_cap(self):
        runtime = Runtime(api=Mock())
        runtime.accounts.accounts = [
            {"phone": "111", "token": fake_jwt(), "is_active": True, "owner_id": "alice"},
        ]
        runtime.refresh_all_orders = Mock()
        runtime.orders_by_no = {
            "PAY-1": {
                "orderNo": "PAY-1",
                "accountPhone": "111",
                "ownerId": "alice",
                "state": 100,
                "canPay": True,
            },
        }

        self.assertIsNotNone(runtime.choose_account("alice"))

    def test_choose_account_rejects_at_two_blocking_orders(self):
        runtime = Runtime(api=Mock())
        runtime.accounts.accounts = [
            {"phone": "111", "token": fake_jwt(), "is_active": True, "owner_id": "alice"},
        ]
        runtime.refresh_all_orders = Mock()
        runtime.orders_by_no = {
            "PAY-1": {"orderNo": "PAY-1", "accountPhone": "111", "ownerId": "alice", "state": 100, "canPay": True},
            "RES-1": {"orderNo": "RES-1", "accountPhone": "111", "ownerId": "alice", "state": 50, "canCancel": True, "pageCode": "waiting_check"},
        }

        self.assertIsNone(runtime.choose_account("alice"))

    def test_choose_account_counts_live_intents_toward_slots(self):
        runtime = Runtime(api=Mock())
        runtime.accounts.accounts = [
            {"phone": "111", "token": fake_jwt(), "is_active": True, "owner_id": "mobile"},
        ]
        runtime.refresh_all_orders = Mock()
        runtime.orders_by_no = {}
        runtime.intents["1"] = make_dead_intent(phone="111", owner="alice")
        runtime.intents["2"] = make_dead_intent(phone="111", owner="alice", device="06号洗衣机")

        self.assertIsNone(runtime.choose_account("mobile"))

    def test_intent_holding_a_blocking_order_is_not_double_counted(self):
        runtime = Runtime(api=Mock())
        runtime.accounts.accounts = [
            {"phone": "111", "token": fake_jwt(), "is_active": True, "owner_id": "alice"},
        ]
        runtime.orders_by_no = {
            "PAY-1": {"orderNo": "PAY-1", "accountPhone": "111", "ownerId": "alice", "state": 100, "canPay": True},
        }
        intent = make_dead_intent(phone="111", owner="alice")
        intent.current_order_no = "PAY-1"
        runtime.intents["1"] = intent

        self.assertEqual(runtime.account_usage("111", "alice"), 1)


def make_dead_intent(phone="111", owner="alice", device="05号烘干机", alive=True):
    intent = Mock()
    intent.account_phone = phone
    intent.owner_id = owner
    intent.device_name = device
    intent.account = {"phone": phone, "token": "stale-token", "owner_id": owner}
    intent.is_alive = Mock(return_value=alive)
    intent.stop_event = Mock()
    intent.stop_event.is_set = Mock(return_value=not alive)
    return intent


class LoginIntentMigrationTest(unittest.TestCase):
    """手机号跨 owner 重新登录后，绑定该号的活跃意图必须跟随迁移。

    回归场景：桌面端 owner 启动抢占后，同一手机号在手机端重新登录。
    账号被移到手机 owner，意图还挂着旧 token 并在桌面 owner 下不可见，
    导致手机端「有账号但抢不了」且无法停止旧意图。
    """

    def test_login_migrates_bound_intent_to_new_owner_and_fresh_token(self):
        runtime = Runtime(api=Mock())
        runtime.save_intents = Mock()
        new_acc = {"phone": "111", "token": "fresh-token", "owner_id": "mobile"}
        intent = make_dead_intent(phone="111", owner="alice")
        runtime.intents["1"] = intent

        migrated = runtime.migrate_intents_for_login(new_acc)

        self.assertEqual(migrated, 1)
        self.assertIs(intent.account, new_acc)
        self.assertEqual(intent.owner_id, "mobile")
        runtime.save_intents.assert_called_once()

    def test_login_leaves_other_phones_intents_alone(self):
        runtime = Runtime(api=Mock())
        runtime.save_intents = Mock()
        intent = make_dead_intent(phone="111", owner="alice")
        runtime.intents["1"] = intent

        migrated = runtime.migrate_intents_for_login({"phone": "999", "owner_id": "mobile"})

        self.assertEqual(migrated, 0)
        self.assertEqual(intent.owner_id, "alice")
        runtime.save_intents.assert_not_called()

    def test_stopped_intent_is_not_migrated(self):
        runtime = Runtime(api=Mock())
        runtime.save_intents = Mock()
        intent = make_dead_intent(phone="111", owner="alice", alive=False)
        runtime.intents["1"] = intent

        migrated = runtime.migrate_intents_for_login({"phone": "111", "owner_id": "mobile"})

        self.assertEqual(migrated, 0)
        self.assertEqual(intent.owner_id, "alice")


class NoAccountReasonTest(unittest.TestCase):
    def test_reason_reports_full_slots_with_count(self):
        runtime = Runtime(api=Mock())
        runtime.accounts.accounts = [
            {"phone": "111", "token": fake_jwt(), "is_active": True, "owner_id": "mobile", "remark": "神舟"},
        ]
        runtime.orders_by_no = {}
        runtime.intents["1"] = make_dead_intent(phone="111", owner="alice", device="05号烘干机")
        runtime.intents["2"] = make_dead_intent(phone="111", owner="alice", device="06号洗衣机")

        reason = runtime.no_account_reason("mobile")

        self.assertIn("神舟", reason)
        self.assertIn("2", reason)

    def test_reason_mentions_expired_login_when_token_dead(self):
        runtime = Runtime(api=Mock())
        runtime.accounts.accounts = [
            {"phone": "111", "token": "not-a-jwt", "is_active": True, "owner_id": "mobile"},
        ]

        self.assertIn("失效", runtime.no_account_reason("mobile"))

    def test_reason_prompts_login_when_no_account(self):
        runtime = Runtime(api=Mock())

        self.assertIn("登录", runtime.no_account_reason("mobile"))

    def test_reason_mentions_blocking_orders(self):
        runtime = Runtime(api=Mock())
        runtime.accounts.accounts = [
            {"phone": "111", "token": fake_jwt(), "is_active": True, "owner_id": "mobile"},
        ]
        runtime.orders_by_no = {
            "P1": {"orderNo": "P1", "accountPhone": "111", "ownerId": "mobile", "state": 100, "canPay": True},
            "P2": {"orderNo": "P2", "accountPhone": "111", "ownerId": "mobile", "state": 100, "canPay": True},
        }

        reason = runtime.no_account_reason("mobile")

        self.assertIn("2", reason)
        self.assertIn("上限", reason)


class AuthFailureTextTest(unittest.TestCase):
    def test_other_device_login_counts_as_auth_failure(self):
        self.assertTrue(is_auth_failure(1, "该账号已在其它设备登录，请重新登录"))
        self.assertTrue(is_auth_failure(1, "该账号已在其他设备登录，请重新登录"))

    def test_auth_failure_codes(self):
        for code in (2, 401, 100002, 100003):
            self.assertTrue(is_auth_failure(code, ""), code)

    def test_normal_error_text_is_not_auth_failure(self):
        self.assertFalse(is_auth_failure(1, "设备忙，请稍后再试"))


class ErrorBackoffTest(unittest.TestCase):
    """连续异常应指数退避（封顶），任意一次正常节奏 sleep 即复位。"""

    def make_intent(self):
        runtime = Mock()
        runtime.lock = MagicMock()
        runtime.orders_by_no = {}
        runtime.device_payment_holds = {}
        intent = PreemptIntent(
            runtime,
            {"id": 85575625, "imei": "x", "name": "07号洗衣机"},
            {"id": 1, "name": "大物"},
            {"phone": "10000000000", "token": "token"},
        )
        intent.stop_event = Mock()
        intent.stop_event.wait = Mock(return_value=True)
        return intent

    def test_consecutive_errors_escalate_delay(self):
        intent = self.make_intent()

        intent.sleep_after_error(1.5)
        intent.sleep_after_error(1.5)
        intent.sleep_after_error(1.5)

        delays = [call.args[0] for call in intent.stop_event.wait.call_args_list]
        self.assertGreater(delays[1], delays[0])
        self.assertGreater(delays[2], delays[1])
        self.assertEqual(intent.error_streak, 3)

    def test_normal_sleep_resets_streak(self):
        intent = self.make_intent()
        intent.sleep_after_error(1.5)
        intent.sleep_after_error(1.5)

        intent.sleep(0.5)

        self.assertEqual(intent.error_streak, 0)
        intent.sleep_after_error(1.5)
        self.assertEqual(intent.error_streak, 1)


class RecommendationAndOccupancyTest(unittest.TestCase):
    def setUp(self):
        self.runtime = Runtime(api=Mock())
        self.now = datetime.now()

    def iso(self, minutes: int) -> str:
        return (self.now + timedelta(minutes=minutes)).strftime("%Y-%m-%d %H:%M:%S")

    def test_state_builds_recommendations_for_each_device_type(self):
        self.runtime.api.devices.return_value = [
            {"id": 1, "imei": "865901075488729", "state": 1, "enableReserve": True, "reserveState": 0, "finishTime": ""},
            {"id": 2, "imei": "865901073935457", "state": 2, "enableReserve": True, "reserveState": 1, "finishTime": self.iso(15)},
            {"id": 3, "imei": "865901073916986", "state": 2, "enableReserve": False, "reserveState": 0, "finishTime": self.iso(5)},
            {"id": 4, "imei": "865901073920962", "state": 2, "enableReserve": False, "reserveState": 0, "finishTime": self.iso(30)},
            {"id": 5, "imei": "869737068397394", "state": 2, "enableReserve": True, "reserveState": 1, "finishTime": self.iso(40)},
        ]

        state = self.runtime.state("alice")

        recommendations = state["recommendations"]
        washer_items = recommendations["washer"]["items"]
        self.assertEqual([item["name"] for item in washer_items[:2]], ["06号洗衣机", "07号洗衣机"])
        self.assertEqual(recommendations["washer"]["bestGoodsId"], 1)
        self.assertIn("06号洗衣机", recommendations["washer"]["summary"])
        self.assertEqual(recommendations["dryer"]["bestGoodsId"], 5)

        device_map = {device["name"]: device for device in state["devices"]}
        self.assertEqual(device_map["06号洗衣机"]["deviceType"], "washer")
        self.assertEqual(device_map["06号洗衣机"]["recommendedRank"], 1)
        self.assertTrue(device_map["06号洗衣机"]["isRecommended"])
        self.assertGreater(
            device_map["06号洗衣机"]["recommendedScore"],
            device_map["07号洗衣机"]["recommendedScore"],
        )
        self.assertGreater(
            device_map["07号洗衣机"]["recommendedScore"],
            device_map["08号洗衣机"]["recommendedScore"],
        )

    def test_other_user_remark_is_exposed_on_occupied_device(self):
        self.runtime.accounts.accounts = [
            {"phone": "10000006042", "token": "", "is_active": True, "owner_id": "alice", "remark": "我的号"},
            {"phone": "10000006041", "token": "", "is_active": True, "owner_id": "bob", "remark": "室友A"},
        ]
        self.runtime.api.devices.return_value = [
            {"id": 1, "imei": "865901075488729", "state": 1, "enableReserve": True, "reserveState": 0, "finishTime": ""},
            {"id": 2, "imei": "865901073935457", "state": 2, "enableReserve": True, "reserveState": 1, "finishTime": self.iso(12)},
        ]

        class FakeStop:
            def is_set(self):
                return False

        class FakeIntent:
            def __init__(self, owner_id: str, account_phone: str):
                self.owner_id = owner_id
                self.account_phone = account_phone
                self.stop_event = FakeStop()

            def is_alive(self):
                return True

        self.runtime.intents = {1: FakeIntent("bob", "10000006041")}

        state = self.runtime.state("alice")
        device_map = {device["name"]: device for device in state["devices"]}

        self.assertTrue(device_map["06号洗衣机"]["occupiedByOtherUser"])
        self.assertEqual(device_map["06号洗衣机"]["occupiedByOtherUserLabel"], "室友A")
        self.assertEqual(state["recommendations"]["washer"]["bestGoodsId"], 2)

    def test_other_user_label_falls_back_to_phone_suffix(self):
        self.runtime.accounts.accounts = [
            {"phone": "10000006041", "token": "", "is_active": True, "owner_id": "bob", "remark": ""},
        ]
        self.runtime.api.devices.return_value = [
            {"id": 1, "imei": "865901075488729", "state": 1, "enableReserve": True, "reserveState": 0, "finishTime": ""},
        ]

        class FakeStop:
            def is_set(self):
                return False

        class FakeIntent:
            owner_id = "bob"
            account_phone = "10000006041"
            stop_event = FakeStop()

            def is_alive(self):
                return True

        self.runtime.intents = {1: FakeIntent()}

        devices = self.runtime.devices("alice")
        self.assertEqual(devices[0]["occupiedByOtherUserLabel"], "尾号6041")


class IntentApiContractTest(unittest.TestCase):
    def test_dryer_minutes_allow_official_120_minute_option(self):
        self.assertEqual(normalize_dryer_minutes(120), 120)
        self.assertEqual(normalize_dryer_minutes(999), 120)
        self.assertEqual(normalize_dryer_minutes(0), 1)

    def test_dryer_minutes_are_passed_to_create_order(self):
        runtime = Mock()
        runtime.api.devices.return_value = [
            {"id": 85579085, "imei": "869737068397394", "name": "05号烘干机", "state": 1}
        ]
        runtime.api.create_order.return_value = {"code": 500, "message": "stop"}
        runtime.device_payment_holds = {}
        intent = PreemptIntent(
            runtime,
            {"id": 85579085, "imei": "869737068397394", "name": "05号烘干机"},
            {"id": 2000807039, "name": "高温"},
            {"phone": "10000000000", "token": "token"},
            dryer_minutes=20,
        )

        with patch.object(intent, "sleep", return_value=None):
            intent.acquire_order()

        runtime.api.create_order.assert_called_once_with(
            "token",
            "05号烘干机",
            85579085,
            2000807039,
            dryer_minutes=20,
        )

    def test_two_person_queue_uses_reserve_endpoint_for_polling(self):
        runtime = Mock()
        runtime.api.devices.return_value = [
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机", "state": 2, "enableReserve": False, "reserveState": 0}
        ]
        runtime.api.reserve_order.return_value = {"code": 500, "message": "暂不可预约"}
        runtime.order_for_device.return_value = None
        intent = PreemptIntent(
            runtime,
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机"},
            {"id": 2000763282, "name": "大物"},
            {"phone": "10000000000", "token": "token"},
        )

        with patch.object(intent, "sleep", return_value=None):
            intent.acquire_order()

        runtime.api.reserve_order.assert_called_once_with(
            "token",
            "07号洗衣机",
            85575625,
            2000763282,
            dryer_minutes=None,
        )

    def test_cancel_on_stop_uses_intent_owner(self):
        runtime = Mock()
        intent = PreemptIntent(
            runtime,
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机"},
            {"id": 2000763282, "name": "大物"},
            {"phone": "10000000000", "token": "token"},
            owner_id="alice",
        )
        intent.current_order_no = "PAY-1"
        intent.cancel_on_stop = True
        intent.stop_event.set()

        intent.run()

        runtime.cancel_order.assert_called_once_with("10000000000", "PAY-1", "alice")


class IntentPersistenceTest(unittest.TestCase):
    def test_save_intents_only_persists_running_active_specs(self):
        runtime = Runtime(api=Mock())

        class FakeStop:
            def __init__(self, stopped=False):
                self.stopped = stopped

            def is_set(self):
                return self.stopped

        class FakeIntent:
            def __init__(self, running=True, stopped=False):
                self.stop_event = FakeStop(stopped)
                self.running = running

            def is_alive(self):
                return self.running

            def persist_spec(self):
                return {"goodsId": 1, "ownerId": "alice"}

        runtime.intents = {
            1: FakeIntent(running=True, stopped=False),
            2: FakeIntent(running=True, stopped=True),
            3: FakeIntent(running=False, stopped=False),
        }

        with tempfile.TemporaryDirectory() as tmpdir:
            path = os.path.join(tmpdir, "intents.json")
            with patch("haile_v3.engine.INTENTS_FILE", path):
                runtime.save_intents()
                with open(path, encoding="utf-8") as handle:
                    self.assertEqual(json.load(handle), [{"goodsId": 1, "ownerId": "alice"}])

    def test_self_clean_addon_passes_addition_select_info_and_persists(self):
        api = HaileApi()
        with patch.object(api, "_guarded_write", return_value={"code": 0, "data": {"orderNo": "ORD-SC"}}) as mock_write:
            api.create_order(
                "token",
                "07号洗衣机",
                85575625,
                2000763282,
                self_clean_item_id=2000763284,
            )
            mock_write.assert_called_once_with(
                "/trade/create",
                {
                    "purchaseList": [
                        {
                            "goodsId": 85575625,
                            "goodsItemId": 2000763282,
                            "soldType": 1,
                            "amount": 1,
                            "num": 1,
                        }
                    ],
                    "optionalInfo": {
                        "additionSelectInfo": [
                            {
                                "goodsId": 85575625,
                                "goodsItemId": 2000763284,
                                "soldType": 1,
                                "amount": 1,
                                "num": 1,
                            }
                        ]
                    },
                },
                "token",
                85575625,
                "instant",
            )

            mock_write.reset_mock()
            api.reserve_order(
                "token",
                "07号洗衣机",
                85575625,
                2000763282,
                self_clean_item_id=2000763284,
            )
            mock_write.assert_called_once_with(
                "/trade/reserveCreate",
                {
                    "purchaseList": [
                        {
                            "goodsId": 85575625,
                            "goodsItemId": 2000763282,
                            "soldType": 1,
                            "amount": 1,
                            "num": 1,
                        }
                    ],
                    "optionalInfo": {
                        "additionSelectInfo": [
                            {
                                "goodsId": 85575625,
                                "goodsItemId": 2000763284,
                                "soldType": 1,
                                "amount": 1,
                                "num": 1,
                            }
                        ]
                    },
                    "reserveMethod": 1,
                },
                "token",
                85575625,
                "reserve",
            )

    def test_detail_summary_infers_self_clean_from_unique_info_or_items(self):
        detail = {
            "orderNo": "ORD-SC-1",
            "state": 50,
            "stateDesc": "自洁中",
            "pageCode": "self_clean",
            "orderType": 300,
            "buttonSwitch": {"canPay": False, "canCancel": True},
            "uniqueInfo": {"selfCleanInfo": {"selfClean": True}},
            "orderItemList": [
                {"goodsId": 85575625, "goodsName": "07号洗衣机", "goodsItemName": "大物"},
                {"goodsId": 85575625, "goodsName": "07号洗衣机", "goodsItemName": "桶自洁", "selfClean": True},
            ],
        }
        summary = summarize_detail(detail, "10000000000")
        self.assertTrue(summary["selfClean"])
        self.assertEqual(summary["stage"], "reserve_waiting")
        self.assertEqual(summary["stageLabel"], "筒自洁中")

    def test_ota_endpoint_returns_version_metadata(self):
        from fastapi.testclient import TestClient
        from haile_v3.app import app as fastapi_app

        client = TestClient(fastapi_app)
        resp = client.get("/api/ota?clientVersion=320")
        self.assertEqual(resp.status_code, 200)
        self.assertIn("no-store", resp.headers.get("cache-control", ""))
        body = resp.json()
        self.assertTrue(body["ok"])
        self.assertTrue(body["hasUpdate"])
        self.assertGreaterEqual(body["versionCode"], 410)
        self.assertTrue(body["apkUrl"].endswith("/haile-bot-v4.apk"))

        resp_same = client.get(f"/api/ota?clientVersion={body['versionCode']}")
        self.assertFalse(resp_same.json()["hasUpdate"])

    def test_refresh_account_orders_skips_detail_for_ended_orders(self):
        api = Mock()
        api.underway_orders.return_value = {"code": 0, "data": []}
        api.trade_list.return_value = {
            "code": 0,
            "data": {
                "items": [
                    {
                        "orderNo": "END-1",
                        "state": 1000,
                        "stateDesc": "已完成",
                        "pageCode": "complete",
                        "orderItemList": [{"goodsId": 85575625, "goodsName": "07号洗衣机", "goodsItemName": "大物"}],
                    },
                    {
                        "orderNo": "PAY-1",
                        "state": 100,
                        "stateDesc": "待支付",
                        "pageCode": "normal_waiting_pay",
                        "orderItemList": [{"goodsId": 85575625, "goodsName": "07号洗衣机", "goodsItemName": "大物"}],
                    },
                ]
            },
        }
        api.order_detail.return_value = {
            "code": 0,
            "data": {
                "orderNo": "PAY-1",
                "state": 100,
                "stateDesc": "待支付",
                "pageCode": "normal_waiting_pay",
                "buttonSwitch": {"canPay": True, "canCancel": True},
                "orderItemList": [{"goodsId": 85575625, "goodsName": "07号洗衣机", "goodsItemName": "大物"}],
            },
        }
        runtime = Runtime(api=api)
        acc = {"phone": "10000000000", "token": fake_jwt(), "owner_id": "alice"}
        orders = runtime.refresh_account_orders(acc)
        self.assertEqual(len(orders), 2)
        api.order_detail.assert_called_once_with(acc["token"], "PAY-1")

    def test_login_auto_fills_remark_from_account_info_nickname(self):
        api = Mock()
        token = fake_jwt()
        api.login.return_value = {"code": 0, "data": {"token": token, "userId": 123}}
        api.account_info.return_value = {"code": 0, "data": {"nickname": "海乐用户A"}}
        api.underway_orders.return_value = {"code": 0, "data": []}
        api.trade_list.return_value = {"code": 0, "data": {"items": []}}
        runtime = Runtime(api=api)
        runtime.accounts.accounts = []
        runtime.accounts.save_accounts = Mock()
        res = runtime.login("10000000000", "123456", owner_id="alice", remark="")
        self.assertTrue(res["success"])
        self.assertEqual(runtime.accounts.accounts[0]["remark"], "海乐用户A")

    def test_reserve_order_starts_in_appointment_waiting_and_not_ready_for_payment(self):
        runtime = Mock()
        runtime.lock = MagicMock()
        runtime.orders_by_no = {}
        runtime.device_payment_holds = {"07号洗衣机": time.time() + 60}
        intent = PreemptIntent(
            runtime,
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机"},
            {"id": 2000763282, "name": "大物"},
            {"phone": "10000000000", "token": "token"},
        )
        intent.handle_order_result({"code": 0, "data": {"orderNo": "RES-WAIT-1"}}, "reserve")

        order = runtime.orders_by_no["RES-WAIT-1"]
        self.assertEqual(order["pageCode"], "appointment_waiting")
        self.assertEqual(order["stageLabel"], "已预约候机")
        self.assertFalse(order["readyForUserAction"])
        self.assertFalse(order_ready_for_user_action(order))
        self.assertNotIn("07号洗衣机", runtime.device_payment_holds)

        ready_order = enrich_order({"state": 50, "pageCode": "waiting_check", "canCancel": True})
        self.assertTrue(ready_order["readyForUserAction"])
        self.assertEqual(ready_order["stageLabel"], "已到号·待验证")

    def test_devices_expose_accurate_queue_text_and_self_reservation_status(self):
        runtime = Runtime(api=Mock())
        runtime.api.devices.return_value = [
            {"id": 1, "imei": "865901075488729", "state": 2, "enableReserve": True, "reserveState": 1, "finishTime": "2099-01-01 12:30:00"},
            {"id": 2, "imei": "865901073935457", "state": 2, "enableReserve": True, "reserveState": 0, "finishTime": "2099-01-01 12:30:00"},
            {"id": 3, "imei": "865901073916986", "state": 2, "enableReserve": False, "reserveState": 0, "finishTime": "2099-01-01 12:30:00"},
        ]
        runtime.orders_by_no = {
            "RES-MY": enrich_order({
                "orderNo": "RES-MY",
                "ownerId": "alice",
                "goodsId": 2,
                "goodsName": "07号洗衣机【大物】",
                "state": 50,
                "pageCode": "appointment_waiting",
                "canCancel": True,
                "updatedAt": 100.0,
            })
        }
        devices = {d["name"]: d for d in runtime.devices("alice")}
        self.assertEqual(devices["06号洗衣机"]["stateLabel"], "运行中，可预约")
        self.assertEqual(devices["06号洗衣机"]["queueText"], "0 人排队")
        self.assertEqual(devices["07号洗衣机"]["stateLabel"], "前序运行中 · 我已预约")
        self.assertEqual(devices["07号洗衣机"]["queueText"], "我排第 1 位")
        self.assertEqual(devices["08号洗衣机"]["stateLabel"], "运行中，1人已约")
        self.assertEqual(devices["08号洗衣机"]["queueText"], "1 人排队")

    def test_reserve_failure_when_device_becomes_idle_immediately_falls_back_to_create(self):
        runtime = Mock()
        runtime.lock = MagicMock()
        runtime.orders_by_no = {}
        runtime.device_payment_holds = {}
        runtime.order_for_device.return_value = None
        runtime.api.devices.return_value = [
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机", "state": 2, "enableReserve": True, "reserveState": 1}
        ]
        runtime.api.reserve_order.return_value = {"code": 500, "message": "设备空闲，不可预约"}
        runtime.api.create_order.return_value = {"code": 0, "data": {"orderNo": "INST-FALLBACK-1"}}

        intent = PreemptIntent(
            runtime,
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机"},
            {"id": 2000763282, "name": "大物"},
            {"phone": "10000000000", "token": "token"},
        )
        with patch.object(intent, "sleep", return_value=None):
            intent.acquire_order()

        runtime.api.reserve_order.assert_called_once()
        runtime.api.create_order.assert_called_once()
        self.assertEqual(intent.current_order_no, "INST-FALLBACK-1")
        self.assertEqual(intent.state, "holding_payment")

    def test_cycle_transition_from_burst_to_cruise_still_attempts_reserve(self):
        runtime = Mock()
        runtime.lock = MagicMock()
        runtime.orders_by_no = {}
        runtime.device_payment_holds = {}
        runtime.order_for_device.return_value = None
        future_finish = (datetime.now() + timedelta(minutes=30)).strftime("%Y-%m-%d %H:%M:%S")
        runtime.api.devices.return_value = [
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机", "state": 2, "enableReserve": False, "reserveState": 0, "finishTime": future_finish}
        ]
        runtime.api.reserve_order.return_value = {"code": 0, "data": {"orderNo": "RES-AFTER-BURST"}}

        intent = PreemptIntent(
            runtime,
            {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机"},
            {"id": 2000763282, "name": "大物"},
            {"phone": "10000000000", "token": "token"},
        )
        intent.poll_phase = "burst"
        with patch.object(intent, "sleep", return_value=None):
            intent.acquire_order()

        runtime.api.reserve_order.assert_called_once()
        self.assertEqual(intent.current_order_no, "RES-AFTER-BURST")

    def test_account_conflict_not_misclassified_as_device_payment_hold(self):
        self.assertTrue(is_account_order_conflict("您有一笔等待支付的订单，请先完成支付"))
        self.assertFalse(is_device_payment_conflict("您有一笔等待支付的订单，请先完成支付"))
        self.assertTrue(is_device_payment_conflict("其他用户等待支付中"))


if __name__ == "__main__":
    unittest.main()
