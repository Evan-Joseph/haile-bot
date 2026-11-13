import requests
import json
import time
import os
import threading
import random
import sys
import base64
from datetime import datetime, timezone

# --- Configuration ---
POSITION_ID = 32577  # 泓园7号楼
API_HOST = "https://yshz-user.haier-ioc.com"
HEADERS = {
    "User-Agent": "Dart/3.7 (dart:io)",
    "appversion": "2.2.3",
    "apptype": "9",
    "Content-Type": "application/json",
    "Host": "yshz-user.haier-ioc.com",
    "Connection": "Keep-Alive",
    "Accept-Encoding": "gzip"
}

# Target Devices Mapping
TARGET_IMEIS = {
    "865901075430960": "04号洗衣机",
    "865901075488729": "06号洗衣机",
    "865901073935457": "07号洗衣机",
    "865901073916986": "08号洗衣机",
    "865901073920962": "09号洗衣机",
    "869737068397394": "05号烘干机"
}

TARGET_DEVICE_QR = {
    "865901075488729": {
        "device_code": "GTB2406241487",
        "qr_url": "https://api.sharewash.cn/mrhi/wechat.html?imei=865901075488729&deviceld=GTB2406241487",
    },
    "865901073935457": {
        "device_code": "GTB2406243590",
        "qr_url": "https://api.sharewash.cn/mrhi/wechat.html?imei=865901073935457&deviceld=GTB2406243590",
    },
    "865901073916986": {
        "device_code": "GTB2406246124",
        "qr_url": "https://api.sharewash.cn/mrhi/wechat.html?imei=865901073916986&deviceld=GTB2406246124",
    },
    "865901073920962": {
        "device_code": "GTB2406243588",
        "qr_url": "https://api.sharewash.cn/mrhi/wechat.html?imei=865901073920962&deviceld=GTB2406243588",
    },
    "869737068397394": {
        "device_code": "GYB2407151332",
        "qr_url": "https://api.sharewash.cn/mrhi/wechat.html?imei=869737068397394&deviceld=GYB2407151332",
    },
}

ACCOUNTS_FILE = "accounts.json"
AUTH_FAILURE_CODES = {2, 100002}
TERMINAL_ORDER_STATES = {401, 411, 1000, 2099}
INSTANT_ORDER_TYPE = 100
RESERVE_ORDER_TYPE = 300
DRYER_DEFAULT_MINUTES = 10
DEFAULT_SERVED_ACCOUNT_HINT = os.getenv("HAILE_TRANSFER_TARGET", "7236")
ACCOUNT_ROLES = {"service", "served"}


def decode_jwt_payload(token):
    if not token:
        return {}

    try:
        parts = token.split(".")
        if len(parts) != 3:
            return {}
        payload = parts[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload).decode("utf-8"))
    except Exception:
        return {}


def get_token_expiry(token):
    payload = decode_jwt_payload(token)
    exp = payload.get("exp")
    return int(exp) if isinstance(exp, (int, float)) else None


def format_timestamp(timestamp):
    if not timestamp:
        return None
    return datetime.fromtimestamp(timestamp).strftime("%Y-%m-%d %H:%M:%S")


def is_token_expired(token, skew_seconds=30):
    expiry = get_token_expiry(token)
    if not expiry:
        return True
    return expiry <= time.time() + skew_seconds


def mask_phone(phone):
    return str(phone or "")


def is_dryer_device_name(device_name):
    return "烘干" in str(device_name or "")


def get_device_qr_info(imei):
    return TARGET_DEVICE_QR.get(str(imei or "").strip(), {})


def infer_default_account_role(phone):
    phone = str(phone or "").strip()
    if phone and phone.endswith(str(DEFAULT_SERVED_ACCOUNT_HINT).strip()):
        return "served"
    return "service"


def normalize_account_record(acc):
    phone = str(acc.get("phone", "")).strip()
    token = str(acc.get("token", "")).strip()
    role = str(acc.get("role", "")).strip()
    if role not in ACCOUNT_ROLES:
        role = infer_default_account_role(phone)
    normalized = {
        "phone": phone,
        "token": token,
        "is_active": bool(acc.get("is_active", True)),
        "role": role,
        "owner_id": str(acc.get("owner_id") or acc.get("ownerId") or "default").strip() or "default",
        "owner_name": str(acc.get("owner_name") or acc.get("ownerName") or "").strip(),
        "remark": str(acc.get("remark") or acc.get("name") or "").strip(),
    }

    user_id = acc.get("user_id")
    if user_id is not None:
        normalized["user_id"] = user_id
    return normalized


