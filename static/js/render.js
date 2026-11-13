import {
  $,
  DRYER_FALLBACK_DURATIONS,
  POOL_WASHER_MODES,
  accountReady,
  activeIntents,
  activeOrders,
  escapeHtml,
  formatClock,
  formatDuration,
  getLocalVersionName,
  isNativeRuntime,
  isOrderReadyToPay,
  parseLocalTime,
  primaryAccount,
  remainingFromEstimate,
  remainingFromOrder,
  store,
} from "./store.js";

export function busyAttr(key) {
  return store.busy.has(key) ? 'disabled aria-busy="true"' : "";
}

export function busyText(key, idleText, busyTextValue) {
  return store.busy.has(key) ? busyTextValue : idleText;
}

export function stateTone(device) {
  if (device.occupiedByOtherUser || device.state === "fault" || device.state === "unknown") return "gray";
  if (device.activeOrder?.stage === "awaiting_payment" || isOrderReadyToPay(device.activeOrder)) return "green";
  if (device.state === "idle") return "green";
  if (device.state === "busy_reservable" || device.activeOrder?.stage === "reserve_waiting") return "yellow";
  if (device.state === "payment_pending") return "red";
  return "yellow";
}

export function orderTone(order) {
  if (!order) return "gray";
  if (order.stage === "awaiting_payment" || isOrderReadyToPay(order) || order.stage === "running") return "green";
  if (order.stage === "reserve_waiting") return "yellow";
  if (order.stage === "tracking_conflict") return "red";
  return "gray";
}

function smsBtnLabel(busyKey, defaultLabel = "获取验证码") {
  if (store.busy.has(busyKey)) return "发送中";
  const rem = Math.ceil(((store.smsCooldownUntil || 0) - Date.now()) / 1000);
  return rem > 0 ? `${rem}s 后重发` : defaultLabel;
}

function smsDisabledAttr(busyKey) {
  const rem = Math.ceil(((store.smsCooldownUntil || 0) - Date.now()) / 1000);
  if (store.busy.has(busyKey) || rem > 0) return 'disabled aria-disabled="true"';
  return "";
}

export function etaText(device) {
  if (device.state === "idle") return "";
  if (device.state === "fault") return "暂不可用";
  if (device.state === "payment_pending") return "等待释放";
  const finish = parseLocalTime(device.finishTime);
  if (finish) {
    const rawDiff = Math.floor((finish.getTime() - (Date.now() + (Number(store.data?.clockOffsetSeconds) || 0) * 1000)) / 1000);
    const clock = formatClock(device.finishTime);
    if (rawDiff <= 0) return `${clock} 已到点·待取衣`;
    const mins = Math.max(1, Math.ceil(rawDiff / 60));
    return `${clock} · 约${mins}分`;
  }
  return device.queueText || "时间同步中";
}

export function deviceCycleProgress(device) {
  if (device.state === "idle" || device.state === "fault") return null;
  const finish = parseLocalTime(device.finishTime);
  if (!finish) return null;
  const sec = Math.max(0, (finish.getTime() - (Date.now() + (Number(store.data?.clockOffsetSeconds) || 0) * 1000)) / 1000);
  const total = device.deviceType === "dryer" ? 3600 : 2700;
  const pct = Math.round(Math.min(98, Math.max(6, (1 - Math.min(1, sec / total)) * 100)));
  return pct;
}

export function canOpenDevice(device) {
  if (!device || device.occupiedByOtherUser) return false;
  if (device.state === "fault" || device.state === "unknown" || device.state === "payment_pending") return false;
  return Boolean(device.canOrder);
}

export function activeIntentForDevice(goodsId) {
  const num = Number(goodsId);
  return activeIntents().find(
    (it) => Number(it.goodsId) === num || Number(it.focusGoodsId) === num || (Array.isArray(it.candidateGoodsIds) && it.candidateGoodsIds.includes(num))
  );
}

export function deviceCtaText(device) {
  if (device.occupiedByOtherUser) return "他人抢占";
  const myIntent = activeIntentForDevice(device.goodsId);
  if (myIntent) {
    return myIntent.isPool ? "并池盯守" : "守护中";
  }
  if (device.activeOrder?.stage === "awaiting_payment" || isOrderReadyToPay(device.activeOrder)) return "去支付";
  if (device.activeOrder?.stage === "reserve_waiting") return "已预约";
  if (device.activeOrder?.stage === "running") return "运行中";
  if (!canOpenDevice(device)) return "等待释放";
  if (device.state === "idle") return "立即抢";
  if (device.state === "busy_reservable") return "去预约";
  return "轮询抢";
}

export function renderTopbar() {
  const posName = store.data?.position?.positionName || "泓园 7 号楼";
  const buildingEl = $("buildingName");
  if (buildingEl) buildingEl.textContent = posName;
  if (typeof document !== "undefined") {
    document.title = `${posName} · 海乐抢占助手`;
  }

  const otaBtn = $("otaTopBtn");
  if (otaBtn) {
    const showOta = Boolean(store.ota?.hasUpdate);
    otaBtn.classList.toggle("hidden", !showOta);
    if (showOta) {
      otaBtn.textContent = store.ota.downloading ? `${store.ota.progress}%` : "新版本";
    }
  }

  const pill = $("accountPill");
  if (pill) {
    const acc = primaryAccount();
    if (!acc) {
      pill.className = "account-pill warn";
      pill.innerHTML = `<span class="dot red"></span><span>未登录</span>`;
    } else if (acc.tokenExpired) {
      pill.className = "account-pill warn";
      pill.innerHTML = `<span class="dot red"></span><span>登录过期</span>`;
    } else {
      const name = acc.displayName || acc.phone || "在线";
      const used = Number(acc.slotUsage ?? acc.blockingCount ?? 0);
      const limit = Number(acc.slotLimit || 2);
      pill.className = "account-pill";
      pill.innerHTML = `<span class="dot green"></span><span class="account-pill-name">${escapeHtml(name)}</span><span class="account-pill-slot">${used}/${limit}</span>`;
    }
  }
}

