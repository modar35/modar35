/*
 * MiniMap — карта без внешних библиотек: тайлы OpenStreetMap рисуются на canvas.
 * Нужна панели родителя, чтобы работать в любой сети, даже если CDN недоступен.
 */
'use strict';

function MiniMap(canvas, options) {
  this.canvas = canvas;
  this.ctx = canvas.getContext('2d');
  this.options = options || {};
  this.center = { lat: 52.3702, lon: 4.8952 };
  this.zoom = 12;
  this.track = [];
  this.places = [];
  this.devices = [];
  this.selectedId = '';
  this.tiles = new Map();
  this.loading = new Set();
  this.dragging = false;
  this.last = null;
  this.pinch = null;
  this.longPressTimer = null;
  this.pressStart = null;
  this.moved = 0;

  const self = this;
  canvas.addEventListener('mousedown', (e) => self.onDown(e));
  canvas.addEventListener('mousemove', (e) => self.onMove(e));
  window.addEventListener('mouseup', () => self.onUp());
  canvas.addEventListener('touchstart', (e) => self.onTouchStart(e), { passive: false });
  canvas.addEventListener('touchmove', (e) => self.onTouchMove(e), { passive: false });
  canvas.addEventListener('touchend', (e) => self.onTouchEnd(e));
  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    self.zoomBy(e.deltaY < 0 ? 0.5 : -0.5);
  }, { passive: false });
  canvas.addEventListener('dblclick', () => self.zoomBy(1));
  canvas.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    const point = self.fromScreen(e.offsetX, e.offsetY);
    if (self.options.onLongPress) self.options.onLongPress(point.lat, point.lon);
  });
  window.addEventListener('resize', () => self.resize());
  this.resize();
}

MiniMap.prototype.TILE = 256;

MiniMap.prototype.resize = function () {
  const rect = this.canvas.getBoundingClientRect();
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  this.width = Math.max(200, rect.width);
  this.height = Math.max(160, rect.height);
  this.canvas.width = Math.round(this.width * dpr);
  this.canvas.height = Math.round(this.height * dpr);
  this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  this.draw();
};

