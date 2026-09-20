'use strict';
/*
 * Modar Family — сервер родительского контроля.
 *
 *   node server.js                 обычный запуск
 *   DEMO=1 node server.js          запуск с демо-семьёй (для показа панели)
 *
 * Переменные окружения:
 *   PORT=8080            порт
 *   DATA_DIR=./data      каталог с db.json
 *   BASE_URL=...         публичный адрес сервера (показывается в инструкциях)
 *   VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY — включают Web Push (см. lib/push.js)
 */

const http = require('node:http');
const path = require('node:path');
const fs = require('node:fs');

const { Store } = require('./lib/store');
const U = require('./lib/util');
const { createApi, computeState } = require('./lib/api');
const push = require('./lib/push');

const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const PUBLIC_DIR = path.join(__dirname, 'public');
const DEMO = process.env.DEMO === '1';
const VERSION = require('./package.json').version;

const store = new Store(DATA_DIR);

/* ------------------------------------------------------------------ статика */

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.woff2': 'font/woff2',
};

function sendFile(res, file, req) {
  let data;
  try {
    data = fs.readFileSync(file);
  } catch (e) {
    return false;
  }
  const gz = file + '.gz';
  const acceptsGzip = /\bgzip\b/.test(String(req.headers['accept-encoding'] || ''));
  if (acceptsGzip && fs.existsSync(gz)) {
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Content-Encoding': 'gzip',
      'Cache-Control': 'no-cache',
    });
    res.end(fs.readFileSync(gz));
    return true;
  }
  const ext = path.extname(file);
  const fresh = ['.html', '.js', '.css', '.webmanifest'].indexOf(ext) >= 0;
  res.writeHead(200, {
    'Content-Type': MIME[ext] || 'application/octet-stream',
    'Content-Length': data.length,
    'Cache-Control': fresh ? 'no-cache' : 'public, max-age=3600',
  });
  res.end(data);
  return true;
}

function serveStatic(req, res, url) {
  let rel = decodeURIComponent(url.pathname);
  if (rel === '/' || rel === '') rel = '/index.html';
  const safe = path.normalize(rel).replace(/^(\.\.[/\\])+/, '');
  const file = path.join(PUBLIC_DIR, safe);
  if (!file.startsWith(PUBLIC_DIR)) return false;
  if (sendFile(res, file, req)) return true;
  if (!path.extname(safe)) {
    // SPA: любой несуществующий маршрут отдаёт панель
    return sendFile(res, path.join(PUBLIC_DIR, 'index.html'), req);
  }
  return false;
}

/* ------------------------------------------------------------------- сервер */

const ctx = {
  store,
  now: () => Date.now(),
  version: VERSION,
  demo: DEMO,
  dataDir: DATA_DIR,
};

const api = createApi(ctx);

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://' + (req.headers.host || 'localhost'));

  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'same-origin');

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type,Authorization,X-Api-Token',
      'Access-Control-Max-Age': '600',
    });
    return res.end();
  }

  if (url.pathname === '/healthz') {
    return U.json(res, 200, {
      ok: true,
      service: 'modar-family',
      version: VERSION,
      uptime: Math.round(process.uptime()),
      families: store.data.families.length,
      devices: store.data.devices.length,
      push: !!push.publicKey,
    });
  }

  if (url.pathname === '/api/v1/ping') {
    return U.json(res, 200, { ok: true, service: 'modar-family', version: VERSION, serverTime: Date.now() });
  }

  if (url.pathname.startsWith('/api/')) {
    Promise.resolve()
      .then(() => api.handle(req, res, url))
      .then((handled) => {
        if (handled === false && !res.headersSent) {
          U.fail(res, 404, 'Неизвестный метод API: ' + req.method + ' ' + url.pathname);
        }
      })
      .catch((err) => {
        console.error('[api]', req.method, url.pathname, err && err.message);
        if (!res.headersSent) U.fail(res, err && err.status ? err.status : 500, (err && err.message) || 'Ошибка сервера');
      });
    return;
  }

  /* APK приложения раздаётся прямо с сервера — родителю не нужно искать файл. */
  if (url.pathname.startsWith('/app/')) {
    const dirs = [
      process.env.APK_DIR,
      path.join(__dirname, '..', 'apk'),
      path.join(__dirname, 'data'),
    ].filter(Boolean);
    const name = path.basename(url.pathname);
    for (const dir of dirs) {
      const candidate = path.join(dir, name === 'app' ? 'modar-family.apk' : name);
      if (fs.existsSync(candidate)) {
        res.writeHead(200, {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Length': fs.statSync(candidate).size,
          'Content-Disposition': 'attachment; filename="' + path.basename(candidate) + '"',
        });
        return fs.createReadStream(candidate).pipe(res);
      }
    }
  }

  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return U.fail(res, 405, 'Метод не поддерживается');
  }
  if (serveStatic(req, res, url)) return;
  U.fail(res, 404, 'Страница не найдена');
});

