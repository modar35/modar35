'use strict';
/*
 * Панель сервера лаунчера: серверы (с живым опросом по SA-MP Query), мод-паки,
 * новости, сборки клиента и обновления приложения.
 */

const state = {
  token: localStorage.getItem('modar_token') || '',
  view: 'servers',
  data: { servers: [], mods: [], news: [], clients: [], launcher: null, limits: {} },
  live: {},
};

const $ = (id) => document.getElementById(id);

function escapeHtml(text) {
  return String(text === undefined || text === null ? '' : text)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function toast(message, isError) {
  const box = $('toast');
  box.textContent = message;
  box.className = 'toast' + (isError ? ' error' : '');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => box.classList.add('hidden'), 3500);
}

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (state.token) {
    headers.Authorization = 'Bearer ' + state.token;
  }
  let body = options.body;
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  const response = await fetch(path, { method: options.method || 'GET', headers, body });
  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch (err) {
    throw new Error('Ответ сервера не разобран: ' + text.slice(0, 120));
  }
  if (!response.ok || data.ok === false) {
    throw new Error(data.error || ('Ошибка ' + response.status));
  }
  return data;
}

/* ------------------------------------------------------------------ вход */

async function login() {
  const password = $('password').value;
  try {
    const data = await api('/api/v1/admin/login', { method: 'POST', body: { password } });
    state.token = data.token;
    localStorage.setItem('modar_token', data.token);
    $('login-error').textContent = '';
    showApp();
    await loadState();
  } catch (err) {
    $('login-error').textContent = err.message;
  }
}

function logout() {
  state.token = '';
  localStorage.removeItem('modar_token');
  $('app').classList.add('hidden');
  $('login').classList.remove('hidden');
}

function showApp() {
  $('login').classList.add('hidden');
  $('app').classList.remove('hidden');
}

/* ------------------------------------------------------------------ состояние */

async function loadState() {
  try {
    const data = await api('/api/v1/admin/state');
    state.data.servers = data.servers || [];
    state.data.mods = data.mods || [];
    state.data.news = data.news || [];
    state.data.clients = data.clients || [];
    state.data.launcher = data.launcher || {};
    state.data.limits = data.limits || {};
    $('status-line').textContent = 'серверов: ' + state.data.servers.length
      + ' · модов: ' + state.data.mods.length
      + ' · новостей: ' + state.data.news.length;
    render();
  } catch (err) {
    if (String(err.message).includes('вход')) {
      logout();
    } else {
      toast(err.message, true);
    }
  }
}

/* ------------------------------------------------------------------ отрисовка */

const TABS = [
  ['servers', 'Серверы'],
  ['mods', 'Мод-паки'],
  ['news', 'Новости'],
  ['clients', 'Клиенты'],
  ['launcher', 'Лаунчер'],
  ['settings', 'Настройки'],
];

function render() {
  const nav = $('tabs');
  nav.innerHTML = TABS.map(([key, title]) =>
    '<button data-tab="' + key + '" class="' + (state.view === key ? 'active' : '') + '">' + title + '</button>').join('');
  nav.querySelectorAll('button').forEach((button) => {
    button.onclick = () => {
      state.view = button.dataset.tab;
      render();
    };
  });

  const view = $('view');
  if (state.view === 'servers') {
    view.innerHTML = viewServers();
    bindServers();
  } else if (state.view === 'mods') {
    view.innerHTML = viewMods();
    bindMods();
  } else if (state.view === 'news') {
    view.innerHTML = viewNews();
    bindNews();
  } else if (state.view === 'clients') {
    view.innerHTML = viewClients();
    bindClients();
  } else if (state.view === 'launcher') {
    view.innerHTML = viewLauncher();
    bindLauncher();
  } else {
    view.innerHTML = viewSettings();
    bindSettings();
  }
}

/* ------------------------------------------------------------------ серверы */

function liveTag(host, port) {
  const live = state.live[host + ':' + port];
  if (!live) {
    return '<span class="tag">не опрошен</span>';
  }
  if (!live.online) {
    return '<span class="tag off">оффлайн</span>';
  }
  return '<span class="tag ok">' + live.players + '/' + live.maxPlayers + ' · ' + live.rtt + ' мс</span>';
}

