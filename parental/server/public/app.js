/* Modar Family — панель родителя (ванильный JS, без сборки и зависимостей кроме Leaflet). */
'use strict';

const $ = (id) => document.getElementById(id);
const qs = (sel, root) => (root || document).querySelector(sel);

const LS_TOKEN = 'mf_token';
const S = {
  token: localStorage.getItem(LS_TOKEN) || '',
  parent: null,
  family: null,
  devices: [],
  events: [],
  places: [],
  offline: [],
  selected: localStorage.getItem('mf_device') || '',
  hours: 6,
  track: [],
  map: null,
  layers: { tracks: null, places: null, markers: null, heat: null },
  timer: null,
  call: null,      // { pc, callId, since, stream, poll }
  tour: null,
  demoMode: false,
};

/* ------------------------------------------------------------------ утилиты */

function toast(text, kind) {
  const node = $('toast');
  node.textContent = text;
  node.className = 'toast' + (kind ? ' ' + kind : '');
  node.hidden = false;
  clearTimeout(node._t);
  node._t = setTimeout(() => { node.hidden = true; }, 3800);
}

async function api(path, opts) {
  const options = Object.assign({ method: 'GET' }, opts || {});
  options.headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
  if (S.token) options.headers.Authorization = 'Bearer ' + S.token;
  if (options.body && typeof options.body !== 'string') options.body = JSON.stringify(options.body);
  const res = await fetch(path, options);
  let data = null;
  try { data = await res.json(); } catch (e) { data = null; }
  if (!res.ok || (data && data.ok === false)) {
    const message = (data && data.error) || ('Ошибка ' + res.status);
    if (res.status === 401 && S.token) { logout(true); }
    throw new Error(message);
  }
  return data || { ok: true };
}

