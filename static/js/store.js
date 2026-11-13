export const APP_VERSION_CODE = 420;
export const APP_VERSION_NAME = "4.2.0";
export const OTA_PRIMARY_BASE = "https://haile-bot.pages.dev";
export const OTA_DEFAULT_APK_URL = `${OTA_PRIMARY_BASE}/haile-bot-v4.apk`;
export const STATE_SYNC_INTERVAL_MS = 10000;
export const PAYMENT_EXPIRE_FALLBACK_SECONDS = 300;
export const WECHAT_MINI_APP_ID = "wx7519e26c1d2f9edc";
export const ALIPAY_MINI_APP_ID = "2021003183687122";

export const DEVICE_CODE_MAP = {
  "06": "GTB2406241487",
  "07": "GTB2406243590",
  "08": "GTB2406246124",
  "09": "GTB2406243588",
  "05": "GYB2407151332",
};

export const DRYER_FALLBACK_DURATIONS = [
  { minutes: 10, price: "1.00" },
  { minutes: 30, price: "3.00" },
  { minutes: 45, price: "4.50" },
  { minutes: 60, price: "6.00" },
  { minutes: 90, price: "9.00" },
];

export const POOL_WASHER_MODES = [
  { id: 0, name: "标准", price: "2.00", unit: "40", attachMap: { SelfClean: true } },
  { id: 0, name: "大物", price: "3.00", unit: "50", attachMap: { SelfClean: true } },
  { id: 0, name: "快速", price: "1.00", unit: "25", attachMap: { SelfClean: true } },
  { id: 0, name: "单脱", price: "0.50", unit: "8", attachMap: { SelfClean: false } },
  { id: 0, name: "桶自洁", price: "0.00", unit: "6", attachMap: { SelfClean: false } },
];

function loadSavedPrefs() {
  try {
    const raw = JSON.parse(localStorage.getItem("hailePrefsV4") || "{}");
    return {
      washerModeName: String(raw.washerModeName || "标准"),
      selfClean: raw.selfClean !== undefined ? Boolean(raw.selfClean) : localStorage.getItem("haileSelfClean") === "1",
      dryerMinutes: Number(raw.dryerMinutes) > 0 ? Number(raw.dryerMinutes) : 30,
    };
  } catch (_) {
    return { washerModeName: "标准", selfClean: false, dryerMinutes: 30 };
  }
}

const initialOwnerId =
  localStorage.getItem("haileOwnerId") ||
  `u_${typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2)}`;
localStorage.setItem("haileOwnerId", initialOwnerId);

export const store = {
  data: null,
  modes: new Map(),
  nearbyPositions: [],
  searchPlaces: [],
  recentUsage: { positions: [], devices: [], loggedIn: false },
  loadingPositions: false,
  loadingRecent: false,
  locatingGps: false,
  mapCenter: null,
  selectedDevice: null,
  poolSheetMode: false,
  selectedMode: null,
  selectedDuration: loadSavedPrefs().dryerMinutes,
  prefs: loadSavedPrefs(),
  theme: localStorage.getItem("haileTheme") || "auto",
  refreshing: false,
  lastSyncAt: 0,
  notifEnabled: localStorage.getItem("haileNotif") !== "0",
  ordersSeeded: false,
  alertedOrders: new Set(),
  alertedPickups: new Set(),
  warnedOrderNos: new Set(),
  events: null,
  sseLive: false,
  evtTimer: null,
  codeTarget: "",
  smsCooldownUntil: 0,
  remarkEditing: false,
  logsExpanded: false,
  pendingRender: false,
  busy: new Set(),
  payChannels: {
    haileApp: false,
    wechat: true,
    alipay: true,
  },
  ota: {
    hasUpdate: false,
    checking: false,
    versionCode: APP_VERSION_CODE,
    versionName: APP_VERSION_NAME,
    apkUrl: OTA_DEFAULT_APK_URL,
    releaseNotes: "",
    downloading: false,
    error: false,
    progress: 0,
    statusText: "",
    lastLaunchAt: 0,
  },
  drafts: {
    loginPhone: "",
    loginCode: "",
    loginRemark: "",
    reviveCode: "",
    remarkInput: "",
    verifyCode: "",
    posSearch: "",
    customPosInput: "",
  },
  owner: {
    id: initialOwnerId,
    name: localStorage.getItem("haileOwnerName") || "",
  },
};

export function savePrefs(partial = {}) {
  Object.assign(store.prefs, partial);
  try {
    localStorage.setItem("hailePrefsV4", JSON.stringify(store.prefs));
    localStorage.setItem("haileSelfClean", store.prefs.selfClean ? "1" : "0");
  } catch (_) {}
}

export const $ = (id) => document.getElementById(id);
export const ownerQuery = () => `ownerId=${encodeURIComponent(store.owner.id)}`;
export const ownerPayload = () => ({ ownerId: store.owner.id, ownerName: store.owner.name });

export function isNativeRuntime() {
  return Boolean(
    window.HaileNative &&
      (typeof window.HaileNative.isNativeApp === "function" ||
        typeof window.HaileNative.getVersionCode === "function" ||
        typeof window.HaileNative.openHailePay === "function")
  );
}