def fetch_device_modes(goods_id, headers=None, timeout=5):
    response = requests.get(
        f"{API_HOST}/goods/normal/details?goodsId={goods_id}",
        headers=headers or HEADERS,
        timeout=timeout,
    )
    data = response.json()
    if data.get("code") != 0:
        return []
    return data.get("data", {}).get("items", [])


def select_preferred_mode(device_name, items, blacklisted_ids=None):
    blacklisted_ids = blacklisted_ids or set()

    if "烘干" in device_name:
        priorities = ["高温", "中温", "低温", "烘干"]
    else:
        priorities = ["大物", "标准", "快速", "单脱"]

    for priority in priorities:
        for item in items:
            if item.get("id") in blacklisted_ids:
                continue
            if priority in item.get("name", ""):
                return item

    for item in items:
        if item.get("id") not in blacklisted_ids:
            return item

    return None


def build_purchase_item(goods_id, goods_item_id, quantity=1):
    return {
        "goodsId": goods_id,
        "goodsItemId": goods_item_id,
        "soldType": 1,
        "amount": quantity,
        "num": quantity,
    }


def build_device_purchase_item(device_name, goods_id, goods_item_id, dryer_minutes=None):
    if is_dryer_device_name(device_name):
        minutes = int(dryer_minutes or DRYER_DEFAULT_MINUTES)
        return {
            "goodsId": goods_id,
            "goodsItemId": goods_item_id,
            "soldType": 2,
            "amount": 1,
            "num": max(1, minutes),
        }
    return build_purchase_item(goods_id, goods_item_id)


def get_position_devices(headers=None, timeout=5):
    response = requests.post(
        f"{API_HOST}/position/deviceDetailPage",
        headers=headers or HEADERS,
        json={"positionId": POSITION_ID, "page": 1, "pageSize": 50},
        timeout=timeout,
    )
    data = response.json()
    if data.get("code") != 0:
        return []
    return data.get("data", {}).get("items", [])


def get_device_snapshot(goods_id, headers=None, timeout=5):
    for item in get_position_devices(headers=headers, timeout=timeout):
        if item.get("id") == goods_id:
            return item
    return None


def extract_project_name(order_item, fallback="未知设备"):
    goods_name = order_item.get("goodsName") or fallback
    mode_name = order_item.get("goodsItemName")
    if goods_name and mode_name:
        return f"{goods_name}【{mode_name}】"
    return goods_name


def infer_order_kind(order_type):
    if order_type == INSTANT_ORDER_TYPE:
        return "instant"
    if order_type == RESERVE_ORDER_TYPE:
        return "reserve"
    return "unknown"


def is_terminal_order_state(state, page_code=None):
    return state in TERMINAL_ORDER_STATES or page_code == "timeout"


def fetch_order_detail(order_no, headers, timeout=5):
    response = requests.get(
        f"{API_HOST}/trade/order/detail?orderNo={order_no}",
        headers=headers,
        timeout=timeout,
    )
    return response.json()


def summarize_order_detail(detail):
    first_item = (detail.get("orderItemList") or [{}])[0]
    buttons = detail.get("buttonSwitch") or {}
    return {
        "orderNo": detail.get("orderNo", ""),
        "goodsName": extract_project_name(first_item),
        "state": detail.get("state", 0),
        "stateDesc": detail.get("stateDesc", "未知"),
        "createTime": detail.get("createTime") or "",
        "pageCode": detail.get("pageCode"),
        "kind": infer_order_kind(detail.get("orderType")),
        "canPay": bool(buttons.get("canPay")),
        "canCancel": bool(buttons.get("canCancel")),
    }