function esc(text) {
  return String(text == null ? '' : text)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function ago(ts) {
  if (!ts) return 'нет данных';
  const diff = Date.now() - ts;
  if (diff < 60000) return 'только что';
  if (diff < 3600000) return Math.round(diff / 60000) + ' мин назад';
  if (diff < 86400000) return Math.round(diff / 3600000) + ' ч назад';
  return new Date(ts).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function mins(ms) {
  const m = Math.round((ms || 0) / 60000);
  if (m < 60) return m + ' мин';
  return Math.floor(m / 60) + ' ч ' + (m % 60) + ' мин';
}

function device() {
  return S.devices.find((d) => d.id === S.selected) || S.devices[0] || null;
}

/* --------------------------------------------------------------------- вход */

function showAuth(mode) {
  $('auth').hidden = false;
  $('app').hidden = true;
  setAuthMode(mode || 'login');
}

function setAuthMode(mode) {
  S.authMode = mode;
  for (const tab of document.querySelectorAll('#authTabs .tab')) {
    tab.classList.toggle('active', tab.dataset.mode === mode);
  }
  $('rowName').hidden = mode !== 'register';
  $('rowFamily').hidden = mode !== 'register';
  $('btnAuth').textContent = mode === 'login' ? 'Войти' : mode === 'register' ? 'Создать аккаунт' : 'Войти в демо';
  $('authHint').textContent = mode === 'demo'
    ? 'Демо-доступ: demo@modar.family / demo1234 (создаётся сервером при запуске с DEMO=1).'
    : mode === 'register'
      ? 'Пароль не короче 6 символов. Данные хранятся только на вашем сервере.'
      : 'Нет аккаунта? Нажмите «Регистрация» — семья создаётся за 5 секунд.';
  if (mode === 'demo') {
    $('inEmail').value = 'demo@modar.family';
    $('inPass').value = 'demo1234';
  }
}

function logout(silent) {
  S.token = '';
  localStorage.removeItem(LS_TOKEN);
  if (S.timer) clearInterval(S.timer);
  showAuth('login');
  if (!silent) toast('Вы вышли из аккаунта');
}

async function doAuth(event) {
  event.preventDefault();
  const mode = S.authMode || 'login';
  const email = $('inEmail').value.trim();
  const password = $('inPass').value;
  const body = { email, password };
  const path = mode === 'register' ? '/api/v1/auth/register' : '/api/v1/auth/login';
  if (mode === 'register') {
    body.name = $('inName').value.trim();
    body.familyName = $('inFamily').value.trim();
  }
  $('btnAuth').disabled = true;
  try {
    const data = await api(path, { method: 'POST', body });
    S.token = data.token;
    localStorage.setItem(LS_TOKEN, S.token);
    S.parent = data.parent;
    S.family = data.family;
    await boot();
    toast(mode === 'register' ? 'Семья создана. Подключите телефон ребёнка.' : 'Добро пожаловать!', 'good');
    if (mode === 'register') { S.selected = ''; openPairBox(); }
  } catch (err) {
    toast(err.message, 'bad');
  } finally {
    $('btnAuth').disabled = false;
  }
}

/* ------------------------------------------------------------------ загрузка */

async function boot() {
  $('auth').hidden = true;
  $('app').hidden = false;
  $('btnLogout').hidden = false;
  try {
    const status = await load();
    if (status) {
      $('famLabel').textContent = (S.family && S.family.name) || '';
      S.demoMode = (S.parent && S.parent.email === 'demo@modar.family');
    }
  } catch (err) {
    toast(err.message, 'bad');
  }
  if (!S.map) initMap();
  if (!S.timer) S.timer = setInterval(() => {
    if (!document.hidden) load().catch(() => {});
  }, 15000);
  $('serverUrl').textContent = location.origin;
  subscribePushIfAllowed();
}

async function load() {
  const status = await api('/api/v1/status');
  S.parent = status.parent;
  S.family = status.family;
  S.devices = status.devices || [];
  S.events = status.events || [];
  S.places = status.places || [];
  S.offline = status.offline || [];
  if (!S.selected && S.devices.length) S.selected = S.devices[0].id;
  if (S.selected && !S.devices.some((d) => d.id === S.selected)) S.selected = S.devices.length ? S.devices[0].id : '';
  localStorage.setItem('mf_device', S.selected || '');
  renderDevices();
  renderEvents();
  renderPlaces();
  renderSettings();
  renderUsage();
  renderNotes();
  renderWebBlocked();
  await loadTrack();
  if (S.map && !S.fitted && S.devices.some((d) => d.lat != null)) {
    S.fitted = true;
    S.map.fitAll();
  }
  return status;
}

async function loadTrack() {
  const dev = device();
  if (!dev || !S.map) { drawMap(); return; }
  try {
    const history = await api('/api/v1/history?deviceId=' + encodeURIComponent(dev.id) + '&hours=' + S.hours);
    S.track = history.track || [];
    S.usage = history.usage || [];
  } catch (e) {
    S.track = [];
  }
  drawMap();
}

/* --------------------------------------------------------------------- карта */

function initMap() {
  const canvas = $('map');
  S.map = new MiniMap(canvas, {
    onLongPress: (lat, lon) => addPlaceDialog(lat, lon),
    onMarkerClick: (id) => selectDevice(id),
  });
  $('zoomIn').addEventListener('click', () => S.map.zoomBy(1));
  $('zoomOut').addEventListener('click', () => S.map.zoomBy(-1));
  setRange(S.hours);
  drawMap();
}

function drawMap() {
  if (!S.map) return;
  S.map.setDevices(S.devices, S.selected);
  S.map.setPlaces(S.places);
  S.map.setTrack(S.track || []);

  const dev = device();
  const points = (S.track || []).length;
  const inside = dev && dev.inside && dev.inside.length
    ? S.places.filter((p) => dev.inside.indexOf(p.id) >= 0).map((p) => p.name).join(', ')
    : null;
  $('mapInfo').textContent = dev
    ? (dev.lat == null ? 'Геопозиция ещё не получена'
      : 'Точек за период: ' + points + ' • точность ±' + (dev.acc == null ? '?' : Math.round(dev.acc)) + ' м' +
        ' • обновлено ' + ago(dev.locatedAt || dev.lastSeen) +
        (inside ? ' • сейчас в зоне: ' + inside : ''))
    : 'Нет подключённых устройств';
}

function fitAll() {
  if (S.map) S.map.fitAll();
}

/* ---------------------------------------------------------------- устройства */

function renderDevices() {
  const host = $('devices');
  if (!S.devices.length) {
    host.innerHTML = '<div class="empty">Пока нет ни одного телефона. Нажмите «Подключить телефон» — ' +
      'появится код, который нужно ввести в приложении ребёнка.</div>';
    return;
  }
  host.innerHTML = S.devices.map((d) => {
    const st = d.state || {};
    const limit = st.limitLeftMin;
    const chips = [];
    chips.push('<span class="chip ' + (d.online ? 'ok' : 'bad') + '"><span class="dot ' + (d.online ? 'on' : '') + '"></span>' + (d.online ? 'на связи' : 'нет связи') + '</span>');
    if (d.battery != null) chips.push('<span class="chip' + (d.battery <= 15 ? ' bad' : d.battery <= 30 ? ' warn' : '') + '">🔋 ' + d.battery + '%' + (d.charging ? ' ⚡' : '') + '</span>');
    if (d.usage) chips.push('<span class="chip">⏱ ' + mins(d.usage.totalMs) + ' экрана</span>');
    if (st.bedtimeNow) chips.push('<span class="chip warn">😴 режим сна</span>');
    if (st.schoolNow) chips.push('<span class="chip warn">🏫 школа</span>');
    if (st.internetOffNow) chips.push('<span class="chip bad">📵 интернет выключен</span>');
    if (limit != null && limit <= 10) chips.push('<span class="chip warn">⏳ осталось ' + limit + ' мин</span>');
    if (!d.accessibility) chips.push('<span class="chip bad">⚠️ нет «Специальных возможностей»</span>');
    if (!d.vpn) chips.push('<span class="chip warn">🌐 фильтр сайтов выкл.</span>');
    if (d.locationPermission === false) chips.push('<span class="chip bad">📍 нет доступа к геопозиции</span>');
    if (!d.notificationsPermission) chips.push('<span class="chip">🔔 нет доступа к уведомлениям</span>');
    if (!d.online) chips.push('<span class="chip">🕓 ' + ago(d.lastSeen) + '</span>');
    return '<div class="device" data-id="' + d.id + '">' +
      '<div class="device-top"><div><b>' + esc(d.name) + '</b><div class="sub">' + esc(d.model || '') + '</div></div>' +
      '<button class="ghost small" data-act="menu">⋯</button></div>' +
      '<div class="chips">' + chips.join('') + '</div>' +
      '</div>';
  }).join('');

  for (const node of host.querySelectorAll('.device')) {
    node.addEventListener('click', (e) => {
      const id = node.dataset.id;
      if (e.target.closest('[data-act="menu"]')) { deviceMenu(id); return; }
      selectDevice(id);
    });
  }
}

function selectDevice(id) {
  S.selected = id;
  localStorage.setItem('mf_device', id);
  renderDevices();
  renderSettings();
  renderNotes();
  renderWebBlocked();
  loadTrack().catch(() => {});
  if (S.map) {
    const dev = S.devices.find((d) => d.id === id);
    if (dev && dev.lat != null) S.map.panTo(dev.lat, dev.lon);
  }
}

function deviceMenu(id) {
  const dev = S.devices.find((d) => d.id === id);
  if (!dev) return;
  openModal('Устройство «' + dev.name + '»',
    '<label class="field"><span>Название</span><input type="text" id="renameInput" value="' + esc(dev.name) + '"></label>' +
    '<div class="muted small">Модель: ' + esc(dev.model || '—') + ' • версия приложения: ' + esc(dev.version || '—') + '<br>' +
    '«Специальные возможности»: ' + (dev.accessibility ? 'включены' : 'выключены') + ' • права администратора: ' + (dev.admin ? 'есть' : 'нет') +
    '<br>Последний отчёт: ' + ago(dev.lastSeen) + '</div>',
    [
      { label: 'Сохранить', cls: 'primary', onClick: async () => {
        await api('/api/v1/device/name', { method: 'POST', body: { deviceId: id, name: $('renameInput').value } });
        closeModal(); await load(); toast('Название обновлено', 'good');
      } },
      { label: 'Отвязать', cls: 'ghost', onClick: async () => {
        if (!confirm('Отвязать устройство? Телефон ребёнка перестанет отправлять данные.')) return;
        await api('/api/v1/device/remove', { method: 'POST', body: { deviceId: id } });
        closeModal(); S.selected = ''; await load(); toast('Устройство отвязано');
      } },
    ]);
}

/* -------------------------------------------------------------------- события */

function renderEvents() {
  const host = $('events');
  const filter = $('eventFilter');
  const current = filter.value;
  const ids = unique(S.devices.map((d) => d.name + '\u0000' + d.id));
  if (filter.dataset.filled !== String(ids.length)) {
    filter.innerHTML = '<option value="">Все устройства</option>' +
      ids.map((pair) => {
        const [name, id] = pair.split('\u0000');
        return '<option value="' + esc(id) + '"' + (id === current ? ' selected' : '') + '>' + esc(name) + '</option>';
      }).join('');
    filter.dataset.filled = String(ids.length);
  }
  const deviceId = filter.value;
  const list = (S.events || []).filter((e) => !deviceId || e.deviceId === deviceId);
  if (!list.length) {
    host.innerHTML = '<div class="empty">Событий пока нет. Как только телефон ребёнка пришлёт отчёт, здесь появятся ' +
      'уведомления, блокировки сайтов, геозоны и тревоги.</div>';
    return;
  }
  host.innerHTML = list.slice(0, 60).map((e) => '<div class="item ' + esc(e.level) + '">' +
    '<b>' + icon(e.type) + ' ' + esc(e.message) + '</b>' +
    '<div class="t">' + ago(e.ts) + ' • ' + esc(deviceName(e.deviceId)) + '</div></div>').join('');
}

function icon(type) {
  return ({ sos: '🆘', geofence: '📍', web: '🚫', battery: '🔋', offline: '📴', cmd: '🎮', pair: '🔗',
    notification: '🔔', camera: '🤳', call: '📞', app: '📱', limit: '⏳', settings: '⚙️', info: 'ℹ️' })[type] || '•';
}

function deviceName(id) {
  const dev = S.devices.find((d) => d.id === id);
  return dev ? dev.name : 'семья';
}

function unique(list) {
  return list.filter((item, index) => list.indexOf(item) === index);
}

function renderNotes() {
  const dev = device();
  const host = $('notes');
  const list = (dev && dev.notifications) || [];
  host.innerHTML = list.length
    ? list.slice(0, 30).map((n) => '<div class="item"><b>' + esc(n.title || n.text) + '</b>' +
      (n.title && n.text ? '<div class="small">' + esc(n.text) + '</div>' : '') +
      '<div class="t">' + esc(n.app || n.pkg || '') + ' • ' + ago(n.ts) + '</div></div>').join('')
    : '<div class="empty">Уведомления появятся, когда телефон ребёнка пришлёт их список. Для этого в приложении ' +
      'нужно выдать доступ к уведомлениям.</div>';
}

function renderWebBlocked() {
  const dev = device();
  const host = $('webBlocked');
  const list = (dev && dev.webBlocked) || [];
  host.innerHTML = list.length
    ? list.slice(0, 30).map((w) => '<div class="item warn"><b>🚫 ' + esc(w.host) + '</b><div class="t">' +
      (w.reason === 'adult' ? 'взрослый контент' : w.reason === 'gambling' ? 'азартные игры' : 'из списка родителей') +
      ' • ' + ago(w.ts) + '</div></div>').join('')
    : '<div class="empty">Заблокированных сайтов пока нет. Фильтр включится, когда в приложении ребёнка будет ' +
      'активна служба «Специальные возможности».</div>';
}

/* ---------------------------------------------------------------- экранное время */

function renderUsage() {
  const dev = device();
  const host = $('usageChart');
  const list = (dev && dev.usage && dev.usage.list) || [];
  $('usageTotal').textContent = dev && dev.usage ? 'всего ' + mins(dev.usage.totalMs) : 'нет данных';
  if (!list.length) {
    host.innerHTML = '<div class="empty">Статистика появится после первого отчёта с телефона ребёнка.</div>';
    return;
  }
  const max = Math.max.apply(null, list.map((u) => u.ms).concat([1]));
  const limits = (dev.settings && dev.settings.appLimits) || {};
  host.innerHTML = list.slice(0, 12).map((u) => {
    const limit = limits[u.pkg];
    const cls = limit && u.ms >= limit * 60000 ? 'bad' : 'ok';
    const width = Math.max(3, Math.round((u.ms / max) * 100));
    return '<div class="usage-row"><div class="name">' + esc(u.label || u.pkg) + '</div>' +
      '<div class="bar"><i class="' + cls + '" style="width:' + width + '%"></i></div>' +
      '<div class="val">' + mins(u.ms) + (limit ? ' / ' + limit + 'м' : '') + '</div></div>';
  }).join('');
}

/* -------------------------------------------------------------------- команды */

async function sendCommand(cmd, arg) {
  const dev = device();
  if (!dev) { toast('Сначала подключите телефон ребёнка', 'bad'); return; }
  try {
    if (cmd === 'message') {
      const text = await modalPrompt('Сообщение ребёнку', 'Текст сообщения', arg || '', 'Текст появится во всплывающем окне на телефоне ребёнка.');
      if (text == null) return;
      arg = text;
    }
    if (cmd === 'snapshot' || cmd === 'tour') {
      const seconds = await modalPrompt('Снимок экрана', 'Сколько секунд снимать (1–60)', cmd === 'tour' ? '30' : '1',
        'Картинка появится в блоке «Живой экран». На Android 11+ для снимка экрана нужно разрешение MediaProjection — ' +
        'ребёнок подтвердит его один раз на телефоне.');
      if (seconds == null) return;
      arg = String(Math.max(1, Math.min(60, Number(seconds) || 1)));
      if (cmd === 'tour') { S.tour = { deviceId: dev.id, until: Date.now() + Number(arg) * 1000 }; pollTour(); }
    }
    if (cmd === 'call') { await startCall(dev); return; }
    await api('/api/v1/command', { method: 'POST', body: { deviceId: dev.id, cmd, arg } });
    toast('Команда отправлена: ' + cmdName(cmd), 'good');
  } catch (err) {
    toast(err.message, 'bad');
  }
}

function cmdName(cmd) {
  return ({ locate: 'где сейчас', alarm: 'сирена', lock: 'блокировка', unlock: 'разблокировка',
    message: 'сообщение', photo: 'фото с камеры', snapshot: 'снимок экрана', tour: 'экскурсия',
    torch: 'фонарик', ring: 'звонок на телефон', sync: 'синхронизация', refresh: 'запрос отчёта' })[cmd] || cmd;
}

/* --------------------------------------------------------------- живой экран */

async function pollTour() {
  const dev = device();
  if (!dev) return;
  try {
    const res = await api('/api/v1/image/latest?deviceId=' + encodeURIComponent(dev.id) + '&kind=screen');
    if (res.image && res.image.data) {
      $('screenSlot').innerHTML = '<img src="' + res.image.data + '" alt="экран"><div class="small muted" style="margin-top:6px">' +
        ago(res.image.ts) + '</div>';
      $('screenState').textContent = 'получено ' + ago(res.image.ts);
    } else {
      $('screenState').textContent = 'ожидание картинки…';
    }
  } catch (e) { /* тихо */ }
  const active = S.tour && S.tour.until > Date.now();
  if (active) setTimeout(pollTour, 2000);
  else if (S.tour) { S.tour = null; $('screenState').textContent = 'сессия завершена'; }
}

/* -------------------------------------------------------------------- звонок */

async function startCall(dev) {
  if (!window.RTCPeerConnection) { toast('Браузер не поддерживает WebRTC-звонки', 'bad'); return; }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    const pc = new RTCPeerConnection({
      iceServers: [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }],
    });
    S.call = { pc, stream, since: 0, callId: null, poll: null };
    stream.getTracks().forEach((track) => pc.addTrack(track, stream));
    const remote = new Audio();
    remote.autoplay = true;
    pc.ontrack = (event) => { remote.srcObject = event.streams[0]; remote.play().catch(() => {}); };
    pc.onicecandidate = (event) => {
      if (event.candidate && S.call && S.call.callId) {
        api('/api/v1/call/ice', { method: 'POST', body: { callId: S.call.callId, side: 'parent', candidate: JSON.stringify(event.candidate) } }).catch(() => {});
      }
    };
    const offer = await pc.createOffer({ offerToReceiveAudio: true });
    await pc.setLocalDescription(offer);
    const started = await api('/api/v1/call/start', { method: 'POST', body: { deviceId: dev.id, offer: JSON.stringify(offer) } });
    S.call.callId = started.callId;
    $('callOverlay').hidden = false;
    $('callTitle').textContent = 'Звонок: ' + dev.name;
    $('callStatus').textContent = 'Ждём, пока ребёнок примет вызов…';
    pollCall();
  } catch (err) {
    toast('Микрофон недоступен: ' + err.message, 'bad');
    endCall();
  }
}

