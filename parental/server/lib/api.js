'use strict';
/*
 * REST API Modar Family (версия 1).
 *
 * Две «стороны»:
 *   • телефон ребёнка  — авторизуется токеном устройства, отдаёт отчёты и забирает команды;
 *   • телефон родителя — авторизуется токеном родителя, читает состояние и отправляет команды.
 *
 * Полный список эндпоинтов — в docs/API.md.
 */

const U = require('./util');
const { pushToFamily } = require('./push');

const MAX_BODY = 512 * 1024;
const FREE_DEVICES = 6; // сколько устройств можно привязать к одной семье

/* --------------------------------------------------- ограничение попыток */

const attempts = new Map();

/* Живые данные, которые не нужно хранить на диске: последние картинки и вызовы. */
const images = new Map();   // "deviceId:kind" -> { ts, data(dataURL), kind, deviceId, familyId }
const calls = new Map();    // callId -> состояние WebRTC-вызова

function pruneCalls() {
  const cutoff = Date.now() - 15 * 60 * 1000;
  for (const [id, call] of calls) {
    if (call.updatedAt < cutoff) calls.delete(id);
  }
}

function rateLimit(key, limit, windowMs) {
  const nowTs = Date.now();
  const row = attempts.get(key);
  if (!row || nowTs - row.start > windowMs) {
    attempts.set(key, { start: nowTs, count: 1 });
    return true;
  }
  row.count += 1;
  return row.count <= limit;
}

/* ------------------------------------------------------------- по умолчанию */

function defaultPolicy() {
  return {
    blockApps: [],                 // полностью заблокированные приложения
    allowOnly: null,               // если задан список — разрешены только эти приложения
    appLimits: {},                 // { "com.vk.android": 30 } — минут в день на приложение
    dailyLimitMin: 0,              // общий лимит экранного времени, минут в день (0 = выключен)
    blockNewApps: false,           // блокировать всё, что установлено после подключения
    schoolMode: { enabled: false, from: '08:00', to: '14:00', days: [1, 2, 3, 4, 5] },
    bedtime: { enabled: false, from: '22:00', to: '07:00' },
    internetOff: { enabled: false, from: '23:00', to: '06:00' },
    webFilter: { adult: true, gambling: true, custom: [] },
    alerts: {
      sos: true,
      lowBattery: 15,
      geofence: true,
      appInstall: true,
      offlineMinutes: 30,
    },
    reportSeconds: 300,            // как часто устройство присылает отчёт
    voiceEnabled: true,            // разрешён ли разговор с ребёнком
    kiosk: false,                  // устройство в режиме «только для учёбы»
  };
}

function defaultSettings() {
  return {
    blockApps: [],
    allowOnly: null,
    appLimits: {},
    dailyLimitMin: 0,
    blockNewApps: false,
    schoolMode: { enabled: false, from: '08:00', to: '14:00', days: [1, 2, 3, 4, 5] },
    bedtime: { enabled: false, from: '22:00', to: '07:00' },
    internetOff: { enabled: false, from: '23:00', to: '06:00' },
    webFilter: { adult: true, gambling: true, custom: [] },
    alerts: { sos: true, lowBattery: 15, geofence: true, appInstall: true, offlineMinutes: 30 },
    reportSeconds: 300,
    voiceEnabled: true,
    kiosk: false,
  };
}

function sanitizeList(value, max) {
  if (!Array.isArray(value)) return [];
  const out = [];
  for (const item of value) {
    const text = U.trimText(item, 200).trim();
    if (text && out.indexOf(text) < 0) out.push(text);
    if (out.length >= (max || 300)) break;
  }
  return out;
}

function sanitizeWindow(value, fallback) {
  const src = value && typeof value === 'object' ? value : {};
  const from = U.minutesOfDay(src.from) >= 0 ? src.from : fallback.from;
  const to = U.minutesOfDay(src.to) >= 0 ? src.to : fallback.to;
  const days = Array.isArray(src.days)
    ? src.days.map((d) => Number(d)).filter((d) => d >= 0 && d <= 6)
    : fallback.days;
  return { enabled: !!src.enabled, from, to, days };
}