function viewServers() {
  const rows = state.data.servers.map((server) => {
    const live = state.live[server.host + ':' + server.port];
    const title = live && live.online && live.hostname ? live.hostname : (server.name || '(без названия)');
    return '<tr>'
      + '<td><b>' + escapeHtml(title) + '</b>'
      + (server.name && live && live.hostname ? '<div class="muted small">' + escapeHtml(server.name) + '</div>' : '')
      + (server.note ? '<div class="muted small">' + escapeHtml(server.note) + '</div>' : '')
      + '</td>'
      + '<td><code>' + escapeHtml(server.host + ':' + server.port) + '</code>'
      + (server.password ? ' <span class="tag warn">пароль</span>' : '') + '</td>'
      + '<td>' + escapeHtml(server.gamemode || '—') + '</td>'
      + '<td>' + liveTag(server.host, server.port) + '</td>'
      + '<td class="actions">'
      + '<button class="small" data-query="' + server.id + '">Опросить</button> '
      + '<button class="small" data-edit="' + server.id + '">Изменить</button> '
      + '<button class="small danger" data-remove="' + server.id + '">Удалить</button>'
      + '</td></tr>';
  }).join('');

  return '<div class="card">'
    + '<h2>Серверы SA-MP в лаунчере</h2>'
    + '<p class="muted small">Эти адреса приложение показывает игрокам. Статус — настоящий опрос по протоколу SA-MP Query (UDP), кэш 20 секунд.</p>'
    + '<div class="buttons"><button id="refresh-live">Обновить статусы</button></div>'
    + (rows
      ? '<table><thead><tr><th>Сервер</th><th>Адрес</th><th>Режим</th><th>Онлайн</th><th></th></tr></thead><tbody>' + rows + '</tbody></table>'
      : '<p class="muted">Пока пусто — добавьте первый сервер ниже.</p>')
    + '</div>'
    + '<div class="card">'
    + '<h2>Добавить сервер</h2>'
    + '<div class="row">'
    + '<div><label>Адрес (ip или домен)</label><input id="srv-host" placeholder="127.0.0.1"></div>'
    + '<div><label>Порт</label><input id="srv-port" value="7777"></div>'
    + '<div><label>Название для лаунчера</label><input id="srv-name" placeholder="Мой сервер"></div>'
    + '</div>'
    + '<div class="row">'
    + '<div><label>Режим</label><input id="srv-gamemode" placeholder="RolePlay"></div>'
    + '<div><label>Пароль (если нужен)</label><input id="srv-password" placeholder="пусто"></div>'
    + '<div><label>Пометка</label><input id="srv-note" placeholder="Только мобильные, x5 EXP…"></div>'
    + '</div>'
    + '<div class="buttons"><button class="primary" id="srv-add">Добавить</button><button id="srv-import-open">Импорт списком</button></div>'
    + '<div id="srv-import" class="hidden">'
    + '<h3>Импорт списком</h3>'
    + '<p class="muted small">Каждая строка: «ip:порт Название» или «домен:порт Название». Строки с # пропускаются.</p>'
    + '<textarea id="srv-import-text" placeholder="127.0.0.1:7777 Мой сервер"></textarea>'
    + '<div class="buttons"><button class="primary" id="srv-import-go">Импортировать</button></div>'
    + '</div>'
    + '</div>';
}