async function pollCall() {
  const call = S.call;
  if (!call || !call.callId) return;
  try {
    const data = await api('/api/v1/call/poll?callId=' + call.callId + '&since=' + call.since);
    call.since = data.ts;
    if (data.answer && call.pc.signalingState === 'have-local-offer') {
      await call.pc.setRemoteDescription(JSON.parse(data.answer));
      $('callStatus').textContent = 'Разговор идёт…';
    }
    for (const item of data.ice || []) {
      if (item.from === 'child') {
        try { await call.pc.addIceCandidate(JSON.parse(item.candidate)); } catch (e) { /* ignore */ }
      }
    }
    if (data.state === 'rejected') { toast('Ребёнок отклонил вызов', 'bad'); endCall(); return; }
    if (data.state === 'missed') { toast('Ребёнок не ответил', 'bad'); endCall(); return; }
    if (data.state === 'ended') { toast('Вызов завершён'); endCall(); return; }
    call.poll = setTimeout(pollCall, 1500);
  } catch (err) {
    call.poll = setTimeout(pollCall, 3000);
  }
}

function endCall() {
  const call = S.call;
  S.call = null;
  $('callOverlay').hidden = true;
  if (!call) return;
  if (call.poll) clearTimeout(call.poll);
  if (call.callId) api('/api/v1/call/end', { method: 'POST', body: { callId: call.callId } }).catch(() => {});
  if (call.stream) call.stream.getTracks().forEach((t) => t.stop());
  if (call.pc) call.pc.close();
}