/** Приводит присланные настройки к известной схеме (никаких лишних полей). */
function sanitizeSettings(input, base) {
  const out = Object.assign({}, base);
  const src = input && typeof input === 'object' ? input : {};
  if ('blockApps' in src) out.blockApps = sanitizeList(src.blockApps, 300);
  if ('allowOnly' in src) {
    out.allowOnly = src.allowOnly === null ? null : sanitizeList(src.allowOnly, 300);
  }
  if ('appLimits' in src && src.appLimits && typeof src.appLimits === 'object') {
    const limits = {};
    for (const key of Object.keys(src.appLimits).slice(0, 300)) {
      limits[U.trimText(key, 200)] = U.clampNumber(src.appLimits[key], 0, 1440, 30);
    }
    out.appLimits = limits;
  }
  if ('dailyLimitMin' in src) out.dailyLimitMin = U.clampNumber(src.dailyLimitMin, 0, 1440, 0);
  if ('blockNewApps' in src) out.blockNewApps = !!src.blockNewApps;
  if ('kiosk' in src) out.kiosk = !!src.kiosk;
  if ('voiceEnabled' in src) out.voiceEnabled = !!src.voiceEnabled;
  if ('reportSeconds' in src) out.reportSeconds = U.clampNumber(src.reportSeconds, 30, 3600, 300);
  if ('schoolMode' in src) out.schoolMode = sanitizeWindow(src.schoolMode, base.schoolMode);
  if ('bedtime' in src) out.bedtime = sanitizeWindow(src.bedtime, base.bedtime);
  if ('internetOff' in src) out.internetOff = sanitizeWindow(src.internetOff, base.internetOff);
  if ('webFilter' in src && src.webFilter && typeof src.webFilter === 'object') {
    out.webFilter = {
      adult: src.webFilter.adult !== false,
      gambling: src.webFilter.gambling !== false,
      custom: sanitizeList(src.webFilter.custom, 200),
    };
  }
  if ('alerts' in src && src.alerts && typeof src.alerts === 'object') {
    const a = src.alerts;
    out.alerts = {
      sos: a.sos !== false,
      lowBattery: U.clampNumber(a.lowBattery, 0, 100, 15),
      geofence: a.geofence !== false,
      appInstall: a.appInstall !== false,
      offlineMinutes: U.clampNumber(a.offlineMinutes, 5, 1440, 30),
    };
  }
  return out;
}

/* ------------------------------------------------------------- вычисления */

const EARTH_R = 6371000;

function distanceMeters(lat1, lon1, lat2, lon2) {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return 2 * EARTH_R * Math.asin(Math.min(1, Math.sqrt(a)));
}

function insideWindow(window, ts) {
  if (!window || !window.enabled) return false;
  const day = new Date(ts || Date.now()).getDay();
  const days = Array.isArray(window.days) && window.days.length ? window.days : [0, 1, 2, 3, 4, 5, 6];
  if (days.indexOf(day) < 0) return false;
  const from = U.minutesOfDay(window.from);
  const to = U.minutesOfDay(window.to);
  if (from < 0 || to < 0 || from === to) return false;
  const nowMin = new Date(ts || Date.now()).getHours() * 60 + new Date(ts || Date.now()).getMinutes();
  return from < to ? nowMin >= from && nowMin < to : nowMin >= from || nowMin < to;
}

/** Серверный «снимок» того, что сейчас запрещено — для панели и для ребёнка. */
function computeState(device, ts) {
  const p = device.policy || defaultPolicy();
  const now = ts || Date.now();
  const schoolNow = insideWindow(p.schoolMode, now);
  const bedtimeNow = insideWindow(p.bedtime, now);
  const internetOffNow = insideWindow(p.internetOff, now);
  const usage = device.usage && device.usage.day === U.dayKey(now) ? device.usage : null;
  const usedMs = usage ? Number(usage.totalMs) || 0 : 0;
  const overDaily = p.dailyLimitMin > 0 && usedMs >= p.dailyLimitMin * 60000;
  return {
    schoolNow,
    bedtimeNow,
    internetOffNow,
    overDaily,
    limitLeftMin: p.dailyLimitMin > 0 ? Math.max(0, p.dailyLimitMin - Math.round(usedMs / 60000)) : null,
    locked: bedtimeNow || schoolNow || overDaily || !!p.kiosk,
  };
}

/* ------------------------------------------------------------------ контекст */

function devicePublic(ctx, device) {
  const state = computeState(device, ctx.now());
  const family = ctx.store.family(device.familyId);
  return {
    id: device.id,
    name: device.name,
    model: device.model || '',
    androidId: device.androidId || '',
    version: device.version || '',
    online: !!device.online && ctx.now() - (device.lastSeen || 0) < 5 * 60 * 1000,
    lastSeen: device.lastSeen || 0,
    battery: device.battery == null ? null : device.battery,
    charging: !!device.charging,
    lat: device.lat,
    lon: device.lon,
    acc: device.acc,
    speed: device.speed,
    locatedAt: device.locatedAt || 0,
    screenOn: !!device.screenOn,
    steps: device.steps == null ? null : device.steps,
    zone: device.zone || null,
    inside: device.inside || [],
    usage: device.usage || null,
    apps: device.apps || [],
    notifications: device.notifications || [],
    webBlocked: device.webBlocked || [],
    accessibility: !!device.accessibility,
    admin: !!device.admin,
    vpn: !!device.vpn,
    notificationsPermission: !!device.notificationsPermission,
    locationPermission: device.locationPermission !== false,
    state,
    settings: device.policy || defaultPolicy(),
    familyName: family ? family.name : '',
  };
}