function bindServers() {
  const add = $('srv-add');
  if (add) {
    add.onclick = async () => {
      try {
        await api('/api/v1/admin/servers', {
          method: 'POST',
          body: {
            host: $('srv-host').value.trim(),
            port: Number($('srv-port').value) || 7777,
            name: $('srv-name').value.trim(),
            gamemode: $('srv-gamemode').value.trim(),
            password: $('srv-password').value.trim(),
            note: $('srv-note').value.trim(),
          },
        });
        toast('Сервер добавлен');
        await loadState();
        queryAll();
      } catch (err) {
        toast(err.message, true);
      }
    };
  }

  const refresh = $('refresh-live');
  if (refresh) {
    refresh.onclick = () => queryAll();
  }

  const importOpen = $('srv-import-open');
  if (importOpen) {
    importOpen.onclick = () => $('srv-import').classList.toggle('hidden');
  }
  const importGo = $('srv-import-go');
  if (importGo) {
    importGo.onclick = async () => {
      try {
        const data = await api('/api/v1/admin/import', { method: 'POST', body: { text: $('srv-import-text').value } });
        toast('Добавлено: ' + data.added + ', пропущено: ' + data.skipped);
        await loadState();
      } catch (err) {
        toast(err.message, true);
      }
    };
  }

  document.querySelectorAll('[data-query]').forEach((button) => {
    button.onclick = async () => {
      const server = state.data.servers.find((item) => item.id === button.dataset.query);
      if (!server) {
        return;
      }
      try {
        const data = await api('/api/v1/admin/query', { method: 'POST', body: { host: server.host, port: server.port } });
        state.live[server.host + ':' + server.port] = data.live;
        render();
        if (data.live.online) {
          const rules = Object.entries(data.rules || {}).slice(0, 8)
            .map(([key, value]) => key + '=' + value).join(', ');
          alert('«' + data.live.hostname + '»\n\nОнлайн: ' + data.live.players + '/' + data.live.maxPlayers
            + '\nРежим: ' + data.live.gamemode + '\nЯзык: ' + data.live.language
            + '\nЗадержка: ' + data.live.rtt + ' мс'
            + '\nИгроков в списке: ' + (data.players || []).length
            + (rules ? '\n\nПравила: ' + rules : ''));
        } else {
          alert('Сервер не ответил: ' + (data.live.error || 'нет ответа'));
        }
      } catch (err) {
        toast(err.message, true);
      }
    };
  });

  document.querySelectorAll('[data-edit]').forEach((button) => {
    button.onclick = async () => {
      const server = state.data.servers.find((item) => item.id === button.dataset.edit);
      if (!server) {
        return;
      }
      const host = prompt('Адрес', server.host);
      if (host === null) {
        return;
      }
      const port = prompt('Порт', String(server.port));
      if (port === null) {
        return;
      }
      const name = prompt('Название', server.name || '');
      if (name === null) {
        return;
      }
      const gamemode = prompt('Режим', server.gamemode || '');
      const note = prompt('Пометка', server.note || '');
      const password = prompt('Пароль', server.password || '');
      try {
        await api('/api/v1/admin/servers/' + encodeURIComponent(server.id), {
          method: 'PATCH',
          body: { host, port: Number(port) || 7777, name, gamemode, note, password },
        });
        toast('Сохранено');
        await loadState();
      } catch (err) {
        toast(err.message, true);
      }
    };
  });

  document.querySelectorAll('[data-remove]').forEach((button) => {
    button.onclick = async () => {
      if (!confirm('Удалить сервер из списка лаунчера?')) {
        return;
      }
      try {
        await api('/api/v1/admin/servers/' + encodeURIComponent(button.dataset.remove), { method: 'DELETE' });
        toast('Удалено');
        await loadState();
      } catch (err) {
        toast(err.message, true);
      }
    };
  });
}

async function queryAll() {
  const servers = state.data.servers.slice();
  if (servers.length === 0) {
    return;
  }
  $('status-line').textContent = 'Опрос серверов…';
  for (const server of servers) {
    try {
      const data = await api('/api/v1/admin/query', { method: 'POST', body: { host: server.host, port: server.port } });
      state.live[server.host + ':' + server.port] = data.live;
    } catch (err) {
      state.live[server.host + ':' + server.port] = { online: false, error: err.message };
    }
    render();
  }
  $('status-line').textContent = 'Статусы обновлены';
}

/* ------------------------------------------------------------------ моды */