/* ------------------------------------------------------------------ геозоны */

function renderPlaces() {
  const host = $('places');
  if (!S.places.length) {
    host.innerHTML = '<div class="empty">Геозон пока нет. Добавьте «Школа», «Дом», «Секция» — и получайте ' +
      'уведомление, когда ребёнок приходит или уходит.</div>';
    return;
  }
  host.innerHTML = S.places.map((p) => '<div class="item" data-id="' + p.id + '">' +
    '<b>🏠 ' + esc(p.name) + '</b>' +
    '<div class="t">радиус ' + p.radius + ' м • ' + (p.notifyIn ? 'вход' : '—') + ' / ' + (p.notifyOut ? 'выход' : '—') +
    ' • <a href="#" data-act="del" data-id="' + p.id + '">удалить</a></div></div>').join('');
  for (const link of host.querySelectorAll('[data-act="del"]')) {
    link.addEventListener('click', async (e) => {
      e.preventDefault();
      await api('/api/v1/places/remove', { method: 'POST', body: { id: link.dataset.id } });
      await load();
      toast('Геозона удалена');
    });
  }
}

function addPlaceDialog(lat, lon) {
  const center = (S.map && S.map.getCenter()) || { lat: 52.3702, lon: 4.8952 };
  const point = lat != null ? { lat, lon } : { lat: center.lat, lon: center.lon };
  openModal('Новое место (геозона)',
    '<label class="field"><span>Название</span><input id="placeName" placeholder="Школа"></label>' +
    '<label class="field"><span>Радиус, метров</span><input id="placeRadius" type="number" value="300" min="50" max="5000"></label>' +
    '<div class="muted small">Координаты: ' + Number(point.lat).toFixed(5) + ', ' + Number(point.lon).toFixed(5) + '</div>' +
    '<div class="inline"><input type="checkbox" id="placeIn" checked><span>Уведомлять о приходе</span></div>' +
    '<div class="inline"><input type="checkbox" id="placeOut" checked><span>Уведомлять об уходе</span></div>',
    [{ label: 'Добавить', cls: 'primary', onClick: async () => {
      await api('/api/v1/places', { method: 'POST', body: {
        name: $('placeName').value || 'Место',
        lat: point.lat, lon: point.lon,
        radius: Number($('placeRadius').value) || 300,
        notifyIn: $('placeIn').checked, notifyOut: $('placeOut').checked,
      } });
      closeModal(); await load(); toast('Геозона добавлена', 'good');
    } }]);
  $('placeName').focus();
}