MiniMap.prototype.lonToX = function (lon, zoom) {
  return (lon + 180) / 360 * this.TILE * Math.pow(2, zoom);
};
MiniMap.prototype.latToY = function (lat, zoom) {
  const rad = Math.max(-85.0511, Math.min(85.0511, lat)) * Math.PI / 180;
  return (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * this.TILE * Math.pow(2, zoom);
};
MiniMap.prototype.xToLon = function (x, zoom) {
  return x / (this.TILE * Math.pow(2, zoom)) * 360 - 180;
};
MiniMap.prototype.yToLat = function (y, zoom) {
  const n = Math.PI - 2 * Math.PI * y / (this.TILE * Math.pow(2, zoom));
  return Math.atan(Math.sinh(n)) * 180 / Math.PI;
};

MiniMap.prototype.toScreen = function (lat, lon) {
  const tileZoom = Math.floor(this.zoom);
  const scale = Math.pow(2, this.zoom - tileZoom);
  const ox = this.width / 2 - this.lonToX(this.center.lon, tileZoom) * scale;
  const oy = this.height / 2 - this.latToY(this.center.lat, tileZoom) * scale;
  return {
    x: this.lonToX(lon, tileZoom) * scale + ox,
    y: this.latToY(lat, tileZoom) * scale + oy,
  };
};

MiniMap.prototype.fromScreen = function (x, y) {
  const tileZoom = Math.floor(this.zoom);
  const scale = Math.pow(2, this.zoom - tileZoom);
  const ox = this.width / 2 - this.lonToX(this.center.lon, tileZoom) * scale;
  const oy = this.height / 2 - this.latToY(this.center.lat, tileZoom) * scale;
  return {
    lat: this.yToLat((y - oy) / scale, tileZoom),
    lon: this.xToLon((x - ox) / scale, tileZoom),
  };
};

/** Метров на пиксель — нужно для радиусов геозон. */
MiniMap.prototype.metersPerPixel = function () {
  return 156543.03392 * Math.cos(this.center.lat * Math.PI / 180) / Math.pow(2, this.zoom);
};

MiniMap.prototype.setCenter = function (lat, lon, zoom) {
  this.center = { lat, lon };
  if (zoom) this.zoom = Math.max(3, Math.min(18, zoom));
  this.draw();
};

MiniMap.prototype.getCenter = function () {
  return this.center;
};

MiniMap.prototype.getZoom = function () {
  return this.zoom;
};

MiniMap.prototype.panTo = function (lat, lon) {
  this.center = { lat, lon };
  this.draw();
};

MiniMap.prototype.zoomBy = function (delta) {
  this.zoom = Math.max(3, Math.min(18, this.zoom + delta));
  this.draw();
};

MiniMap.prototype.setTrack = function (points) {
  this.track = points || [];
  this.draw();
};

MiniMap.prototype.setPlaces = function (places) {
  this.places = places || [];
  this.draw();
};

MiniMap.prototype.setDevices = function (devices, selectedId) {
  this.devices = devices || [];
  this.selectedId = selectedId || '';
  this.draw();
};

MiniMap.prototype.fitAll = function () {
  const points = [];
  for (const point of this.track) points.push([point.lat, point.lon]);
  for (const device of this.devices) {
    if (device.lat != null) points.push([device.lat, device.lon]);
  }
  if (!points.length) return;
  let minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
  for (const [lat, lon] of points) {
    minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
    minLon = Math.min(minLon, lon); maxLon = Math.max(maxLon, lon);
  }
  this.center = { lat: (minLat + maxLat) / 2, lon: (minLon + maxLon) / 2 };
  const spanLat = Math.max(0.003, maxLat - minLat);
  const spanLon = Math.max(0.003, maxLon - minLon);
  const zoomLat = Math.log2(360 / (spanLat * 1.6) * (this.height / this.TILE));
  const zoomLon = Math.log2(360 / (spanLon * 1.6) * (this.width / this.TILE) / 2);
  this.zoom = Math.max(3, Math.min(15, Math.min(zoomLat, zoomLon) + 1));
  this.draw();
};

/* ------------------------------------------------------------------ отрисовка */

MiniMap.prototype.draw = function () {
  const ctx = this.ctx;
  if (!ctx) return;
  ctx.clearRect(0, 0, this.width, this.height);
  ctx.fillStyle = '#0a0f1e';
  ctx.fillRect(0, 0, this.width, this.height);

  const tileZoom = Math.floor(this.zoom);
  const scale = Math.pow(2, this.zoom - tileZoom);
  const tileSize = this.TILE * scale;
  const ox = this.width / 2 - this.lonToX(this.center.lon, tileZoom) * scale;
  const oy = this.height / 2 - this.latToY(this.center.lat, tileZoom) * scale;
  const maxIndex = Math.pow(2, tileZoom);

  const firstCol = Math.floor(-ox / tileSize);
  const lastCol = Math.ceil((this.width - ox) / tileSize);
  const firstRow = Math.floor(-oy / tileSize);
  const lastRow = Math.ceil((this.height - oy) / tileSize);

  for (let col = firstCol; col <= lastCol; col++) {
    for (let row = firstRow; row <= lastRow; row++) {
      if (row < 0 || row >= maxIndex) continue;
      const wrapped = ((col % maxIndex) + maxIndex) % maxIndex;
      const x = ox + col * tileSize;
      const y = oy + row * tileSize;
      const tile = this.tile(tileZoom, wrapped, row);
      if (tile) {
        ctx.drawImage(tile, x, y, tileSize, tileSize);
      } else {
        ctx.fillStyle = '#0e1428';
        ctx.fillRect(x, y, tileSize, tileSize);
      }
    }
  }

  // Геозоны
  const mpp = this.metersPerPixel();
  for (const place of this.places) {
    const point = this.toScreen(place.lat, place.lon);
    const radius = Math.max(8, place.radius / mpp);
    ctx.beginPath();
    ctx.arc(point.x, point.y, radius, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(108, 140, 255, 0.13)';
    ctx.fill();
    ctx.strokeStyle = 'rgba(108, 140, 255, 0.75)';
    ctx.lineWidth = 1.5;
    ctx.stroke();
    ctx.fillStyle = '#cdd8ff';
    ctx.font = '12px -apple-system, Segoe UI, Roboto, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(place.name, point.x, point.y + 4);
  }

  // Маршрут
  if (this.track.length > 1) {
    ctx.beginPath();
    this.track.forEach((item, index) => {
      const point = this.toScreen(item.lat, item.lon);
      if (index === 0) ctx.moveTo(point.x, point.y);
      else ctx.lineTo(point.x, point.y);
    });
    ctx.strokeStyle = 'rgba(0,0,0,0.35)';
    ctx.lineWidth = 8;
    ctx.lineJoin = 'round';
    ctx.stroke();
    ctx.strokeStyle = '#6c8cff';
    ctx.lineWidth = 4;
    ctx.stroke();

    const start = this.toScreen(this.track[0].lat, this.track[0].lon);
    const end = this.toScreen(this.track[this.track.length - 1].lat, this.track[this.track.length - 1].lon);
    ctx.fillStyle = '#37d6b0';
    ctx.beginPath();
    ctx.arc(start.x, start.y, 5, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#ff5d6c';
    ctx.beginPath();
    ctx.arc(end.x, end.y, 5, 0, Math.PI * 2);
    ctx.fill();
  }

  // Дети
  for (const device of this.devices) {
    if (device.lat == null) continue;
    const point = this.toScreen(device.lat, device.lon);
    const selected = device.id === this.selectedId;
    const radius = selected ? 13 : 10;
    ctx.beginPath();
    ctx.arc(point.x, point.y, radius + 4, 0, Math.PI * 2);
    ctx.fillStyle = device.online ? (selected ? '#6c8cff' : '#4a63b8') : '#8c93a8';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(point.x, point.y, radius, 0, Math.PI * 2);
    ctx.fillStyle = '#ffffff';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(point.x, point.y, radius - 4, 0, Math.PI * 2);
    ctx.fillStyle = device.online ? '#1b2559' : '#8c93a8';
    ctx.fill();
    ctx.fillStyle = '#e9eefb';
    ctx.font = '12px -apple-system, Segoe UI, Roboto, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(device.name, point.x, point.y - radius - 8);
  }

  ctx.fillStyle = 'rgba(154,166,196,0.85)';
  ctx.font = '10px -apple-system, Segoe UI, Roboto, sans-serif';
  ctx.textAlign = 'left';
  ctx.fillText('© OpenStreetMap', 6, this.height - 6);
  this.hitTest = this.devices.map((device) => ({
    device,
    point: device.lat == null ? null : this.toScreen(device.lat, device.lon),
  }));
};

MiniMap.prototype.tile = function (zoom, col, row) {
  const key = zoom + '/' + col + '/' + row;
  const cached = this.tiles.get(key);
  if (cached) return cached;
  if (!this.loading.has(key)) {
    this.loading.add(key);
    const self = this;
    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.onload = () => {
      self.loading.delete(key);
      self.tiles.set(key, image);
      if (self.tiles.size > 240) {
        self.tiles.delete(self.tiles.keys().next().value);
      }
      self.draw();
    };
    image.onerror = () => { self.loading.delete(key); };
    image.src = 'https://tile.openstreetmap.org/' + key + '.png';
  }
  return null;
};

/* --------------------------------------------------------------------- жесты */

MiniMap.prototype.onDown = function (event) {
  this.dragging = true;
  this.moved = 0;
  this.last = { x: event.offsetX, y: event.offsetY };
  this.pressStart = { x: event.offsetX, y: event.offsetY };
  const self = this;
  clearTimeout(this.longPressTimer);
  this.longPressTimer = setTimeout(() => {
    if (self.moved < 6 && self.options.onLongPress) {
      const point = self.fromScreen(self.pressStart.x, self.pressStart.y);
      self.options.onLongPress(point.lat, point.lon);
    }
  }, 650);
};

MiniMap.prototype.onMove = function (event) {
  if (!this.dragging) return;
  const point = { x: event.offsetX, y: event.offsetY };
  const dx = point.x - this.last.x;
  const dy = point.y - this.last.y;
  this.moved += Math.abs(dx) + Math.abs(dy);
  this.pan(dx, dy);
  this.last = point;
};

MiniMap.prototype.onUp = function () {
  if (this.dragging && this.moved < 6) {
    this.click(this.pressStart.x, this.pressStart.y);
  }
  this.dragging = false;
  this.last = null;
  clearTimeout(this.longPressTimer);
};

MiniMap.prototype.onTouchStart = function (event) {
  event.preventDefault();
  if (event.touches.length === 1) {
    const rect = this.canvas.getBoundingClientRect();
    this.dragging = true;
    this.moved = 0;
    this.pressStart = { x: event.touches[0].clientX - rect.left, y: event.touches[0].clientY - rect.top };
    this.last = { x: this.pressStart.x, y: this.pressStart.y };
    const self = this;
    clearTimeout(this.longPressTimer);
    this.longPressTimer = setTimeout(() => {
      if (self.moved < 8 && self.options.onLongPress) {
        const point = self.fromScreen(self.pressStart.x, self.pressStart.y);
        self.options.onLongPress(point.lat, point.lon);
      }
    }, 650);
  } else if (event.touches.length === 2) {
    this.pinch = {
      distance: Math.hypot(
        event.touches[0].clientX - event.touches[1].clientX,
        event.touches[0].clientY - event.touches[1].clientY
      ),
      zoom: this.zoom,
    };
  }
};

MiniMap.prototype.onTouchMove = function (event) {
  event.preventDefault();
  const rect = this.canvas.getBoundingClientRect();
  if (event.touches.length === 2 && this.pinch) {
    const distance = Math.hypot(
      event.touches[0].clientX - event.touches[1].clientX,
      event.touches[0].clientY - event.touches[1].clientY
    );
    if (this.pinch.distance > 0) {
      this.zoom = Math.max(3, Math.min(18, this.pinch.zoom + Math.log2(distance / this.pinch.distance)));
      this.draw();
    }
    return;
  }
  const point = { x: event.touches[0].clientX - rect.left, y: event.touches[0].clientY - rect.top };
  if (!this.last) {
    this.last = point;
    return;
  }
  const dx = point.x - this.last.x;
  const dy = point.y - this.last.y;
  this.moved += Math.abs(dx) + Math.abs(dy);
  this.pan(dx, dy);
  this.last = point;
};

MiniMap.prototype.onTouchEnd = function () {
  if (this.dragging && this.moved < 8) this.click(this.pressStart.x, this.pressStart.y);
  this.dragging = false;
  this.last = null;
  this.pinch = null;
  clearTimeout(this.longPressTimer);
};

MiniMap.prototype.pan = function (dx, dy) {
  const tileZoom = Math.floor(this.zoom);
  const scale = Math.pow(2, this.zoom - tileZoom);
  const x = this.lonToX(this.center.lon, tileZoom) - dx / scale;
  const y = this.latToY(this.center.lat, tileZoom) - dy / scale;
  this.center = { lat: this.yToLat(y, tileZoom), lon: this.xToLon(x, tileZoom) };
  this.draw();
};

MiniMap.prototype.click = function (x, y) {
  for (const item of this.hitTest || []) {
    if (item.point && Math.hypot(item.point.x - x, item.point.y - y) < 26) {
      if (this.options.onMarkerClick) this.options.onMarkerClick(item.device.id);
      return;
    }
  }
};
