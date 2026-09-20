'use strict';
/*
 * Хранилище состояния Modar Family.
 *
 * По умолчанию — один JSON-файл (db.json), запись атомарная, с задержкой склейки
 * (несколько изменений подряд попадают на диск одним сбросом). Этого с запасом
 * хватает семье: 2–6 устройств, несколько точек в минуту.
 *
 * Если задать DATABASE_URL=postgres://... — включается режим PostgreSQL
 * (см. lib/pgstore.js), интерфейс тот же.
 */

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const EMPTY = {
  version: 1,
  parents: [],   // { id, familyId, email, name, salt, hash, createdAt }
  families: [],  // { id, name, createdAt, pairCode, pairCodeExpires, plan }
  devices: [],   // см. device-поля ниже
  events: [],    // { id, familyId, deviceId, type, message, ts, level }
  spots: [],     // { id, familyId, deviceId, lat, lon, acc, ts }
  cmds: [],      // { id, familyId, deviceId, cmd, arg, ts, deliveredAt }
  usage: [],     // { id, familyId, deviceId, day, pkg, label, ms }
  places: [],    // геозоны: { id, familyId, name, lat, lon, radius, notifyIn, notifyOut, days }
  pushSubs: [],  // подписки браузеров на уведомления
  logins: [],    // { id, email, ts, ip }
};

const MAX_EVENTS = 4000;
const MAX_SPOTS = 4000;
const MAX_CMDS = 300;
const MAX_USAGE = 20000;
const MAX_LOGINS = 200;
const SPOT_MIN_GAP_MS = 10000;      // точки ближе 10 с сливаются
const CMD_TTL_MS = 10 * 60 * 1000;  // недоставленная команда живёт 10 минут
const CODE_TTL_MS = 24 * 60 * 60 * 1000;

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

class Store {
  constructor(dir) {
    this.file = path.join(dir, 'db.json');
    this.data = clone(EMPTY);
    this.dirty = false;
    this.timer = null;
    this.fs = fs;
    try {
      fs.mkdirSync(dir, { recursive: true });
    } catch (e) { /* каталог уже есть */ }
    this.load();
  }

  load() {
    let raw = null;
    try {
      raw = this.fs.readFileSync(this.file, 'utf8');
    } catch (e) {
      raw = null;
    }
    if (raw) {
      try {
        const parsed = JSON.parse(raw);
        for (const key of Object.keys(EMPTY)) {
          if (Array.isArray(parsed[key])) this.data[key] = parsed[key];
        }
        return;
      } catch (e) {
        const backup = this.file + '.broken-' + Date.now();
        try { this.fs.renameSync(this.file, backup); } catch (e2) { /* ignore */ }
        console.error('[store] db.json повреждён, сохранил копию:', backup);
      }
    }
    this.touch();
  }