function viewMods() {
  const rows = state.data.mods.map((mod) => '<tr>'
    + '<td><b>' + escapeHtml(mod.name) + '</b> ' + escapeHtml(mod.version)
    + (mod.description ? '<div class="muted small">' + escapeHtml(mod.description) + '</div>' : '')
    + '</td>'
    + '<td>' + (mod.kind === 'file' ? 'файл → ' + escapeHtml(mod.target) : 'архив') + '</td>'
    + '<td>' + (mod.size ? Math.round(mod.size / 1048576 * 10) / 10 + ' МБ' : '—') + '</td>'
    + '<td class="small">' + (mod.sha256 ? '<code>' + escapeHtml(mod.sha256.slice(0, 12)) + '…</code>' : '—') + '</td>'
    + '<td class="actions"><a href="' + escapeHtml(mod.url) + '" target="_blank"><button class="small">Файл</button></a> '
    + '<button class="small danger" data-mod-remove="' + mod.id + '">Удалить</button></td>'
    + '</tr>').join('');

  return '<div class="card">'
    + '<h2>Мод-паки</h2>'
    + '<p class="muted small">Приложение скачивает мод, проверяет SHA-256, распаковывает в каталог игры и хранит резервную копию заменённых файлов.</p>'
    + (rows
      ? '<table><thead><tr><th>Мод</th><th>Установка</th><th>Размер</th><th>SHA-256</th><th></th></tr></thead><tbody>' + rows + '</tbody></table>'
      : '<p class="muted">Модов пока нет.</p>')
    + '</div>'
    + '<div class="card">'
    + '<h2>Загрузить мод-пак</h2>'
    + '<div class="row">'
    + '<div><label>Название</label><input id="mod-name" placeholder="Сборка текстур"></div>'
    + '<div><label>Версия</label><input id="mod-version" value="1.0"></div>'
    + '<div><label>Тип</label><select id="mod-kind"><option value="zip">Архив (распаковать в игру)</option><option value="file">Один файл</option></select></div>'
    + '</div>'
    + '<div class="row">'
    + '<div><label>Куда ставить внутри каталога игры (target)</label><input id="mod-target" placeholder="texdb/samp"></div>'
    + '<div><label>Автор</label><input id="mod-author" placeholder="Ник автора"></div>'
    + '<div><label>Для сервера (id из списка, необязательно)</label><input id="mod-server" placeholder="srv-…"></div>'
    + '</div>'
    + '<div><label>Описание</label><textarea id="mod-description" placeholder="Что меняет мод"></textarea></div>'
    + '<div><label>Файл (zip или отдельный файл)</label><input id="mod-file" type="file"></div>'
    + '<div class="buttons"><button class="primary" id="mod-add">Загрузить и добавить</button></div>'
    + '</div>';
}

function bindMods() {
  document.querySelectorAll('[data-mod-remove]').forEach((button) => {
    button.onclick = async () => {
      if (!confirm('Удалить мод и его файл с сервера?')) {
        return;
      }
      try {
        await api('/api/v1/admin/mods/' + encodeURIComponent(button.dataset.modRemove), { method: 'DELETE' });
        toast('Мод удалён');
        await loadState();
      } catch (err) {
        toast(err.message, true);
      }
    };
  });

  const add = $('mod-add');
  if (!add) {
    return;
  }
  add.onclick = async () => {
    const input = $('mod-file');
    if (!input.files || !input.files[0]) {
      toast('Выберите файл мода', true);
      return;
    }
    try {
      add.disabled = true;
      add.textContent = 'Загружаем…';
      const form = new FormData();
      form.append('file', input.files[0]);
      const uploaded = await api('/api/v1/admin/upload?kind=mod', { method: 'POST', body: form });
      await api('/api/v1/admin/mods', {
        method: 'POST',
        body: {
          name: $('mod-name').value.trim(),
          version: $('mod-version').value.trim(),
          kind: $('mod-kind').value,
          target: $('mod-target').value.trim(),
          author: $('mod-author').value.trim(),
          serverId: $('mod-server').value.trim(),
          description: $('mod-description').value,
          url: '',
          file: uploaded.file,
          size: uploaded.size,
          sha256: uploaded.sha256,
        },
      });
      toast('Мод загружен: ' + uploaded.sizeLabel);
      await loadState();
    } catch (err) {
      toast(err.message, true);
      add.disabled = false;
      add.textContent = 'Загрузить и добавить';
    }
  };
}

/* ------------------------------------------------------------------ новости */

function viewNews() {
  const rows = state.data.news.map((item) => '<tr>'
    + '<td>' + (item.pinned ? '📌 ' : '') + '<b>' + escapeHtml(item.title) + '</b>'
    + '<div class="muted small">' + escapeHtml(item.text) + '</div></td>'
    + '<td>' + escapeHtml(item.tag || '—') + '</td>'
    + '<td class="small">' + new Date(item.date || Date.now()).toLocaleString('ru-RU') + '</td>'
    + '<td class="actions"><button class="small danger" data-news-remove="' + item.id + '">Удалить</button></td>'
    + '</tr>').join('');

  return '<div class="card">'
    + '<h2>Новости</h2>'
    + '<p class="muted small">Показываются в приложении на главном экране и в разделе «Новости».</p>'
    + (rows ? '<table><tbody>' + rows + '</tbody></table>' : '<p class="muted">Новостей нет.</p>')
    + '</div>'
    + '<div class="card">'
    + '<h2>Добавить новость</h2>'
    + '<div class="row">'
    + '<div><label>Заголовок</label><input id="news-title"></div>'
    + '<div><label>Метка</label><input id="news-tag" placeholder="Обновление"></div>'
    + '</div>'
    + '<div><label>Текст</label><textarea id="news-text"></textarea></div>'
    + '<label class="checkbox"><input type="checkbox" id="news-pinned"> Закрепить</label>'
    + '<div class="buttons"><button class="primary" id="news-add">Опубликовать</button></div>'
    + '</div>';
}

