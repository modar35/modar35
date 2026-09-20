#!/usr/bin/env node
/*
 * Тест веб-панели без браузера: поднимает разметку в jsdom, подсовывает демо-данные
 * и прогоняет все экраны и действия (карта, настройки, команды, сопряжение, push).
 *
 * Запуск:
 *   npm install jsdom          # один раз (папка с node_modules может быть временной)
 *   node parental/tools/test-panel.js
 *
 * Проверяет: скрипты панели не падают, все элементы разметки на месте,
 * обратное преобразование координат на карте и лимиты зума.
 */
'use strict';

const fs = require('fs');
const path = require('path');

let JSDOM;
try {
  JSDOM = require('jsdom').JSDOM;
} catch (e) {
  console.error('Нужен jsdom: npm install jsdom');
  process.exit(2);
}

const PUB = path.join(__dirname, '..', 'server', 'public');
const html = fs.readFileSync(path.join(PUB, 'index.html'), 'utf8');
const mapJs = fs.readFileSync(path.join(PUB, 'map.js'), 'utf8');
const appJs = fs.readFileSync(path.join(PUB, 'app.js'), 'utf8');
const problems = [];

const dom = new JSDOM(html.replace(/<script[^>]*><\/script>/g, ''), {
  runScripts: 'outside-only',
  pretendToBeVisual: true,
  url: 'http://localhost:8080/',
});
const { window } = dom;

window.eval = window.eval || eval;
const evalIn = (code) => window.eval(code);
window.addEventListener('error', (e) => problems.push('window.error: ' + e.message));
window.HTMLCanvasElement.prototype.getContext = () => new Proxy({}, { get: () => () => {}, set: () => true });
window.HTMLCanvasElement.prototype.getBoundingClientRect = () => ({ width: 800, height: 380, left: 0, top: 0 });

const NOW = Date.now();
const device = {
  id: 'dev_demo_phone', name: 'Телефон Алисы', model: 'Samsung Galaxy A54', online: true,
  lastSeen: NOW, battery: 62, charging: false, lat: 52.3585, lon: 4.8686, acc: 12,
  locatedAt: NOW, accessibility: true, admin: true, vpn: true, notificationsPermission: true,
  locationPermission: true,
  state: { schoolNow: false, bedtimeNow: false, internetOffNow: false, overDaily: false, limitLeftMin: 84, locked: false },
  usage: { day: '2026-09-20', totalMs: 5760000, list: [{ pkg: 'com.google.android.youtube', label: 'YouTube', ms: 2460000 }] },
  apps: [{ pkg: 'com.google.android.youtube', label: 'YouTube' }, { pkg: 'com.vk.android', label: 'VK' }],
  notifications: [{ app: 'WhatsApp', title: 'Мама', text: 'Позвони', ts: NOW - 60000 }],
  webBlocked: [{ host: 'casino.example', reason: 'gambling', ts: NOW - 3600000 }],
  settings: {
    blockApps: ['com.vk.android'],
    appLimits: { 'com.google.android.youtube': 60 },
    dailyLimitMin: 180,
    schoolMode: { enabled: true, from: '08:00', to: '14:00', days: [1, 2, 3, 4, 5] },
    bedtime: { enabled: true, from: '21:30', to: '07:00' },
    internetOff: { enabled: false, from: '23:00', to: '06:00' },
    webFilter: { adult: true, gambling: true, custom: ['example.com'] },
    alerts: { sos: true, lowBattery: 15, geofence: true, appInstall: true, offlineMinutes: 30 },
    reportSeconds: 300, voiceEnabled: true, kiosk: false,
  },
};

const ROUTES = {
  '/api/v1/status': {
    ok: true, parent: { email: 'demo@modar.family' },
    family: { id: 'fam_1', name: 'Семья Соколовых', pairCode: '123456' },
    devices: [device],
    events: [{ id: 'e1', type: 'web', message: 'Заблокирован сайт: casino.example', level: 'warn', ts: NOW - 120000, deviceId: device.id }],
    places: [{ id: 'p1', name: 'Школа', lat: 52.3585, lon: 4.8686, radius: 300, notifyIn: true, notifyOut: true }],
    offline: [],
  },
  '/api/v1/dashboard': { ok: true, totals: { devices: 1, online: 1, alerts24h: 2, screenTodayMin: 96 }, devices: [] },
  '/api/v1/history': {
    ok: true,
    track: [{ lat: 52.36, lon: 4.86, ts: NOW - 3600000 }, { lat: 52.3585, lon: 4.8686, ts: NOW }],
    usage: [{ pkg: 'com.google.android.youtube', label: 'YouTube', ms: 2460000 }],
  },
  '/api/v1/paircode': { ok: true, code: '777777' },
  '/api/v1/push/key': { ok: true, publicKey: '', enabled: false },
};
const calls = [];