  touch() {
    this.dirty = true;
    if (this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.flush();
    }, 800);
    if (this.timer.unref) this.timer.unref();
  }

  flush() {
    if (!this.dirty) return;
    this.dirty = false;
    const tmp = this.file + '.tmp';
    try {
      this.fs.writeFileSync(tmp, JSON.stringify(this.data));
      this.fs.renameSync(tmp, this.file);
    } catch (e) {
      console.error('[store] не удалось сохранить db.json:', e.message);
    }
  }

  /* ------------------------------------------------------------- выборки */

  family(id) {
    return this.data.families.find((f) => f.id === id) || null;
  }

  parentByEmail(email) {
    const key = String(email || '').trim().toLowerCase();
    return this.data.parents.find((p) => p.email === key) || null;
  }

  parentByToken(token) {
    if (!token) return null;
    return this.data.parents.find((p) => p.token === token) || null;
  }

  parentById(id) {
    return this.data.parents.find((p) => p.id === id) || null;
  }

  deviceById(id) {
    return this.data.devices.find((d) => d.id === id) || null;
  }

  deviceByToken(token) {
    if (!token) return null;
    return this.data.devices.find((d) => d.token === token) || null;
  }

  devicesOf(familyId) {
    return this.data.devices.filter((d) => d.familyId === familyId);
  }

  eventsOf(familyId, deviceId, limit) {
    const list = this.data.events.filter(
      (e) => e.familyId === familyId && (!deviceId || e.deviceId === deviceId)
    );
    list.sort((a, b) => b.ts - a.ts);
    return list.slice(0, limit || 100);
  }

  spotsOf(familyId, deviceId, since, limit) {
    let list = this.data.spots.filter((s) => s.familyId === familyId);
    if (deviceId) list = list.filter((s) => s.deviceId === deviceId);
    if (since) list = list.filter((s) => s.ts >= since);
    list.sort((a, b) => a.ts - b.ts);
    if (limit && list.length > limit) list = list.slice(list.length - limit);
    return list;
  }

  usageOf(familyId, deviceId, day) {
    return this.data.usage.filter(
      (u) => u.familyId === familyId && (!deviceId || u.deviceId === deviceId) && (!day || u.day === day)
    );
  }

  /* ------------------------------------------------------------ изменения */

  createFamily(name) {
    const family = {
      id: 'fam_' + crypto.randomBytes(4).toString('hex'),
      name: name || 'Семья',
      createdAt: Date.now(),
      pairCode: null,
      pairCodeExpires: 0,
      plan: 'free',
    };
    this.data.families.push(family);
    this.touch();
    return family;
  }

  createParent(familyId, email, name, saltValue, hash) {
    const parent = {
      id: 'par_' + crypto.randomBytes(4).toString('hex'),
      familyId,
      email: String(email || '').trim().toLowerCase(),
      name: name || '',
      salt: saltValue,
      hash,
      token: null,
      deviceToken: null,
      createdAt: Date.now(),
    };
    this.data.parents.push(parent);
    this.touch();
    return parent;
  }

  saveParent(parent) {
    const i = this.data.parents.indexOf(parent);
    if (i < 0) this.data.parents.push(parent);
    this.touch();
    return parent;
  }

  logLogin(email, ip) {
    this.data.logins.unshift({ id: 'log_' + Date.now().toString(36), email, ip, ts: Date.now() });
    if (this.data.logins.length > MAX_LOGINS) this.data.logins.length = MAX_LOGINS;
    this.touch();
  }

  /** Новый код сопряжения для ребёнка (действует сутки). */
  newPairCode(familyId) {
    const family = this.family(familyId);
    if (!family) return null;
    family.pairCode = require('./util').pairCode();
    family.pairCodeExpires = Date.now() + CODE_TTL_MS;
    this.touch();
    return family.pairCode;
  }

  pairCodeValid(familyId, code) {
    const family = this.family(familyId);
    if (!family || !family.pairCode) return false;
    if (family.pairCodeExpires && family.pairCodeExpires < Date.now()) return false;
    return String(code || '').trim() === String(family.pairCode);
  }

  addDevice(device) {
    this.data.devices.push(device);
    this.touch();
    return device;
  }

  saveDevice(device) {
    const i = this.data.devices.indexOf(device);
    if (i < 0) this.data.devices.push(device);
    this.touch();
    return device;
  }

  removeDevice(deviceId) {
    const familyId = (this.deviceById(deviceId) || {}).familyId;
    this.data.devices = this.data.devices.filter((d) => d.id !== deviceId);
    this.data.events = this.data.events.filter((e) => e.deviceId !== deviceId);
    this.data.spots = this.data.spots.filter((s) => s.deviceId !== deviceId);
    this.data.usage = this.data.usage.filter((u) => u.deviceId !== deviceId);
    this.data.cmds = this.data.cmds.filter((c) => c.deviceId !== deviceId);
    this.touch();
    return familyId;
  }

  addEvent(familyId, deviceId, type, message, level) {
    const event = {
      id: 'ev_' + Date.now().toString(36) + crypto.randomBytes(3).toString('hex'),
      familyId,
      deviceId,
      type: String(type || 'info').slice(0, 40),
      message: String(message == null ? '' : message).slice(0, 500),
      level: level || 'info',
      ts: Date.now(),
    };
    this.data.events.unshift(event);
    if (this.data.events.length > MAX_EVENTS) {
      this.data.events.length = MAX_EVENTS;
    }
    this.touch();
    return event;
  }

  addSpot(familyId, deviceId, lat, lon, acc, ts, extra) {
    const time = Number(ts) || Date.now();
    const last = this.data.spots.filter((s) => s.deviceId === deviceId).pop();
    if (last && time - last.ts < SPOT_MIN_GAP_MS) {
      last.lat = lat;
      last.lon = lon;
      last.acc = acc;
      last.ts = time;
      if (extra) Object.assign(last, extra);
      this.touch();
      return last;
    }
    const spot = Object.assign(
      {
        id: 'sp_' + Date.now().toString(36) + crypto.randomBytes(3).toString('hex'),
        familyId,
        deviceId,
        lat,
        lon,
        acc,
        ts: time,
      },
      extra || {}
    );
    this.data.spots.push(spot);
    if (this.data.spots.length > MAX_SPOTS) {
      this.data.spots.sort((a, b) => a.ts - b.ts);
      this.data.spots.splice(0, this.data.spots.length - MAX_SPOTS);
    }
    this.touch();
    return spot;
  }

  addUsage(familyId, deviceId, day, list) {
    for (const item of list || []) {
      const pkg = String(item.pkg || '').slice(0, 200);
      if (!pkg) continue;
      let row = this.data.usage.find(
        (u) => u.deviceId === deviceId && u.day === day && u.pkg === pkg
      );
      if (!row) {
        row = { id: 'us_' + crypto.randomBytes(4).toString('hex'), familyId, deviceId, day, pkg, ms: 0 };
        this.data.usage.push(row);
      }
      row.label = String(item.label || row.label || pkg).slice(0, 120);
      row.ms = Math.max(Number(row.ms) || 0, Number(item.ms) || 0);
    }
    if (this.data.usage.length > MAX_USAGE) {
      this.data.usage = this.data.usage.slice(this.data.usage.length - MAX_USAGE);
    }
    this.touch();
  }

  addCmd(familyId, deviceId, cmd, arg) {
    const command = {
      id: 'cmd_' + Date.now().toString(36) + crypto.randomBytes(3).toString('hex'),
      familyId,
      deviceId,
      cmd,
      arg: arg == null ? null : arg,
      ts: Date.now(),
      deliveredAt: 0,
    };
    this.data.cmds.push(command);
    this.pruneCmds();
    this.touch();
    return command;
  }

  pendingCmds(deviceId) {
    this.pruneCmds();
    const list = this.data.cmds.filter((c) => c.deviceId === deviceId && !c.deliveredAt);
    list.sort((a, b) => a.ts - b.ts);
    return list.slice(0, 20);
  }

  ackCmds(deviceId, ids) {
    const set = new Set((ids || []).map(String));
    for (const c of this.data.cmds) {
      if (c.deviceId === deviceId && (set.size === 0 || set.has(c.id))) {
        c.deliveredAt = Date.now();
      }
    }
    this.touch();
  }

  pruneCmds() {
    const cutoff = Date.now() - CMD_TTL_MS;
    this.data.cmds = this.data.cmds.filter((c) => c.deliveredAt > cutoff || c.ts > cutoff);
    if (this.data.cmds.length > MAX_CMDS) {
      this.data.cmds = this.data.cmds.slice(this.data.cmds.length - MAX_CMDS);
    }
  }

  /** Сколько точек/событий у семьи — для лимитов бесплатного тарифа. */
  counts(familyId) {
    return {
      devices: this.devicesOf(familyId).length,
      events: this.data.events.filter((e) => e.familyId === familyId).length,
      spots: this.data.spots.filter((s) => s.familyId === familyId).length,
    };
  }
}

module.exports = { Store, EMPTY };
