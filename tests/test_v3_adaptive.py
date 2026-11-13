import unittest
from datetime import datetime, timedelta
from unittest.mock import MagicMock, Mock, patch

from haile_v3.domain import (
    BURST_ATTEMPT_LIMIT,
    BURST_COOLDOWN_RANGE,
    POLL_APPROACH_RANGE,
    POLL_BURST_RANGE,
    POLL_CRUISE_RANGE,
    POLL_WATCH_SECONDS,
    plan_poll,
)
from haile_v3.engine import PreemptIntent


class PlanPollTest(unittest.TestCase):
    def test_unknown_release_falls_back_to_watch_polling(self):
        plan = plan_poll(None)

        self.assertEqual(plan.phase, "watch")
        self.assertEqual(plan.delay, POLL_WATCH_SECONDS)
        self.assertTrue(plan.attempt)

    def test_cruise_when_release_is_far(self):
        for remaining in (61.0, 300.0, 3600.0):
            plan = plan_poll(remaining)
            self.assertEqual(plan.phase, "cruise")
            self.assertGreaterEqual(plan.delay, POLL_CRUISE_RANGE[0])
            self.assertLessEqual(plan.delay, POLL_CRUISE_RANGE[1])
            self.assertFalse(plan.attempt)

    def test_approach_when_release_is_near(self):
        for remaining in (10.5, 30.0, 59.9):
            plan = plan_poll(remaining)
            self.assertEqual(plan.phase, "approach")
            self.assertGreaterEqual(plan.delay, POLL_APPROACH_RANGE[0])
            self.assertLessEqual(plan.delay, POLL_APPROACH_RANGE[1])
            self.assertTrue(plan.attempt)

    def test_burst_when_release_is_imminent(self):
        for remaining in (10.0, 3.0, 0.0, -5.0):
            plan = plan_poll(remaining, burst_attempts=0)
            self.assertEqual(plan.phase, "burst")
            self.assertGreaterEqual(plan.delay, POLL_BURST_RANGE[0])
            self.assertLessEqual(plan.delay, POLL_BURST_RANGE[1])
            self.assertTrue(plan.attempt)

    def test_burst_cooldown_after_attempt_limit(self):
        plan = plan_poll(5.0, burst_attempts=BURST_ATTEMPT_LIMIT)

        self.assertEqual(plan.phase, "cooldown")
        self.assertGreaterEqual(plan.delay, BURST_COOLDOWN_RANGE[0])
        self.assertLessEqual(plan.delay, BURST_COOLDOWN_RANGE[1])
        self.assertFalse(plan.attempt)

    def test_boundary_60_seconds_is_approach(self):
        plan = plan_poll(60.0)

        self.assertEqual(plan.phase, "approach")
        self.assertTrue(plan.attempt)

    def test_boundary_60_plus_epsilon_is_cruise(self):
        plan = plan_poll(60.01)

        self.assertEqual(plan.phase, "cruise")
        self.assertFalse(plan.attempt)


DEVICE = {"id": 85575625, "imei": "865901073935457", "name": "07号洗衣机"}
BUSY_FULL = {"id": 85575625, "imei": "865901073935457", "state": 2, "enableReserve": False, "reserveState": 0}
MODE = {"id": 2000763282, "name": "大物"}
ACCOUNT = {"phone": "10000000000", "token": "token"}


def dead_time(seconds: float) -> str:
    return (datetime.now() + timedelta(seconds=seconds)).strftime("%Y-%m-%d %H:%M:%S")


class IntentAdaptivePollTest(unittest.TestCase):
    def make_runtime(self, dead_seconds=None):
        runtime = Mock()
        runtime.api.devices.return_value = [dict(BUSY_FULL)]
        runtime.api.run_info.return_value = {
            "code": 0,
            "data": {"deadTime": dead_time(dead_seconds)} if dead_seconds is not None else {},
        }
        runtime.api.reserve_order.return_value = {"code": 500, "message": "暂不可预约"}
        runtime.order_for_device.return_value = None
        return runtime

    def make_intent(self, runtime):
        intent = PreemptIntent(runtime, dict(DEVICE), dict(MODE), dict(ACCOUNT))
        intent.sleeps = []
        intent.sleep = lambda seconds, *a, **k: intent.sleeps.append(seconds)
        return intent

    def test_cruise_probes_without_write(self):
        runtime = self.make_runtime(dead_seconds=300)
        intent = self.make_intent(runtime)

        intent.acquire_order()

        runtime.api.reserve_order.assert_not_called()
        self.assertEqual(intent.poll_phase, "cruise")
        self.assertEqual(len(intent.sleeps), 1)
        self.assertGreaterEqual(intent.sleeps[0], POLL_CRUISE_RANGE[0])
        self.assertLessEqual(intent.sleeps[0], POLL_CRUISE_RANGE[1])

    def test_approach_attempts_with_mid_interval(self):
        runtime = self.make_runtime(dead_seconds=30)
        intent = self.make_intent(runtime)

        intent.acquire_order()

        runtime.api.reserve_order.assert_called_once()
        self.assertEqual(intent.poll_phase, "approach")
        self.assertGreaterEqual(intent.sleeps[-1], POLL_APPROACH_RANGE[0])
        self.assertLessEqual(intent.sleeps[-1], POLL_APPROACH_RANGE[1])

    def test_burst_attempts_with_subsecond_interval(self):
        runtime = self.make_runtime(dead_seconds=5)
        intent = self.make_intent(runtime)

        intent.acquire_order()

        runtime.api.reserve_order.assert_called_once()
        self.assertEqual(intent.poll_phase, "burst")
        self.assertEqual(intent.burst_attempts, 1)
        self.assertGreaterEqual(intent.sleeps[-1], POLL_BURST_RANGE[0])
        self.assertLessEqual(intent.sleeps[-1], POLL_BURST_RANGE[1])

    def test_burst_limit_triggers_cooldown_without_write(self):
        runtime = self.make_runtime(dead_seconds=5)
        intent = self.make_intent(runtime)
        intent.burst_attempts = BURST_ATTEMPT_LIMIT

        intent.acquire_order()

        runtime.api.reserve_order.assert_not_called()
        self.assertEqual(intent.poll_phase, "cooldown")
        self.assertEqual(intent.burst_attempts, 0)
        self.assertGreaterEqual(intent.sleeps[-1], BURST_COOLDOWN_RANGE[0])
        self.assertLessEqual(intent.sleeps[-1], BURST_COOLDOWN_RANGE[1])

    def test_no_release_estimate_falls_back_to_watch(self):
        runtime = self.make_runtime(dead_seconds=None)
        intent = self.make_intent(runtime)

        intent.acquire_order()

        runtime.api.reserve_order.assert_called_once()
        self.assertEqual(intent.poll_phase, "watch")
        self.assertEqual(intent.sleeps[-1], POLL_WATCH_SECONDS)

    def test_device_finish_time_skips_run_info_call(self):
        runtime = self.make_runtime(dead_seconds=None)
        raw = dict(BUSY_FULL, finishTime=dead_time(45))
        intent = self.make_intent(runtime)

        intent.update_release_estimate(raw)

        runtime.api.run_info.assert_not_called()
        self.assertIsNotNone(intent.release_at)


if __name__ == "__main__":
    unittest.main()