window.fetch = (url, opts) => {
  const clean = String(url).split('?')[0];
  calls.push((opts && opts.method ? opts.method : 'GET') + ' ' + clean);
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(ROUTES[clean] || { ok: true }) });
};
window.localStorage.setItem('mf_token', 'test-token');
window.Notification = { permission: 'default', requestPermission: () => Promise.resolve('denied') };
window.confirm = () => true;
window.alert = () => {};

function run(label, code) {
  try {
    evalIn(code);
  } catch (err) {
    problems.push(label + ': ' + err.message);
  }
}

// 'use strict' + evalMe создаёт отдельную область видимости, поэтому выставляем нужное в window
run('скрипты панели', mapJs + '\n' + appJs.replace("'use strict';", '') + `
window.S = S;
window.__api = { load, fitAll, selectDevice, renderSettings, saveSettings, openPairBox, addPlaceDialog,
  renderEvents, renderUsage, renderPlaces, renderWebBlocked, renderNotes, sendCommand, deviceMenu, enablePush };
window.__map = MiniMap;`);

setTimeout(async () => {
  const steps = [
    ['load()', '__api.load()'],
    ['fitAll()', '__api.fitAll()'],
    ['selectDevice()', '__api.selectDevice("dev_demo_phone")'],
    ['renderSettings()', '__api.renderSettings()'],
    ['saveSettings()', '__api.saveSettings()'],
    ['openPairBox()', '__api.openPairBox()'],
    ['addPlaceDialog()', '__api.addPlaceDialog(52.36, 4.87)'],
    ['renderEvents()', '__api.renderEvents()'],
    ['renderUsage()', '__api.renderUsage()'],
    ['renderPlaces()', '__api.renderPlaces()'],
    ['renderWebBlocked()', '__api.renderWebBlocked()'],
    ['renderNotes()', '__api.renderNotes()'],
    ['sendCommand(message)', '__api.sendCommand("message")'],
    ['sendCommand(alarm)', '__api.sendCommand("alarm")'],
    ['deviceMenu()', '__api.deviceMenu("dev_demo_phone")'],
    ['жесты карты', 'S.map.zoomBy(1); S.map.fitAll(); S.map.panTo(52.36, 4.87); S.map.setCenter(52.36, 4.87, 13)'],
    ['enablePush()', '__api.enablePush()'],
  ];
  for (const [label, code] of steps) {
    run(label, code.indexOf(';') >= 0 ? code : 'void (' + code + ')');
  }
  await new Promise((r) => setTimeout(r, 400));

  const canvas = window.document.getElementById('map');
  const event = (type, x, y) => {
    const node = new window.MouseEvent(type, { bubbles: true, clientX: x, clientY: y });
    Object.defineProperty(node, 'offsetX', { value: x });
    Object.defineProperty(node, 'offsetY', { value: y });
    return node;
  };
  canvas.dispatchEvent(event('mousedown', 100, 100));
  canvas.dispatchEvent(event('mousemove', 150, 130));
  window.dispatchEvent(new window.MouseEvent('mouseup'));
  canvas.dispatchEvent(event('contextmenu', 200, 150));
  window.document.getElementById('zoomIn').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  window.document.getElementById('btnSettingsSave').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  window.document.getElementById('btnPair').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  await new Promise((r) => setTimeout(r, 300));

  // все id, которые ищет app.js, должны существовать в разметке
  const ids = new Set(Array.from(window.document.querySelectorAll('[id]')).map((n) => n.id));
  const dynamic = ['promptInput', 'renameInput', 'placeName', 'placeRadius', 'placeIn', 'placeOut'];
  const used = new Set();
  for (const match of appJs.matchAll(/\$\('([a-zA-Z0-9_]+)'\)/g)) used.add(match[1]);
  for (const id of used) {
    if (!ids.has(id) && dynamic.indexOf(id) < 0) problems.push('в разметке нет элемента #' + id);
  }

  // геометрия карты
  const map = new window.__map(canvas, {});
  let worst = 0;
  for (const [lat, lon] of [[52.3585, 4.8686], [0, 0], [-33.86, 151.2], [60.1, 30.3]]) {
    const screen = map.toScreen(lat, lon);
    const back = map.fromScreen(screen.x, screen.y);
    worst = Math.max(worst, Math.abs(back.lat - lat), Math.abs(back.lon - lon));
  }
  map.zoomBy(-99);
  const minZoom = map.getZoom();
  map.zoomBy(99);
  const maxZoom = map.getZoom();

  console.log('Запросов к API: ' + calls.length + ' (' + new Set(calls).size + ' уникальных)');
  console.log('Элементов разметки с id: ' + ids.size + ', используется в JS: ' + used.size);
  console.log('Точность карты (обратное преобразование): ' + worst.toExponential(2));
  console.log('Пределы зума: ' + minZoom + ' … ' + maxZoom);
  if (problems.length) {
    console.log('\n❌ ПРОБЛЕМЫ:\n' + problems.join('\n'));
    process.exit(1);
  }
  console.log('\n✅ Панель работает без ошибок');
  process.exit(0);
}, 600);
