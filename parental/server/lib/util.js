'use strict';
/*
 * Мелкие утилиты сервера Modar Family: идентификаторы, хеши, JSON, время.
 * Никаких сторонних зависимостей — только стандартная библиотека Node.
 */

const crypto = require('node:crypto');

/* ---------------------------------------------------------------- служебное */

function rid(prefix) {
  return prefix + '_' + Date.now().toString(36) + crypto.randomBytes(4).toString('hex');
}

function token() {
  return crypto.randomBytes(24).toString('hex');
}

/** Шестизначный код сопряжения, который родитель диктует ребёнку. */
function pairCode() {
  return String(crypto.randomInt(100000, 1000000));
}

function salt() {
  return crypto.randomBytes(12).toString('hex');
}

function hashPassword(password, saltValue) {
  return crypto.scryptSync(String(password), String(saltValue), 32).toString('hex');
}

/** Сравнение строк без утечки по времени (для токенов и хешей). */
function sameSecret(a, b) {
  const left = Buffer.from(String(a || ''), 'utf8');
  const right = Buffer.from(String(b || ''), 'utf8');
  if (left.length !== right.length || left.length === 0) {
    return false;
  }
  return crypto.timingSafeEqual(left, right);
}

/* -------------------------------------------------------------------- время */

function now() {
  return Date.now();
}

/** Ключ дня в локальной зоне сервера: 2026-09-20. */
function dayKey(ts) {
  const d = ts ? new Date(ts) : new Date();
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

/** «21:30» → 1290 минут от полуночи. Возвращает -1, если формат не распознан. */
function minutesOfDay(text) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(text || '').trim());
  if (!m) return -1;
  const h = Number(m[1]);
  const min = Number(m[2]);
  if (h > 23 || min > 59) return -1;
  return h * 60 + min;
}

function clampNumber(value, min, max, fallback) {
  const n = Number(value);
  if (!isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, n));
}

function trimText(value, max) {
  return String(value == null ? '' : value).slice(0, max);
}

/* --------------------------------------------------------------- HTTP-слой */

function json(res, code, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function ok(res, payload) {
  json(res, 200, Object.assign({ ok: true }, payload || {}));
}

function fail(res, code, message) {
  json(res, code, { ok: false, error: message });
}

/** Читает тело запроса целиком, но не больше limit байт. */
function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('Слишком большой запрос'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

async function readJson(req, limit) {
  const raw = await readBody(req, limit || 256 * 1024);
  if (!raw.trim()) return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch (e) {
    const err = new Error('Некорректный JSON в теле запроса');
    err.status = 400;
    throw err;
  }
}

/** Достаёт Bearer-токен из заголовка Authorization (или X-Api-Token). */
function bearer(req) {
  const header = req.headers['authorization'] || '';
  const m = /^Bearer\s+(.+)$/i.exec(header.trim());
  if (m) return m[1].trim();
  const alt = req.headers['x-api-token'];
  return typeof alt === 'string' ? alt.trim() : '';
}

function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (typeof fwd === 'string' && fwd.length) {
    return fwd.split(',')[0].trim();
  }
  return (req.socket && req.socket.remoteAddress) || '';
}

module.exports = {
  rid,
  token,
  pairCode,
  salt,
  hashPassword,
  sameSecret,
  now,
  dayKey,
  minutesOfDay,
  clampNumber,
  trimText,
  json,
  ok,
  fail,
  readJson,
  readBody,
  bearer,
  clientIp,
};
