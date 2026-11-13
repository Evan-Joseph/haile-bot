import {
  ALIPAY_MINI_APP_ID,
  DEVICE_CODE_MAP,
  OTA_DEFAULT_APK_URL,
  OTA_PRIMARY_BASE,
  WECHAT_MINI_APP_ID,
  api,
  getLocalVersionCode,
  getLocalVersionName,
  haptic,
  isNativeRuntime,
  store,
  toast,
} from "./store.js";

export function resolveDeviceCode(deviceHint = "") {
  const match = String(deviceHint || "").match(/(\d{2})号/);
  if (!match) return "";
  return DEVICE_CODE_MAP[match[1]] || "";
}

export function buildWechatMiniScheme(deviceHint = "", { forPayment = true } = {}) {
  const base = `weixin://dl/business/?appid=${WECHAT_MINI_APP_ID}&path=pages/tabbar/home`;
  if (!forPayment) {
    const code = resolveDeviceCode(deviceHint);
    if (code) {
      return `${base}&query=${encodeURIComponent(`N=${code}`)}`;
    }
  }
  return base;
}

export function buildAlipayCashierScheme(orderNo = "") {
  const cleanNo = String(orderNo || "").trim();
  const page = cleanNo ? `pages/pay/cashier?orderNo=${cleanNo}` : "pages/tabbar/home";
  return `alipays://platformapi/startapp?appId=${ALIPAY_MINI_APP_ID}&page=${encodeURIComponent(page)}`;
}

export function syncNativePayChannels() {
  if (!isNativeRuntime() || typeof window.HaileNative?.getPayChannelsJson !== "function") return;
  try {
    const raw = JSON.parse(window.HaileNative.getPayChannelsJson() || "{}");
    store.payChannels = {
      haileApp: Boolean(raw.haileApp),
      wechat: raw.wechat !== false,
      alipay: raw.alipay !== false,
    };
  } catch (_) {}
}

export function openHailePay({ deviceHint = "", orderNo = "", stage = "awaiting_payment", preferMode = "auto" } = {}) {
  haptic("start");
  const mode = String(preferMode || "auto").toLowerCase();
  const cleanOrderNo = String(orderNo || "").trim();
  const wxHint =
    stage === "awaiting_payment"
      ? "正在打开海乐小程序 · 进入后点底部「订单」即可付款"
      : "正在打开海乐小程序 · 首页顶部即可处理预约单";

  if (isNativeRuntime() && typeof window.HaileNative?.openHailePay === "function") {
    try {
      const target =
        window.HaileNative.openHailePay.length >= 3 || typeof window.HaileNative.openHailePayV4 === "function"
          ? (window.HaileNative.openHailePayV4
              ? window.HaileNative.openHailePayV4(String(deviceHint || ""), mode, cleanOrderNo)
              : window.HaileNative.openHailePay(String(deviceHint || ""), mode))
          : window.HaileNative.openHailePay(String(deviceHint || ""), mode);

      if (target === "app") {
        toast("正在打开海乐生活 App...", "success");
        return;
      }
      if (target === "alipay") {
        toast("正在打开支付宝海乐收银台（需同手机号登录）", "success");
        return;
      }
      if (target === "weapp" || target === "wechat") {
        toast(wxHint, "success");
        return;
      }
    } catch (_) {}
  }

  if (mode === "alipay") {
    toast("正在打开支付宝海乐收银台（需同手机号登录）", "success");
    window.location.href = buildAlipayCashierScheme(cleanOrderNo);
    return;
  }

  toast(wxHint, "success");
  window.location.href = buildWechatMiniScheme(deviceHint, { forPayment: true });
}

export function normalizeOtaApkUrl(rawUrl) {
  const clean = String(rawUrl || "").trim();
  if (!clean || clean.includes("4c666.top") || clean.endsWith("/haile-bot-v3.apk")) {
    return isNativeRuntime() ? OTA_DEFAULT_APK_URL : "/haile-bot-v4.apk";
  }
  return clean;
}

