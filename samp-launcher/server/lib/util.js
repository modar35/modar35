'use strict';
/* Мелкие помощники сервера: ответы JSON, хеши, имена файлов. */

const fs = require('node:fs');
const crypto = require('node:crypto');

/** Ответ в JSON. */
function json(res, data, code = 200) {
  const body = JSON.stringify(data, null, 2);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function ok(res, data = {}) {
  json(res, { ok: true, ...data });
}

function fail(res, error, code = 400) {
  json(res, { ok: false, error: String(error && error.message ? error.message : error) }, code);
}

/** Тело запроса как JSON с ограничением размера. */
function readJson(req, limit = 2 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('Слишком большой запрос'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (chunks.length === 0) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (err) {
        reject(new Error('Некорректный JSON в запросе'));
      }
    });
    req.on('error', reject);
  });
}

function sha256File(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(file);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('end', () => resolve(hash.digest('hex')));
    stream.on('error', reject);
  });
}

function sha256(text) {
  return crypto.createHash('sha256').update(String(text)).digest('hex');
}

/** Имя файла без переходов по каталогам: только буквы, цифры, точка, тире, подчёркивание. */
function safeName(name) {
  const base = String(name || 'file').split(/[\\/]/).pop();
  const cleaned = base.replace(/[^A-Za-z0-9._-]/g, '_').replace(/^\.+/, '');
  return cleaned || 'file';
}

/** Имя для ссылок: из названия делаем читаемый слаг. */
function slug(text, fallback = 'item') {
  const map = {
    а: 'a', б: 'b', в: 'v', г: 'g', д: 'd', е: 'e', ё: 'e', ж: 'zh', з: 'z', и: 'i', й: 'y',
    к: 'k', л: 'l', м: 'm', н: 'n', о: 'o', п: 'p', р: 'r', с: 's', т: 't', у: 'u', ф: 'f',
    х: 'h', ц: 'c', ч: 'ch', ш: 'sh', щ: 'sch', ъ: '', ы: 'y', ь: '', э: 'e', ю: 'yu', я: 'ya',
  };
  const text2 = String(text || '').toLowerCase().split('').map((ch) => map[ch] !== undefined ? map[ch] : ch).join('');
  const result = text2.replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 48);
  return result || fallback;
}

function randomId(prefix = 'id') {
  return prefix + '-' + crypto.randomBytes(4).toString('hex');
}

/** Размер в удобном виде (для панели). */
function sizeLabel(bytes) {
  if (!bytes) {
    return '0 Б';
  }
  const units = ['Б', 'КБ', 'МБ', 'ГБ'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return (unit === 0 ? value : value.toFixed(1)) + ' ' + units[unit];
}

module.exports = {
  json, ok, fail, readJson, sha256, sha256File, safeName, slug, randomId, sizeLabel,
};