export function renderHeroZone() {
  const container = $("heroZone");
  if (!container) return;

  const acc = primaryAccount();
  const intents = activeIntents();
  const orders = activeOrders();

  // Case 1: Logged out or token expired -> Inline Auth / Revive Card
  if (!acc) {
    container.innerHTML = `
      <section class="card">
        <div class="task-head">
          <div>
            <div class="eyebrow">第一步 · 绑定海乐账号</div>
            <div class="pool-hero-title" style="margin-top:2px;">手机号验证码登录</div>
          </div>
          <span class="tag"><span class="dot"></span>本机隔离存储</span>
        </div>
        <div class="form-stack">
          <div class="form-row">
            <input id="loginPhone" type="tel" inputmode="numeric" autocomplete="tel" maxlength="11" placeholder="输入 11 位手机号" value="${escapeHtml(store.drafts.loginPhone)}">
            <button type="button" class="btn secondary tabular" id="loginSendCodeBtn" data-action="send-code-login" ${smsDisabledAttr("send-code-login")}>${smsBtnLabel("send-code-login", "获取验证码")}</button>
          </div>
          <div class="form-row">
            <input id="loginCode" type="tel" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="短信验证码" value="${escapeHtml(store.drafts.loginCode)}">
            <button type="button" class="btn" data-action="login" ${busyAttr("login")}>${busyText("login", "登录", "登录中")}</button>
          </div>
          <input id="loginRemark" type="text" placeholder="备注（可选，默认读取海乐昵称）" value="${escapeHtml(store.drafts.loginRemark)}">
        </div>
      </section>
    `;
    return;
  }

  if (acc.tokenExpired) {
    container.innerHTML = `
      <section class="card urgent">
        <div class="task-head">
          <div>
            <div class="eyebrow">账号凭证已过期</div>
            <div class="pool-hero-title" style="margin-top:2px;">${escapeHtml(acc.displayName || acc.phone)} 需接码恢复</div>
          </div>
          <span class="tag red"><span class="dot red"></span>已过期</span>
        </div>
        <div class="form-stack">
          <div class="form-row">
            <input id="reviveCode" type="tel" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="输入短信验证码" value="${escapeHtml(store.drafts.reviveCode)}">
            <button type="button" class="btn" data-action="revive-login" data-phone="${escapeHtml(acc.phone || "")}" ${busyAttr("revive-login")}>${busyText("revive-login", "恢复登录", "恢复中")}</button>
          </div>
          <div style="display:flex; justify-content:space-between; gap:8px;">
            <button type="button" class="btn secondary small tabular" id="reviveSendCodeBtn" data-action="send-code-revive" data-phone="${escapeHtml(acc.phone || "")}" ${smsDisabledAttr("send-code-revive")}>${smsBtnLabel("send-code-revive", "发送验证码")}</button>
            <button type="button" class="btn ghost small" data-action="open-account-sheet">切换其他账号</button>
          </div>
        </div>
      </section>
    `;
    return;
  }

  // Build blocks: Active Task / Orders Card + Optional Pool Quick-Start Card
  const blocks = [];
  const hasPoolIntent = intents.some((it) => it.isPool);
  const slotUsed = Number(acc.slotUsage ?? acc.blockingCount ?? 0);
  const slotLimit = Number(acc.slotLimit || 2);

  if (intents.length > 0 || orders.length > 0) {
    const anyUrgent = intents.some((it) => {
      const ord = orderForIntent(it);
      const rem = remainingFromOrder(ord);
      return rem !== null && ord?.stage === "awaiting_payment" && rem <= 35;
    });
    blocks.push(`
      <section class="card ${anyUrgent ? "urgent" : ""}" id="taskCard">
        <div class="task-head">
          <div>
            <div class="eyebrow">实时守护与活跃订单</div>
            <div class="pool-hero-title" style="margin-top:2px;">${intents.length ? `正在守护 ${intents.length} 项抢占` : "当前进行中订单"}</div>
          </div>
          <span class="tag primary">槽位 ${slotUsed}/${slotLimit}</span>
        </div>
        ${intents.length ? `<div class="task-list">${intents.map(renderTaskItem).join("")}</div>` : ""}
        ${orders.length ? `<div class="order-section-label">订单明细（洗烘结束前将自动提醒收衣）</div><div class="order-list">${orders.map(renderOrderRow).join("")}</div>` : ""}
      </section>
    `);
  }

  const washers = (store.data?.devices || []).filter((d) => d.deviceType === "washer");
  if (!hasPoolIntent && washers.length >= 2 && slotUsed < slotLimit) {
    const idleCount = washers.filter((d) => d.state === "idle" && !d.occupiedByOtherUser).length;
    const reservableCount = washers.filter((d) => d.state === "busy_reservable" && !d.occupiedByOtherUser).length;
    const prefSummary = `${store.prefs.washerModeName}${store.prefs.selfClean && store.prefs.washerModeName !== "单脱" && store.prefs.washerModeName !== "桶自洁" ? " + 筒自洁" : ""}`;
    const stateHint = idleCount > 0
      ? `${idleCount} 台空闲可秒锁`
      : reservableCount > 0
        ? `${reservableCount} 台可预约，优选最早释放`
        : `${washers.length} 台并池蹲守，释放即锁`;

    blocks.push(`
      <section class="pool-hero" aria-label="多机并池快捷抢占">
        <div class="pool-hero-top">
          <div>
            <div class="pool-hero-title">一键抢任意最快洗衣机</div>
            <div class="pool-hero-sub">${escapeHtml(stateHint)} · <span class="nowrap">偏好：${escapeHtml(prefSummary)}</span></div>
          </div>
          <span class="tag ${idleCount > 0 ? "green" : "primary"}">${washers.length} 台并池</span>
        </div>
        <div class="pool-hero-actions">
          <button type="button" class="btn secondary" data-action="open-pool-sheet">设偏好</button>
          <button type="button" class="btn" data-action="quick-start-pool" ${busyAttr("quick-pool")}>${busyText("quick-pool", "按偏好立即开抢", "启动中...")}</button>
        </div>
      </section>
    `);
  }

  container.innerHTML = blocks.join("");
}

export function orderForIntent(intent) {
  if (!intent) return null;
  const orders = store.data?.orders || [];
  if (intent.orderNo) {
    const byNo = orders.find((o) => o.orderNo === intent.orderNo && o.stage !== "ended");
    if (byNo) return byNo;
  }
  const focusId = Number(intent.focusGoodsId || intent.goodsId);
  if (focusId > 0) {
    return orders.find((o) => Number(o.goodsId) === focusId && (o.stage === "awaiting_payment" || o.stage === "reserve_waiting")) || null;
  }
  return null;
}

