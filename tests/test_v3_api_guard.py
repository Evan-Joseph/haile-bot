import unittest
from datetime import datetime, timedelta
from unittest.mock import Mock, patch

import requests

from haile_v3.api import HaileApi
from haile_v3.domain import is_auth_failure


def future_dead_time(seconds: int) -> str:
    return (datetime.now() + timedelta(seconds=seconds)).strftime("%Y-%m-%d %H:%M:%S")


def underway_item(order_no, goods_id, state=100, order_type=None):
    item = {
        "orderNo": order_no,
        "state": state,
        "orderItemList": [{"goodsId": goods_id}],
    }
    if order_type is not None:
        item["orderType"] = order_type
    return item


class HttpStatusMappingTest(unittest.TestCase):
    """上游非 2xx 不能再落到「非 JSON」泛错：401 视为登录失效，其余显式标记。"""

    @staticmethod
    def fake_resp(status: int, payload=None):
        resp = Mock()
        resp.status_code = status
        resp.json.return_value = payload if payload is not None else {"code": 0}
        resp.text = ""
        return resp

    def test_http_401_maps_to_auth_failure(self):
        result = HaileApi._parse_json(self.fake_resp(401))
        self.assertTrue(is_auth_failure(result["code"], result["message"]))

    def test_http_5xx_maps_to_upstream_error(self):
        result = HaileApi._parse_json(self.fake_resp(503))
        self.assertEqual(result["code"], -3)
        self.assertIn("503", result["message"])

    def test_http_200_still_parses_json(self):
        result = HaileApi._parse_json(self.fake_resp(200, {"code": 0, "data": [1]}))
        self.assertEqual(result["code"], 0)


class GuardedWriteTest(unittest.TestCase):
    def make_api(self):
        return HaileApi(timeout=1.0)

    def test_create_order_recovers_pending_after_read_timeout(self):
        api = self.make_api()
        item = underway_item("PAY-9", 85575625, state=100)
        item["deadTime"] = future_dead_time(240)
        underway = {"code": 0, "data": [item]}

        with patch.object(api, "_post_once", side_effect=requests.exceptions.ReadTimeout("read timeout")), \
             patch.object(api, "underway_orders", return_value=underway) as mock_underway:
            result = api.create_order("token-1", "07号洗衣机", 85575625, 2000763282)

        mock_underway.assert_called_once_with("token-1")
        self.assertEqual(result["code"], 0)
        self.assertEqual(result["data"]["orderNo"], "PAY-9")
        self.assertTrue(result["recovered"])
        self.assertTrue(0 < result["data"]["expireSeconds"] <= 240)

    def test_create_order_marks_ambiguous_when_recheck_finds_nothing(self):
        api = self.make_api()
        underway = {"code": 0, "data": [underway_item("PAY-OTHER", 999)]}

        with patch.object(api, "_post_once", side_effect=requests.exceptions.ConnectionError("reset")), \
             patch.object(api, "underway_orders", return_value=underway):
            result = api.create_order("token-1", "07号洗衣机", 85575625, 2000763282)

        self.assertEqual(result["code"], -1)
        self.assertTrue(result["ambiguous"])
        self.assertIn("网络请求失败", result["message"])

    def test_reserve_recheck_accepts_reserve_type_orders(self):
        api = self.make_api()
        underway = {"code": 0, "data": [underway_item("RES-1", 85575625, state=50, order_type=300)]}

        with patch.object(api, "_post_once", side_effect=requests.exceptions.ReadTimeout("t")), \
             patch.object(api, "underway_orders", return_value=underway):
            result = api.reserve_order("token-1", "07号洗衣机", 85575625, 2000763282)

        self.assertEqual(result["code"], 0)
        self.assertEqual(result["data"]["orderNo"], "RES-1")

    def test_reserve_recheck_rejects_instant_state(self):
        api = self.make_api()
        underway = {"code": 0, "data": [underway_item("PAY-1", 85575625, state=100)]}

        with patch.object(api, "_post_once", side_effect=requests.exceptions.ReadTimeout("t")), \
             patch.object(api, "underway_orders", return_value=underway):
            result = api.reserve_order("token-1", "07号洗衣机", 85575625, 2000763282)

        self.assertEqual(result["code"], -1)
        self.assertTrue(result["ambiguous"])

    def test_write_never_replays_request(self):
        api = self.make_api()
        calls = []

        def fail_once(path, body, token=None, timeout=None):
            calls.append(path)
            raise requests.exceptions.ReadTimeout("t")

        with patch.object(api, "_post_once", side_effect=fail_once), \
             patch.object(api, "underway_orders", return_value={"code": 0, "data": []}), \
             patch.object(api, "trade_list", return_value={"code": 0, "data": {"items": []}}):
            api.create_order("token-1", "07号洗衣机", 85575625, 2000763282)

        self.assertEqual(calls, ["/trade/create"])

    def test_per_account_sessions_are_isolated(self):
        api = self.make_api()

        session_a = api.session_for("token-aaa")
        session_b = api.session_for("token-bbb")
        session_a_again = api.session_for("token-aaa")

        self.assertIs(session_a, session_a_again)
        self.assertIsNot(session_a, session_b)
        self.assertIs(api.session_for(None), api.session)


if __name__ == "__main__":
    unittest.main()
