import {
  $,
  POOL_WASHER_MODES,
  STATE_SYNC_INTERVAL_MS,
  accountReady,
  activeIntents,
  activeOrders,
  api,
  formatDuration,
  haptic,
  isNativeRuntime,
  isOrderReadyToPay,
  ownerPayload,
  ownerQuery,
  post,
  primaryAccount,
  remainingFromOrder,
  savePrefs,
  store,
  toast,
} from "./store.js";
import {
  activeIntentForDevice,
  canOpenDevice,
  deviceCycleProgress,
  etaText,
  modeSupportsSelfClean,
  orderForIntent,
  orderTimeParts,
  renderAccountSheet,
  renderDevices,
  renderHeroZone,
  renderModeSheetContent,
  renderOtaSheet,
  renderPoolModeSheet,
  renderPositionSheet,
  renderTopbar,
} from "./render.js";
import { bindNativeOtaCallback, checkOtaUpdate, installOtaUpdate, openHailePay, syncNativePayChannels } from "./ota.js";
import { SlippyMapPicker, requestCurrentGcjLocation } from "./map.js";

let mapPicker = null;

function effectiveDarkTheme() {
  if (store.theme === "dark") return true;
  if (store.theme === "light") return false;
  return Boolean(window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
}

function applyTheme(nextTheme) {
  store.theme = nextTheme;
  const meta = $("colorSchemeMeta");
  const themeMeta = $("themeColorMeta");
  if (nextTheme === "dark" || nextTheme === "light") {
    localStorage.setItem("haileTheme", nextTheme);
    document.documentElement.setAttribute("data-theme", nextTheme);
    if (meta) meta.setAttribute("content", nextTheme);
  } else {
    localStorage.removeItem("haileTheme");
    document.documentElement.removeAttribute("data-theme");
    if (meta) meta.setAttribute("content", "light dark");
  }
  const isDark = effectiveDarkTheme();
  if (themeMeta) themeMeta.setAttribute("content", isDark ? "#0b101b" : "#f3f5f9");
  try {
    if (window.HaileNative && typeof window.HaileNative.setDarkTheme === "function") {
      window.HaileNative.setDarkTheme(isDark);
    }
  } catch (_) {}
  const icon = $("themeIcon");
  if (icon) {
    icon.innerHTML = isDark
      ? '<circle cx="12" cy="12" r="4"></circle><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"></path>'
      : '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"></path>';
  }
  $("themeBtn")?.setAttribute("aria-label", isDark ? "切换为浅色模式" : "切换为深色模式");
}

function toggleTheme() {
  applyTheme(effectiveDarkTheme() ? "light" : "dark");
  haptic("start");
}

function syncBellBtn() {
  const btn = $("bellBtn");
  if (!btn) return;
  let on = false;
  const storedMuted = localStorage.getItem("haileNotif") === "0";
  if (isNativeRuntime()) {
    try {
      const sysEnabled =
        typeof window.HaileNative.areNotificationsEnabled === "function"
          ? Boolean(window.HaileNative.areNotificationsEnabled())
          : true;
      const nativeMuted =
        typeof window.HaileNative.isNotificationsMuted === "function"
          ? Boolean(window.HaileNative.isNotificationsMuted())
          : false;
      const muted = storedMuted || nativeMuted;
      if (muted !== nativeMuted && typeof window.HaileNative.setNotificationsMuted === "function") {
        window.HaileNative.setNotificationsMuted(muted);
      }
      localStorage.setItem("haileNotif", muted ? "0" : "1");
      store.notifEnabled = !muted && sysEnabled;
      on = store.notifEnabled;
    } catch (_) {
      on = !storedMuted;
      store.notifEnabled = on;
    }
  } else {
    store.notifEnabled = !storedMuted;
    on = store.notifEnabled;
  }

  btn.classList.toggle("on", on);
  btn.setAttribute("aria-pressed", on ? "true" : "false");
  btn.setAttribute("aria-label", on ? "锁单与收衣提醒已开启，点击静音" : "锁单与收衣提醒已静音，点击开启");
  btn.innerHTML = on
    ? `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8"></path><path d="M13.7 21a2 2 0 0 1-3.4 0"></path></svg>`
    : `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8"></path><path d="M13.7 21a2 2 0 0 1-3.4 0"></path><path d="m4 4 16 16"></path></svg>`;
}

function ringBellBtn() {
  const btn = $("bellBtn");
  if (!btn) return;
  btn.classList.remove("ringing");
  void btn.offsetWidth;
  btn.classList.add("ringing");
  setTimeout(() => btn.classList.remove("ringing"), 600);
}

async function toggleNotifications() {
  if (isNativeRuntime()) {
    try {
      const sysEnabled = Boolean(window.HaileNative.areNotificationsEnabled());
      const muted =
        typeof window.HaileNative.isNotificationsMuted === "function"
          ? Boolean(window.HaileNative.isNotificationsMuted())
          : localStorage.getItem("haileNotif") === "0";
      if (!sysEnabled) {
        window.HaileNative.setNotificationsMuted?.(false);
        localStorage.setItem("haileNotif", "1");
        window.HaileNative.requestNotificationPermission?.();
        toast("请在系统设置中允许通知权限", "warning");
        setTimeout(syncBellBtn, 600);
        return;
      }
      const nextMuted = !muted;
      window.HaileNative.setNotificationsMuted?.(nextMuted);
      store.notifEnabled = !nextMuted;
      localStorage.setItem("haileNotif", nextMuted ? "0" : "1");
      syncBellBtn();
      if (nextMuted) {
        try { navigator.vibrate?.(0); } catch (_) {}
        toast("已静音：关闭弹窗与震动提醒");
      } else {
        haptic("start");
        ringBellBtn();
        window.HaileNative.requestIgnoreBatteryOptimizations?.();
        toast("已开启锁单与收衣提醒", "success");
      }
      return;
    } catch (_) {}
  }

  if (store.notifEnabled && localStorage.getItem("haileNotif") !== "0") {
    store.notifEnabled = false;
    localStorage.setItem("haileNotif", "0");
    syncBellBtn();
    try { navigator.vibrate?.(0); } catch (_) {}
    toast("已静音：关闭锁单与震动提醒");
    return;
  }

  store.notifEnabled = true;
  localStorage.setItem("haileNotif", "1");
  syncBellBtn();
  haptic("start");
  ringBellBtn();

  if ("Notification" in window && window.isSecureContext && Notification.permission === "default") {
    try {
      const perm = await Notification.requestPermission();
      if (perm === "granted") {
        toast("已开启系统级锁单与收衣提醒", "success");
        return;
      }
    } catch (_) {}
  }
  toast(
    "Notification" in window && Notification.permission === "granted"
      ? "已开启系统级锁单与收衣提醒"
      : "已开启页面内锁单与收衣提醒",
    "success"
  );
}

function isTyping() {
  const el = document.activeElement;
  return Boolean(el && ["INPUT", "TEXTAREA", "SELECT"].includes(el.tagName));
}

function openSheet(id) {
  const wrap = $(id);
  if (!wrap) return;
  wrap.classList.add("open");
  wrap.setAttribute("aria-hidden", "false");
}

function closeSheet(id) {
  const wrap = $(id);
  if (!wrap) return;
  wrap.classList.remove("open");
  wrap.setAttribute("aria-hidden", "true");
  if (store.pendingRender && !document.querySelector(".sheet-wrap.open")) {
    store.pendingRender = false;
    renderAll();
  }
}

function renderAll() {
  renderTopbar();
  renderHeroZone();
  renderDevices();
  if ($("positionSheet")?.classList.contains("open")) {
    renderPositionSheet();
    mapPicker?.setMarkers(store.nearbyPositions, store.data?.position?.positionId);
  }
  if ($("accountSheet")?.classList.contains("open")) {
    renderAccountSheet();
  }
  if ($("otaSheet")?.classList.contains("open")) {
    renderOtaSheet();
  }
}

function renderSafely({ force = false } = {}) {
  if (!force && (isTyping() || $("modeSheet")?.classList.contains("open") || store.busy.size > 0)) {
    store.pendingRender = true;
    renderTopbar();
    if ($("positionSheet")?.classList.contains("open") && !isTyping()) {
      renderPositionSheet();
    }
    if ($("accountSheet")?.classList.contains("open") && !isTyping()) {
      renderAccountSheet();
    }
    if ($("otaSheet")?.classList.contains("open")) {
      renderOtaSheet();
    }
    return;
  }
  store.pendingRender = false;
  renderAll();
}

function fireWebNotification(title, body, tag) {
  if (!store.notifEnabled || isNativeRuntime()) return;
  if ("Notification" in window && Notification.permission === "granted") {
    try {
      new Notification(title, { body, tag, icon: "/icon-192.png" });
    } catch (_) {}
  }
}

function checkOrderAlerts(data) {
  const isFirst = !store.ordersSeeded;
  store.ordersSeeded = true;
  for (const order of data.orders || []) {
    if (!["awaiting_payment", "reserve_waiting"].includes(order.stage)) continue;
    const ready = isOrderReadyToPay(order);
    if (!ready) continue;
    const key = `${order.orderNo}:${order.stage}:ready`;
    if (store.alertedOrders.has(key)) continue;
    store.alertedOrders.add(key);
    if (isFirst) continue;
    if (!store.notifEnabled) continue;
    if (!isNativeRuntime()) haptic("success");
    const dev = String(order.goodsName || "设备").split("【")[0];
    const title = order.stage === "awaiting_payment" ? `已抢到 ${dev} · 待支付` : `已排到 ${dev} · 请验证`;
    toast(title, "success");
    fireWebNotification(title, "请尽快打开海乐生活小程序或 App 完成操作", key);
  }
}

function handlePickupEvent(ev) {
  if (!ev || ev.type !== "pickup") return;
  if (ev.ownerId && ev.ownerId !== store.owner.id && ev.ownerId !== "default") return;
  const key = `${ev.orderNo}:${ev.stage}`;
  if (store.alertedPickups.has(key)) return;
  store.alertedPickups.add(key);
  if (!store.notifEnabled) return;
  const dev = ev.deviceName || "洗衣设备";
  const title = ev.stage === "pickup_done" ? `${dev} 洗烘已完成` : `${dev} 即将洗完`;
  const body = ev.stage === "pickup_done" ? "请及时前往洗衣房取回衣物" : "预计 3 分钟内结束，可准备前往收衣";
  if (!isNativeRuntime()) haptic("warning");
  toast(`${title} · ${body}`, ev.stage === "pickup_done" ? "success" : "warning");
  fireWebNotification(title, body, key);
}

async function refresh({ silent = false, forceRender = false } = {}) {
  if (store.refreshing) return;
  store.refreshing = true;
  $("refreshBtn")?.classList.add("spinning");
  try {
    const data = await api(`/api/state?${ownerQuery()}`);
    store.data = data;
    store.lastSyncAt = Date.now();
    checkOrderAlerts(data);
    renderSafely({ force: forceRender });
    updateLiveTick();
  } catch (_) {
    if (!silent) toast("状态同步失败，正在自动重试", "error");
  } finally {
    store.refreshing = false;
    $("refreshBtn")?.classList.remove("spinning");
  }
}

function scheduleEventRefresh() {
  if (store.evtTimer) return;
  store.evtTimer = setTimeout(() => {
    store.evtTimer = null;
    refresh({ silent: true });
  }, 300);
}

function connectEvents() {
  if (typeof EventSource === "undefined") return;
  try { store.events?.close(); } catch (_) {}
  const es = new EventSource(`/api/events?${ownerQuery()}`);
  store.events = es;
  es.onopen = () => { store.sseLive = true; };
  es.onmessage = (msg) => {
    try {
      const parsed = JSON.parse(msg.data);
      if (parsed?.type === "pickup") {
        handlePickupEvent(parsed);
      }
    } catch (_) {}
    scheduleEventRefresh();
  };
  es.onerror = () => { store.sseLive = false; };
}

async function loadModes(goodsId) {
  const num = Number(goodsId);
  if (store.modes.has(num)) return store.modes.get(num);
  const res = await api(`/api/devices/${num}/modes`);
  const list = res.modes || [];
  store.modes.set(num, list);
  return list;
}

async function handleTapDevice(goodsId) {
  const num = Number(goodsId);
  const device = (store.data?.devices || []).find((d) => Number(d.goodsId) === num);
  if (!device) return;
  if (!accountReady()) {
    const phoneInput = $("loginPhone") || $("reviveCode");
    if (phoneInput) {
      phoneInput.classList.add("input-error");
      phoneInput.focus();
      setTimeout(() => phoneInput.classList.remove("input-error"), 400);
    } else {
      renderAccountSheet();
      openSheet("accountSheet");
    }
    toast("请先登录或恢复海乐账号", "warning");
    return;
  }

  if (device.activeOrder && isOrderReadyToPay(device.activeOrder)) {
    openHailePay({
      deviceHint: device.name,
      orderNo: device.activeOrder.orderNo || "",
      stage: device.activeOrder.stage || "awaiting_payment",
      preferMode: "auto",
    });
    return;
  }

  const myIntent = activeIntentForDevice(num);
  if (myIntent) {
    promptStopIntent(myIntent.goodsId, Boolean(orderForIntent(myIntent)), Boolean(myIntent.isPool));
    return;
  }

  if (!canOpenDevice(device)) {
    toast(device.occupiedByOtherUser ? `${device.occupiedByOtherUserLabel || "室友"} 正在抢占该设备` : "该设备当前暂不可抢", "warning");
    return;
  }

  store.poolSheetMode = false;
  store.selectedDevice = device;
  store.selectedMode = null;
  store.selectedDuration = store.prefs.dryerMinutes || 30;
  $("sheetKicker").textContent = device.orderKind === "reserve" ? "选择预约套餐" : "选择洗烘套餐";
  $("sheetTitle").textContent = device.name || "设备";
  $("sheetMeta").innerHTML = `<span>${device.stateLabel || ""}</span><span>·</span><span>${etaText(device)}</span>`;
  $("modeGrid").innerHTML = `<div class="empty-state" style="grid-column:1/-1;">正在加载套餐...</div>`;
  openSheet("modeSheet");

  try {
    const modes = await loadModes(num);
    renderModeSheetContent(modes);
  } catch (_) {
    $("modeGrid").innerHTML = `<div class="empty-state" style="grid-column:1/-1;">套餐读取失败，请重试</div>`;
  }
}

async function runBusy(key, fn) {
  if (store.busy.has(key)) return;
  store.busy.add(key);
  renderSafely({ force: true });
  try {
    await fn();
  } finally {
    store.busy.delete(key);
    renderSafely({ force: true });
  }
}

async function quickStartPool() {
  if (!accountReady()) {
    toast("请先登录海乐账号", "warning");
    return;
  }
  await runBusy("quick-pool", async () => {
    const res = await post("/api/intents/start", {
      ...ownerPayload(),
      anyWasher: true,
      preferredModeName: store.prefs.washerModeName || "标准",
      selfClean: Boolean(store.prefs.selfClean),
    });
    if (res.success) {
      haptic("start");
      window.HaileNative?.requestIgnoreBatteryOptimizations?.();
      toast(`已启动并池抢占（${store.prefs.washerModeName}${store.prefs.selfClean ? "+筒自洁" : ""}）`, "success");
      await refresh({ silent: true, forceRender: true });
    } else {
      haptic("error");
      toast(res.message || "并池抢占启动失败", "error");
    }
  });
}

async function submitSheetStart() {
  if (!store.selectedMode) return toast("请选择套餐", "warning");
  const isDryer = Boolean(!store.poolSheetMode && String(store.selectedDevice?.name || "").includes("烘干"));
  if (isDryer) {
    savePrefs({ dryerMinutes: store.selectedDuration });
  } else {
    savePrefs({ washerModeName: store.selectedMode.name || "标准" });
  }

  const scSupported = modeSupportsSelfClean(store.selectedMode, isDryer);
  const body = {
    ...ownerPayload(),
    selfClean: Boolean(!isDryer && scSupported && store.prefs.selfClean),
  };

  if (store.poolSheetMode) {
    body.anyWasher = true;
    body.preferredModeName = store.selectedMode.name || "标准";
  } else {
    body.goodsId = Number(store.selectedDevice.goodsId);
    body.modeId = Number(store.selectedMode.id);
    if (isDryer) body.dryerMinutes = store.selectedDuration;
  }

  await runBusy("start-intent", async () => {
    const res = await post("/api/intents/start", body);
    if (res.success) {
      haptic("start");
      window.HaileNative?.requestIgnoreBatteryOptimizations?.();
      closeSheet("modeSheet");
      toast(store.poolSheetMode ? "已开启多机并池竞速抢占" : "已开始守护抢占", "success");
      await refresh({ silent: true, forceRender: true });
    } else {
      haptic("error");
      toast(res.message || "启动失败", "error");
    }
  });
}

function promptStopIntent(goodsId, hasOrder = false, isPool = false) {
  const btn = $("confirmStopBtn");
  if (btn) {
    btn.dataset.goodsId = String(goodsId);
    btn.textContent = hasOrder ? "放弃并释放订单" : isPool ? "停止多机并池" : "确认停止抢占";
  }
  $("stopTitle").textContent = hasOrder
    ? "放弃抢占并释放当前订单？"
    : isPool
      ? "停止当前多机并池抢占？"
      : "停止当前抢占任务？";
  const stopSub = $("stopSub");
  if (stopSub) {
    stopSub.textContent = isPool
      ? "停止后将释放全楼洗衣机并池盯守槽位；你可随时重新按偏好启动或单选某台设备。"
      : "将终止本任务轮询，并释放名下尚未支付或预约中的占坑单。";
  }
  openSheet("stopSheet");
}

async function confirmStopIntent() {
  const goodsId = Number($("confirmStopBtn")?.dataset.goodsId || 0);
  await runBusy("stop-intent", async () => {
    const res = await post("/api/intents/stop", { ...ownerPayload(), goodsId, cancelOrder: true });
    if (res.success) {
      haptic("stop");
      closeSheet("stopSheet");
      toast("已停止抢占", "warning");
      await refresh({ silent: true, forceRender: true });
    } else {
      haptic("error");
      toast(res.message || "停止失败", "error");
    }
  });
}

async function sendSmsCode(phone, busyKey = "send-code-login") {
  const rem = Math.ceil(((store.smsCooldownUntil || 0) - Date.now()) / 1000);
  if (rem > 0) {
    toast(`验证码已发出，请 ${rem}s 后再试`, "warning");
    return;
  }
  const trimmed = String(phone || "").trim();
  if (!/^1\d{10}$/.test(trimmed)) {
    toast("请输入正确的 11 位手机号", "warning");
    return;
  }
  await runBusy(busyKey, async () => {
    const res = await post("/api/accounts/code", { phone: trimmed });
    if (res.code === 0) {
      store.codeTarget = trimmed;
      store.smsCooldownUntil = Date.now() + 60_000;
      haptic("success");
      toast("短信验证码已发送，请留意查收", "success");
    } else {
      haptic("error");
      toast(res.message || "发送失败", "error");
    }
  });
}

async function submitLogin(phone, code, remark = "", busyKey = "login") {
  const p = String(phone || "").trim();
  const c = String(code || "").trim();
  if (!p || !c) {
    toast("请输入手机号与短信验证码", "warning");
    return;
  }
  await runBusy(busyKey, async () => {
    const res = await post("/api/accounts/login", { ...ownerPayload(), phone: p, code: c, remark: String(remark || "").trim() });
    if (res.success) {
      store.drafts.loginCode = "";
      store.drafts.reviveCode = "";
      haptic("success");
      const autoName = res.autoPosition?.positionName;
      toast(autoName ? `登录成功，已自动定位至 ${autoName}` : "账号登录成功", "success");
      closeSheet("accountSheet");
      await refresh({ silent: true, forceRender: true });
      loadRecentUsage(true);
    } else {
      haptic("error");
      toast(res.message || "登录失败", "error");
    }
  });
}

function updateLiveTick() {
  for (const intent of activeIntents()) {
    const order = orderForIntent(intent);
    const rem = remainingFromOrder(order);
    const key = String(intent.goodsId).replace(/[^a-zA-Z0-9_-]/g, "_");
    const cdEl = $(`task-cd-${key}`);
    if (cdEl && rem !== null) {
      cdEl.textContent = formatDuration(rem);
    }
    const barEl = $(`task-bar-${key}`);
    if (barEl && rem !== null && order?.stage === "awaiting_payment") {
      const pct = Math.max(2, Math.min(100, Math.round((rem / (Number(order.expireSeconds) || 300)) * 100)));
      barEl.style.width = `${pct}%`;
      barEl.style.background = pct < 20 ? "var(--red)" : "var(--green)";
    }
    if (rem !== null && rem <= 35 && order?.orderNo && !store.warnedOrderNos.has(order.orderNo)) {
      store.warnedOrderNos.add(order.orderNo);
      haptic("warning");
    }
  }

  for (const order of activeOrders()) {
    const timeEl = $(`order-time-${order.orderNo}`);
    if (timeEl) {
      const t = orderTimeParts(order);
      timeEl.innerHTML = `${t.main}<small>${t.sub}</small>`;
    }
  }

  for (const dev of store.data?.devices || []) {
    const etaEl = document.querySelector(`[data-eta-goods="${dev.goodsId}"]`);
    if (etaEl) etaEl.textContent = etaText(dev);
    const cycEl = document.querySelector(`[data-cycle-goods="${dev.goodsId}"]`);
    const pct = deviceCycleProgress(dev);
    if (cycEl && pct !== null) cycEl.style.width = `${pct}%`;
  }

  const smsRem = Math.ceil(((store.smsCooldownUntil || 0) - Date.now()) / 1000);
  const smsBtns = [
    [$("loginSendCodeBtn"), "获取验证码"],
    [$("reviveSendCodeBtn"), "发送验证码"],
    [$("sheetSendCodeBtn"), "发验证码"],
  ];
  for (const [b, idleLbl] of smsBtns) {
    if (!b || store.busy.has("send-code-login") || store.busy.has("send-code-revive")) continue;
    if (smsRem > 0) {
      b.disabled = true;
      b.textContent = `${smsRem}s 后重发`;
    } else if (b.disabled) {
      b.disabled = false;
      b.textContent = idleLbl;
    }
  }
}

function openOtaUpdateSheet() {
  renderOtaSheet();
  openSheet("otaSheet");
}

function defaultMapCenter() {
  if (store.mapCenter && Number.isFinite(store.mapCenter.lng) && Number.isFinite(store.mapCenter.lat)) {
    return store.mapCenter;
  }
  const p = store.data?.position;
  if (p && Number.isFinite(Number(p.lng)) && Number.isFinite(Number(p.lat))) {
    return { lng: Number(p.lng), lat: Number(p.lat) };
  }
  return { lng: 120.425, lat: 36.0716 };
}

async function loadNearbyAt(lng, lat) {
  store.loadingPositions = true;
  if (Number.isFinite(Number(lng)) && Number.isFinite(Number(lat))) {
    store.mapCenter = { lng: Number(lng), lat: Number(lat) };
  }
  renderPositionSheet();
  try {
    const qs =
      Number.isFinite(Number(lng)) && Number.isFinite(Number(lat))
        ? `?lng=${encodeURIComponent(lng)}&lat=${encodeURIComponent(lat)}&coordType=gcj02`
        : "";
    const res = await api(`/api/positions/nearby${qs}`);
    store.nearbyPositions = res.positions || [];
    mapPicker?.setMarkers(store.nearbyPositions, store.data?.position?.positionId);
  } catch (_) {
    toast("周边点位读取失败", "warning");
  } finally {
    store.loadingPositions = false;
    renderPositionSheet();
  }
}

async function loadRecentUsage(force = false) {
  const acc = primaryAccount();
  if (!acc || acc.tokenExpired) {
    store.recentUsage = { positions: [], devices: [], loggedIn: false };
    if ($("positionSheet")?.classList.contains("open")) renderPositionSheet();
    return;
  }
  store.loadingRecent = true;
  if ($("positionSheet")?.classList.contains("open")) renderPositionSheet();
  try {
    const res = await api(`/api/recent?${ownerQuery()}${force ? "&force=1" : ""}`);
    store.recentUsage = res || { positions: [], devices: [], loggedIn: true };
  } catch (_) {
  } finally {
    store.loadingRecent = false;
    if ($("positionSheet")?.classList.contains("open")) renderPositionSheet();
  }
}

async function selectPositionByInfo({ positionId, positionName = "", lng = null, lat = null, address = "" }) {
  const pid = Number(positionId || 32577);
  const body = { positionId: pid, positionName: positionName || "" };
  if (lng !== null && lng !== "" && Number.isFinite(Number(lng))) body.lng = Number(lng);
  if (lat !== null && lat !== "" && Number.isFinite(Number(lat))) body.lat = Number(lat);
  if (address) body.address = address;
  const res = await post("/api/position", body);
  if (res.success) {
    store.modes.clear();
    if (Number.isFinite(Number(res.lng)) && Number.isFinite(Number(res.lat))) {
      store.mapCenter = { lng: Number(res.lng), lat: Number(res.lat) };
      mapPicker?.setCenter(res.lng, res.lat);
    }
    haptic("start");
    toast(`已切换至 ${res.positionName || positionName || `#${pid}`}`, "success");
    closeSheet("positionSheet");
    await refresh({ silent: true, forceRender: true });
    return true;
  }
  haptic("warning");
  toast(res.message || "切换点位失败", "warning");
  return false;
}

function ensureMapPicker() {
  const vp = $("posMapViewport");
  if (!vp) return;
  const center = defaultMapCenter();
  if (!mapPicker) {
    mapPicker = new SlippyMapPicker(vp, {
      lng: center.lng,
      lat: center.lat,
      zoom: 16,
      onMoveEnd: ({ lng, lat }) => {
        loadNearbyAt(lng, lat);
      },
      onSelectMarker: ({ positionId, name, lng, lat }) => {
        selectPositionByInfo({ positionId, positionName: name, lng, lat });
      },
    });
  } else {
    mapPicker.setCenter(center.lng, center.lat);
  }
  mapPicker.setMarkers(store.nearbyPositions, store.data?.position?.positionId);
}

function openPositionSheet() {
  closeSheet("accountSheet");
  renderPositionSheet();
  openSheet("positionSheet");
  requestAnimationFrame(() => {
    ensureMapPicker();
    const center = defaultMapCenter();
    if (!store.nearbyPositions.length) {
      loadNearbyAt(center.lng, center.lat);
    }
    loadRecentUsage(false);
  });
}

async function submitPositionSearch() {
  const q = ($("posSearchInput")?.value ?? store.drafts.posSearch ?? "").trim();
  const center = defaultMapCenter();
  if (!q) {
    store.searchPlaces = [];
    await loadNearbyAt(center.lng, center.lat);
    return;
  }
  store.loadingPositions = true;
  renderPositionSheet();
  try {
    const res = await api(
      `/api/positions/search?q=${encodeURIComponent(q)}&${ownerQuery()}&lng=${encodeURIComponent(center.lng)}&lat=${encodeURIComponent(center.lat)}`
    );
    store.searchPlaces = res.places || [];
    store.nearbyPositions = res.positions || [];
    const topPlace = store.searchPlaces[0];
    const firstPosWithCoords = store.nearbyPositions.find((p) => Number.isFinite(Number(p.lng)) && Number.isFinite(Number(p.lat)));
    if (firstPosWithCoords) {
      store.mapCenter = { lng: Number(firstPosWithCoords.lng), lat: Number(firstPosWithCoords.lat) };
      mapPicker?.setCenter(firstPosWithCoords.lng, firstPosWithCoords.lat, 16);
    } else if (topPlace) {
      store.mapCenter = { lng: Number(topPlace.lng), lat: Number(topPlace.lat) };
      mapPicker?.setCenter(topPlace.lng, topPlace.lat, 15);
    }
    mapPicker?.setMarkers(store.nearbyPositions, store.data?.position?.positionId);
  } catch (_) {
    toast("搜索失败，请稍后重试", "warning");
  } finally {
    store.loadingPositions = false;
    renderPositionSheet();
  }
}

async function triggerGpsLocate() {
  if (store.locatingGps) return;
  store.locatingGps = true;
  renderPositionSheet();
  try {
    const gcj = await requestCurrentGcjLocation();
    store.searchPlaces = [];
    store.mapCenter = { lng: gcj.lng, lat: gcj.lat };
    mapPicker?.setCenter(gcj.lng, gcj.lat, 16);
    await loadNearbyAt(gcj.lng, gcj.lat);
    haptic("success");
    toast("已定位当前周边洗衣房", "success");
  } catch (err) {
    toast(err?.message || "定位失败，可直接搜索学校名称", "warning");
  } finally {
    store.locatingGps = false;
    renderPositionSheet();
  }
}

async function submitCustomPositionResolve() {
  const raw = ($("customPosInput")?.value ?? store.drafts.customPosInput ?? "").trim();
  if (!raw) {
    toast("请输入点位 ID、机身码或二维码链接", "warning");
    return;
  }
  await runBusy("resolve-custom-pos", async () => {
    const res = await post("/api/position/resolve", {
      ...ownerPayload(),
      input: raw,
      autoSwitch: true,
    });
    if (res.success) {
      store.modes.clear();
      store.drafts.customPosInput = "";
      if ($("customPosInput")) $("customPosInput").value = "";
      if (Number.isFinite(Number(res.lng)) && Number.isFinite(Number(res.lat))) {
        store.mapCenter = { lng: Number(res.lng), lat: Number(res.lat) };
      }
      haptic("success");
      closeSheet("positionSheet");
      toast(`已切换至 ${res.positionName || `#${res.positionId}`}`, "success");
      await refresh({ silent: true, forceRender: true });
      if (res.focusGoodsId) {
        handleTapDevice(res.focusGoodsId);
      }
    } else {
      haptic("warning");
      toast(res.message || "未能解析该点位", "warning");
    }
  });
}

function bindEvents() {
  document.addEventListener("input", (e) => {
    const t = e.target;
    if (!t || !t.id) return;
    if (t.id === "loginPhone" || t.id === "sheetLoginPhone") store.drafts.loginPhone = t.value;
    if (t.id === "loginCode" || t.id === "sheetLoginCode") store.drafts.loginCode = t.value;
    if (t.id === "loginRemark") store.drafts.loginRemark = t.value;
    if (t.id === "reviveCode") store.drafts.reviveCode = t.value;
    if (t.id === "sheetRemarkInput") store.drafts.remarkInput = t.value;
    if (t.id === "verifyCodeInput") store.drafts.verifyCode = t.value;
    if (t.id === "posSearchInput") store.drafts.posSearch = t.value;
    if (t.id === "customPosInput") store.drafts.customPosInput = t.value;
  });

  $("posSearchForm")?.addEventListener("submit", (e) => {
    e.preventDefault();
    submitPositionSearch();
  });

  $("customPosForm")?.addEventListener("submit", (e) => {
    e.preventDefault();
    submitCustomPositionResolve();
  });

  document.addEventListener("keydown", (e) => {
    if (e.key !== "Enter") return;
    const t = e.target;
    if (!t || !t.id) return;
    if (t.id === "posSearchInput") {
      e.preventDefault();
      if (e.isComposing || e.keyCode === 229) return;
      $("posSearchForm")?.requestSubmit();
      return;
    }
    if (t.id === "customPosInput") {
      e.preventDefault();
      if (e.isComposing || e.keyCode === 229) return;
      $("customPosForm")?.requestSubmit();
      return;
    }
    if (e.isComposing || e.keyCode === 229) return;
    if (t.id === "loginCode" || t.id === "loginRemark") {
      e.preventDefault();
      submitLogin($("loginPhone")?.value || store.drafts.loginPhone, $("loginCode")?.value || store.drafts.loginCode, $("loginRemark")?.value || store.drafts.loginRemark, "login");
    } else if (t.id === "sheetLoginCode") {
      e.preventDefault();
      submitLogin($("sheetLoginPhone")?.value || store.drafts.loginPhone, $("sheetLoginCode")?.value || store.drafts.loginCode, "", "login");
    } else if (t.id === "reviveCode") {
      e.preventDefault();
      submitLogin(primaryAccount()?.phone, $("reviveCode")?.value || store.drafts.reviveCode, primaryAccount()?.remark || "", "revive-login");
    } else if (t.id === "verifyCodeInput") {
      e.preventDefault();
      const btn = document.querySelector("[data-action='submit-verify-order']");
      btn?.click();
    }
  });

  document.addEventListener("focusout", () => {
    setTimeout(() => {
      if (store.pendingRender && !isTyping() && !$("modeSheet")?.classList.contains("open")) {
        renderSafely({ force: true });
      }
    }, 60);
  });

  document.addEventListener("click", async (e) => {
    const el = e.target.closest("[data-action]");
    if (!el) return;
    const action = el.dataset.action;

    if (action === "open-position-sheet") {
      openPositionSheet();
    } else if (action === "close-position-sheet") {
      closeSheet("positionSheet");
    } else if (action === "locate-gps") {
      triggerGpsLocate();
    } else if (action === "refresh-recent-usage") {
      loadRecentUsage(true);
    } else if (action === "jump-map-place") {
      const lng = Number(el.dataset.lng);
      const lat = Number(el.dataset.lat);
      if (Number.isFinite(lng) && Number.isFinite(lat)) {
        store.mapCenter = { lng, lat };
        mapPicker?.setCenter(lng, lat, 16);
        loadNearbyAt(lng, lat);
      }
    } else if (action === "tap-recent-device") {
      const gid = Number(el.dataset.goodsId || 0);
      const pid = Number(el.dataset.posId || 0);
      const pname = el.dataset.posName || "";
      const curPid = Number(store.data?.position?.positionId || 0);
      if (pid > 0 && pid !== curPid) {
        const ok = await selectPositionByInfo({ positionId: pid, positionName: pname });
        if (!ok) return;
      } else {
        closeSheet("positionSheet");
      }
      if (gid > 0) {
        handleTapDevice(gid);
      }
    } else if (action === "open-account-sheet") {
      renderAccountSheet();
      openSheet("accountSheet");
    } else if (action === "close-mode-sheet") {
      closeSheet("modeSheet");
    } else if (action === "close-account-sheet") {
      closeSheet("accountSheet");
    } else if (action === "open-ota-sheet") {
      openOtaUpdateSheet();
    } else if (action === "close-ota-sheet") {
      closeSheet("otaSheet");
    } else if (action === "confirm-install-ota") {
      installOtaUpdate(() => renderAll(), { forceExternal: false });
    } else if (action === "fallback-browser-ota") {
      installOtaUpdate(() => renderAll(), { forceExternal: true });
    } else if (action === "close-stop-sheet") {
      closeSheet("stopSheet");
    } else if (action === "tap-device") {
      handleTapDevice(el.dataset.goodsId);
    } else if (action === "open-pool-sheet") {
      renderPoolModeSheet();
      openSheet("modeSheet");
    } else if (action === "quick-start-pool") {
      quickStartPool();
    } else if (action === "select-mode") {
      const id = Number(el.dataset.modeId || 0);
      const name = el.dataset.modeName || "";
      const modes = store.poolSheetMode ? POOL_WASHER_MODES : store.modes.get(Number(store.selectedDevice?.goodsId)) || [];
      store.selectedMode = modes.find((m) => (id ? Number(m.id) === id : m.name === name)) || modes[0];
      haptic("start");
      renderModeSheetContent(modes);
    } else if (action === "select-duration") {
      store.selectedDuration = Number(el.dataset.minutes || 30);
      haptic("start");
      const modes = store.modes.get(Number(store.selectedDevice?.goodsId)) || [];
      renderModeSheetContent(modes);
    } else if (action === "toggle-self-clean") {
      savePrefs({ selfClean: !store.prefs.selfClean });
      haptic("start");
      const modes = store.poolSheetMode ? POOL_WASHER_MODES : store.modes.get(Number(store.selectedDevice?.goodsId)) || [];
      renderModeSheetContent(modes);
    } else if (action === "prompt-stop-intent") {
      promptStopIntent(el.dataset.goodsId, el.dataset.hasOrder === "1", el.dataset.isPool === "1");
    } else if (action === "open-haile-pay") {
      openHailePay({
        deviceHint: el.dataset.device || "",
        orderNo: el.dataset.orderNo || "",
        stage: el.dataset.stage || "awaiting_payment",
        preferMode: el.dataset.prefer || "auto",
      });
    } else if (action === "submit-verify-order") {
      const orderNo = el.dataset.orderNo;
      const phone = el.dataset.phone;
      const checkCode = ($("verifyCodeInput")?.value || store.drafts.verifyCode || "").trim();
      if (!checkCode) {
        toast("请输入预约短信核验码", "warning");
        return;
      }
      await runBusy("verify-order", async () => {
        const res = await post("/api/orders/verify", { ...ownerPayload(), phone, orderNo, checkCode });
        if (res.success) {
          store.drafts.verifyCode = "";
          haptic("success");
          toast("预约核验成功", "success");
          await refresh({ silent: true, forceRender: true });
        } else {
          haptic("error");
          toast(res.message || "核验失败", "error");
        }
      });
    } else if (action === "resend-verify-order") {
      const orderNo = el.dataset.orderNo;
      const phone = el.dataset.phone;
      await runBusy("resend-verify", async () => {
        const res = await post("/api/orders/resend_verify", { ...ownerPayload(), phone, orderNo });
        toast(res.success ? "核验码已重发，请留意短信" : (res.message || "重发失败"), res.success ? "success" : "warning");
      });
    } else if (action === "remind-prev-pickup") {
      const orderNo = el.dataset.orderNo;
      const phone = el.dataset.phone;
      await runBusy("remind-pickup", async () => {
        const res = await post("/api/orders/remind_pickup", { ...ownerPayload(), phone, orderNo });
        toast(res.success ? "已向上一位同学发送官方取衣提醒" : (res.message || "发送失败"), res.success ? "success" : "warning");
      });
    } else if (action === "confirm-place-clothes") {
      const orderNo = el.dataset.orderNo;
      const phone = el.dataset.phone;
      await runBusy("place-clothes", async () => {
        const res = await post("/api/orders/place_clothes", { ...ownerPayload(), phone, orderNo });
        if (res.success) {
          haptic("success");
          toast("已确认放衣关门，可继续完成支付", "success");
          await refresh({ silent: true, forceRender: true });
        } else {
          toast(res.message || "操作失败", "warning");
        }
      });
    } else if (action === "cancel-order") {
      const orderNo = el.dataset.orderNo;
      const phone = el.dataset.phone;
      await runBusy(`cancel-${orderNo}`, async () => {
        const res = await post("/api/orders/cancel", { ...ownerPayload(), phone, orderNo });
        if (res.success) {
          haptic("stop");
          toast("订单已取消", "warning");
          await refresh({ silent: true, forceRender: true });
        } else {
          toast(res.message || "取消失败", "error");
        }
      });
    } else if (action === "send-code-login") {
      sendSmsCode($("loginPhone")?.value || store.drafts.loginPhone, "send-code-login");
    } else if (action === "login") {
      submitLogin($("loginPhone")?.value || store.drafts.loginPhone, $("loginCode")?.value || store.drafts.loginCode, $("loginRemark")?.value || store.drafts.loginRemark, "login");
    } else if (action === "send-code-revive") {
      sendSmsCode(el.dataset.phone || primaryAccount()?.phone, "send-code-revive");
    } else if (action === "revive-login") {
      submitLogin(el.dataset.phone || primaryAccount()?.phone, $("reviveCode")?.value || store.drafts.reviveCode, primaryAccount()?.remark || "", "revive-login");
    } else if (action === "sheet-send-code") {
      sendSmsCode($("sheetLoginPhone")?.value || store.drafts.loginPhone, "send-code-login");
    } else if (action === "sheet-do-login") {
      submitLogin($("sheetLoginPhone")?.value || store.drafts.loginPhone, $("sheetLoginCode")?.value || store.drafts.loginCode, "", "login");
    } else if (action === "save-remark") {
      const phone = el.dataset.phone || primaryAccount()?.phone;
      const remark = $("sheetRemarkInput")?.value ?? store.drafts.remarkInput;
      await runBusy("save-remark", async () => {
        const res = await post("/api/accounts/remark", { ...ownerPayload(), phone, remark });
        if (res.success) {
          store.drafts.remarkInput = "";
          toast("账号备注已保存", "success");
          await refresh({ silent: true, forceRender: true });
        }
      });
    } else if (action === "load-nearby-positions") {
      openPositionSheet();
    } else if (action === "select-position") {
      await selectPositionByInfo({
        positionId: el.dataset.posId,
        positionName: el.dataset.posName,
        lng: el.dataset.posLng,
        lat: el.dataset.posLat,
        address: el.dataset.posAddress,
      });
    } else if (action === "manual-check-ota") {
      if (store.ota.hasUpdate) {
        closeSheet("accountSheet");
        openOtaUpdateSheet();
      } else {
        checkOtaUpdate({
          manual: true,
          onUpdated: () => renderAll(),
          onOpenSheet: () => {
            closeSheet("accountSheet");
            openOtaUpdateSheet();
          },
        });
      }
    } else if (action === "toggle-logs") {
      store.logsExpanded = !store.logsExpanded;
      renderAccountSheet();
    }
  });

  $("refreshBtn")?.addEventListener("click", () => refresh());
  $("bellBtn")?.addEventListener("click", toggleNotifications);
  $("themeBtn")?.addEventListener("click", toggleTheme);
  $("startBtn")?.addEventListener("click", submitSheetStart);
  $("confirmStopBtn")?.addEventListener("click", confirmStopIntent);

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") {
      syncBellBtn();
      if (!store.events || store.events.readyState === EventSource.CLOSED) {
        connectEvents();
      }
      refresh({ silent: true });
    }
  });
}

function init() {
  applyTheme(store.theme);
  syncNativePayChannels();
  syncBellBtn();
  bindNativeOtaCallback(() => renderAll());
  bindEvents();

  if ("serviceWorker" in navigator && !isNativeRuntime()) {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  }

  refresh({ silent: true, forceRender: true });
  connectEvents();
  setInterval(updateLiveTick, 1000);
  setInterval(() => refresh({ silent: true }), STATE_SYNC_INTERVAL_MS);
  setTimeout(() => checkOtaUpdate({ manual: false, onUpdated: () => renderAll() }), 1800);
}

init();