/* --------------------------------------------------------------- маршруты */

function createApi(ctx) {
  const { store } = ctx;

  function parentFrom(req) {
    const secret = U.bearer(req);
    if (!secret) return null;
    return store.parentByToken(secret) || null;
  }

  function deviceFrom(req) {
    const secret = U.bearer(req);
    if (!secret) return null;
    const device = store.deviceByToken(secret);
    if (device) {
      device.lastSeen = Date.now();
      device.online = true;
    }
    return device || null;
  }

  async function handle(req, res, url) {
    const method = req.method.toUpperCase();
    const path = url.pathname.replace(/\/+$/, '') || '/';
    const ip = U.clientIp(req);

    /* ------------------------------------------------------------ регистрация */

    if (path === '/api/v1/auth/register' && method === 'POST') {
      if (!rateLimit('reg:' + ip, 10, 3600 * 1000)) return U.fail(res, 429, 'Слишком много попыток');
      const body = await U.readJson(req);
      const email = String(body.email || '').trim().toLowerCase();
      const password = String(body.password || '');
      const name = U.trimText(body.name, 80).trim();
      const familyName = U.trimText(body.familyName, 80).trim() || 'Семья';
      if (!/^[^@\s]+@[^@\s]+\.[a-zа-я]{2,}$/i.test(email)) {
        return U.fail(res, 400, 'Укажите корректный e-mail');
      }
      if (password.length < 6) return U.fail(res, 400, 'Пароль должен быть не короче 6 символов');
      if (store.parentByEmail(email)) return U.fail(res, 409, 'Такой e-mail уже зарегистрирован');
      const family = store.createFamily(familyName);
      const saltValue = U.salt();
      const parent = store.createParent(family.id, email, name, saltValue, U.hashPassword(password, saltValue));
      parent.token = U.token();
      parent.deviceToken = U.token();
      store.saveParent(parent);
      store.logLogin(email, ip);
      store.addEvent(family.id, null, 'family', 'Семья создана', 'info');
      return U.ok(res, {
        token: parent.token,
        parent: { id: parent.id, email: parent.email, name: parent.name },
        family: { id: family.id, name: family.name },
      });
    }

    if (path === '/api/v1/auth/login' && method === 'POST') {
      if (!rateLimit('login:' + ip, 20, 15 * 60 * 1000)) return U.fail(res, 429, 'Слишком много попыток');
      const body = await U.readJson(req);
      const email = String(body.email || '').trim().toLowerCase();
      const password = String(body.password || '');
      const parent = store.parentByEmail(email);
      if (!parent || !U.sameSecret(U.hashPassword(password, parent.salt), parent.hash)) {
        return U.fail(res, 401, 'Неверный e-mail или пароль');
      }
      parent.token = U.token();
      if (!parent.deviceToken) parent.deviceToken = U.token();
      store.saveParent(parent);
      store.logLogin(email, ip);
      return U.ok(res, {
        token: parent.token,
        parent: { id: parent.id, email: parent.email, name: parent.name },
        family: store.family(parent.familyId),
      });
    }

    if (path === '/api/v1/auth/password' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const body = await U.readJson(req);
      const current = String(body.current || '');
      if (!U.sameSecret(U.hashPassword(current, parent.salt), parent.hash)) {
        return U.fail(res, 403, 'Текущий пароль неверен');
      }
      const next = String(body.next || '');
      if (next.length < 6) return U.fail(res, 400, 'Новый пароль короче 6 символов');
      parent.salt = U.salt();
      parent.hash = U.hashPassword(next, parent.salt);
      parent.deviceToken = U.token(); // отзываем вход на всех прежних устройствах
      store.saveParent(parent);
      return U.ok(res, { token: parent.token });
    }

    if (path === '/api/v1/me' && method === 'GET') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      return U.ok(res, {
        parent: { id: parent.id, email: parent.email, name: parent.name },
        family: store.family(parent.familyId),
      });
    }

    /* ------------------------------------------------- сопряжение устройства */

    if (path === '/api/v1/device/register' && method === 'POST') {
      if (!rateLimit('pair:' + ip, 30, 10 * 60 * 1000)) {
        return U.fail(res, 429, 'Слишком много попыток сопряжения');
      }
      const body = await U.readJson(req);
      const code = String(body.code || body.pairCode || '').trim();
      const family = store.data.families.find((f) => store.pairCodeValid(f.id, code));
      if (!family) return U.fail(res, 403, 'Код не подходит или устарел — попросите новый у родителя');
      if (store.devicesOf(family.id).length >= FREE_DEVICES) {
        return U.fail(res, 409, 'К семье уже привязано максимум устройств: ' + FREE_DEVICES);
      }
      const device = {
        id: 'dev_' + require('node:crypto').randomBytes(4).toString('hex'),
        familyId: family.id,
        token: U.token(),
        name: U.trimText(body.name, 60).trim() || U.trimText(body.model, 60).trim() || 'Телефон ребёнка',
        model: U.trimText(body.model, 80),
        androidId: U.trimText(body.androidId, 80),
        version: U.trimText(body.version, 40),
        createdAt: Date.now(),
        lastSeen: Date.now(),
        online: true,
        battery: null,
        charging: false,
        lat: null,
        lon: null,
        acc: null,
        speed: null,
        locatedAt: 0,
        screenOn: true,
        steps: null,
        zone: null,
        inside: [],
        usage: null,
        apps: [],
        notifications: [],
        webBlocked: [],
        accessibility: false,
        admin: false,
        locationPermission: true,
        placeState: {},
        policy: defaultPolicy(),
        lastReport: 0,
        lastBatteryAlert: 0,
      };
      store.addDevice(device);
      store.addEvent(family.id, device.id, 'pair', 'Устройство «' + device.name + '» подключено', 'info');
      pushToFamily(store, family.id, {
        title: 'Устройство подключено',
        body: device.name + ' присоединился к семье',
      });
      return U.ok(res, {
        deviceToken: device.token,
        deviceId: device.id,
        familyName: family.name,
        reportSeconds: device.policy.reportSeconds,
      });
    }

    /* ------------------------------------------------ отчёты телефона ребёнка */

    if (path === '/api/v1/device/report' && method === 'POST') {
      const device = deviceFrom(req);
      if (!device) return U.fail(res, 401, 'Устройство не авторизовано');
      const body = await U.readJson(req, MAX_BODY);
      const familyId = device.familyId;
      const ts = ctx.now();

      if (body.battery != null) device.battery = U.clampNumber(body.battery, 0, 100, device.battery);
      if (body.charging != null) device.charging = !!body.charging;
      if (body.screenOn != null) device.screenOn = !!body.screenOn;
      if (body.steps != null) device.steps = U.clampNumber(body.steps, 0, 1000000, null);
      if (body.zone) device.zone = U.trimText(body.zone, 80);
      if (body.accessibility != null) device.accessibility = !!body.accessibility;
      if (body.admin != null) device.admin = !!body.admin;
      if (body.locationPermission != null) device.locationPermission = !!body.locationPermission;
      if (body.vpn != null) device.vpn = !!body.vpn;
      if (body.notificationsPermission != null) device.notificationsPermission = !!body.notificationsPermission;
      if (body.version) device.version = U.trimText(body.version, 40);
      if (body.model) device.model = U.trimText(body.model, 80);

      if (Array.isArray(body.apps)) {
        device.apps = body.apps.slice(0, 400).map((a) => ({
          pkg: U.trimText(a && a.pkg, 200),
          label: U.trimText(a && a.label, 120),
          system: !!(a && a.system),
        })).filter((a) => a.pkg);
      }

      if (Array.isArray(body.usage)) {
        const day = U.trimText(body.day, 10) || U.dayKey(ts);
        const list = body.usage.slice(0, 300).map((u) => ({
          pkg: U.trimText(u && u.pkg, 200),
          label: U.trimText(u && u.label, 120),
          ms: U.clampNumber(u && u.ms, 0, 24 * 3600 * 1000, 0),
        })).filter((u) => u.pkg);
        const totalMs = list.reduce((sum, u) => sum + u.ms, 0);
        device.usage = { day, list, totalMs };
        store.addUsage(familyId, device.id, day, list);
      }

      if (Array.isArray(body.notifications)) {
        for (const note of body.notifications.slice(0, 40)) {
          const text = U.trimText(note && note.text, 400);
          const title = U.trimText(note && note.title, 200);
          const app = U.trimText(note && note.app, 120);
          if (!text && !title) continue;
          device.notifications.unshift({
            app, title, text, pkg: U.trimText(note && note.pkg, 200), ts: ctx.now(),
          });
          store.addEvent(familyId, device.id, 'notification', (app ? app + ': ' : '') + (title || text), 'info');
        }
        device.notifications = device.notifications.slice(0, 60);
      }

      if (Array.isArray(body.webBlocked)) {
        for (const item of body.webBlocked.slice(0, 40)) {
          const host = U.trimText(item && item.host, 200);
          if (!host) continue;
          device.webBlocked.unshift({ host, reason: U.trimText(item && item.reason, 60), ts: ctx.now() });
          store.addEvent(familyId, device.id, 'web', 'Заблокирован сайт: ' + host, 'warn');
        }
        device.webBlocked = device.webBlocked.slice(0, 50);
      }

      if (Array.isArray(body.events)) {
        for (const ev of body.events.slice(0, 60)) {
          const type = U.trimText(ev && ev.type, 40) || 'info';
          const message = U.trimText(ev && ev.message, 400);
          const level = ev && ev.level === 'alert' ? 'alert' : ev && ev.level === 'warn' ? 'warn' : 'info';
          store.addEvent(familyId, device.id, type, message, level);
          if (level === 'alert') {
            pushToFamily(store, familyId, { title: 'Modar Family', body: message || type });
          }
        }
      }

      const hasLocation = body.lat != null && body.lon != null;
      if (hasLocation) {
        const lat = U.clampNumber(body.lat, -90, 90, null);
        const lon = U.clampNumber(body.lon, -180, 180, null);
        if (lat != null && lon != null) {
          device.lat = lat;
          device.lon = lon;
          device.acc = U.clampNumber(body.acc, 0, 100000, null);
          device.speed = U.clampNumber(body.speed, 0, 300, null);
          device.locatedAt = ctx.now();
          store.addSpot(familyId, device.id, lat, lon, device.acc, ts);
          checkPlaces(store, device, lat, lon);
        }
      }

      const alerts = device.policy.alerts || {};
      if (alerts.lowBattery > 0 && device.battery != null && device.battery <= alerts.lowBattery) {
        if (ctx.now() - (device.lastBatteryAlert || 0) > 30 * 60 * 1000) {
          device.lastBatteryAlert = ctx.now();
          const message = 'Батарея ' + device.battery + '% на устройстве «' + device.name + '»';
          store.addEvent(familyId, device.id, 'battery', message, 'warn');
          pushToFamily(store, familyId, { title: 'Низкий заряд', body: message });
        }
      }

      device.lastReport = ctx.now();
      device.lastSeen = ctx.now();
      device.online = true;
      store.saveDevice(device);
      store.ackCmds(device.id, body.ackIds || []);

      const cmds = store.pendingCmds(device.id).map((c) => ({ id: c.id, cmd: c.cmd, arg: c.arg }));
      return U.ok(res, {
        serverTime: ctx.now(),
        commands: cmds,
        settings: device.policy,
        settingsVersion: device.settingsVersion || 1,
      });
    }

    /* --------------------------------------------- команды от родителя */

    if (path === '/api/v1/command' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const body = await U.readJson(req);
      const allowed = [
        'refresh', 'lock', 'unlock', 'alarm', 'message', 'locate', 'snapshot',
        'sync', 'ring', 'call', 'photo', 'tour', 'sos_clear', 'apply_settings',
      ];
      const cmd = String(body.cmd || '').trim();
      if (allowed.indexOf(cmd) < 0) return U.fail(res, 400, 'Неизвестная команда: ' + cmd);
      const arg = body.arg === undefined ? null : body.arg;
      const deviceIds = Array.isArray(body.deviceId) ? body.deviceId : [body.deviceId];
      const done = [];
      for (const id of deviceIds) {
        const device = store.deviceById(String(id));
        if (!device || device.familyId !== parent.familyId) continue;
        store.addCmd(parent.familyId, device.id, cmd, typeof arg === 'string' ? arg.slice(0, 500) : arg);
        store.addEvent(parent.familyId, device.id, 'cmd', 'Родитель: ' + describeCmd(cmd, arg), 'info');
        done.push(device.id);
      }
      if (!done.length) return U.fail(res, 404, 'Устройство не найдено');
      return U.ok(res, { devices: done });
    }

    if (path === '/api/v1/settings' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const body = await U.readJson(req);
      const device = store.deviceById(String(body.deviceId || ''));
      if (!device || device.familyId !== parent.familyId) return U.fail(res, 404, 'Устройство не найдено');
      device.policy = sanitizeSettings(body.settings, device.policy || defaultPolicy());
      device.settingsVersion = (device.settingsVersion || 1) + 1;
      store.saveDevice(device);
      store.addCmd(parent.familyId, device.id, 'apply_settings', null);
      const message = 'Настройки обновлены';
      store.addEvent(parent.familyId, device.id, 'settings', message, 'info');
      return U.ok(res, { settings: device.policy, settingsVersion: device.settingsVersion });
    }

    if (path === '/api/v1/device/name' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const body = await U.readJson(req);
      const device = store.deviceById(String(body.deviceId || ''));
      if (!device || device.familyId !== parent.familyId) return U.fail(res, 404, 'Устройство не найдено');
      device.name = U.trimText(body.name, 60).trim() || device.name;
      store.saveDevice(device);
      return U.ok(res, { device: devicePublic(ctx, device) });
    }

    if (path === '/api/v1/device/remove' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const body = await U.readJson(req);
      const device = store.deviceById(String(body.deviceId || ''));
      if (!device || device.familyId !== parent.familyId) return U.fail(res, 404, 'Устройство не найдено');
      store.addEvent(parent.familyId, device.id, 'unpair', 'Устройство «' + device.name + '» отключено', 'warn');
      store.removeDevice(device.id);
      return U.ok(res, {});
    }

    if (path === '/api/v1/paircode' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const code = store.newPairCode(parent.familyId);
      return U.ok(res, { code, expiresIn: 24 * 60 * 60 * 1000 });
    }

    /* --------------------------------------------------- состояние для панели */

    if (path === '/api/v1/status' && method === 'GET') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const family = store.family(parent.familyId);
      const devices = store.devicesOf(parent.familyId).map((d) => devicePublic(ctx, d));
      const events = store.eventsOf(parent.familyId, url.searchParams.get('deviceId'), 60);
      const useEvents = parent.familyId;
      const openOffline = devices.filter((d) => !d.online).map((d) => ({
        id: d.id, name: d.name, lastSeen: d.lastSeen,
      }));
      return U.ok(res, {
        parent: { id: parent.id, email: parent.email, name: parent.name },
        family: {
          id: family.id, name: family.name,
          pairCode: family.pairCode, pairCodeExpires: family.pairCodeExpires,
        },
        serverTime: ctx.now(),
        devices,
        events,
        offline: openOffline,
        places: placesOf(store, useEvents),
      });
    }

    if (path === '/api/v1/history' && method === 'GET') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const deviceId = url.searchParams.get('deviceId');
      const device = store.deviceById(String(deviceId || ''));
      if (!device || device.familyId !== parent.familyId) return U.fail(res, 404, 'Устройство не найдено');
      const hours = U.clampNumber(url.searchParams.get('hours'), 1, 24 * 14, 24);
      const from = url.searchParams.get('from');
      const since = from ? Number(from) : ctx.now() - hours * 3600 * 1000;
      const spots = store.spotsOf(parent.familyId, device.id, since, 3000);
      const events = store.eventsOf(parent.familyId, device.id, 200);
      const day = url.searchParams.get('day') || U.dayKey(ctx.now());
      return U.ok(res, {
        deviceId: device.id,
        since,
        track: spots,
        usage: store.usageOf(parent.familyId, device.id, day),
        day,
        events,
      });
    }

    if (path === '/api/v1/dashboard' && method === 'GET') {
      // сводка по всей семье: используется веб-панелью и приложением родителя
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const devices = store.devicesOf(parent.familyId);
      const day = U.dayKey(ctx.now());
      const summary = devices.map((d) => {
        const pub = devicePublic(ctx, d);
        const limitsUsed = [];
        for (const pkg of Object.keys((d.policy || {}).appLimits || {})) {
          const row = (d.usage && d.usage.day === day ? d.usage.list : []).find((u) => u.pkg === pkg);
          limitsUsed.push({ pkg, minutes: Math.round(((row && row.ms) || 0) / 60000), limit: d.policy.appLimits[pkg] });
        }
        return {
          id: pub.id,
          name: pub.name,
          online: pub.online,
          battery: pub.battery,
          state: pub.state,
          screenTodayMin: Math.round(((pub.usage && pub.usage.totalMs) || 0) / 60000),
          steps: pub.steps,
          lat: pub.lat,
          lon: pub.lon,
          limitsUsed,
        };
      });
      const totals = {
        devices: devices.length,
        online: summary.filter((s) => s.online).length,
        alerts24h: store.eventsOf(parent.familyId, null, 500)
          .filter((e) => e.ts > ctx.now() - 24 * 3600 * 1000 && e.level !== 'info').length,
        screenTodayMin: summary.reduce((sum, s) => sum + s.screenTodayMin, 0),
      };
      return U.ok(res, { serverTime: ctx.now(), day, totals, devices: summary });
    }

    /* --------------------------------------------------------------- геозоны */

    if (path === '/api/v1/places' && method === 'GET') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      return U.ok(res, { places: placesOf(store, parent.familyId) });
    }

    if (path === '/api/v1/places' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const body = await U.readJson(req);
      const lat = U.clampNumber(body.lat, -90, 90, null);
      const lon = U.clampNumber(body.lon, -180, 180, null);
      if (lat == null || lon == null) return U.fail(res, 400, 'Нужны координаты места');
      const place = {
        id: 'pl_' + require('node:crypto').randomBytes(4).toString('hex'),
        familyId: parent.familyId,
        name: U.trimText(body.name, 60).trim() || 'Место',
        lat,
        lon,
        radius: U.clampNumber(body.radius, 50, 5000, 300),
        notifyIn: body.notifyIn !== false,
        notifyOut: body.notifyOut !== false,
        days: Array.isArray(body.days) ? body.days.map(Number).filter((d) => d >= 0 && d <= 6) : [],
        createdAt: ctx.now(),
      };
      store.data.places = store.data.places || [];
      store.data.places.push(place);
      store.touch();
      return U.ok(res, { place });
    }

    if (path === '/api/v1/places/remove' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const body = await U.readJson(req);
      const before = (store.data.places || []).length;
      store.data.places = (store.data.places || []).filter(
        (p) => !(p.id === String(body.id) && p.familyId === parent.familyId)
      );
      store.touch();
      return U.ok(res, { removed: before - store.data.places.length });
    }

    /* ------------------------------------------------------- голосовые сообщения */

    if (path === '/api/v1/voice/probe' && method === 'POST') {
      // телефон ребёнка проверяет, разрешён ли разговор
      const device = deviceFrom(req);
      if (!device) return U.fail(res, 401, 'Устройство не авторизовано');
      const p = device.policy || defaultPolicy();
      const st = computeState(device, ctx.now());
      return U.ok(res, {
        allowed: p.voiceEnabled !== false && !st.locked,
        state: st,
      });
    }

    if (path === '/api/v1/push/subscribe' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const body = await U.readJson(req);
      if (!body.subscription || !body.subscription.endpoint) {
        return U.fail(res, 400, 'Нужна подписка браузера');
      }
      ctx.store.data.pushSubs = (ctx.store.data.pushSubs || []).filter(
        (s) => s.endpoint !== body.subscription.endpoint
      );
      ctx.store.data.pushSubs.push({
        id: 'ps_' + require('node:crypto').randomBytes(4).toString('hex'),
        familyId: parent.familyId,
        endpoint: body.subscription.endpoint,
        keys: body.subscription.keys || {},
        createdAt: ctx.now(),
      });
      ctx.store.touch();
      return U.ok(res, {});
    }

    if (path === '/api/v1/push/key' && method === 'GET') {
      const { publicKey } = require('./push');
      return U.ok(res, { publicKey, enabled: !!publicKey });
    }

    /* ------------------------------------------------- снимки экрана и камеры */

    if (path === '/api/v1/device/image' && method === 'POST') {
      const device = deviceFrom(req);
      if (!device) return U.fail(res, 401, 'Устройство не авторизовано');
      const body = await U.readJson(req, 3 * 1024 * 1024);
      const kind = U.trimText(body.kind, 20) || 'screen';
      const data = String(body.data || '');
      if (!data || data.length > 2.5 * 1024 * 1024) {
        return U.fail(res, 400, 'Пустое или слишком большое изображение');
      }
      images.set(device.id + ':' + kind, { ts: ctx.now(), data, kind, deviceId: device.id, familyId: device.familyId });
      if (kind === 'camera') {
        store.addEvent(device.familyId, device.id, 'camera', 'Получено фото с камеры', 'warn');
      }
      return U.ok(res, { ts: ctx.now() });
    }

    if (path === '/api/v1/image/latest' && method === 'GET') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const device = store.deviceById(String(url.searchParams.get('deviceId') || ''));
      if (!device || device.familyId !== parent.familyId) return U.fail(res, 404, 'Устройство не найдено');
      const kind = U.trimText(url.searchParams.get('kind'), 20) || 'screen';
      const row = images.get(device.id + ':' + kind);
      return U.ok(res, { image: row ? { ts: row.ts, data: row.data, kind: row.kind } : null });
    }

    /* ---------------------------------------------------------- голосовой вызов */

    if (path === '/api/v1/call/start' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const body = await U.readJson(req);
      const device = store.deviceById(String(body.deviceId || ''));
      if (!device || device.familyId !== parent.familyId) return U.fail(res, 404, 'Устройство не найдено');
      const call = {
        id: 'call_' + require('node:crypto').randomBytes(4).toString('hex'),
        familyId: parent.familyId,
        deviceId: device.id,
        state: 'ringing',
        offer: body.offer || null,
        answer: null,
        ice: [],
        createdAt: ctx.now(),
        updatedAt: ctx.now(),
      };
      calls.set(call.id, call);
      pruneCalls();
      store.addCmd(parent.familyId, device.id, 'call', call.id);
      store.addEvent(parent.familyId, device.id, 'call', 'Родитель звонит на устройство «' + device.name + '»', 'info');
      return U.ok(res, { callId: call.id });
    }

    if (path === '/api/v1/call/poll' && method === 'GET') {
      const call = calls.get(String(url.searchParams.get('callId') || ''));
      if (!call) return U.fail(res, 404, 'Вызов не найден');
      const parent = parentFrom(req);
      const device = deviceFrom(req);
      if (!(parent && parent.familyId === call.familyId) && !(device && device.id === call.deviceId)) {
        return U.fail(res, 401, 'Нет доступа к вызову');
      }
      const since = U.clampNumber(url.searchParams.get('since'), 0, Number.MAX_SAFE_INTEGER, 0);
      if (call.state === 'ringing' && ctx.now() - call.createdAt > 60000) call.state = 'missed';
      return U.ok(res, {
        state: call.state,
        offer: call.offer,
        answer: call.answer,
        ice: call.ice.filter((c) => c.ts > since),
        ts: ctx.now(),
      });
    }

    if (path === '/api/v1/call/answer' && method === 'POST') {
      const device = deviceFrom(req);
      if (!device) return U.fail(res, 401, 'Устройство не авторизовано');
      const body = await U.readJson(req);
      const call = calls.get(String(body.callId || ''));
      if (!call || call.deviceId !== device.id) return U.fail(res, 404, 'Вызов не найден');
      call.state = body.accepted === false ? 'rejected' : 'accepted';
      call.answer = body.answer || null;
      call.updatedAt = ctx.now();
      if (call.state === 'rejected') {
        store.addEvent(call.familyId, device.id, 'call', 'Ребёнок отклонил вызов', 'warn');
      }
      return U.ok(res, { state: call.state });
    }

    if (path === '/api/v1/call/ice' && method === 'POST') {
      const body = await U.readJson(req);
      const call = calls.get(String(body.callId || ''));
      if (!call) return U.fail(res, 404, 'Вызов не найден');
      const parent = parentFrom(req);
      const device = deviceFrom(req);
      const side = String(body.side || (parent ? 'parent' : 'child'));
      if (!(parent && parent.familyId === call.familyId) && !(device && device.id === call.deviceId)) {
        return U.fail(res, 401, 'Нет доступа к вызову');
      }
      if (body.candidate) {
        call.ice.push({ from: side, candidate: String(body.candidate).slice(0, 1000), ts: ctx.now() });
        if (call.ice.length > 200) call.ice.splice(0, call.ice.length - 200);
      }
      return U.ok(res, {});
    }

    if (path === '/api/v1/call/end' && method === 'POST') {
      const body = await U.readJson(req);
      const call = calls.get(String(body.callId || ''));
      if (!call) return U.fail(res, 404, 'Вызов не найден');
      const parent = parentFrom(req);
      const device = deviceFrom(req);
      if (!(parent && parent.familyId === call.familyId) && !(device && device.id === call.deviceId)) {
        return U.fail(res, 401, 'Нет доступа к вызову');
      }
      call.state = 'ended';
      call.updatedAt = ctx.now();
      return U.ok(res, {});
    }

    if (path === '/api/v1/alerts/clear' && method === 'POST') {
      const parent = parentFrom(req);
      if (!parent) return U.fail(res, 401, 'Нужен вход');
      const body = await U.readJson(req);
      const device = store.deviceById(String(body.deviceId || ''));
      if (!device || device.familyId !== parent.familyId) return U.fail(res, 404, 'Устройство не найдено');
      store.addCmd(parent.familyId, device.id, 'sos_clear', null);
      return U.ok(res, {});
    }

    return false; // маршрут не найден — обрабатывает вызывающий код
  }

  return { handle, defaultPolicy, defaultSettings, devicePublic, computeState };
}

