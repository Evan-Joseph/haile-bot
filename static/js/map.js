import { escapeHtml, isNativeRuntime } from "./store.js";

const GCJ_A = 6378245.0;
const GCJ_EE = 0.00669342162296594323;

function outOfChina(lng, lat) {
  return !(lng >= 72.004 && lng <= 137.8347 && lat >= 0.8293 && lat <= 55.8271);
}

function transformLat(x, y) {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin((y / 3.0) * Math.PI)) * 2.0) / 3.0;
  ret += ((160.0 * Math.sin((y / 12.0) * Math.PI) + 320.0 * Math.sin((y * Math.PI) / 30.0)) * 2.0) / 3.0;
  return ret;
}

function transformLng(x, y) {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin((x / 3.0) * Math.PI)) * 2.0) / 3.0;
  ret += ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300.0 * Math.sin((x / 30.0) * Math.PI)) * 2.0) / 3.0;
  return ret;
}

export function wgs84ToGcj02(lng, lat) {
  const lon = Number(lng);
  const la = Number(lat);
  if (!Number.isFinite(lon) || !Number.isFinite(la) || outOfChina(lon, la)) {
    return { lng: Number(lon.toFixed(6)), lat: Number(la.toFixed(6)) };
  }
  let dLat = transformLat(lon - 105.0, la - 35.0);
  let dLng = transformLng(lon - 105.0, la - 35.0);
  const radLat = (la / 180.0) * Math.PI;
  let magic = Math.sin(radLat);
  magic = 1.0 - GCJ_EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / (((GCJ_A * (1.0 - GCJ_EE)) / (magic * sqrtMagic)) * Math.PI);
  dLng = (dLng * 180.0) / ((GCJ_A / sqrtMagic) * Math.cos(radLat) * Math.PI);
  return {
    lng: Number((lon + dLng).toFixed(6)),
    lat: Number((la + dLat).toFixed(6)),
  };
}

function lngToWorldX(lng, zoom) {
  return ((lng + 180) / 360) * 256 * Math.pow(2, zoom);
}

function latToWorldY(lat, zoom) {
  const clamped = Math.max(-85.0511, Math.min(85.0511, lat));
  const sin = Math.sin((clamped * Math.PI) / 180);
  return (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * 256 * Math.pow(2, zoom);
}

function worldXToLng(wx, zoom) {
  const world = 256 * Math.pow(2, zoom);
  return (wx / world) * 360 - 180;
}

function worldYToLat(wy, zoom) {
  const world = 256 * Math.pow(2, zoom);
  const n = Math.PI - (2 * Math.PI * wy) / world;
  return (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
}

export function requestCurrentGcjLocation() {
  return new Promise((resolve, reject) => {
    if (isNativeRuntime() && typeof window.HaileNative?.requestDeviceLocation === "function") {
      let settled = false;
      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          reject(new Error("定位超时，可直接搜索学校名称或拖动地图"));
        }
      }, 7500);
      window.__onHaileLocation = (payload) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (payload && payload.ok && Number.isFinite(Number(payload.lng)) && Number.isFinite(Number(payload.lat))) {
          resolve({ lng: Number(payload.lng), lat: Number(payload.lat) });
        } else {
          reject(new Error(payload?.message || "未获取到手机定位"));
        }
      };
      try {
        window.HaileNative.requestDeviceLocation();
        return;
      } catch (_) {
        settled = true;
        clearTimeout(timer);
      }
    }

    if (!navigator.geolocation) {
      reject(new Error("当前环境不支持浏览器定位，请直接搜索学校名称"));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const gcj = wgs84ToGcj02(pos.coords.longitude, pos.coords.latitude);
        resolve(gcj);
      },
      (err) => {
        const msg =
          err?.code === 1
            ? "未授予位置权限，请直接输入学校名称搜索或拖动地图"
            : "定位信号较弱，可直接搜索学校名称或拖动地图";
        reject(new Error(msg));
      },
      { enableHighAccuracy: true, timeout: 6500, maximumAge: 180000 }
    );
  });
}