function taskStepIndex(intent, order) {
  const state = intent?.state || "acquiring";
  if (state === "fulfilled" || order?.stage === "running") return 3;
  if (state === "holding_payment" || order?.stage === "awaiting_payment" || isOrderReadyToPay(order)) return 2;
  if (state === "holding_reserve" || order?.stage === "reserve_waiting") return 1;
  return 0;
}

function taskHeadlineText(intent, order) {
  if (intent.state === "renewing") return "临近超时，续抢准备中";
  if (intent.state === "holding_payment" || order?.stage === "awaiting_payment") return "已抢到锁单 · 待支付";
  if (intent.state === "holding_reserve" || order?.stage === "reserve_waiting") {
    if (isOrderReadyToPay(order)) return "已排到号 · 请去验证/支付";
    if (order?.pageCode === "self_clean") return "洗前筒自洁进行中";
    return "已排上预约 · 候机第 1 位";
  }
  if (intent.state === "blocked") return intent.status || "账号需重新验证";
  return intent.isPool ? `并池竞速 · 盯 ${intent.focusDeviceName || "最快机"}` : `正在抢占 ${intent.deviceName || ""}`;
}

export function renderTaskItem(intent) {
  const order = orderForIntent(intent);
  const state = intent.state || "acquiring";
  const remaining = remainingFromOrder(order);
  const hasCountdown = remaining !== null && order?.stage === "awaiting_payment";
  const urgent = hasCountdown && remaining <= 35;
  const canPay = isOrderReadyToPay(order) || (state === "holding_payment" && Boolean(intent.orderNo) && !order);
  const selfClean = Boolean(intent.selfClean || order?.selfClean);
  const stepIdx = taskStepIndex(intent, order);
  const tone = canPay ? "green" : state === "blocked" ? "red" : "primary";
  const key = String(intent.goodsId).replace(/[^a-zA-Z0-9_-]/g, "_");
  const stopLabel = order?.stage === "awaiting_payment" || order?.stage === "reserve_waiting" ? "放弃并释放" : "停止抢占";
  const pct = hasCountdown ? Math.max(2, Math.min(100, Math.round((remaining / (Number(order?.expireSeconds) || 300)) * 100))) : null;

  const stepLabels = ["1 寻机抢占", selfClean ? "2 预约/自洁" : "2 预约候机", "3 App支付"];
  const metaParts = [
    intent.isPool ? `${intent.candidateCount || 5}台并池` : intent.deviceName,
    intent.modeName ? `${intent.modeName}${selfClean ? "+筒自洁" : ""}` : "",
    state === "acquiring" ? `${intent.pollPhaseLabel || "轮询"} · ${Number(intent.attempts) || 0}次` : "",
  ].filter(Boolean);

  const orderNo = String(order?.orderNo || intent.orderNo || "");
  const acctPhone = String(order?.accountPhone || intent.accountPhone || "");
  const orderStage = String(order?.stage || (state === "holding_payment" ? "awaiting_payment" : "reserve_waiting"));
  const pageCode = String(order?.pageCode || "");
  const isPayStage = orderStage === "awaiting_payment" || Boolean(order?.canPay);
  const primaryPayLabel = store.payChannels?.haileApp
    ? (isPayStage ? "打开海乐生活 App 支付" : "打开海乐生活 App 处理")
    : (isPayStage ? "去微信海乐小程序支付" : "去微信海乐小程序处理");
  const payGuideText = isPayStage
    ? "进入微信小程序点底部「订单」即可付款；同号支付宝可直达收银台"
    : "也可点上方按钮去微信小程序首页顶部查看该预约单";

  return `
    <article class="task-item ${urgent ? "urgent" : ""}" data-task-key="${key}" data-goods-id="${intent.goodsId}">
      <div class="task-item-top">
        <div class="task-headline">
          <span class="dot ${tone} ${state === "acquiring" || state === "renewing" ? "pulse" : ""}"></span>
          <span id="task-title-${key}">${escapeHtml(taskHeadlineText(intent, order))}</span>
        </div>
        <button type="button" class="btn danger small" data-action="prompt-stop-intent" data-goods-id="${intent.goodsId}" data-is-pool="${intent.isPool ? "1" : "0"}" data-has-order="${order ? "1" : "0"}">${stopLabel}</button>
      </div>
      <div class="task-steps">
        ${stepLabels.map((lbl, idx) => `<div class="task-step ${idx < stepIdx ? "done" : idx === stepIdx ? "current" : ""}">${escapeHtml(lbl)}</div>`).join("")}
      </div>
      <div class="task-timer-block">
        ${hasCountdown ? `<div class="task-countdown-big" id="task-cd-${key}">${formatDuration(remaining)}</div>` : ""}
        <div class="task-meta-line">
          ${metaParts.map((p) => `<span>${escapeHtml(p)}</span>`).join('<span class="meta-sep">·</span>')}
        </div>
      </div>
      <div class="task-status-note" id="task-note-${key}">${escapeHtml(intent.status || "")}</div>
      ${pct !== null ? `<div class="progress-track"><div class="progress-fill" id="task-bar-${key}" style="width:${pct}%; background:${pct < 20 ? "var(--red)" : "var(--green)"};"></div></div>` : ""}
      ${pageCode === "waiting_check" && orderNo && acctPhone ? `
        <div class="reserve-ops-box">
          <div class="eyebrow">已排到号 · 输入短信核验码或催前序取衣</div>
          <div class="form-row">
            <input id="verifyCodeInput" type="tel" inputmode="numeric" maxlength="6" placeholder="输入预约短信核验码" value="${escapeHtml(store.drafts.verifyCode || "")}" data-order-no="${escapeHtml(orderNo)}" data-phone="${escapeHtml(acctPhone)}">
            <button type="button" class="btn small" data-action="submit-verify-order" data-order-no="${escapeHtml(orderNo)}" data-phone="${escapeHtml(acctPhone)}" ${busyAttr("verify-order")}>${busyText("verify-order", "核验排号", "核验中")}</button>
          </div>
          <div class="pay-channel-row">
            <button type="button" class="pay-channel-pill" data-action="resend-verify-order" data-order-no="${escapeHtml(orderNo)}" data-phone="${escapeHtml(acctPhone)}" ${busyAttr("resend-verify")}>重发核验码</button>
            <button type="button" class="pay-channel-pill" data-action="remind-prev-pickup" data-order-no="${escapeHtml(orderNo)}" data-phone="${escapeHtml(acctPhone)}" ${busyAttr("remind-pickup")}>提醒前序同学取衣</button>
          </div>
        </div>
      ` : ""}
      ${pageCode === "place_clothes" && orderNo && acctPhone ? `
        <div class="reserve-ops-box">
          <div class="eyebrow">筒自洁已完成 · 请放入衣物并关好仓门</div>
          <button type="button" class="btn small" data-action="confirm-place-clothes" data-order-no="${escapeHtml(orderNo)}" data-phone="${escapeHtml(acctPhone)}" ${busyAttr("place-clothes")}>${busyText("place-clothes", "已放好衣物并关门 · 确认", "提交中")}</button>
        </div>
      ` : ""}
      ${canPay ? `
        <div class="pay-cta-stack">
          <button type="button" class="pay-cta-btn" data-action="open-haile-pay" data-prefer="auto" data-order-no="${escapeHtml(orderNo)}" data-stage="${escapeHtml(orderStage)}" data-device="${escapeHtml(intent.focusDeviceName || intent.deviceName || "")}">
            <span>${primaryPayLabel}</span>
            <span>立即前往 ›</span>
          </button>
          ${store.payChannels?.haileApp || (isPayStage && orderNo) ? `
            <div class="pay-channel-row">
              ${store.payChannels?.haileApp ? `<button type="button" class="pay-channel-pill" data-action="open-haile-pay" data-prefer="weapp" data-order-no="${escapeHtml(orderNo)}" data-stage="${escapeHtml(orderStage)}" data-device="${escapeHtml(intent.focusDeviceName || intent.deviceName || "")}">微信小程序</button>` : ""}
              ${isPayStage && orderNo ? `<button type="button" class="pay-channel-pill" data-action="open-haile-pay" data-prefer="alipay" data-order-no="${escapeHtml(orderNo)}" data-stage="${escapeHtml(orderStage)}" data-device="${escapeHtml(intent.focusDeviceName || intent.deviceName || "")}">支付宝收银台直达 ›</button>` : ""}
            </div>
          ` : ""}
          <div class="pay-hint">${escapeHtml(payGuideText)}</div>
        </div>
      ` : ""}
    </article>
  `;
}