function placesOf(store, familyId) {
  return (store.data.places || []).filter((p) => p.familyId === familyId);
}

function describeCmd(cmd, arg) {
  switch (cmd) {
    case 'lock': return 'блокировка экрана';
    case 'unlock': return 'снятие блокировки';
    case 'alarm': return 'сирена';
    case 'message': return 'сообщение: ' + U.trimText(arg, 80);
    case 'triggerFetch': return 'запрос фото';
    case 'photo': return 'запрос фото с камеры';
    case 'locate': return 'запрос геопозиции';
    case 'refresh': return 'запрос отчёта';
    case 'sync': return 'синхронизация настроек';
    case 'ring': return 'звонок ребёнку';
    case 'tour': return 'экскурсия по телефону';
    case 'apply_settings': return 'обновление настроек';
    default: return cmd;
  }
}

/** Проверка геозон при каждой новой точке. */
function checkPlaces(store, device, lat, lon) {
  const places = placesOf(store, device.familyId);
  if (!places.length) return;
  device.inside = device.inside || [];
  device.placeState = device.placeState || {};
  const nowInside = [];
  for (const place of places) {
    const dist = distanceMeters(lat, lon, place.lat, place.lon);
    const isInside = dist <= place.radius;
    if (isInside) nowInside.push(place.id);
    const was = device.placeState[place.id];
    const alerts = (device.policy && device.policy.alerts) || {};
    if (alerts.geofence === false) continue;
    if (isInside && was !== 'in' && place.notifyIn !== false) {
      store.addEvent(device.familyId, device.id, 'geofence', 'Ребёнок пришёл: ' + place.name, 'info');
      pushToFamily(store, device.familyId, { title: place.name, body: device.name + ': пришёл(ла) в ' + place.name });
    } else if (!isInside && was === 'in' && place.notifyOut !== false) {
      store.addEvent(device.familyId, device.id, 'geofence', 'Ребёнок ушёл: ' + place.name, 'warn');
      pushToFamily(store, device.familyId, { title: place.name, body: device.name + ': покинул(а) ' + place.name });
    }
    device.placeState[place.id] = isInside ? 'in' : 'out';
  }
  device.inside = nowInside;
}

module.exports = { createApi, defaultPolicy, defaultSettings, computeState, sanitizeSettings, distanceMeters, insideWindow };
