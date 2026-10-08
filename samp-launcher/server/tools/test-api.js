'use strict';
/*
 * Проверка API сервера лаунчера: запускаем настоящий server.js на временном каталоге,
 * поднимаем mock-сервер SA-MP и проходим сценарий целиком — вход в панель, добавление
 * сервера с живым опросом, загрузка мод-пака (multipart), раздача файла, новости, лаунчер.
 *
 *   npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const { createMockServer } = require('../../tools/mock-samp-server.js');

const PASSWORD = 'test-password-1234';

function freePort() {
  return new Promise((resolve) => {
    const probe = net.createServer();
    probe.listen(0, '127.0.0.1', () => {
      const port = probe.address().port;
      probe.close(() => resolve(port));
    });
  });
}

async function waitFor(url, attempts = 60) {
  for (let i = 0; i < attempts; i++) {
    try {
      const response = await fetch(url);
      if (response.ok) {
        return true;
      }
    } catch (err) {
      /* сервер ещё поднимается */
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error('Сервер не поднялся: ' + url);
}

async function startServer() {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'modar-samp-'));
  const port = await freePort();
  const base = 'http://127.0.0.1:' + port;

  fs.writeFileSync(path.join(dataDir, 'config.json'), JSON.stringify({
    adminPassword: PASSWORD,
    secret: 'test-secret',
    createdAt: Date.now(),
  }));

  const child = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], {
    env: {
      ...process.env,
      PORT: String(port),
      HOST: '127.0.0.1',
      DATA_DIR: dataDir,
      QUERY_TIMEOUT: '900',
      DEMO: '1',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.on('data', () => {});
  child.stderr.on('data', () => {});

  await waitFor(base + '/api/v1/ping');
  return {
    base,
    dataDir,
    async stop() {
      child.kill('SIGTERM');
      await new Promise((resolve) => child.once('exit', resolve));
      fs.rmSync(dataDir, { recursive: true, force: true });
    },
  };
}

async function call(base, path, options = {}, token) {
  const headers = { ...(options.headers || {}) };
  if (token) {
    headers.Authorization = 'Bearer ' + token;
  }
  let body = options.body;
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  const response = await fetch(base + path, { method: options.method || 'GET', headers, body });
  const text = await response.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch (err) {
    data = { raw: text };
  }
  return { status: response.status, data, response };
}

async function login(base) {
  const result = await call(base, '/api/v1/admin/login', { method: 'POST', body: { password: PASSWORD } });
  assert.equal(result.status, 200, JSON.stringify(result.data));
  return result.data.token;
}

test('сервер лаунчера: полный сценарий', async (t) => {
  const server = await startServer();
  t.after(() => server.stop());

  await t.test('ping сообщает версию и счётчики', async () => {
    const { data } = await call(server.base, '/api/v1/ping');
    assert.equal(data.ok, true);
    assert.ok(data.version);
    assert.equal(typeof data.servers, 'number');
  });

  await t.test('панель без входа закрыта, с неверным паролем — 401', async () => {
    const anonymous = await call(server.base, '/api/v1/admin/state');
    assert.equal(anonymous.status, 401);
    const wrong = await call(server.base, '/api/v1/admin/login', { method: 'POST', body: { password: 'nope' } });
    assert.equal(wrong.status, 401);
  });

  const token = await login(server.base);

  await t.test('демо-данные: серверы, новости, клиенты', async () => {
    const state = await call(server.base, '/api/v1/admin/state', {}, token);
    assert.equal(state.data.ok, true);
    assert.ok(state.data.servers.length >= 2, 'демо-серверы есть');
    assert.ok(state.data.news.length >= 1, 'демо-новость есть');
    assert.ok(state.data.clients.length >= 1, 'сборки клиента есть');
  });

  await t.test('живой опрос: mock-сервер SA-MP попадает в список', async () => {
    const mock = createMockServer({ port: 0, hostname: 'Panel Test RP', players: 11 });
    const address = await mock.ready;
    try {
      const created = await call(server.base, '/api/v1/admin/servers', {
        method: 'POST',
        body: { host: '127.0.0.1', port: address.port, name: 'Тест', gamemode: 'Freeroam' },
      }, token);
      assert.equal(created.data.ok, true, JSON.stringify(created.data));

      const list = await call(server.base, '/api/v1/servers?live=1');
      const found = list.data.servers.find((item) => item.port === address.port);
      assert.ok(found, 'сервер есть в списке');
      assert.equal(found.live.online, true);
      assert.equal(found.live.hostname, 'Panel Test RP');
      assert.equal(found.live.players, 11);

      const details = await call(server.base, '/api/v1/admin/query', {
        method: 'POST',
        body: { host: '127.0.0.1', port: address.port },
      }, token);
      assert.equal(details.data.live.online, true);
      assert.equal(details.data.rules.mapname, 'San Andreas');
      assert.equal(details.data.players.length, 11);
    } finally {
      await mock.close();
    }
  });

  await t.test('оффлайн-сервер помечается как offline', async () => {
    const deadPort = await freePort();
    const list = await call(server.base, '/api/v1/servers?live=1');
    const created = await call(server.base, '/api/v1/admin/servers', {
      method: 'POST',
      body: { host: '127.0.0.1', port: deadPort },
    }, token);
    assert.equal(created.data.ok, true);
    const again = await call(server.base, '/api/v1/servers?live=1');
    const found = again.data.servers.find((item) => item.port === deadPort);
    assert.equal(found.live.online, false);
    assert.ok(found.live.error);
  });

  await t.test('импорт списка строками «ip:порт Название»', async () => {
    const imported = await call(server.base, '/api/v1/admin/import', {
      method: 'POST',
      body: { text: '# комментарий\n127.0.0.1:7901 Первый\n127.0.0.1:7902 Второй\nмусор\n127.0.0.1:7901 Дубль' },
    }, token);
    assert.equal(imported.data.ok, true);
    assert.equal(imported.data.added, 2);
    assert.equal(imported.data.skipped, 2);
  });

  await t.test('загрузка мод-пака и раздача файла', async () => {
    const content = Buffer.from('modar-samp-mod-' + Date.now() + '-x'.repeat(5000));
    const sha = crypto.createHash('sha256').update(content).digest('hex');

    const form = new FormData();
    form.append('file', new Blob([content], { type: 'application/zip' }), 'texpack.zip');
    const uploaded = await call(server.base, '/api/v1/admin/upload?kind=mod', { method: 'POST', body: form }, token);
    assert.equal(uploaded.data.ok, true, JSON.stringify(uploaded.data));
    assert.equal(uploaded.data.sha256, sha);
    assert.equal(uploaded.data.size, content.length);

    const mod = await call(server.base, '/api/v1/admin/mods', {
      method: 'POST',
      body: {
        name: 'Текстурный пак', version: '1.2', kind: 'zip', target: 'texdb/samp',
        author: 'Modar', description: 'Проверка загрузки', file: uploaded.data.file,
        size: uploaded.data.size, sha256: uploaded.data.sha256,
      },
    }, token);
    assert.equal(mod.data.ok, true, JSON.stringify(mod.data));

    const list = await call(server.base, '/api/v1/mods');
    const published = list.data.mods.find((item) => item.name === 'Текстурный пак');
    assert.ok(published, 'мод попал в каталог');
    assert.equal(published.sha256, sha);
    assert.equal(published.version, '1.2');
    assert.ok(published.url.startsWith('/files/mods/'), published.url);

    const download = await fetch(server.base + published.url);
    assert.equal(download.status, 200);
    const got = Buffer.from(await download.arrayBuffer());
    assert.equal(got.length, content.length);
    assert.equal(crypto.createHash('sha256').update(got).digest('hex'), sha, 'файл отдаётся без изменений');

    // Range-запрос: качалка на телефоне умеет докачивать
    const ranged = await fetch(server.base + published.url, { headers: { Range: 'bytes=10-19' } });
    assert.equal(ranged.status, 206);
    const part = Buffer.from(await ranged.arrayBuffer());
    assert.deepEqual([...part], [...content.subarray(10, 20)]);
  });

  await t.test('мод-файл без target отклоняется', async () => {
    const result = await call(server.base, '/api/v1/admin/mods', {
      method: 'POST',
      body: { name: 'Одинокий файл', kind: 'file', url: '/files/mods/x.dat', target: '' },
    }, token);
    assert.equal(result.data.ok, false);
    assert.match(result.data.error, /target/);
  });

  await t.test('новости и обновление лаунчера', async () => {
    const news = await call(server.base, '/api/v1/admin/news', {
      method: 'POST',
      body: { title: 'Вышел мод-пак', text: 'Подробности внутри', tag: 'Обновление', pinned: true },
    }, token);
    assert.equal(news.data.ok, true);

    const list = await call(server.base, '/api/v1/news');
    assert.equal(list.data.news[0].title, 'Вышел мод-пак');
    assert.equal(list.data.news[0].pinned, true);

    const apk = Buffer.from('PK\u0003\u0004fake-apk-content');
    const form = new FormData();
    form.append('file', new Blob([apk]), 'ModarSAMP-1.1.apk');
    const uploaded = await call(server.base, '/api/v1/admin/upload?kind=launcher', { method: 'POST', body: form }, token);
    assert.equal(uploaded.data.ok, true, JSON.stringify(uploaded.data));

    const saved = await call(server.base, '/api/v1/admin/launcher', {
      method: 'POST',
      body: { version: '1.1', versionCode: 2, notes: 'Что нового', minSupported: 1 },
    }, token);
    assert.equal(saved.data.launcher.version, '1.1');

    const published = await call(server.base, '/api/v1/launcher');
    assert.equal(published.data.version, '1.1');
    assert.equal(published.data.versionCode, 2);
    assert.equal(published.data.sha256, crypto.createHash('sha256').update(apk).digest('hex'));
    assert.ok(published.data.url.startsWith('/dl/'));

    const apkResponse = await fetch(server.base + published.data.url);
    assert.equal(apkResponse.status, 200);
    assert.equal((await apkResponse.arrayBuffer()).byteLength, apk.length);
  });

  await t.test('смена пароля панели', async () => {
    const wrong = await call(server.base, '/api/v1/admin/password', {
      method: 'POST',
      body: { current: 'не тот', next: 'новый-пароль' },
    }, token);
    assert.equal(wrong.status, 403);

    const changed = await call(server.base, '/api/v1/admin/password', {
      method: 'POST',
      body: { current: PASSWORD, next: 'новый-пароль' },
    }, token);
    assert.equal(changed.data.ok, true);

    const oldToken = await call(server.base, '/api/v1/admin/state', {}, token);
    assert.equal(oldToken.status, 401, 'старые токены отозваны');
    const fresh = await call(server.base, '/api/v1/admin/login', { method: 'POST', body: { password: 'новый-пароль' } });
    assert.equal(fresh.status, 200);
  });

  await t.test('панель отдаётся и её можно встроить в iframe', async () => {
    const response = await fetch(server.base + '/');
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /text\/html/);
    assert.equal(response.headers.get('content-security-policy'), 'frame-ancestors *');
    assert.equal(response.headers.get('access-control-allow-origin'), '*');
    const html = await response.text();
    assert.match(html, /Modar SAMP/);
  });

  await t.test('обход каталогов запрещён', async () => {
    const attempt = await fetch(server.base + '/files/mods/..%2f..%2fconfig.json');
    assert.ok(attempt.status === 400 || attempt.status === 404, 'статус ' + attempt.status);
    const attempt2 = await fetch(server.base + '/dl/%2e%2e%2fconfig.json');
    assert.ok(attempt2.status === 400 || attempt2.status === 404, 'статус ' + attempt2.status);
  });
});