export function orderTimeParts(order) {
  if (order.stage === "awaiting_payment") {
    const rem = remainingFromOrder(order);
    return { main: rem !== null ? formatDuration(rem) : formatClock(order.deadline) || "--", sub: "支付剩余" };
  }
  if (order.stage === "reserve_waiting") {
    if (isOrderReadyToPay(order)) {
      return { main: formatClock(order.deadline) || "请去验证", sub: order.deadline ? "验证截止" : "前序已完" };
    }
    return { main: "排第 1 位", sub: "候机中" };
  }
  if (order.stage === "running") {
    const rem = remainingFromEstimate(order);
    const remMin = rem !== null ? (rem <= 0 ? "即将洗完" : rem < 60 ? "1分钟内" : `剩约 ${Math.ceil(rem / 60)} 分`) : "";
    const clock = formatClock(order.estimatedFinishTime);
    return {
      main: clock ? `${clock} 完成` : remMin || "洗烘中",
      sub: clock && remMin ? remMin : "自动收衣提醒",
    };
  }
  return { main: order.stageLabel || "处理中", sub: "订单状态" };
}

function splitOrderName(order) {
  const text = String(order?.goodsName || order?.projectName || "洗衣设备");
  if (text.includes("【") && text.includes("】")) {
    const [dev, rest] = text.split("【", 2);
    return { device: dev.trim() || text, mode: order?.modeName || rest.split("】", 1)[0] || "" };
  }
  return { device: text, mode: order?.modeName || "" };
}

export function renderOrderRow(order) {
  const { device, mode } = splitOrderName(order);
  const tone = orderTone(order);
  const time = orderTimeParts(order);
  const canPay = isOrderReadyToPay(order);
  const canCancel = Boolean((order.stage === "awaiting_payment" || order.stage === "reserve_waiting") && order.canCancel && order.accountPhone && order.orderNo);
  const cancelKey = `cancel-${order.orderNo}`;
  const cleanNote = order.selfClean && !String(mode).includes("自洁") ? "+筒自洁" : "";

  return `
    <article class="order-row" data-order-no="${escapeHtml(order.orderNo || "")}">
      <div class="order-main">
        <div class="order-title">${escapeHtml(device)}</div>
        <div class="order-sub">
          <span class="tag ${tone}"><span class="dot ${tone}"></span>${escapeHtml(order.stageLabel || "处理中")}</span>
          ${mode ? `<span>${escapeHtml(mode)}${cleanNote}</span>` : ""}
          ${order.amount && Number(order.amount) > 0 ? `<span class="tabular">¥${escapeHtml(order.amount)}</span>` : ""}
        </div>
      </div>
      <div class="order-side">
        <div class="order-time" id="order-time-${escapeHtml(order.orderNo || "")}">${escapeHtml(time.main)}<small>${escapeHtml(time.sub)}</small></div>
        ${(canCancel || canPay) ? `
          <div class="order-actions">
            ${canCancel ? `<button type="button" class="btn ghost small" data-action="cancel-order" data-order-no="${escapeHtml(order.orderNo)}" data-phone="${escapeHtml(order.accountPhone)}" ${busyAttr(cancelKey)}>${busyText(cancelKey, "取消", "取消中")}</button>` : ""}
            ${canPay ? `<button type="button" class="btn green small" data-action="open-haile-pay" data-prefer="auto" data-order-no="${escapeHtml(order.orderNo || "")}" data-stage="${escapeHtml(order.stage || "")}" data-device="${escapeHtml(device)}">去支付 ›</button>` : ""}
          </div>
        ` : ""}
      </div>
    </article>
  `;
}