/* ------------------------------------------------- мониторинг «нет связи» */

setInterval(() => {
  const now = Date.now();
  for (const device of store.data.devices) {
    const policy = device.policy || {};
    const minutes = ((policy.alerts || {}).offlineMinutes) || 30;
    if (!device.lastSeen) continue;
    const offlineMs = now - device.lastSeen;
    if (offlineMs > minutes * 60000 && (device.lastOfflineAlert || 0) < device.lastSeen) {
      device.lastOfflineAlert = now;
      store.addEvent(device.familyId, device.id, 'offline', 'Нет связи с «' + device.name + '» более ' + minutes + ' мин', 'warn');
      push.pushToFamily(store, device.familyId, {
        title: 'Нет связи',
        body: device.name + ': нет отчётов ' + minutes + ' мин',
      });
    }
  }
  store.flush();
}, 60 * 1000).unref();

/* ---------------------------------------------------------------- демо-семья */

function seedDemo() {
  const email = 'demo@modar.family';
  if (store.parentByEmail(email)) {
    console.log('Демо-семья уже есть: ' + email + ' / demo1234');
    return;
  }
  const family = store.createFamily('Семья Соколовых');
  const saltValue = U.salt();
  const parent = store.createParent(family.id, email, 'Мария', saltValue, U.hashPassword('demo1234', saltValue));
  parent.token = U.token();
  store.saveParent(parent);
  store.newPairCode(family.id);
  store.data.places = store.data.places || [];

  const base = { lat: 52.3702, lon: 4.8952 }; // Амстердам
  const school = { lat: 52.3585, lon: 4.8686 };
  const home = base;
  store.data.places.push(
    { id: 'pl_demo_home', familyId: family.id, name: 'Дом', lat: home.lat, lon: home.lon, radius: 250, notifyIn: true, notifyOut: true, days: [], createdAt: Date.now() },
    { id: 'pl_demo_school', familyId: family.id, name: 'Школа', lat: school.lat, lon: school.lon, radius: 300, notifyIn: true, notifyOut: true, days: [1, 2, 3, 4, 5], createdAt: Date.now() },
    { id: 'pl_demo_gym', familyId: family.id, name: 'Секция', lat: 52.3620, lon: 4.8830, radius: 200, notifyIn: true, notifyOut: false, days: [], createdAt: Date.now() }
  );

  const phone = {
    id: 'dev_demo_phone',
    familyId: family.id,
    token: U.token(),
    name: 'Телефон Алисы',
    model: 'Samsung Galaxy A54',
    androidId: 'demo-phone',
    version: '2.1.0',
    createdAt: Date.now(),
    lastSeen: Date.now(),
    online: true,
    battery: 62,
    charging: false,
    lat: school.lat,
    lon: school.lon,
    acc: 12,
    speed: 0,
    locatedAt: Date.now(),
    screenOn: true,
    steps: 4210,
    zone: 'Europe/Amsterdam',
    inside: ['pl_demo_school'],
    placeState: { pl_demo_school: 'in', pl_demo_home: 'out' },
    accessibility: true,
    admin: true,
    vpn: true,
    notificationsPermission: true,
    locationPermission: true,
    policy: Object.assign(require('./lib/api').defaultPolicy(), {
      blockApps: ['com.instagram.android', 'com.zhiliaoapp.musically'],
      appLimits: { 'com.google.android.youtube': 60, 'com.vk.android': 30 },
      dailyLimitMin: 180,
      schoolMode: { enabled: true, from: '08:00', to: '14:00', days: [1, 2, 3, 4, 5] },
      bedtime: { enabled: true, from: '21:30', to: '07:00' },
    }),
    apps: [
      { pkg: 'com.google.android.youtube', label: 'YouTube' },
      { pkg: 'com.vk.android', label: 'VK' },
      { pkg: 'com.instagram.android', label: 'Instagram' },
      { pkg: 'com.whatsapp', label: 'WhatsApp' },
      { pkg: 'com.android.chrome', label: 'Chrome' },
      { pkg: 'org.telegram.messenger', label: 'Telegram' },
      { pkg: 'com.duolingo', label: 'Duolingo' },
    ],
    usage: {
      day: U.dayKey(),
      totalMs: 96 * 60000,
      list: [
        { pkg: 'com.google.android.youtube', label: 'YouTube', ms: 41 * 60000 },
        { pkg: 'com.vk.android', label: 'VK', ms: 22 * 60000 },
        { pkg: 'com.whatsapp', label: 'WhatsApp', ms: 14 * 60000 },
        { pkg: 'com.duolingo', label: 'Duolingo', ms: 11 * 60000 },
        { pkg: 'com.android.chrome', label: 'Chrome', ms: 8 * 60000 },
      ],
    },
    notifications: [
      { app: 'WhatsApp', title: 'Мама', text: 'Не забудь про секцию в 18:00', ts: Date.now() - 12 * 60000 },
      { app: 'VK', title: 'Новое сообщение', text: 'Илья: го в игру', ts: Date.now() - 40 * 60000 },
    ],
    webBlocked: [
      { host: 'example-adult-site.com', reason: 'adult', ts: Date.now() - 90 * 60000 },
      { host: 'free-game-casino.net', reason: 'gambling', ts: Date.now() - 5 * 3600000 },
    ],
    lastReport: Date.now(),
  };
  const tablet = {
    id: 'dev_demo_tablet',
    familyId: family.id,
    token: U.token(),
    name: 'Планшет Миши',
    model: 'Lenovo Tab M10',
    androidId: 'demo-tablet',
    version: '2.1.0',
    createdAt: Date.now(),
    lastSeen: Date.now() - 3 * 3600000,
    online: false,
    battery: 18,
    charging: true,
    lat: home.lat + 0.0009,
    lon: home.lon - 0.0012,
    acc: 25,
    locatedAt: Date.now() - 3 * 3600000,
    screenOn: false,
    steps: 820,
    inside: ['pl_demo_home'],
    placeState: { pl_demo_home: 'in' },
    accessibility: false,
    admin: true,
    vpn: false,
    notificationsPermission: true,
    locationPermission: true,
    policy: Object.assign(require('./lib/api').defaultPolicy(), {
      dailyLimitMin: 120,
      blockApps: ['com.zhiliaoapp.musically'],
      bedtime: { enabled: true, from: '21:00', to: '07:00' },
    }),
    apps: [
      { pkg: 'com.google.android.youtube', label: 'YouTube' },
      { pkg: 'com.roblox.client', label: 'Roblox' },
      { pkg: 'com.zhiliaoapp.musically', label: 'TikTok' },
    ],
    usage: {
      day: U.dayKey(),
      totalMs: 154 * 60000,
      list: [
        { pkg: 'com.roblox.client', label: 'Roblox', ms: 88 * 60000 },
        { pkg: 'com.google.android.youtube', label: 'YouTube', ms: 51 * 60000 },
        { pkg: 'com.zhiliaoapp.musically', label: 'TikTok', ms: 15 * 60000 },
      ],
    },
    notifications: [],
    webBlocked: [],
    lastReport: Date.now() - 3 * 3600000,
  };
  store.addDevice(phone);
  store.addDevice(tablet);

  /* Трек за сутки: дом → школа → секция → дом. */
  const path = [
    [home, 7.6], [school, 8.2], [school, 13.4], [home, 14.0],
    [home, 16.6], [ { lat: 52.3620, lon: 4.8830 }, 17.0 ], [home, 19.4],
  ];
  const now = Date.now();
  const dayStart = now - 24 * 3600 * 1000;
  for (let i = 0; i < path.length - 1; i++) {
    const [from, hourFrom] = path[i];
    const [to, hourTo] = path[i + 1];
    const start = dayStart + hourFrom * 3600 * 1000;
    const end = dayStart + hourTo * 3600 * 1000;
    const steps = Math.max(2, Math.round((end - start) / (5 * 60 * 1000)));
    for (let s = 0; s <= steps; s++) {
      const k = s / steps;
      const jitterLat = (Math.sin(s * 1.7) + Math.cos(s * 0.6)) * 0.0008;
      const jitterLon = (Math.cos(s * 1.3) + Math.sin(s * 0.9)) * 0.0009;
      store.addSpot(family.id, phone.id, from.lat + (to.lat - from.lat) * k + jitterLat,
        from.lon + (to.lon - from.lon) * k + jitterLon, 8 + (s % 5) * 3, start + (end - start) * k);
    }
  }

  const events = [
    ['geofence', 'Ребёнок пришёл: Школа', 'info', 5.5],
    ['web', 'Заблокирован сайт: example-adult-site.com', 'warn', 4.2],
    ['app', 'Попытка открыть Instagram — заблокировано', 'warn', 3.1],
    ['limit', 'Лимит YouTube 60 мин исчерпан', 'warn', 2.4],
    ['web', 'Заблокирован сайт: free-game-casino.net', 'warn', 1.8],
    ['battery', 'Батарея 18% на устройстве «Планшет Миши»', 'warn', 1.1],
    ['geofence', 'Ребёнок ушёл: Секция', 'warn', 0.9],
    ['notification', 'WhatsApp: Мама — Не забудь про секцию в 18:00', 'info', 0.2],
  ];
  for (const [type, message, level, hoursAgo] of events) {
    store.addEvent(family.id, type === 'battery' ? tablet.id : phone.id, type, message, level);
    const last = store.data.events[0];
    last.ts = Date.now() - hoursAgo * 3600 * 1000;
  }
  store.touch();
  store.flush();
  console.log('Демо-семья создана: ' + email + ' / demo1234 (код сопряжения: ' + family.pairCode + ')');
}