/* ------------------------------------------------------------------ настройки */

function renderSettings() {
  const dev = device();
  const select = $('settingsDevice');
  select.innerHTML = S.devices.map((d) => '<option value="' + d.id + '"' + (d.id === S.selected ? ' selected' : '') + '>' +
    esc(d.name) + '</option>').join('');
  const host = $('settingsHost');
  if (!dev) { host.innerHTML = '<div class="empty">Подключите телефон ребёнка — здесь появятся лимиты и блокировки.</div>'; return; }
  const s = dev.settings || {};
  const apps = mergeApps(dev);

  host.innerHTML = '<div class="settings-grid">' +

    '<div class="box"><h4>📱 Приложения</h4>' +
      '<div class="small muted" style="margin-bottom:8px">Отметьте «блок» — приложение не откроется. ' +
      'В поле минут укажите лимит на день (0 — без лимита).</div>' +
      '<div class="applist">' + (apps.length ? apps.map((a) =>
        '<div class="approw" data-pkg="' + esc(a.pkg) + '">' +
        '<input type="checkbox" class="blk"' + ((s.blockApps || []).indexOf(a.pkg) >= 0 ? ' checked' : '') +
        '><span class="nm">' + esc(a.label) + '</span>' +
        '<input type="number" class="lim" min="0" max="600" value="' + (((s.appLimits || {})[a.pkg]) || 0) + '" title="минут в день">' +
        '</div>').join('') : '<div class="empty">Список приложений придёт с телефона ребёнка в первом отчёте.</div>') +
      '</div></div>' +

    '<div class="box"><h4>⏳ Лимиты и режимы</h4>' +
      '<label class="field"><span>Общий лимит экранного времени в день, минут (0 — выключен)</span>' +
        '<input id="setDaily" type="number" min="0" max="1440" value="' + (s.dailyLimitMin || 0) + '"></label>' +
      '<div class="inline"><input type="checkbox" id="setNewApps"' + (s.blockNewApps ? ' checked' : '') + '>' +
        '<span>Запретить новые приложения</span></div>' +
      '<div class="inline"><input type="checkbox" id="setVoice"' + (s.voiceEnabled !== false ? ' checked' : '') + '>' +
        '<span>Разрешить голосовые звонки родителю</span></div>' +
      '<div class="inline"><input type="checkbox" id="setKiosk"' + (s.kiosk ? ' checked' : '') + '>' +
        '<span>Режим «только учёба» (экстренный)</span></div>' +
      '<label class="field"><span>Присылать отчёты</span><select id="setReport">' +
        [['60', 'раз в минуту'], ['300', 'раз в 5 минут'], ['900', 'раз в 15 минут'], ['1800', 'раз в 30 минут']]
          .map(([v, t]) => '<option value="' + v + '"' + (String(s.reportSeconds || 300) === v ? ' selected' : '') + '>' + t + '</option>').join('') +
      '</select></label>' +
      '</div>' +

    windowBox('🏫 Школа (блокировка развлечений)', 'school') +
    windowBox('😴 Ночной режим', 'bedtime') +
    windowBox('📵 Интернет по расписанию', 'internetOff') +

    '<div class="box"><h4>🚫 Сайты и контент</h4>' +
      '<div class="inline"><input type="checkbox" id="setAdult"' + ((s.webFilter || {}).adult !== false ? ' checked' : '') + '>' +
        '<span>Блокировать взрослый контент (18+)</span></div>' +
      '<div class="inline"><input type="checkbox" id="setGambling"' + ((s.webFilter || {}).gambling !== false ? ' checked' : '') + '>' +
        '<span>Блокировать азартные игры и казино</span></div>' +
      '<label class="field"><span>Свои запрещённые сайты (по одному в строке)</span>' +
        '<textarea id="setCustomSites">' + esc(((s.webFilter || {}).custom || []).join('\n')) + '</textarea></label>' +
      '</div>' +

    '<div class="box"><h4>🔔 Уведомления родителю</h4>' +
      '<div class="inline"><input type="checkbox" id="setSos"' + (((s.alerts || {}).sos !== false) ? ' checked' : '') + '>' +
        '<span>Тревога SOS от ребёнка</span></div>' +
      '<div class="inline"><input type="checkbox" id="setGeo"' + (((s.alerts || {}).geofence !== false) ? ' checked' : '') + '>' +
        '<span>Приход и уход из мест</span></div>' +
      '<div class="inline"><input type="checkbox" id="setInstall"' + (((s.alerts || {}).appInstall !== false) ? ' checked' : '') + '>' +
        '<span>Установка новых приложений</span></div>' +
      '<label class="field"><span>Предупреждать, если заряд ниже (%)</span>' +
        '<input id="setBattery" type="number" min="0" max="100" value="' + (((s.alerts || {}).lowBattery) || 15) + '"></label>' +
      '<label class="field"><span>Тревога «нет связи» более, минут</span>' +
        '<input id="setOffline" type="number" min="5" max="1440" value="' + (((s.alerts || {}).offlineMinutes) || 30) + '"></label>' +
      '</div>' +
    '</div>';
}