function sortedDevices(devices) {
  return [...devices].sort((a, b) => {
    const typeDiff = (a.deviceType === "dryer" ? 1 : 0) - (b.deviceType === "dryer" ? 1 : 0);
    if (typeDiff !== 0) return typeDiff;
    const scoreDiff = (Number(b.recommendedScore) || 0) - (Number(a.recommendedScore) || 0);
    if (scoreDiff !== 0) return scoreDiff;
    return String(a.name || "").localeCompare(String(b.name || ""), "zh-CN", { numeric: true });
  });
}

export function renderDevices() {
  const listEl = $("deviceList");
  if (!listEl) return;
  const devices = store.data?.devices || [];
  if (!devices.length) {
    listEl.innerHTML = `<div class="card empty-state">正在同步楼内洗衣与烘干设备状态...</div>`;
    return;
  }

  const byType = new Map();
  for (const dev of sortedDevices(devices)) {
    const t = dev.deviceType || "washer";
    if (!byType.has(t)) byType.set(t, []);
    byType.get(t).push(dev);
  }

  const html = [];
  for (const [type, items] of byType.entries()) {
    const label = type === "dryer" ? "烘干机" : "洗衣机";
    const idleNum = items.filter((d) => d.state === "idle" && !d.occupiedByOtherUser).length;
    const summary = idleNum > 0 ? `${idleNum} 台立即可用` : "按最快释放排序";
    html.push(`
      <section class="device-group">
        <div class="device-group-head">
          <div class="device-group-title">
            <span>${label}</span>
            <span class="device-group-count">${items.length} 台</span>
          </div>
          <div class="device-group-meta">${summary}</div>
        </div>
        <div class="device-stack">
          ${items.map(renderDeviceCard).join("")}
        </div>
      </section>
    `);
  }
  listEl.innerHTML = html.join("");
}

function renderDeviceCard(device) {
  const tone = stateTone(device);
  const myIntent = activeIntentForDevice(device.goodsId);
  const hasMyOrder = Boolean(device.activeOrder);
  const disabled = !canOpenDevice(device) && !myIntent && !hasMyOrder;
  const no = String(device.name || "").match(/\d+/)?.[0] || "--";
  const kindLabel = device.deviceType === "dryer" ? "烘干" : "洗衣";
  const cyclePct = deviceCycleProgress(device);
  const isTopRec = Boolean(device.isRecommended && Number(device.recommendedRank) === 1 && !device.occupiedByOtherUser && !myIntent);

  return `
    <article class="device-card ${disabled ? "disabled" : ""} ${myIntent ? "active" : ""} ${device.inPool ? "in-pool" : ""}" data-tone="${tone}" data-action="tap-device" data-goods-id="${device.goodsId}">
      <div class="device-badge">
        <span class="device-num">${escapeHtml(no)}</span>
        <span class="device-kind">${kindLabel}</span>
      </div>
      <div class="device-body">
        <div class="device-title-row">
          <span class="device-name">${escapeHtml(device.name || "设备")}</span>
          ${isTopRec ? `<span class="rec-badge">首选</span>` : ""}
          ${device.inPool ? `<span class="rec-badge">并池中</span>` : ""}
        </div>
        <div class="device-meta-row">
          <span class="tag ${tone}"><span class="dot ${tone}"></span>${escapeHtml(device.stateLabel || "未知")}</span>
          ${device.occupiedByOtherUser ? `<span>${escapeHtml(device.occupiedByOtherUserLabel || "室友")}抢占中</span>` : `<span>${escapeHtml(device.queueText || "")}</span>`}
          <span class="device-eta" data-eta-goods="${device.goodsId}">${escapeHtml(etaText(device))}</span>
        </div>
        ${cyclePct !== null ? `<div class="device-cycle"><span class="device-cycle-bar" data-cycle-goods="${device.goodsId}" style="width:${cyclePct}%;"></span></div>` : ""}
      </div>
      <div class="device-cta">
        <span>${escapeHtml(deviceCtaText(device))}</span>
        ${disabled ? "" : `<span aria-hidden="true">›</span>`}
      </div>
    </article>
  `;
}

export function durationOptionsForMode(mode) {
  const items = mode?.extAttrDto?.items;
  const values = Array.isArray(items)
    ? items
        .filter((it) => it?.isEnabled !== false && Number.isFinite(Number(it?.unitAmount)))
        .map((it) => ({
          minutes: Number(it.unitAmount),
          price: it.unitPrice || it.discountAfterPrice || it.discountPrice || "",
        }))
        .sort((a, b) => a.minutes - b.minutes)
    : [];
  return values.length ? values : DRYER_FALLBACK_DURATIONS;
}

export function modeSupportsSelfClean(mode, isDryer = false) {
  if (isDryer || !mode) return false;
  return Boolean(mode.attachMap && mode.attachMap.SelfClean === true);
}