def list_actionable_orders(account_manager, acc, page_size=10):
    token = acc.get("token", "")
    if not token or is_token_expired(token):
        return []

    headers = account_manager.get_headers(token)
    actionable = {}

    underway = requests.post(
        f"{API_HOST}/trade/underway/orderList",
        headers=headers,
        json={},
        timeout=5,
    ).json()
    if underway.get("code") == 0:
        for order in underway.get("data", []):
            order_no = order.get("orderNo")
            if not order_no:
                continue
            state = order.get("state", 0)
            summary = {
                "orderNo": order_no,
                "goodsName": order.get("projectName") or order.get("goodsName") or "未知设备",
                "state": state,
                "stateDesc": order.get("stateDesc", "未知"),
                "createTime": order.get("deadTime", ""),
                "pageCode": "normal_waiting_pay" if state == 100 else "appointment_waiting",
                "kind": "instant" if state == 100 else "reserve",
                "canPay": state == 100,
                "canCancel": True,
            }

            try:
                detail = fetch_order_detail(order_no, headers)
                if detail.get("code") == 0:
                    summary = summarize_order_detail(detail.get("data") or {})
            except Exception:
                pass

            if summary.get("canPay") or summary.get("canCancel"):
                actionable[order_no] = summary

    trade = requests.post(
        f"{API_HOST}/trade/list",
        headers=headers,
        json={"page": 1, "pageSize": page_size, "newOrderState": "", "categoryCode": ""},
        timeout=5,
    ).json()
    if trade.get("code") != 0:
        return sorted(actionable.values(), key=lambda item: item.get("createTime") or "", reverse=True)

    for item in trade.get("data", {}).get("items", []):
        order_no = item.get("orderNo")
        if not order_no or order_no in actionable:
            continue
        if is_terminal_order_state(item.get("state")):
            continue

        summary = {
            "orderNo": order_no,
            "goodsName": extract_project_name((item.get("orderItemList") or [{}])[0]),
            "state": item.get("state", 0),
            "stateDesc": item.get("stateDesc", "未知"),
            "createTime": item.get("createTime") or "",
            "pageCode": None,
            "kind": infer_order_kind(item.get("orderType")),
            "canPay": item.get("state") == 100,
            "canCancel": not is_terminal_order_state(item.get("state")),
        }

        try:
            detail = fetch_order_detail(order_no, headers)
            if detail.get("code") == 0:
                summary = summarize_order_detail(detail.get("data") or {})
        except Exception:
            pass

        if summary.get("canPay") or summary.get("canCancel"):
            actionable[order_no] = summary

    return sorted(actionable.values(), key=lambda item: item.get("createTime") or "", reverse=True)

class AccountManager:
    def __init__(self):
        self.accounts = []
        self.lock = threading.Lock()
        self.load_accounts()

    def load_accounts(self):
        if os.path.exists(ACCOUNTS_FILE):
            try:
                with open(ACCOUNTS_FILE, 'r') as f:
                    raw_accounts = json.load(f)

                self.accounts = []
                for item in raw_accounts:
                    if not isinstance(item, dict):
                        continue
                    acc = normalize_account_record(item)
                    if not acc["phone"]:
                        continue
                    acc["active_tasks"] = 0
                    self.accounts.append(acc)
            except Exception:
                self.accounts = []

    def save_accounts(self):
        with open(ACCOUNTS_FILE, 'w') as f:
            to_save = [normalize_account_record(acc) for acc in self.accounts]
            json.dump(to_save, f, indent=2)

    def set_account_active(self, phone, is_active):
        for acc in self.accounts:
            if acc.get("phone") == phone:
                acc["is_active"] = bool(is_active)
                break
        self.save_accounts()

    def set_account_role(self, phone, role):
        if role not in ACCOUNT_ROLES:
            return
        for acc in self.accounts:
            if acc.get("phone") == phone:
                acc["role"] = role
                break
        self.save_accounts()

    def add_account(self):
        phone = input("Enter Phone Number: ")
        # Login flow
        try:
            requests.post(f"{API_HOST}/login/getCode", headers=HEADERS, json={"target": phone, "sendType": 1, "method": 1})
        except Exception as e:
            print(f"Error sending code: {e}")
            return

        code = input(f"Enter verification code for {phone}: ")
        resp = requests.post(f"{API_HOST}/login/login", headers=HEADERS, json={
            "account": phone, "loginType": 2, "verificationCode": code, "authorizationClientType": "9"
        })
        res = resp.json()
        
        if res.get('code') == 0:
            new_acc = {
                'phone': phone,
                'token': res['data']['token'],
                'user_id': res['data']['userId'],
                'is_active': True,
                'active_tasks': 0
            }
            # Remove old if exists
            self.accounts = [a for a in self.accounts if a['phone'] != phone]
            self.accounts.append(new_acc)
            self.save_accounts()
            print(f"Login successful for {phone}.")
        else:
            print(f"Login failed: {res.get('message')}")

    def list_accounts(self):
        print(f"\nCurrent Accounts ({len(self.accounts)}):")
        for i, acc in enumerate(self.accounts):
            status = "enabled" if acc.get("is_active", True) else "disabled"
            expiry = format_timestamp(get_token_expiry(acc.get("token")))
            role = acc.get("role", infer_default_account_role(acc.get("phone")))
            print(f"{i+1}. {mask_phone(acc['phone'])} [{role}/{status}] exp={expiry or 'unknown'}")

    def get_headers(self, token):
        h = HEADERS.copy()
        h['Authorization'] = token
        return h

    def cancel_all_orders(self):
        print("\n--- Cancelling ALL active orders for ALL accounts ---")
        for acc in self.accounts:
            try:
                if not acc.get("is_active", True) or is_token_expired(acc.get("token")):
                    print(f"[{mask_phone(acc['phone'])}] Skipped: token expired or account disabled.")
                    continue

                orders = list_actionable_orders(self, acc)
                if not orders:
                    print(f"[{mask_phone(acc['phone'])}] No actionable orders.")
                    continue

                for order in orders:
                    if not order.get("canCancel"):
                        continue

                    order_no = order['orderNo']
                    name = order.get('goodsName', 'Unknown Device')
                    print(f"[{mask_phone(acc['phone'])}] Cancelling {name} ({order_no})...")
                    
                    url_cancel = f"{API_HOST}/trade/cancel"
                    c_resp = requests.post(url_cancel, headers=self.get_headers(acc['token']), json={"orderNo": order_no})
                    if c_resp.json().get('code') == 0:
                        print("  -> Success.")
                    else:
                        print(f"  -> Failed: {c_resp.json().get('message')}")
            except Exception as e:
                print(f"[{mask_phone(acc['phone'])}] Error: {e}")