export function getLocalVersionCode() {
  if (isNativeRuntime() && typeof window.HaileNative.getVersionCode === "function") {
    try {
      const code = Number(window.HaileNative.getVersionCode());
      if (code > 0) return code;
    } catch (_) {}
  }
  return Number(store.data?.versionCode) || APP_VERSION_CODE;
}

export function getLocalVersionName() {
  if (isNativeRuntime() && typeof window.HaileNative.getVersionName === "function") {
    try {
      const name = String(window.HaileNative.getVersionName() || "").trim();
      if (name) return name;
    } catch (_) {}
  }
  return String(store.data?.version || APP_VERSION_NAME);
}

export async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export function post(path, body = {}) {
  return api(path, { method: "POST", body: JSON.stringify(body) });
}

export function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "\"": "&quot;",
    "'": "&#39;",
  }[char]));
}

export function toast(message, type = "info") {
  const stack = $("toastStack");
  if (!stack || !message) return;
  while (stack.firstChild) {
    stack.firstChild.remove();
  }
  const el = document.createElement("div");
  el.className = `toast ${type === "success" ? "green" : type === "warning" ? "yellow" : type === "error" ? "red" : ""}`;
  el.textContent = message;
  stack.appendChild(el);
  requestAnimationFrame(() => el.classList.add("show"));
  setTimeout(() => {
    el.classList.remove("show");
    setTimeout(() => el.remove(), 200);
  }, 2400);
}

export function haptic(type) {
  if (!store.notifEnabled) return;
  try {
    if (window.HaileNative && typeof window.HaileNative.isNotificationsMuted === "function" && window.HaileNative.isNotificationsMuted()) {
      return;
    }
    if (window.HaileNative && typeof window.HaileNative.vibrate === "function") {
      const ms = type === "error" ? 40 : type === "success" ? 28 : type === "stop" ? 24 : 14;
      window.HaileNative.vibrate(ms);
      return;
    }
  } catch (_) {}
  if (!navigator.vibrate) return;
  if (navigator.userActivation && !navigator.userActivation.hasBeenActive) return;
  try {
    if (type === "start") navigator.vibrate(10);
    if (type === "success") navigator.vibrate([20, 30, 20]);
    if (type === "error") navigator.vibrate(40);
    if (type === "warning") navigator.vibrate([12, 18, 12]);
    if (type === "stop") navigator.vibrate([15, 20, 15]);
  } catch (_) {}
}

export function primaryAccount() {
  const accounts = store.data?.accounts || [];
  return accounts.find((a) => a.enabled && !a.tokenExpired) || accounts[0] || null;
}

export function accountReady() {
  const acc = primaryAccount();
  return Boolean(acc && acc.enabled && !acc.tokenExpired);
}

export function activeIntents() {
  return (store.data?.intents || []).filter((it) => it && it.running && it.state !== "stopped" && it.state !== "fulfilled");
}

export function activeOrders() {
  return (store.data?.orders || []).filter((o) => o && o.stage !== "ended");
}

export function isOrderReadyToPay(order) {
  if (!order) return false;
  if (order.readyForUserAction === true) return true;
  if (order.stage === "awaiting_payment") return true;
  if (order.stage === "reserve_waiting") {
    return Boolean(order.canPay || order.pageCode === "waiting_check" || order.pageCode === "place_clothes");
  }
  return false;
}

export function parseLocalTime(value) {
  if (!value) return null;
  const iso = String(value).trim().replace(" ", "T");
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function calibratedNowMs() {
  const offsetSec = Number(store.data?.clockOffsetSeconds) || 0;
  return Date.now() + offsetSec * 1000;
}

export function remainingFromOrder(order) {
  if (!order) return null;
  const deadline = parseLocalTime(order.deadline);
  if (deadline) {
    return Math.max(0, Math.floor((deadline.getTime() - calibratedNowMs()) / 1000));
  }
  if (Number.isFinite(Number(order.deadlineRemainingSeconds)) && store.lastSyncAt) {
    const elapsed = (Date.now() - store.lastSyncAt) / 1000;
    return Math.max(0, Math.floor(Number(order.deadlineRemainingSeconds) - elapsed));
  }
  if (order.expireSeconds && order.createdAtLocal) {
    const elapsed = Date.now() / 1000 - Number(order.createdAtLocal);
    return Math.max(0, Math.floor(Number(order.expireSeconds) - elapsed));
  }
  return null;
}

export function remainingFromEstimate(order) {
  if (!order) return null;
  const finish = parseLocalTime(order.estimatedFinishTime);
  if (finish) {
    return Math.max(0, Math.floor((finish.getTime() - calibratedNowMs()) / 1000));
  }
  if (Number.isFinite(Number(order.estimatedRemainingSeconds)) && store.lastSyncAt) {
    const elapsed = (Date.now() - store.lastSyncAt) / 1000;
    return Math.max(0, Math.floor(Number(order.estimatedRemainingSeconds) - elapsed));
  }
  return null;
}

export function formatDuration(totalSeconds) {
  if (totalSeconds === null || totalSeconds === undefined || Number.isNaN(totalSeconds)) return "--:--";
  const sec = Math.max(0, Math.floor(Number(totalSeconds)));
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

export function formatClock(value) {
  const dt = parseLocalTime(value);
  if (!dt) return "";
  return `${String(dt.getHours()).padStart(2, "0")}:${String(dt.getMinutes()).padStart(2, "0")}`;
}