export function renderModeSheetContent(modes) {
  const isDryer = Boolean(!store.poolSheetMode && String(store.selectedDevice?.name || "").includes("烘干"));
  const grid = $("modeGrid");
  const startBtn = $("startBtn");
  const durationBlock = $("durationBlock");
  const scBlock = $("selfCleanBlock");

  if (!modes || !modes.length) {
    if (grid) grid.innerHTML = `<div class="empty-state" style="grid-column:1/-1;">暂无可用洗烘套餐</div>`;
    if (startBtn) startBtn.disabled = true;
    return;
  }

  if (!store.selectedMode) {
    const preferredName = store.prefs.washerModeName;
    store.selectedMode =
      (!isDryer && modes.find((m) => String(m.name || "") === preferredName || String(m.name || "").includes(preferredName))) ||
      modes[0];
  }

  if (startBtn) startBtn.disabled = false;
  if (durationBlock) durationBlock.classList.toggle("hidden", !isDryer);

  if (grid) {
    grid.innerHTML = modes
      .map((m) => {
        const active = store.selectedMode && (m.id ? m.id === store.selectedMode.id : m.name === store.selectedMode.name);
        const price = isDryer ? (durationOptionsForMode(m)[0]?.price ? `¥${durationOptionsForMode(m)[0].price}起` : "按时长") : m.price ? `¥${m.price}` : "--";
        const sub = isDryer ? "下方选择烘干时长" : [m.unit ? `约${m.unit}分钟` : "", modeSupportsSelfClean(m, false) ? "支持洗前自洁" : "独立程序"].filter(Boolean).join(" · ");
        return `
          <button type="button" class="mode-tile ${active ? "active" : ""}" data-action="select-mode" data-mode-id="${m.id || 0}" data-mode-name="${escapeHtml(m.name || "")}">
            <span class="mode-tile-top">
              <span class="mode-name">${escapeHtml(m.name || "套餐")}</span>
              <span class="mode-price">${escapeHtml(price)}</span>
            </span>
            <span class="mode-sub">${escapeHtml(sub)}</span>
          </button>
        `;
      })
      .join("");
  }

  if (isDryer) {
    const opts = durationOptionsForMode(store.selectedMode);
    if (!opts.some((o) => o.minutes === store.selectedDuration)) {
      store.selectedDuration = opts.find((o) => o.minutes === 30)?.minutes || opts[0]?.minutes || 30;
    }
    $("durationOptions").innerHTML = opts
      .map(
        (o) => `
        <button type="button" class="seg-btn ${o.minutes === store.selectedDuration ? "active" : ""}" data-action="select-duration" data-minutes="${o.minutes}">
          <span>${o.minutes}分</span>
          ${o.price ? `<small>¥${escapeHtml(o.price)}</small>` : ""}
        </button>
      `
      )
      .join("");
  }

  const scSupported = modeSupportsSelfClean(store.selectedMode, isDryer);
  if (scBlock) {
    scBlock.classList.toggle("hidden", !scSupported);
    const scOn = Boolean(scSupported && store.prefs.selfClean);
    scBlock.classList.toggle("active", scOn);
    scBlock.setAttribute("aria-checked", scOn ? "true" : "false");
  }

  if (startBtn && !store.busy.has("start-intent")) {
    const mName = store.selectedMode?.name || "套餐";
    if (isDryer) {
      const opt = durationOptionsForMode(store.selectedMode).find((o) => o.minutes === store.selectedDuration);
      startBtn.textContent = `开始抢占 · ${mName} ${store.selectedDuration}分钟${opt?.price ? ` · ¥${opt.price}` : ""}`;
    } else {
      const scOn = Boolean(scSupported && store.prefs.selfClean);
      const prefix = store.poolSheetMode ? "保存偏好并并池开抢" : "开始抢占";
      startBtn.textContent = `${prefix} · ${mName}${scOn ? "+筒自洁" : ""}${store.selectedMode?.price && !store.poolSheetMode ? ` · ¥${store.selectedMode.price}` : ""}`;
    }
  }
}

export function renderAccountSheet() {
  const body = $("accountSheetBody");
  if (!body) return;
  const acc = primaryAccount();
  const curPos = store.data?.position || { positionId: 32577, positionName: "泓园 7 号楼" };
  const localVer = getLocalVersionName();
  const ota = store.ota;
  const logs = (store.data?.logs || []).slice(-30).reverse();

  body.innerHTML = `
    <div>
      <div class="eyebrow">海乐生活账号</div>
      ${acc ? `
        <div style="display:flex; align-items:center; justify-content:space-between; gap:8px; margin-top:6px;">
          <div>
            <div style="font-size:15.5px; font-weight:700;">${escapeHtml(acc.displayName || acc.phone)}</div>
            <div class="tabular" style="font-size:12.5px; color:var(--text-muted);">${escapeHtml(acc.phone || "")} · 占用 ${Number(acc.slotUsage ?? acc.blockingCount ?? 0)}/2</div>
          </div>
          <span class="tag ${acc.tokenExpired ? "red" : "green"}">${acc.tokenExpired ? "已过期" : "在线"}</span>
        </div>
        <div class="form-row" style="margin-top:10px;">
          <input id="sheetRemarkInput" type="text" placeholder="修改账号备注（如 神舟 / 室友）" value="${escapeHtml(store.drafts.remarkInput || acc.remark || "")}">
          <button type="button" class="btn secondary" data-action="save-remark" data-phone="${escapeHtml(acc.phone || "")}" ${busyAttr("save-remark")}>保存</button>
        </div>
      ` : `<div class="sheet-sub">尚未登录账号，可在首页直接输入手机号验证码登录。</div>`}

      <details style="margin-top:10px;">
        <summary style="font-size:13px; color:var(--primary-text); font-weight:600; cursor:pointer;">${acc ? "切换手机号或重新接码登录" : "在此处验证码登录"}</summary>
        <div class="form-stack">
          <div class="form-row">
            <input id="sheetLoginPhone" type="tel" inputmode="numeric" maxlength="11" placeholder="11 位手机号" value="${escapeHtml(store.drafts.loginPhone || acc?.phone || "")}">
            <button type="button" class="btn secondary tabular" id="sheetSendCodeBtn" data-action="sheet-send-code" ${smsDisabledAttr("send-code-login")}>${smsBtnLabel("send-code-login", "发验证码")}</button>
          </div>
          <div class="form-row">
            <input id="sheetLoginCode" type="tel" inputmode="numeric" maxlength="6" placeholder="短信验证码" value="${escapeHtml(store.drafts.loginCode)}">
            <button type="button" class="btn" data-action="sheet-do-login" ${busyAttr("login")}>完成登录</button>
          </div>
        </div>
      </details>
    </div>

    <div class="settings-group">
      <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
        <div>
          <div class="eyebrow">宿舍洗衣房点位</div>
          <div style="font-size:14px; font-weight:700; margin-top:2px;">${escapeHtml(curPos.positionName)} <span class="tabular" style="font-weight:500; color:var(--text-muted);">#${curPos.positionId}</span></div>
        </div>
        <button type="button" class="btn secondary small" data-action="open-position-sheet">地图与常用点位 ›</button>
      </div>
    </div>

    <div class="settings-group">
      <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
        <div>
          <div class="eyebrow">客户端版本与在线更新</div>
          <div style="font-size:14px; font-weight:700; margin-top:2px;">v${escapeHtml(localVer)} ${ota.hasUpdate ? `<span class="tag primary" style="margin-left:6px;">新版本 v${escapeHtml(ota.versionName)}</span>` : `<span class="tag green" style="margin-left:6px;">最新</span>`}</div>
        </div>
        <button type="button" class="btn ${ota.hasUpdate ? "" : "secondary"} small" data-action="manual-check-ota" ${ota.checking || ota.downloading ? "disabled" : ""}>${ota.downloading ? `下载中 ${ota.progress}%` : ota.checking ? "检查中..." : ota.hasUpdate ? "查看更新" : "检查更新"}</button>
      </div>
      ${ota.releaseNotes ? `<div style="font-size:12px; color:var(--text-muted);">${escapeHtml(ota.releaseNotes)}</div>` : ""}
      ${ota.downloading || ota.statusText ? `
        <div style="font-size:12px; color:var(--primary-text);">${escapeHtml(ota.statusText)}</div>
        ${ota.downloading ? `<div class="progress-track"><div class="progress-fill" style="width:${ota.progress}%;"></div></div>` : ""}
      ` : ""}
    </div>

    <div class="settings-group">
      <div style="display:flex; align-items:center; justify-content:space-between;">
        <span class="eyebrow">实时引擎日志（最近 ${logs.length} 条）</span>
        <button type="button" class="btn ghost small" data-action="toggle-logs">${store.logsExpanded ? "收起" : "展开查看"}</button>
      </div>
      ${store.logsExpanded ? `<div class="log-box">${logs.length ? escapeHtml(logs.join("\n")) : "暂无日志"}</div>` : ""}
    </div>
  `;
}