function bindNews() {
  document.querySelectorAll('[data-news-remove]').forEach((button) => {
    button.onclick = async () => {
      try {
        await api('/api/v1/admin/news/' + encodeURIComponent(button.dataset.newsRemove), { method: 'DELETE' });
        toast('Удалено');
        await loadState();
      } catch (err) {
        toast(err.message, true);
      }
    };
  });
  const add = $('news-add');
  if (add) {
    add.onclick = async () => {
      try {
        await api('/api/v1/admin/news', {
          method: 'POST',
          body: {
            title: $('news-title').value.trim(),
            tag: $('news-tag').value.trim(),
            text: $('news-text').value,
            pinned: $('news-pinned').checked,
          },
        });
        toast('Новость опубликована');
        await loadState();
      } catch (err) {
        toast(err.message, true);
      }
    };
  }
}

/* ------------------------------------------------------------------ клиенты */

function viewClients() {
  const rows = state.data.clients.map((client) => '<tr>'
    + '<td><b>' + escapeHtml(client.name) + '</b><div class="muted small">' + escapeHtml(client.description || '') + '</div></td>'
    + '<td class="small"><a href="' + escapeHtml(client.url) + '" target="_blank">' + escapeHtml(client.url) + '</a></td>'
    + '<td class="small">' + escapeHtml(client.pkg || '—') + '</td>'
    + '<td class="actions"><button class="small danger" data-client-remove="' + client.id + '">Удалить</button></td>'
    + '</tr>').join('');

  return '<div class="card">'
    + '<h2>Сборки клиента SA-MP</h2>'
    + '<p class="muted small">Эти ссылки лаунчер показывает, если клиент ещё не установлен на телефоне.</p>'
    + (rows ? '<table><tbody>' + rows + '</tbody></table>' : '<p class="muted">Ссылок нет.</p>')
    + '</div>'
    + '<div class="card">'
    + '<h2>Добавить ссылку</h2>'
    + '<div class="row">'
    + '<div><label>Название</label><input id="client-name" placeholder="SA-MP Mobile 2.11"></div>'
    + '<div><label>Пакет (если известен)</label><input id="client-pkg" placeholder="com.gta.game"></div>'
    + '</div>'
    + '<div><label>Ссылка на скачивание</label><input id="client-url" placeholder="https://…/client.apk"></div>'
    + '<div><label>Описание</label><input id="client-description" placeholder="ARM64, требует GTA SA 2.11"></div>'
    + '<div class="buttons"><button class="primary" id="client-add">Добавить</button></div>'
    + '</div>';
}

function bindClients() {
  document.querySelectorAll('[data-client-remove]').forEach((button) => {
    button.onclick = async () => {
      try {
        await api('/api/v1/admin/clients/' + encodeURIComponent(button.dataset.clientRemove), { method: 'DELETE' });
        toast('Удалено');
        await loadState();
      } catch (err) {
        toast(err.message, true);
      }
    };
  });
  const add = $('client-add');
  if (add) {
    add.onclick = async () => {
      try {
        await api('/api/v1/admin/clients', {
          method: 'POST',
          body: {
            name: $('client-name').value.trim(),
            pkg: $('client-pkg').value.trim(),
            url: $('client-url').value.trim(),
            description: $('client-description').value.trim(),
          },
        });
        toast('Ссылка добавлена');
        await loadState();
      } catch (err) {
        toast(err.message, true);
      }
    };
  }
}

/* ------------------------------------------------------------------ лаунчер */