class PreemptTask(threading.Thread):
    def __init__(self, device_info, account_manager):
        super().__init__()
        self.device = device_info
        self.am = account_manager
        self.stop_event = threading.Event()
        self.daemon = True
        self.current_mode_id = None
        self.current_mode_name = None
        self.blacklisted_ids = set()
        self.needs_manual_restart = False

    def run(self):
        device_name = self.device['name']
        print(f"[{device_name}] Monitoring started...")

        while not self.stop_event.is_set():
            if self.needs_manual_restart:
                time.sleep(1)
                continue

            # Round-robin selection of account
            # In a real heavy-load scenario, we might want to check account rate limits
            # For now, just pick a random available one to distribute load
            candidates = [
                acc for acc in self.am.accounts
                if acc.get("is_active", True) and not is_token_expired(acc.get("token"))
            ]
            if not candidates:
                print(f"[{device_name}] No usable accounts. Re-login required.")
                time.sleep(5)
                continue

            acc = random.choice(candidates) # Simple random load balancing

            try:
                if not self.current_mode_id:
                    items = fetch_device_modes(self.device["goodsId"], headers=HEADERS)
                    selected = select_preferred_mode(device_name, items, self.blacklisted_ids)
                    if not selected:
                        time.sleep(1.2)
                        continue
                    self.current_mode_id = selected["id"]
                    self.current_mode_name = selected["name"]
                    print(f"[{device_name}] Selected mode: {self.current_mode_name}")

                device_snapshot = get_device_snapshot(self.device["goodsId"], headers=HEADERS)
                if not device_snapshot:
                    print(f"[{device_name}] Device state unavailable. Retrying...")
                    time.sleep(1.2)
                    continue

                order_kind = "instant" if device_snapshot.get("state") == 1 else "reserve"
                if order_kind == "instant":
                    url = f"{API_HOST}/trade/create"
                    payload = {
                        "purchaseList": [build_device_purchase_item(device_name, self.device['goodsId'], self.current_mode_id)]
                    }
                else:
                    url = f"{API_HOST}/trade/reserveCreate"
                    payload = {
                        "purchaseList": [build_device_purchase_item(device_name, self.device['goodsId'], self.current_mode_id)],
                        "optionalInfo": {"additionSelectInfo": []},
                        "reserveMethod": 1,
                    }

                resp = requests.post(url, headers=self.am.get_headers(acc['token']), json=payload, timeout=5)
                res = resp.json()

                if res.get('code') == 0:
                    order_no = res['data']['orderNo']
                    if order_kind == "instant":
                        expire_seconds = (res.get("data") or {}).get("expireSeconds")
                        expire_desc = f"{int(expire_seconds / 60)} minutes to pay" if expire_seconds else "Pay in App"
                        print(
                            f"\n!!! SUCCESS !!!\nType: Instant Order\nDevice: {device_name}\n"
                            f"Mode: {self.current_mode_name}\n"
                            f"Account: {mask_phone(acc['phone'])}\nOrder: {order_no}\n"
                            f"Next: {expire_desc}\n"
                        )
                    else:
                        print(
                            f"\n!!! SUCCESS !!!\nType: Reserve Order\nDevice: {device_name}\n"
                            f"Mode: {self.current_mode_name}\n"
                            f"Account: {mask_phone(acc['phone'])}\nOrder: {order_no}\n"
                        )
                    print(
                        "[Hint] Order has been created successfully. Complete payment in the Haile app if required."
                    )
                    self.stop_event.set()
                    return

                elif res.get("code") in AUTH_FAILURE_CODES or "登录已失效" in res.get("message", ""):
                    print(f"[Warn] Token expired for {mask_phone(acc['phone'])}. Please re-login.")
                elif res.get("code") == 100000:
                    message = res.get("message", "")
                    if any(key in message for key in ["预约单", "存在", "订单进行中", "等待支付", "已有一笔订单"]):
                        print(f"[{device_name}] Account already has an order: {mask_phone(acc['phone'])}")
                    else:
                        self.blacklisted_ids.add(self.current_mode_id)
                        self.current_mode_id = None
                        self.current_mode_name = None
                elif res.get("code") == 500 and "空闲" in res.get("message", ""):
                    pass
                elif "占用" in res.get("message", "") or "使用中" in res.get("message", ""):
                    pass
                else:
                    print(f"[{device_name}] {res.get('message', 'Unknown error')} ({res.get('code')})")

                # Else: device busy, etc. Just logging dot to show activity
                # print(".", end="", flush=True)

            except Exception as e:
                print(f"[{device_name}] Network error: {e}")

            time.sleep(1.2) # Polling interval