export function renderOtaSheet() {
  const body = $("otaSheetBody");
  const titleEl = $("otaSheetTitle");
  if (!body) return;
  const localVer = getLocalVersionName();
  const ota = store.ota;
  const nativeInApp = isNativeRuntime() && typeof window.HaileNative?.downloadAndInstallApk === "function";

  if (titleEl) {
    titleEl.textContent = ota.hasUpdate ? `发现新版本 v${ota.versionName}` : `当前版本 v${localVer}`;
  }

  body.innerHTML = `
    <div class="ota-card-box">
      <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
        <span style="font-size:13px; color:var(--text-secondary);">版本变更</span>
        <span class="tabular" style="font-size:13.5px; font-weight:700;">v${escapeHtml(localVer)} → v${escapeHtml(ota.versionName || localVer)}</span>
      </div>
      <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
        <span style="font-size:13px; color:var(--text-secondary);">安装包文件</span>
        <span class="mono" style="font-size:12.5px; color:var(--text-muted);">HaileBot-v4.apk</span>
      </div>
      ${ota.releaseNotes ? `<div style="font-size:13px; color:var(--text); line-height:1.5; padding-top:4px; border-top:1px solid var(--border);">${escapeHtml(ota.releaseNotes)}</div>` : ""}
    </div>
    ${ota.statusText ? `
      <div style="margin-top:10px; font-size:12.5px; color:${ota.error ? "var(--red-text)" : "var(--primary-text)"};">${escapeHtml(ota.statusText)}</div>
    ` : ""}
    ${ota.downloading ? `
      <div class="progress-track" style="margin-top:8px;"><div class="progress-fill" style="width:${ota.progress}%;"></div></div>
    ` : ""}
    <div style="display:flex; flex-direction:column; gap:8px; margin-top:14px;">
      <button type="button" class="btn" data-action="confirm-install-ota" ${ota.downloading ? "disabled" : ""}>
        ${ota.downloading ? `正在下载更新包 (${ota.progress}%)` : nativeInApp ? "应用内下载并安装（推荐）" : "下载 HaileBot-v4.apk 安装包"}
      </button>
      ${nativeInApp ? `
        <button type="button" class="btn ghost small" data-action="fallback-browser-ota" ${ota.downloading ? "disabled" : ""}>若网络受阻，改用系统浏览器下载</button>
      ` : ""}
    </div>
  `;
}

export function renderPoolModeSheet() {
  store.poolSheetMode = true;
  store.selectedDevice = null;
  store.selectedMode = POOL_WASHER_MODES.find((m) => m.name === store.prefs.washerModeName) || POOL_WASHER_MODES[0];
  $("sheetKicker").textContent = "多机并池竞速 · 仅占 1 个槽位";
  $("sheetTitle").textContent = "抢任意最快洗衣机";
  $("sheetMeta").innerHTML = `<span>自动盯全楼候选洗衣机，任一率先空闲或可约即刻锁单</span>`;
  renderModeSheetContent(POOL_WASHER_MODES);
}

function formatDistanceLabel(dist) {
  const n = Number(dist);
  if (!Number.isFinite(n) || n < 0) return "";
  if (n < 1000) return `${Math.round(n)}m`;
  return `${(n / 1000).toFixed(1)}km`;
}