if (DEMO) {
  seedDemo();
  /* В демо-режиме «телефон ребёнка» немного двигается, чтобы карта была живой. */
  let step = 0;
  setInterval(() => {
    const demo = store.deviceById('dev_demo_phone');
    if (!demo) return;
    step += 1;
    const base = { lat: 52.3585, lon: 4.8686 };
    const lat = base.lat + Math.sin(step / 12) * 0.004;
    const lon = base.lon + Math.cos(step / 15) * 0.005;
    demo.battery = Math.max(20, (demo.battery || 62) - (step % 7 === 0 ? 1 : 0));
    store.addSpot(demo.familyId, demo.id, lat, lon, 10, Date.now());
    demo.lat = lat;
    demo.lon = lon;
    demo.locatedAt = Date.now();
    demo.lastSeen = Date.now();
    demo.online = true;
    store.touch();
  }, 15000).unref();
}

/* -------------------------------------------------------------------- старт */

server.listen(PORT, HOST, () => {
  const shown = process.env.BASE_URL || 'http://localhost:' + PORT;
  console.log('');
  console.log('  Modar Family — сервер родительского контроля v' + VERSION);
  console.log('  ─────────────────────────────────────────────────────');
  console.log('  Панель родителя:   ' + shown + '/');
  console.log('  API устройств:     ' + shown + '/api/v1/device/report');
  console.log('  Состояние:         ' + shown + '/healthz');
  console.log('  Данные:            ' + DATA_DIR);
  console.log('  Web Push:          ' + (push.publicKey ? 'включён' : 'выключен (нет VAPID-ключей)'));
  if (DEMO) console.log('  ДЕМО-РЕЖИМ:        demo@modar.family / demo1234');
  console.log('');
});

process.on('SIGTERM', () => {
  console.log('Остановка: сохраняю данные…');
  store.flush();
  server.close(() => process.exit(0));
});
process.on('SIGINT', () => {
  store.flush();
  process.exit(0);
});

module.exports = { server, store, api };