export class SlippyMapPicker {
  constructor(container, { lng = 120.425, lat = 36.0716, zoom = 16, onMoveEnd, onSelectMarker } = {}) {
    this.container = container;
    this.lng = Number(lng) || 120.425;
    this.lat = Number(lat) || 36.0716;
    this.zoom = Math.max(11, Math.min(18, Number(zoom) || 16));
    this.markers = [];
    this.activePositionId = 0;
    this.onMoveEnd = onMoveEnd;
    this.onSelectMarker = onSelectMarker;
    this._dragging = false;
    this._moved = false;
    this._startX = 0;
    this._startY = 0;
    this._startWx = 0;
    this._startWy = 0;
    this._moveTimer = null;
    this._mount();
  }

  _mount() {
    if (!this.container) return;
    this.container.innerHTML = `
      <div class="map-tiles-layer"></div>
      <div class="map-markers-layer"></div>
      <div class="map-center-pin" aria-hidden="true">
        <span class="map-center-dot"></span>
        <span class="map-center-stem"></span>
      </div>
      <div class="map-zoom-controls">
        <button type="button" class="map-ctrl-btn" data-map-zoom="1" aria-label="放大地图">+</button>
        <button type="button" class="map-ctrl-btn" data-map-zoom="-1" aria-label="缩小地图">−</button>
      </div>
      <div class="map-coord-badge tabular"></div>
    `;
    this.tilesLayer = this.container.querySelector(".map-tiles-layer");
    this.markersLayer = this.container.querySelector(".map-markers-layer");
    this.coordBadge = this.container.querySelector(".map-coord-badge");

    this.container.addEventListener("pointerdown", (e) => {
      if (e.target.closest("[data-map-zoom]") || e.target.closest("[data-marker-pid]")) return;
      this._dragging = true;
      this._moved = false;
      this._startX = e.clientX;
      this._startY = e.clientY;
      this._startWx = lngToWorldX(this.lng, this.zoom);
      this._startWy = latToWorldY(this.lat, this.zoom);
      this.container.classList.add("dragging");
      try {
        this.container.setPointerCapture(e.pointerId);
      } catch (_) {}
    });

    this.container.addEventListener("pointermove", (e) => {
      if (!this._dragging) return;
      const dx = e.clientX - this._startX;
      const dy = e.clientY - this._startY;
      if (Math.hypot(dx, dy) > 6) this._moved = true;
      const nextWx = this._startWx - dx;
      const nextWy = this._startWy - dy;
      this.lng = Number(worldXToLng(nextWx, this.zoom).toFixed(6));
      this.lat = Number(worldYToLat(nextWy, this.zoom).toFixed(6));
      this.render();
    });

    const finishDrag = (e) => {
      if (!this._dragging) return;
      this._dragging = false;
      this.container.classList.remove("dragging");
      try {
        this.container.releasePointerCapture(e.pointerId);
      } catch (_) {}
      if (this._moved && typeof this.onMoveEnd === "function") {
        clearTimeout(this._moveTimer);
        this._moveTimer = setTimeout(() => {
          this.onMoveEnd({ lng: this.lng, lat: this.lat, zoom: this.zoom });
        }, 260);
      }
    };

    this.container.addEventListener("pointerup", finishDrag);
    this.container.addEventListener("pointercancel", finishDrag);

    this.container.addEventListener("click", (e) => {
      const zBtn = e.target.closest("[data-map-zoom]");
      if (zBtn) {
        e.stopPropagation();
        const delta = Number(zBtn.dataset.mapZoom || 0);
        const nextZoom = Math.max(11, Math.min(18, this.zoom + delta));
        if (nextZoom !== this.zoom) {
          this.zoom = nextZoom;
          this.render();
          if (typeof this.onMoveEnd === "function") {
            this.onMoveEnd({ lng: this.lng, lat: this.lat, zoom: this.zoom });
          }
        }
        return;
      }
      const mBtn = e.target.closest("[data-marker-pid]");
      if (mBtn && typeof this.onSelectMarker === "function") {
        e.stopPropagation();
        this.onSelectMarker({
          positionId: Number(mBtn.dataset.markerPid),
          name: mBtn.dataset.markerName || "",
          lng: Number(mBtn.dataset.markerLng),
          lat: Number(mBtn.dataset.markerLat),
        });
      }
    });

    this.render();
  }

  setCenter(lng, lat, zoom = null) {
    if (Number.isFinite(Number(lng)) && Number.isFinite(Number(lat))) {
      this.lng = Number(Number(lng).toFixed(6));
      this.lat = Number(Number(lat).toFixed(6));
    }
    if (zoom !== null && Number.isFinite(Number(zoom))) {
      this.zoom = Math.max(11, Math.min(18, Number(zoom)));
    }
    this.render();
  }