export async function checkOtaUpdate({ manual = false, onUpdated = null, onOpenSheet = null } = {}) {
  if (store.ota.checking || store.ota.downloading) {
    if (manual && store.ota.downloading && typeof onOpenSheet === "function") {
      onOpenSheet();
    }
    return;
  }
  store.ota.checking = true;
  if (typeof onUpdated === "function") onUpdated();

  const localCode = getLocalVersionCode();
  const customOtaUrl = (localStorage.getItem("haileOtaUrl") || "").trim();
  const q = `_t=${Date.now()}&clientVersion=${localCode}${customOtaUrl ? `&otaUrl=${encodeURIComponent(customOtaUrl)}` : ""}`;
  try {
    let info = await api(`/api/ota?${q}`, { cache: "no-store" }).catch(() => null);
    if (!info || info.ok === false || !(Number(info.versionCode) > 0)) {
      const fallbackUrls = [
        customOtaUrl,
        `${OTA_PRIMARY_BASE}/ota.json`,
        `${OTA_PRIMARY_BASE}/api/ota`,
      ].filter(Boolean);
      for (const rawUrl of fallbackUrls) {
        try {
          const sep = rawUrl.includes("?") ? "&" : "?";
          const direct = await fetch(`${rawUrl}${sep}_t=${Date.now()}&clientVersion=${localCode}`, { cache: "no-store" });
          if (direct.ok) {
            const parsed = await direct.json();
            if (parsed && Number(parsed.versionCode) > 0) {
              info = { ...parsed, ok: true };
              break;
            }
          }
        } catch (_) {}
      }
    }
    const remoteCode = Number(info?.versionCode) || 0;
    if (!info || info.ok === false || remoteCode <= 0) {
      if (manual) {
        haptic("warning");
        toast(info?.error || "更新源暂不可达，请检查网络后重试", "warning");
      }
      return;
    }
    const hasUpdate = remoteCode > localCode;
    const releaseNotes = String(info.releaseNotes || info.notes || "");
    const normalizedApkUrl = normalizeOtaApkUrl(info.apkUrl);

    store.ota = {
      ...store.ota,
      checking: false,
      hasUpdate,
      versionCode: remoteCode || localCode,
      versionName: String(info.versionName || getLocalVersionName()),
      apkUrl: normalizedApkUrl,
      releaseNotes,
    };
    if (typeof onUpdated === "function") onUpdated();

    if (hasUpdate) {
      if (manual && typeof onOpenSheet === "function") {
        haptic("start");
        onOpenSheet();
      } else if (!manual) {
        toast(`发现新版本 v${store.ota.versionName}，点击顶栏「升 v${store.ota.versionName}」查看更新`, "warning");
      }
    } else if (manual) {
      haptic("success");
      toast(`当前已是最新版本 v${getLocalVersionName()}`, "success");
    }
  } catch (_) {
    if (manual) {
      haptic("warning");
      toast("检查更新失败，请稍后重试", "warning");
    }
  } finally {
    store.ota.checking = false;
    if (typeof onUpdated === "function") onUpdated();
  }
}

export function installOtaUpdate(onUpdated = null, { forceExternal = false } = {}) {
  const now = Date.now();
  if (store.ota.downloading || now - (store.ota.lastLaunchAt || 0) < 3500) {
    toast("更新任务进行中，请勿重复点击", "warning");
    return;
  }
  store.ota.lastLaunchAt = now;
  haptic("start");
  const url = normalizeOtaApkUrl(store.ota?.apkUrl);

  if (!forceExternal && isNativeRuntime() && typeof window.HaileNative?.downloadAndInstallApk === "function") {
    try {
      store.ota.downloading = true;
      store.ota.error = false;
      store.ota.progress = 2;
      store.ota.statusText = "正在应用内下载 HaileBot-v4.apk...";
      if (typeof onUpdated === "function") onUpdated();
      window.HaileNative.downloadAndInstallApk(url);
      return;
    } catch (_) {
      store.ota.downloading = false;
    }
  }

  if (isNativeRuntime() && typeof window.HaileNative?.openExternalUrl === "function") {
    try {
      store.ota.statusText = "已拉起系统浏览器下载 HaileBot-v4.apk（请勿重复点击）";
      if (typeof onUpdated === "function") onUpdated();
      window.HaileNative.openExternalUrl(url);
      toast("已拉起浏览器下载 HaileBot-v4.apk", "success");
      return;
    } catch (_) {}
  }

  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = "HaileBot-v4.apk";
  anchor.rel = "noopener";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  toast("已开始下载 HaileBot-v4.apk", "success");
}

export function bindNativeOtaCallback(onUpdated) {
  window.__onHaileOtaProgress = (pct, state, message) => {
    const progress = Math.max(0, Math.min(100, Number(pct) || 0));
    store.ota.progress = progress;
    if (state === "downloading") {
      store.ota.downloading = true;
      store.ota.error = false;
      store.ota.statusText = `正在应用内下载更新包 ${progress}%`;
    } else if (state === "installing") {
      store.ota.downloading = false;
      store.ota.error = false;
      store.ota.progress = 100;
      store.ota.statusText = "下载完成，已拉起系统安装器（安装后账号与配置自动保留）";
      toast("更新包校验通过，请确认安装", "success");
    } else if (state === "permission_needed") {
      store.ota.downloading = false;
      store.ota.error = false;
      store.ota.statusText = "请开启「允许安装未知应用」权限，返回本应用后将自动继续安装";
      toast("请允许安装应用权限后返回", "warning");
    } else if (state === "error") {
      store.ota.downloading = false;
      store.ota.error = true;
      store.ota.statusText = message || "应用内下载超时，可重试或点击下方浏览器备用下载";
      toast(store.ota.statusText, "warning");
    }
    if (typeof onUpdated === "function") onUpdated();
  };
}
