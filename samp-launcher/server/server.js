'use strict';
/*
 * Modar SAMP Launcher server — сервер лаунчера SA-MP.
 *
 *   node server.js                 обычный запуск (порт 8090)
 *   DEMO=1 node server.js          с примерами: серверы, новости, сборки клиента
 *
 * Переменные окружения:
 *   PORT=8090            порт HTTP
 *   HOST=0.0.0.0         интерфейс
 *   DATA_DIR=./data      где лежат servers.json, mods.json, файлы модов
 *   QUERY_TIMEOUT=1500   таймаут опроса SA-MP-серверов, мс
 *   MAX_UPLOAD=...       предел размера загружаемого файла, байт
 *
 * Что отдаёт:
 *   /api/v1/ping | servers?live=1 | mods | news | clients | launcher — приложению
 *   /api/v1/admin/*  — панель (пароль по умолчанию «modar», меняется в панели)
 *   /files/mods/*    — файлы мод-паков
 *   /dl/*            — сборки лаунчера (APK)
 *   /                — веб-панель
 */

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const { Store } = require('./lib/store');
const { createApi } = require('./lib/api');
const U = require('./lib/util');

const PORT = Number(process.env.PORT || 8090);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const PUBLIC_DIR = path.join(__dirname, 'public');
const DEMO = process.env.DEMO === '1';
const QUERY_TIMEOUT = Number(process.env.QUERY_TIMEOUT || 1500);
const MAX_UPLOAD = Number(process.env.MAX_UPLOAD || 1024 * 1024 * 1024);
const VERSION = require('./package.json').version;

const store = new Store(DATA_DIR);
if (DEMO) {
  store.seedDemo();
}

const modsDir = path.join(DATA_DIR, 'mods');
const dlDir = path.join(DATA_DIR, 'dl');
fs.mkdirSync(modsDir, { recursive: true });
fs.mkdirSync(dlDir, { recursive: true });

const api = createApi(store, {
  modsDir,
  dlDir,
  queryTimeout: QUERY_TIMEOUT,
  maxUpload: MAX_UPLOAD,
});

/* ------------------------------------------------------------------ статика */

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.apk': 'application/vnd.android.package-archive',
  '.zip': 'application/zip',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
};

function commonHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Allow-Methods': 'GET, POST, PATCH, PUT, DELETE, OPTIONS',
    // панель встраивается в iframe предпросмотра
    'Content-Security-Policy': "frame-ancestors *",
    'X-Content-Type-Options': 'nosniff',
  };
}

/** Отдаёт файл с поддержкой Range — так качаются большие моды и APK. */
function sendFile(req, res, file, options = {}) {
  let stat;
  try {
    stat = fs.statSync(file);
  } catch (err) {
    U.fail(res, 'Файл не найден', 404);
    return;
  }
  if (stat.isDirectory()) {
    U.fail(res, 'Это каталог', 404);
    return;
  }
  const type = MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
  const headers = {
    ...commonHeaders(),
    'Content-Type': type,
    'Accept-Ranges': 'bytes',
    'Cache-Control': options.cache ? 'public, max-age=3600' : 'no-store',
  };
  const range = req.headers.range;
  if (range) {
    const match = /bytes=(\d*)-(\d*)/.exec(range);
    if (match) {
      const start = match[1] ? Number(match[1]) : 0;
      const end = match[2] ? Number(match[2]) : stat.size - 1;
      if (start >= stat.size || end >= stat.size || start > end) {
        headers['Content-Range'] = 'bytes */' + stat.size;
        res.writeHead(416, headers);
        res.end();
        return;
      }
      res.writeHead(206, {
        ...headers,
        'Content-Range': 'bytes ' + start + '-' + end + '/' + stat.size,
        'Content-Length': end - start + 1,
      });
      fs.createReadStream(file, { start, end }).pipe(res);
      return;
    }
  }
  res.writeHead(200, { ...headers, 'Content-Length': stat.size });
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  fs.createReadStream(file).pipe(res);
}

function resolveInside(root, relative) {
  const target = path.join(root, relative);
  const normalizedRoot = path.resolve(root) + path.sep;
  const normalizedTarget = path.resolve(target);
  if (!normalizedTarget.startsWith(normalizedRoot)) {
    return null;
  }
  return normalizedTarget;
}

/* ------------------------------------------------------------------ маршруты */

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://' + (req.headers.host || 'localhost'));

  if (req.method === 'OPTIONS') {
    res.writeHead(204, commonHeaders());
    res.end();
    return;
  }

  try {
    if (await api.handle(req, res, url)) {
      return;
    }

    const pathname = decodeURIComponent(url.pathname);

    /* файлы мод-паков */
    if (pathname.startsWith('/files/mods/')) {
      const relative = pathname.slice('/files/mods/'.length);
      const file = resolveInside(modsDir, relative);
      if (!file) {
        U.fail(res, 'Недопустимый путь', 400);
        return;
      }
      sendFile(req, res, file, { cache: true });
      return;
    }

    /* сборки лаунчера */
    if (pathname.startsWith('/dl/')) {
      const relative = pathname.slice('/dl/'.length);
      const file = resolveInside(dlDir, relative);
      if (!file) {
        U.fail(res, 'Недопустимый путь', 400);
        return;
      }
      sendFile(req, res, file, { cache: true });
      return;
    }

    /* предпросмотр интерфейса приложения (samp-launcher/docs/preview.html) */
    if (pathname === '/preview' || pathname === '/preview/') {
      sendFile(req, res, path.join(__dirname, '..', 'docs', 'preview.html'));
      return;
    }

    /* панель */
    const relative = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
    const file = resolveInside(PUBLIC_DIR, relative);
    if (!file || !fs.existsSync(file)) {
      U.fail(res, 'Страница не найдена', 404);
      return;
    }
    sendFile(req, res, file);
  } catch (err) {
    console.error('[modar-samp] ошибка запроса:', err);
    if (!res.headersSent) {
      U.fail(res, err, 500);
    } else {
      res.end();
    }
  }
});

/* Медленная загрузка файла не должна ронять сервер. */
server.requestTimeout = 0;
server.headersTimeout = 60000;

server.listen(PORT, HOST, () => {
  console.log('[modar-samp] сервер лаунчера v' + VERSION + ' слушает http://' + HOST + ':' + PORT);
  console.log('[modar-samp] данные: ' + DATA_DIR + (DEMO ? ' (демо-режим)' : ''));
  console.log('[modar-samp] панель: http://localhost:' + PORT + '/  пароль: ' +
    (DEMO ? store.config().adminPassword : '(смотрите data/config.json)'));
});

module.exports = { server, store };