function windowBox(title, key) {
  const dev = device();
  const w = ((dev.settings || {})[key]) || { enabled: false, from: '08:00', to: '14:00', days: [1, 2, 3, 4, 5] };
  const days = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
  return '<div class="box" data-window="' + key + '"><h4>' + title + '</h4>' +
    '<div class="inline"><input type="checkbox" class="w-enabled"' + (w.enabled ? ' checked' : '') + '><span>Включено</span></div>' +
    '<div class="time-row"><input type="time" class="w-from" value="' + esc(w.from) + '"> — ' +
      '<input type="time" class="w-to" value="' + esc(w.to) + '"></div>' +
    '<div class="days" style="margin-top:9px">' + days.map((d, i) =>
      '<label class="' + ((w.days || []).indexOf(i) >= 0 ? 'on' : '') + '"><input type="checkbox" value="' + i + '"' +
      ((w.days || []).indexOf(i) >= 0 ? ' checked' : '') + '>' + d + '</label>').join('') + '</div></div>';
}

function mergeApps(dev) {
  const map = new Map();
  for (const a of dev.apps || []) map.set(a.pkg, a.label || a.pkg);
  for (const u of (dev.usage && dev.usage.list) || []) if (!map.has(u.pkg)) map.set(u.pkg, u.label || u.pkg);
  for (const pkg of (dev.settings && dev.settings.blockApps) || []) if (!map.has(pkg)) map.set(pkg, pkg);
  return Array.from(map.entries()).map(([pkg, label]) => ({ pkg, label })).sort((a, b) => a.label.localeCompare(b.label));
}

