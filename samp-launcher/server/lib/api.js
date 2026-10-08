'use strict';
/*
 * API сервера лаунчера: раздаёт приложению список серверов, мод-паки, новости,
 * список сборок клиента и обновления; панель управляет всем этим через /admin.
 */

const fs = require('node:fs');
const path = require('node:path');

const U = require('./util');
const query = require('./query');
const { parseMultipart } = require('./upload');

const VERSION = require('../package.json').version;
const TOKEN_TTL = 12 * 60 * 60 * 1000;

/** Активные токены панели: token → время окончания. */
const tokens = new Map();

function createApi(store, options) {
  const modsDir = options.modsDir;
  const dlDir = options.dlDir;

  /* ------------------------------------------------------------ доступ */

  function login(password, config) {
    if (!password || String(password) !== String(config.adminPassword)) {
      return null;
    }
    const token = U.sha256(config.secret + ':' + Date.now() + ':' + Math.random());
    tokens.set(token, Date.now() + TOKEN_TTL);
    return token;
  }

  function admin(req) {
    const header = req.headers.authorization || '';
    const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
    if (!token) {
      return false;
    }
    const expires = tokens.get(token);
    if (!expires || expires < Date.now()) {
      tokens.delete(token);
      return false;
    }
    return true;
  }

  /* ------------------------------------------------------------ данные для приложения */

  function publicServers() {
    return store.list('servers').map((server) => ({
      id: server.id,
      name: server.name || '',
      host: server.host,
      port: Number(server.port) || 7777,
      password: server.password || '',
      gamemode: server.gamemode || '',
      note: server.note || '',
      tags: server.tags || [],
    }));
  }

  function publicMods() {
    return store.list('mods').map((mod) => ({
      id: mod.id,
      name: mod.name,
      version: mod.version || '1.0',
      kind: mod.kind === 'file' ? 'file' : 'zip',
      url: mod.file ? '/files/mods/' + mod.file : (mod.url || ''),
      target: mod.target || '',
      description: mod.description || '',
      author: mod.author || '',
      serverId: mod.serverId || '',
      sha256: mod.sha256 || '',
      size: Number(mod.size) || 0,
      updatedAt: mod.updatedAt || mod.createdAt || 0,
    })).filter((mod) => mod.url);
  }

  function publicNews() {
    return store.list('news')
      .map((item) => ({
        id: item.id,
        title: item.title || '',
        text: item.text || '',
        tag: item.tag || '',
        date: item.date || item.createdAt || 0,
        pinned: Boolean(item.pinned),
      }))
      .sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || b.date - a.date);
  }

  function publicClients() {
    return store.list('clients').map((client) => ({
      name: client.name || '',
      description: client.description || '',
      url: client.url || '',
      pkg: client.pkg || '',
    })).filter((client) => client.url);
  }

  function publicLauncher() {
    const launcher = store.read('launcher', {});
    return {
      version: launcher.version || '1.0',
      versionCode: Number(launcher.versionCode) || 1,
      url: launcher.url || '',
      sha256: launcher.sha256 || '',
      size: Number(launcher.size) || 0,
      notes: launcher.notes || '',
      minSupported: Number(launcher.minSupported) || 1,
    };
  }

  /** Опрос всех серверов списка (с кэшем) — до 8 одновременно. */
  async function serversWithLive() {
    const servers = publicServers();
    const timeout = Number(options.queryTimeout) || query.DEFAULT_TIMEOUT;
    const results = [];
    let index = 0;
    const workers = new Array(Math.min(8, Math.max(1, servers.length))).fill(0).map(async () => {
      for (;;) {
        const current = index++;
        if (current >= servers.length) {
          return;
        }
        const server = servers[current];
        const live = await query.cachedInfo(server.host, server.port, { timeout });
        results[current] = { ...server, live };
      }
    });
    await Promise.all(workers);
    return results;
  }

  /* ------------------------------------------------------------ маршруты */

  async function handle(req, res, url) {
    const p = url.pathname;
    if (!p.startsWith('/api/v1')) {
      return false;
    }
    const method = req.method.toUpperCase();
    const config = store.config();

    /* --- служебное --- */

    if (p === '/api/v1/ping' && method === 'GET') {
      U.ok(res, {
        version: VERSION,
        name: 'Modar SAMP Launcher server',
        time: Date.now(),
        servers: store.list('servers').length,
        mods: store.list('mods').length,
        news: store.list('news').length,
      });
      return true;
    }

    /* --- приложению --- */

    if (p === '/api/v1/servers' && method === 'GET') {
      const live = url.searchParams.get('live');
      if (live === '0' || live === 'false') {
        U.ok(res, { servers: publicServers(), time: Date.now() });
      } else {
        U.ok(res, { servers: await serversWithLive(), time: Date.now() });
      }
      return true;
    }

    if (p === '/api/v1/mods' && method === 'GET') {
      U.ok(res, { mods: publicMods(), time: Date.now() });
      return true;
    }

    if (p === '/api/v1/news' && method === 'GET') {
      U.ok(res, { news: publicNews(), time: Date.now() });
      return true;
    }

    if (p === '/api/v1/clients' && method === 'GET') {
      U.ok(res, { clients: publicClients(), time: Date.now() });
      return true;
    }

    if (p === '/api/v1/launcher' && method === 'GET') {
      U.ok(res, publicLauncher());
      return true;
    }

    /* --- панель: вход --- */

    if (p === '/api/v1/admin/login' && method === 'POST') {
      const body = await U.readJson(req);
      const token = login(body.password, config);
      if (!token) {
        U.fail(res, 'Неверный пароль', 401);
        return true;
      }
      U.ok(res, { token, expiresIn: TOKEN_TTL });
      return true;
    }

    if (p === '/api/v1/admin/password' && method === 'POST' && admin(req)) {
      const body = await U.readJson(req);
      if (String(body.current || '') !== String(config.adminPassword)) {
        U.fail(res, 'Текущий пароль неверный', 403);
        return true;
      }
      if (!body.next || String(body.next).length < 4) {
        U.fail(res, 'Новый пароль слишком короткий (минимум 4 символа)');
        return true;
      }
      store.saveConfig({ adminPassword: String(body.next) });
      tokens.clear();
      U.ok(res, { message: 'Пароль изменён, войдите заново' });
      return true;
    }

    if (!p.startsWith('/api/v1/admin')) {
      U.fail(res, 'Неизвестный метод API', 404);
      return true;
    }

    if (!admin(req)) {
      U.fail(res, 'Нужен вход в панель', 401);
      return true;
    }

    /* --- панель: состояние --- */

    if (p === '/api/v1/admin/state' && method === 'GET') {
      U.ok(res, {
        servers: publicServers(),
        mods: publicMods(),
        news: storageNews(),
        clients: store.list('clients'),
        launcher: publicLauncher(),
        limits: { queryTimeout: options.queryTimeout, modsDir, dlDir },
      });
      return true;
    }

    function storageNews() {
      return store.list('news').slice().sort((a, b) => (b.date || 0) - (a.date || 0));
    }

    /* --- панель: серверы --- */

    if (p === '/api/v1/admin/servers' && method === 'POST') {
      const body = await U.readJson(req);
      const parsed = validateServer(body);
      if (parsed.error) {
        U.fail(res, parsed.error);
        return true;
      }
      const created = store.create('servers', parsed.server, 'srv');
      U.ok(res, { server: created });
      return true;
    }

    if (p.startsWith('/api/v1/admin/servers/') && (method === 'PATCH' || method === 'PUT')) {
      const id = decodeURIComponent(p.split('/').pop());
      const body = await U.readJson(req);
      const parsed = validateServer({ ...store.list('servers').find((s) => s.id === id) || {}, ...body });
      if (parsed.error) {
        U.fail(res, parsed.error);
        return true;
      }
      const updated = store.update('servers', id, parsed.server);
      if (!updated) {
        U.fail(res, 'Сервер не найден', 404);
        return true;
      }
      U.ok(res, { server: updated });
      return true;
    }

    if (p.startsWith('/api/v1/admin/servers/') && method === 'DELETE') {
      const id = decodeURIComponent(p.split('/').pop());
      query.clearCache();
      U.ok(res, { removed: store.remove('servers', id) });
      return true;
    }

    /** Массовый импорт: строки «ip:port Название» или «ip Название». */
    if (p === '/api/v1/admin/import' && method === 'POST') {
      const body = await U.readJson(req);
      const lines = String(body.text || '').split(/\r?\n/);
      let added = 0;
      let skipped = 0;
      for (const raw of lines) {
        const line = raw.trim();
        if (!line || line.startsWith('#')) {
          continue;
        }
        const match = /^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?\s*(.*)$/.exec(line)
          || /^([a-z0-9.-]+\.[a-z]{2,})(?::(\d{1,5}))?\s*(.*)$/i.exec(line);
        if (!match) {
          skipped += 1;
          continue;
        }
        const host = match[1];
        const port = Number(match[2] || 7777);
        const name = (match[3] || '').trim();
        const exists = store.list('servers').some((s) => s.host === host && Number(s.port) === port);
        if (exists) {
          skipped += 1;
          continue;
        }
        store.create('servers', { host, port, name, gamemode: '', note: '', password: '', tags: [] }, 'srv');
        added += 1;
      }
      U.ok(res, { added, skipped });
      return true;
    }

    /* --- панель: живой опрос --- */

    if (p === '/api/v1/admin/query' && method === 'POST') {
      const body = await U.readJson(req);
      const host = String(body.host || '').trim();
      const port = Number(body.port) || 7777;
      if (!host) {
        U.fail(res, 'Не указан адрес сервера');
        return true;
      }
      const info = await query.cachedInfo(host, port, { force: true, timeout: Number(body.timeout) || options.queryTimeout });
      let rules = {};
      let players = [];
      if (info.online) {
        try {
          rules = await query.rules(host, port, Number(body.timeout) || options.queryTimeout);
        } catch (err) {
          rules = { error: String(err.message || err) };
        }
        try {
          players = await query.players(host, port, Number(body.timeout) || options.queryTimeout);
        } catch (err) {
          players = [];
        }
      }
      U.ok(res, { live: info, rules, players });
      return true;
    }

    /* --- панель: моды --- */

    if (p === '/api/v1/admin/mods' && method === 'POST') {
      const body = await U.readJson(req);
      const parsed = validateMod(body);
      if (parsed.error) {
        U.fail(res, parsed.error);
        return true;
      }
      const created = store.create('mods', parsed.mod, 'mod');
      U.ok(res, { mod: created });
      return true;
    }

    if (p.startsWith('/api/v1/admin/mods/') && (method === 'PATCH' || method === 'PUT')) {
      const id = decodeURIComponent(p.split('/').pop());
      const existing = store.list('mods').find((m) => m.id === id);
      if (!existing) {
        U.fail(res, 'Мод не найден', 404);
        return true;
      }
      const body = await U.readJson(req);
      const parsed = validateMod({ ...existing, ...body });
      if (parsed.error) {
        U.fail(res, parsed.error);
        return true;
      }
      U.ok(res, { mod: store.update('mods', id, parsed.mod) });
      return true;
    }

    if (p.startsWith('/api/v1/admin/mods/') && method === 'DELETE') {
      const id = decodeURIComponent(p.split('/').pop());
      const mod = store.list('mods').find((m) => m.id === id);
      if (mod && mod.file) {
        const target = path.join(modsDir, mod.file);
        if (target.startsWith(modsDir)) {
          try {
            fs.unlinkSync(target);
          } catch (ignored) {
            /* файла уже нет */
          }
        }
      }
      U.ok(res, { removed: store.remove('mods', id) });
      return true;
    }

    /* --- панель: новости --- */

    if (p === '/api/v1/admin/news' && method === 'POST') {
      const body = await U.readJson(req);
      if (!String(body.title || '').trim()) {
        U.fail(res, 'Нужен заголовок новости');
        return true;
      }
      const created = store.create('news', {
        title: String(body.title).trim(),
        text: String(body.text || ''),
        tag: String(body.tag || ''),
        pinned: Boolean(body.pinned),
        date: Number(body.date) || Date.now(),
      }, 'news');
      U.ok(res, { news: created });
      return true;
    }

    if (p.startsWith('/api/v1/admin/news/') && (method === 'PATCH' || method === 'PUT')) {
      const id = decodeURIComponent(p.split('/').pop());
      const body = await U.readJson(req);
      U.ok(res, {
        news: store.update('news', id, {
          title: String(body.title || '').trim(),
          text: String(body.text || ''),
          tag: String(body.tag || ''),
          pinned: Boolean(body.pinned),
          date: Number(body.date) || Date.now(),
        }),
      });
      return true;
    }

    if (p.startsWith('/api/v1/admin/news/') && method === 'DELETE') {
      const id = decodeURIComponent(p.split('/').pop());
      U.ok(res, { removed: store.remove('news', id) });
      return true;
    }

    /* --- панель: клиенты --- */

    if (p === '/api/v1/admin/clients' && method === 'POST') {
      const body = await U.readJson(req);
      if (!String(body.name || '').trim() || !String(body.url || '').trim()) {
        U.fail(res, 'Нужны название и ссылка');
        return true;
      }
      U.ok(res, {
        client: store.create('clients', {
          name: String(body.name).trim(),
          description: String(body.description || ''),
          url: String(body.url).trim(),
          pkg: String(body.pkg || '').trim(),
        }, 'client'),
      });
      return true;
    }

    if (p.startsWith('/api/v1/admin/clients/') && method === 'DELETE') {
      const id = decodeURIComponent(p.split('/').pop());
      U.ok(res, { removed: store.remove('clients', id) });
      return true;
    }

    /* --- панель: лаунчер --- */

    if (p === '/api/v1/admin/launcher' && method === 'POST') {
      const body = await U.readJson(req);
      const current = store.read('launcher', {});
      const launcher = {
        ...current,
        version: String(body.version || current.version || '1.0').trim(),
        versionCode: Number(body.versionCode) || Number(current.versionCode) || 1,
        notes: String(body.notes === undefined ? current.notes || '' : body.notes),
        minSupported: Number(body.minSupported) || Number(current.minSupported) || 1,
        url: body.url === undefined ? current.url || '' : String(body.url).trim(),
        updatedAt: Date.now(),
      };
      store.write('launcher', launcher);
      U.ok(res, { launcher: publicLauncher() });
      return true;
    }

    /* --- панель: загрузка файлов --- */

    if (p === '/api/v1/admin/upload' && method === 'POST') {
      const kind = url.searchParams.get('kind') || 'mod';
      const targetDir = kind === 'launcher' ? dlDir : modsDir;
      const result = await parseMultipart(req, {
        dir: targetDir,
        prefix: '',
        maxBytes: Number(options.maxUpload) || 1024 * 1024 * 1024,
      });
      const file = result.files[0];
      if (!file) {
        U.fail(res, 'В запросе нет файла');
        return true;
      }
      const sha = await U.sha256File(file.path);
      const url2 = (kind === 'launcher' ? '/dl/' : '/files/mods/') + file.filename;
      if (kind === 'launcher') {
        const current = store.read('launcher', {});
        store.write('launcher', {
          ...current,
          url: url2,
          size: file.size,
          sha256: sha,
          updatedAt: Date.now(),
        });
      }
      U.ok(res, {
        file: file.filename,
        size: file.size,
        sizeLabel: U.sizeLabel(file.size),
        sha256: sha,
        url: url2,
      });
      return true;
    }

    U.fail(res, 'Метод не найден', 404);
    return true;
  }

  /* ------------------------------------------------------------ проверки */

  function validateServer(body) {
    const host = String(body.host || '').trim();
    const port = Number(body.port) || 7777;
    if (!host) {
      return { error: 'Не указан адрес сервера' };
    }
    if (port < 1 || port > 65535) {
      return { error: 'Неверный порт' };
    }
    return {
      server: {
        name: String(body.name || '').trim(),
        host,
        port,
        password: String(body.password || ''),
        gamemode: String(body.gamemode || ''),
        note: String(body.note || ''),
        tags: Array.isArray(body.tags) ? body.tags.map(String) : String(body.tags || '').split(',').map((t) => t.trim()).filter(Boolean),
      },
    };
  }

  function validateMod(body) {
    const name = String(body.name || '').trim();
    if (!name) {
      return { error: 'Нужно название мода' };
    }
    const kind = body.kind === 'file' ? 'file' : 'zip';
    const url = String(body.url || '').trim();
    const file = String(body.file || '').trim();
    if (!url && !file) {
      return { error: 'Загрузите файл мода или укажите ссылку' };
    }
    if (kind === 'file' && !String(body.target || '').trim()) {
      return { error: 'Для мода-файла укажите путь установки (target)' };
    }
    return {
      mod: {
        name,
        version: String(body.version || '1.0').trim(),
        kind,
        url: file ? '' : url,
        file,
        target: String(body.target || '').trim(),
        description: String(body.description || ''),
        author: String(body.author || ''),
        serverId: String(body.serverId || ''),
        sha256: String(body.sha256 || ''),
        size: Number(body.size) || 0,
        updatedAt: Date.now(),
      },
    };
  }

  return { handle, serversWithLive, publicServers, publicMods, live: publicServers };
}

module.exports = { createApi };