export function renderPositionSheet() {
  const curPos = store.data?.position || { positionId: 32577, positionName: "泓园 7 号楼" };
  const quickEl = $("posQuickSection");
  const chipsEl = $("posPlaceChips");
  const listEl = $("posNearbyList");
  const locBtn = $("posLocateBtn");
  const searchBtn = $("posSearchBtn");
  const customBtn = $("customPosBtn");

  if (locBtn) {
    locBtn.disabled = Boolean(store.locatingGps);
    locBtn.textContent = store.locatingGps ? "定位中..." : "定位";
  }
  if (searchBtn) {
    searchBtn.disabled = Boolean(store.loadingPositions);
    searchBtn.textContent = store.loadingPositions ? "检索中" : "搜索";
  }
  if (customBtn) {
    customBtn.disabled = store.busy.has("resolve-custom-pos");
    customBtn.textContent = store.busy.has("resolve-custom-pos") ? "解析中..." : "解析切换";
  }

  if (quickEl) {
    const mergedPosMap = new Map();
    for (const p of store.data?.position?.recentPositions || []) {
      if (p?.positionId) mergedPosMap.set(Number(p.positionId), p);
    }
    for (const p of store.recentUsage?.positions || []) {
      if (p?.positionId && !mergedPosMap.has(Number(p.positionId))) {
        mergedPosMap.set(Number(p.positionId), p);
      }
    }
    const recentPositions = Array.from(mergedPosMap.values()).slice(0, 6);
    const recentDevs = (store.recentUsage?.devices || []).slice(0, 6);
    const loggedIn = Boolean(primaryAccount() && !primaryAccount().tokenExpired);

    quickEl.innerHTML = `
      <div class="pos-cur-banner">
        <div style="min-width:0;">
          <div class="eyebrow">当前绑定点位</div>
          <div style="font-size:14.5px; font-weight:700; margin-top:1px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">
            ${escapeHtml(curPos.positionName)}
            <span class="tabular" style="font-size:12px; font-weight:500; color:var(--text-muted); margin-left:4px;">#${curPos.positionId}</span>
          </div>
        </div>
        <span class="tag green">已激活</span>
      </div>

      ${recentPositions.length ? `
        <div class="recent-block">
          <div class="eyebrow">常用与历史楼栋</div>
          <div class="recent-chip-row">
            ${recentPositions.map((p) => `
              <button
                type="button"
                class="recent-chip ${Number(p.positionId) === Number(curPos.positionId) ? "active" : ""}"
                data-action="select-position"
                data-pos-id="${p.positionId}"
                data-pos-name="${escapeHtml(p.name || `点位 ${p.positionId}`)}"
                data-pos-lng="${p.lng ?? ""}"
                data-pos-lat="${p.lat ?? ""}"
              >
                <span>${escapeHtml(p.name || `点位 ${p.positionId}`)}</span>
              </button>
            `).join("")}
          </div>
        </div>
      ` : ""}

      <div class="recent-block">
        <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
          <span class="eyebrow">最近使用设备</span>
          ${loggedIn ? `<button type="button" class="btn ghost small" style="min-height:24px; padding:0 6px; font-size:11.5px;" data-action="refresh-recent-usage" ${store.loadingRecent ? "disabled" : ""}>${store.loadingRecent ? "同步中..." : "刷新"}</button>` : ""}
        </div>
        ${!loggedIn ? `
          <div class="pay-hint">登录海乐账号后，将自动同步常用宿舍楼与历史洗烘设备。</div>
        ` : store.loadingRecent && !recentDevs.length ? `
          <div class="pay-hint">正在读取海乐历史使用设备...</div>
        ` : recentDevs.length ? `
          <div class="recent-dev-list">
            ${recentDevs.map((d) => {
              const tone = stateTone(d);
              const samePos = Number(d.positionId) === Number(curPos.positionId);
              const remMin = Number.isFinite(Number(d.remainingSeconds)) && Number(d.remainingSeconds) > 0
                ? `约${Math.ceil(Number(d.remainingSeconds) / 60)}分`
                : "";
              return `
                <button
                  type="button"
                  class="recent-dev-card"
                  data-action="tap-recent-device"
                  data-goods-id="${d.goodsId}"
                  data-pos-id="${d.positionId || 0}"
                  data-pos-name="${escapeHtml(d.positionName || "")}"
                >
                  <span class="recent-dev-top">
                    <span class="recent-dev-name">${escapeHtml(d.name || "设备")}</span>
                    <span class="tag ${tone}" style="height:20px; padding:0 6px; font-size:10.5px;">${escapeHtml(d.stateLabel || "未知")}</span>
                  </span>
                  <span class="recent-dev-sub">
                    <span style="overflow:hidden; text-overflow:ellipsis;">${escapeHtml(d.positionName || (samePos ? "当前楼栋" : `点位 #${d.positionId}`))}</span>
                    <span style="color:var(--primary-text); font-weight:600; flex-shrink:0;">${remMin ? remMin : samePos ? "去抢占 ›" : "切楼 ›"}</span>
                  </span>
                </button>
              `;
            }).join("")}
          </div>
        ` : `
          <div class="pay-hint">当前账号暂无近期洗烘设备记录，可在下方地图直接选择宿舍楼。</div>
        `}
      </div>
    `;
  }

  if (chipsEl) {
    const places = (store.searchPlaces || []).slice(0, 5);
    chipsEl.innerHTML = places.length
      ? `<div class="recent-chip-row">
          ${places.map((pl) => `
            <button
              type="button"
              class="recent-chip"
              data-action="jump-map-place"
              data-lng="${pl.lng}"
              data-lat="${pl.lat}"
              data-place-name="${escapeHtml(pl.name)}"
              title="${escapeHtml(pl.subtitle || "")}"
            >
              <span>${escapeHtml(pl.name)}</span>
            </button>
          `).join("")}
        </div>`
      : "";
  }

  if (listEl) {
    const list = store.nearbyPositions || [];
    if (store.loadingPositions && !list.length) {
      listEl.innerHTML = `<div class="empty-state" style="padding:14px 8px;">正在检索周边海乐洗衣房...</div>`;
    } else if (!list.length) {
      listEl.innerHTML = `<div class="empty-state" style="padding:14px 8px;">当前地图区域暂无海乐点位，可搜索学校名或拖动地图重试</div>`;
    } else {
      listEl.innerHTML = `
        <div class="pos-list">
          ${list.map((p) => {
            const active = Number(p.positionId) === Number(curPos.positionId);
            const distText = formatDistanceLabel(p.distance);
            const idleHint = Number.isFinite(Number(p.totalWasherCount)) && Number(p.totalWasherCount) > 0
              ? `空闲 ${Number(p.idleWasherCount || 0)}/${Number(p.totalWasherCount)}`
              : Number.isFinite(Number(p.idleCount)) && Number(p.idleCount) >= 0
                ? `${Number(p.idleCount)} 台空`
                : "";
            const idleZero = (Number.isFinite(Number(p.totalWasherCount)) && Number(p.idleWasherCount || 0) === 0)
              || (!Number.isFinite(Number(p.totalWasherCount)) && Number.isFinite(Number(p.idleCount)) && Number(p.idleCount) === 0);
            return `
              <button
                type="button"
                class="pos-item ${active ? "active" : ""}"
                data-action="select-position"
                data-pos-id="${p.positionId}"
                data-pos-name="${escapeHtml(p.name)}"
                data-pos-lng="${p.lng ?? ""}"
                data-pos-lat="${p.lat ?? ""}"
                data-pos-address="${escapeHtml(p.address || "")}"
              >
                <span class="pos-item-main">
                  <span class="pos-item-title">${escapeHtml(p.name)}</span>
                  ${p.address ? `<span class="pos-item-sub">${escapeHtml(p.address)}</span>` : ""}
                </span>
                <span class="pos-item-side tabular">
                  ${idleHint ? `<span class="tag ${idleZero ? "" : "green"}" style="height:20px; padding:0 6px; font-size:10.5px;">${idleHint}</span>` : ""}
                  ${distText ? `<span>${distText}</span>` : ""}
                  <span>#${p.positionId}</span>
                </span>
              </button>
            `;
          }).join("")}
        </div>
      `;
    }
  }
}