async function saveSettings() {
  const dev = device();
  if (!dev) return;
  const blockApps = [];
  const appLimits = {};
  for (const row of document.querySelectorAll('#settingsHost .approw')) {
    const pkg = row.dataset.pkg;
    if (qs('.blk', row).checked) blockApps.push(pkg);
    const limit = Number(qs('.lim', row).value) || 0;
    if (limit > 0) appLimits[pkg] = limit;
  }
  const settings = {
    blockApps,
    appLimits,
    dailyLimitMin: Number($('setDaily').value) || 0,
    blockNewApps: $('setNewApps').checked,
    voiceEnabled: $('setVoice').checked,
    kiosk: $('setKiosk').checked,
    reportSeconds: Number($('setReport').value) || 300,
    schoolMode: readWindow('school'),
    bedtime: readWindow('bedtime'),
    internetOff: readWindow('internetOff'),
    webFilter: {
      adult: $('setAdult').checked,
      gambling: $('setGambling').checked,
      custom: $('setCustomSites').value.split('\n').map((s) => s.trim()).filter(Boolean),
    },
    alerts: {
      sos: $('setSos').checked,
      geofence: $('setGeo').checked,
      appInstall: $('setInstall').checked,
      lowBattery: Number($('setBattery').value) || 0,
      offlineMinutes: Number($('setOffline').value) || 30,
    },
  };
  try {
    await api('/api/v1/settings', { method: 'POST', body: { deviceId: dev.id, settings } });
    toast('Настройки отправлены на телефон ребёнка', 'good');
    await load();
  } catch (err) {
    toast(err.message, 'bad');
  }
}

function readWindow(key) {
  const box = document.querySelector('[data-window="' + key + '"]');
  return {
    enabled: qs('.w-enabled', box).checked,
    from: qs('.w-from', box).value || '08:00',
    to: qs('.w-to', box).value || '14:00',
    days: Array.from(box.querySelectorAll('.days input:checked')).map((i) => Number(i.value)),
  };
}

/* ------------------------------------------------------------------ сопряжение */

async function openPairBox() {
  try {
    const data = await api('/api/v1/paircode', { method: 'POST' });
    $('pairCode').textContent = data.code;
    $('pairBox').hidden = false;
    $('apkLink').href = '/app/modar-family.apk';
    $('apkLink').textContent = 'Modar Family (APK)';
  } catch (err) {
    toast(err.message, 'bad');
  }
}

/* -------------------------------------------------------------------- модалка */

function openModal(title, bodyHtml, buttons) {
  $('modalTitle').textContent = title;
  $('modalBody').innerHTML = bodyHtml;
  const foot = $('modalFoot');
  foot.innerHTML = '';
  for (const btn of buttons || []) {
    const node = document.createElement('button');
    node.className = btn.cls || 'ghost';
    node.textContent = btn.label;
    node.addEventListener('click', () => Promise.resolve(btn.onClick()).catch((e) => toast(e.message, 'bad')));
    foot.appendChild(node);
  }
  if (!buttons || !buttons.length) {
    const node = document.createElement('button');
    node.className = 'ghost';
    node.textContent = 'Закрыть';
    node.addEventListener('click', closeModal);
    foot.appendChild(node);
  }
  $('modal').hidden = false;
}