function viewLauncher() {
  const launcher = state.data.launcher || {};
  return '<div class="card">'
    + '<h2>Обновления приложения</h2>'
    + '<p class="muted small">Приложение сравнивает свой versionCode с этим значением и предлагает обновиться. '
    + 'Загруженный APK раздаётся с этого сервера — можно не зависеть от сторонних ссылок.</p>'
    + '<div class="row">'
    + '<div><label>Версия</label><input id="launcher-version" value="' + escapeHtml(launcher.version || '1.0') + '"></div>'
    + '<div><label>versionCode</label><input id="launcher-code" value="' + escapeHtml(launcher.versionCode || 1) + '"></div>'
    + '<div><label>Минимальная поддерживаемая версия</label><input id="launcher-min" value="' + escapeHtml(launcher.minSupported || 1) + '"></div>'
    + '</div>'
    + '<div><label>Что нового</label><textarea id="launcher-notes">' + escapeHtml(launcher.notes || '') + '</textarea></div>'
    + '<p class="muted small">Текущая ссылка: ' + (launcher.url ? '<code>' + escapeHtml(launcher.url) + '</code>' : 'нет')
    + (launcher.sha256 ? ' · SHA-256 <code>' + escapeHtml(String(launcher.sha256).slice(0, 16)) + '…</code>' : '') + '</p>'
    + '<label>Новый APK</label><input id="launcher-file" type="file" accept=".apk">'
    + '<div class="buttons">'
    + '<button class="primary" id="launcher-save">Сохранить</button>'
    + '<button id="launcher-upload">Загрузить APK</button>'
    + '</div></div>';
}

function bindLauncher() {
  const save = $('launcher-save');
  if (save) {
    save.onclick = async () => {
      try {
        await api('/api/v1/admin/launcher', {
          method: 'POST',
          body: {
            version: $('launcher-version').value.trim(),
            versionCode: Number($('launcher-code').value) || 1,
            minSupported: Number($('launcher-min').value) || 1,
            notes: $('launcher-notes').value,
          },
        });
        toast('Сохранено');
        await loadState();
      } catch (err) {
        toast(err.message, true);
      }
    };
  }
  const upload = $('launcher-upload');
  if (upload) {
    upload.onclick = async () => {
      const input = $('launcher-file');
      if (!input.files || !input.files[0]) {
        toast('Выберите APK', true);
        return;
      }
      try {
        upload.disabled = true;
        upload.textContent = 'Загружаем…';
        const form = new FormData();
        form.append('file', input.files[0]);
        const data = await api('/api/v1/admin/upload?kind=launcher', { method: 'POST', body: form });
        toast('APK загружен: ' + data.sizeLabel);
        await loadState();
      } catch (err) {
        toast(err.message, true);
      } finally {
        upload.disabled = false;
        upload.textContent = 'Загрузить APK';
      }
    };
  }
}

/* ------------------------------------------------------------------ настройки */

function viewSettings() {
  return '<div class="card">'
    + '<h2>Настройки сервера</h2>'
    + '<div class="row">'
    + '<div><label>Текущий пароль</label><input id="pass-current" type="password"></div>'
    + '<div><label>Новый пароль</label><input id="pass-next" type="password"></div>'
    + '</div>'
    + '<div class="buttons"><button class="primary" id="pass-save">Сменить пароль</button></div>'
    + '<h3>Как подключить приложение</h3>'
    + '<p class="muted small">В лаунчере: <b>Настройки → Адрес сервера лаунчера</b> — укажите адрес этой панели '
    + '(например, <code>http://192.168.1.10:8090</code>). После этого в приложении появятся серверы, мод-паки, '
    + 'новости и обновления. Каталог игры по умолчанию — <code>/storage/emulated/0/GTA</code>, '
    + 'журнал модов и резервные копии — в <code>/storage/emulated/0/ModarLauncher</code>.</p>'
    + '<h3>Проверка без живого сервера</h3>'
    + '<p class="muted small">Мок-сервер SA-MP для тестов: '
    + '<code>node ../tools/mock-samp-server.js 7777 --players 42</code>, затем «Опросить» у сервера 127.0.0.1:7777.</p>'
    + '</div>';
}

function bindSettings() {
  const save = $('pass-save');
  if (save) {
    save.onclick = async () => {
      try {
        await api('/api/v1/admin/password', {
          method: 'POST',
          body: { current: $('pass-current').value, next: $('pass-next').value },
        });
        toast('Пароль изменён — войдите заново');
        logout();
      } catch (err) {
        toast(err.message, true);
      }
    };
  }
}

/* ------------------------------------------------------------------ старт */

$('login-button').onclick = login;
$('password').addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    login();
  }
});
$('logout').onclick = logout;

if (state.token) {
  showApp();
  loadState();
}