  setMarkers(positions = [], activePositionId = 0) {
    this.markers = Array.isArray(positions) ? positions : [];
    this.activePositionId = Number(activePositionId) || 0;
    this.renderMarkers();
  }

  render() {
    if (!this.container || !this.tilesLayer) return;
    const width = this.container.clientWidth || 340;
    const height = this.container.clientHeight || 190;
    const cWx = lngToWorldX(this.lng, this.zoom);
    const cWy = latToWorldY(this.lat, this.zoom);
    const leftWx = cWx - width / 2;
    const topWy = cWy - height / 2;
    const maxTile = Math.pow(2, this.zoom);

    const minTx = Math.floor(leftWx / 256);
    const maxTx = Math.floor((leftWx + width) / 256);
    const minTy = Math.max(0, Math.floor(topWy / 256));
    const maxTy = Math.min(maxTile - 1, Math.floor((topWy + height) / 256));

    const keep = new Set();
    for (let tx = minTx; tx <= maxTx; tx++) {
      for (let ty = minTy; ty <= maxTy; ty++) {
        const wrappedTx = ((tx % maxTile) + maxTile) % maxTile;
        const key = `${this.zoom}_${wrappedTx}_${ty}`;
        keep.add(key);
        let img = this.tilesLayer.querySelector(`[data-tile-key="${key}"]`);
        if (!img) {
          img = document.createElement("img");
          img.className = "map-tile";
          img.dataset.tileKey = key;
          img.alt = "";
          img.decoding = "async";
          img.draggable = false;
          const sub = ((wrappedTx + ty) & 3) + 1;
          img.src = `https://webrd0${sub}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=${wrappedTx}&y=${ty}&z=${this.zoom}`;
          img.onerror = () => {
            img.style.opacity = "0";
          };
          this.tilesLayer.appendChild(img);
        }
        img.style.transform = `translate3d(${Math.round(tx * 256 - leftWx)}px, ${Math.round(ty * 256 - topWy)}px, 0)`;
      }
    }

    for (const child of Array.from(this.tilesLayer.children)) {
      if (!keep.has(child.dataset.tileKey)) {
        child.remove();
      }
    }

    if (this.coordBadge) {
      this.coordBadge.textContent = `${this.lng.toFixed(4)}, ${this.lat.toFixed(4)} · 拖动选点`;
    }
    this.renderMarkers();
  }

  renderMarkers() {
    if (!this.container || !this.markersLayer) return;
    const width = this.container.clientWidth || 340;
    const height = this.container.clientHeight || 190;
    const cWx = lngToWorldX(this.lng, this.zoom);
    const cWy = latToWorldY(this.lat, this.zoom);
    const leftWx = cWx - width / 2;
    const topWy = cWy - height / 2;

    const visible = [];
    for (const p of this.markers.slice(0, 18)) {
      if (!Number.isFinite(Number(p.lng)) || !Number.isFinite(Number(p.lat))) continue;
      const x = lngToWorldX(Number(p.lng), this.zoom) - leftWx;
      const y = latToWorldY(Number(p.lat), this.zoom) - topWy;
      if (x < -20 || x > width + 20 || y < -20 || y > height + 20) continue;
      const isCur = Number(p.positionId) === this.activePositionId;
      const rawTail = String(p.name || "").replace(/^.*[-·]/, "").replace(/^.*校区/, "").trim();
      const shortName = (rawTail || String(p.name || "").trim()).slice(0, 8);
      visible.push(`
        <button
          type="button"
          class="map-marker ${isCur ? "active" : ""}"
          style="left:${Math.round(x)}px; top:${Math.round(y)}px;"
          data-marker-pid="${p.positionId}"
          data-marker-name="${escapeHtml(p.name || "")}"
          data-marker-lng="${p.lng}"
          data-marker-lat="${p.lat}"
          title="${escapeHtml(p.name || "")}"
        >
          <span class="map-marker-dot"></span>
          <span class="map-marker-label">${escapeHtml(shortName || `#${p.positionId}`)}</span>
        </button>
      `);
    }
    this.markersLayer.innerHTML = visible.join("");
  }
}