def interactive_menu():
    am = AccountManager()
    tasks = []

    while True:
        print("\n=== 海乐生活 Haile Bot ===")
        print("1. Add Account")
        print("2. List Accounts")
        print("3. Start Preemption (Select Targets)")
        print("4. Cancel/Release All Orders (One-Key)")
        print("5. Stop All Tasks")
        print("6. Exit")
        
        c = input("Choice: ")
        
        if c == '1':
            am.add_account()
        elif c == '2':
            am.list_accounts()
        elif c == '3':
            if not am.accounts:
                print("No accounts!")
                continue

            usable_accounts = [
                acc for acc in am.accounts
                if acc.get("is_active", True) and not is_token_expired(acc.get("token"))
            ]
            if not usable_accounts:
                print("No usable accounts. Please re-login first.")
                continue
                
            # Scan
            print("Scanning devices info...")
            try:
                items = get_position_devices(headers=HEADERS)
            except Exception:
                print("Scan failed. Check network or token.")
                continue

            targets_map = {}
            print("\nAvailable Devices:")
            for item in items:
                imei = item.get('imei')
                if imei in TARGET_IMEIS:
                    name = TARGET_IMEIS[imei]
                    targets_map[str(len(targets_map)+1)] = {
                        'name': name,
                        'goodsId': item['id'],
                        'imei': imei
                    }
                    state = "Idle" if item['state'] == 1 else "Busy"
                    print(f"{len(targets_map)}. {name} [{state}]")
            
            sel = input("Enter numbers to preempt (e.g. 1,3) or 'all': ")
            selected_devices = []
            if sel == 'all':
                selected_devices = list(targets_map.values())
            else:
                for s in sel.split(','):
                    if s.strip() in targets_map:
                        selected_devices.append(targets_map[s.strip()])
            
            if not selected_devices:
                continue
                
            print(f"Starting {len(selected_devices)} tasks...")
            for dev in selected_devices:
                t = PreemptTask(dev, am)
                t.start()
                tasks.append(t)
                
        elif c == '4':
            am.cancel_all_orders()
            
        elif c == '5':
            for t in tasks:
                t.stop_event.set()
            tasks = []
            print("Tasks stopped.")
            
        elif c == '6':
            for t in tasks:
                t.stop_event.set()
            sys.exit(0)

if __name__ == "__main__":
    interactive_menu()