function closeModal() { $('modal').hidden = true; }

function modalPrompt(title, label, value, hint) {
  return new Promise((resolve) => {
    openModal(title,
      '<label class="field"><span>' + esc(label) + '</span><input id="promptInput" value="' + esc(value || '') + '"></label>' +
      (hint ? '<div class="muted small">' + esc(hint) + '</div>' : ''),
      [
        { label: 'Отмена', cls: 'ghost', onClick: () => { closeModal(); resolve(null); } },
        { label: 'Отправить', cls: 'primary', onClick: () => { const v = $('promptInput').value; closeModal(); resolve(v); } },
      ]);
    setTimeout(() => { const input = $('promptInput'); if (input) { input.focus(); input.select(); } }, 50);
  });
}

/* -------------------------------------------------------------------- push */

function subscribePushIfAllowed() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return;
  if (Notification.permission === 'granted') subscribePush().catch(() => {});
}

async function enablePush() {
  if (!('serviceWorker' in navigator)) { toast('Браузер не поддерживает уведомления', 'bad'); return; }
  try {
    const permission = await Notification.requestPermission();
    if (permission !== 'granted') { toast('Разрешение на уведомления не выдано', 'bad'); return; }
    const result = await subscribePush();
    toast(result ? 'Уведомления включены' : 'Сервер не настроен на уведомления (нет VAPID-ключей)', result ? 'good' : 'bad');
  } catch (err) {
    toast('Не удалось включить уведомления: ' + err.message, 'bad');
  }
}

async function subscribePush() {
  const reg = await navigator.serviceWorker.register('/sw.js');
  const keyData = await api('/api/v1/push/key');
  if (!keyData.enabled) return false;
  const sub = await reg.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(keyData.publicKey),
  });
  await api('/api/v1/push/subscribe', { method: 'POST', body: { subscription: sub.toJSON() } });
  return true;
}

function urlBase64ToUint8Array(base64) {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4);
  const raw = atob((base64 + padding).replace(/-/g, '+').replace(/_/g, '/'));
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

/* ---------------------------------------------------------------- привязка UI */

function setRange(hours) {
  S.hours = hours || 6;
  for (const button of document.querySelectorAll('#rangeSeg button')) {
    button.classList.toggle('active', Number(button.dataset.hours) === S.hours);
  }
}

function wire() {
  $('authForm').addEventListener('submit', doAuth);
  for (const tab of document.querySelectorAll('#authTabs .tab')) {
    tab.addEventListener('click', () => setAuthMode(tab.dataset.mode));
  }
  $('btnLogout').addEventListener('click', () => logout());
  $('btnRefresh').addEventListener('click', () => load().then(() => toast('Обновлено')).catch((e) => toast(e.message, 'bad')));
  $('btnNotify').addEventListener('click', enablePush);
  $('btnPair').addEventListener('click', openPairBox);
  $('btnNewCode').addEventListener('click', openPairBox);
  $('btnClearAlerts').addEventListener('click', async () => {
    const dev = device();
    if (!dev) return;
    await api('/api/v1/alerts/clear', { method: 'POST', body: { deviceId: dev.id } });
    toast('Команда сброса тревоги отправлена');
  });
  $('eventFilter').addEventListener('change', renderEvents);
  $('settingsDevice').addEventListener('change', (e) => selectDevice(e.target.value));
  $('btnSettingsSave').addEventListener('click', saveSettings);
  $('btnSettingsReload').addEventListener('click', () => { renderSettings(); toast('Изменения отменены'); });
  $('btnAddPlace').addEventListener('click', () => addPlaceDialog());
  $('btnFit').addEventListener('click', fitAll);
  $('btnTourStart').addEventListener('click', () => sendCommand('tour'));
  $('btnTourStop').addEventListener('click', () => { S.tour = null; $('screenState').textContent = 'остановлено'; });
  $('modalClose').addEventListener('click', closeModal);
  $('modal').addEventListener('click', (e) => { if (e.target === $('modal')) closeModal(); });
  $('callHangup').addEventListener('click', endCall);

  for (const btn of document.querySelectorAll('#cmdGrid .cmd')) {
    btn.addEventListener('click', () => sendCommand(btn.dataset.cmd));
  }
  for (const btn of document.querySelectorAll('#rangeSeg button')) {
    btn.addEventListener('click', () => {
      setRange(Number(btn.dataset.hours));
      loadTrack().then(fitAll).catch(() => {});
    });
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden && S.token) load().catch(() => {}); });
}

/* --------------------------------------------------------------------- старт */

wire();
if (S.token) {
  boot().catch(() => showAuth('login'));
} else {
  showAuth('login');
}
