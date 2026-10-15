'use strict';
/*
 * Опрос SA-MP-серверов по UDP (протокол Query) — та же логика, что и в приложении
 * (samp-launcher/app/src/main/java/com/modar/samp/SampQuery.java), но на Node.
 * Нужен серверу лаунчера, чтобы показывать онлайн в панели и отдавать статус приложению.
 */

const dgram = require('node:dgram');
const dns = require('node:dns');
const crypto = require('node:crypto');

const DEFAULT_PORT = 7777;
const DEFAULT_TIMEOUT = 1500;
const CACHE_TTL = 20000;

/* ------------------------------------------------------------------ разбор строк */

/**
 * SA-MP не сообщает кодировку. Русские серверы обычно отдают Windows-1251,
 * остальные — UTF-8: пробуем UTF-8, при «битых» байтах декодируем как CP1251.
 */
function decodeString(buf) {
  const utf8 = buf.toString('utf8');
  if (!utf8.includes('\uFFFD')) {
    return utf8;
  }
  return buf.toString('latin1')
    .split('')
    .map((ch) => {
      const code = ch.charCodeAt(0);
      if (code < 0x80) {
        return ch;
      }
      if (code >= 0xC0) {
        return String.fromCharCode(0x0410 + (code - 0xC0));
      }
      const map = {
        0x80: 'Ђ', 0x81: 'Ѓ', 0x82: '‚', 0x83: 'ѓ', 0x84: '„', 0x85: '…', 0x86: '†', 0x87: '‡',
        0x88: '€', 0x89: '‰', 0x8A: 'Љ', 0x8B: '‹', 0x8C: 'Њ', 0x8D: 'Ќ', 0x8E: 'Ћ', 0x8F: 'Џ',
        0x90: 'ђ', 0x91: '‘', 0x92: '’', 0x93: '“', 0x94: '”', 0x95: '•', 0x96: '–', 0x97: '—',
        0x99: '™', 0x9A: 'љ', 0x9B: '›', 0x9C: 'њ', 0x9D: 'ќ', 0x9E: 'ћ', 0x9F: 'џ',
        0xA1: 'Ў', 0xA2: 'ў', 0xA3: 'Ј', 0xA5: 'Ґ', 0xA8: 'Ё', 0xAA: 'Є', 0xAF: 'Ї',
        0xB2: 'І', 0xB3: 'і', 0xB4: 'ґ', 0xB8: 'ё', 0xB9: '№', 0xBA: 'є', 0xBD: 'Ѕ', 0xBE: 'ѕ', 0xBF: 'ї',
      };
      return map[code] !== undefined ? map[code] : '?';
    })
    .join('');
}

/* ------------------------------------------------------------------ сеть */

function buildPacket(ip, port, opcode) {
  const query = Buffer.alloc(11);
  query.write('SAMP', 0, 'latin1');
  const octets = ip.split('.').map((part) => Number(part) & 0xFF);
  for (let i = 0; i < 4; i++) {
    query[4 + i] = octets[i] || 0;
  }
  query[8] = port & 0xFF;
  query[9] = (port >> 8) & 0xFF;
  query[10] = opcode.charCodeAt(0);
  return query;
}

function resolve(host) {
  return new Promise((resolve, reject) => {
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
      resolve(host);
      return;
    }
    dns.lookup(host, { family: 4 }, (err, address) => {
      if (err) {
        reject(new Error('Не удалось определить адрес ' + host));
      } else {
        resolve(address);
      }
    });
  });
}

function exchange(ip, port, opcode, timeout, extra) {
  return new Promise((resolve, reject) => {
    const socket = dgram.createSocket('udp4');
    const query = Buffer.concat([buildPacket(ip, port, opcode), extra || Buffer.alloc(0)]);
    const started = Date.now();
    let attempts = 0;
    let timer = null;
    let settled = false;

    /** Единственная точка выхода: снимает таймер, закрывает сокет, не даёт «двойных» ошибок. */
    const done = (error, data, rtt) => {
      if (settled) {
        return;
      }
      settled = true;
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      socket.removeAllListeners();
      try {
        socket.close();
      } catch (ignored) {
        /* уже закрыт */
      }
      if (error) {
        reject(error);
      } else {
        resolve({ data, rtt });
      }
    };

    socket.on('message', (msg) => {
      if (settled) {
        return;
      }
      if (msg.length < 11 || msg.toString('latin1', 0, 4) !== 'SAMP') {
        return;
      }
      if (msg[10] !== opcode.charCodeAt(0)) {
        return; // ответ на другой запрос
      }
      done(null, msg, Date.now() - started);
    });

    socket.on('error', (err) => done(err));

    const send = () => {
      if (settled) {
        return;
      }
      attempts += 1;
      socket.send(query, port, ip, (err) => {
        if (err) {
          done(err);
        }
      });
      const left = timeout - (Date.now() - started);
      if (left <= 0) {
        done(new Error('Сервер не ответил за ' + timeout + ' мс'));
        return;
      }
      timer = setTimeout(() => {
        if (settled) {
          return;
        }
        if (attempts >= 2) {
          done(new Error('Сервер не ответил за ' + timeout + ' мс'));
        } else {
          send();
        }
      }, Math.max(150, left));
    };

    send();
  });
}

/* ------------------------------------------------------------------ ответы */

function parseInfo(buf, host, port, rtt) {
  let pos = 16;
  const readString = () => {
    const length = buf.readUInt32LE(pos);
    pos += 4;
    const value = decodeString(buf.subarray(pos, pos + length));
    pos += length;
    return value;
  };
  const info = {
    online: true,
    host,
    port,
    password: buf[11] !== 0,
    players: buf.readUInt16LE(12),
    maxPlayers: buf.readUInt16LE(14),
    hostname: readString(),
    gamemode: readString(),
    language: readString(),
    rtt,
    time: Date.now(),
  };
  return info;
}

function parseRules(buf) {
  const rules = {};
  const count = buf.readUInt16LE(11);
  let pos = 13;
  for (let i = 0; i < count && pos < buf.length; i++) {
    const nameLength = buf[pos];
    pos += 1;
    const name = decodeString(buf.subarray(pos, pos + nameLength));
    pos += nameLength;
    if (pos >= buf.length) {
      break;
    }
    const valueLength = buf[pos];
    pos += 1;
    const value = decodeString(buf.subarray(pos, pos + valueLength));
    pos += valueLength;
    rules[name] = value;
  }
  return rules;
}

function parsePlayers(buf, detailed) {
  const players = [];
  const count = buf.readUInt16LE(11);
  let pos = 13;
  for (let i = 0; i < count; i++) {
    const player = { id: detailed ? buf[pos++] : -1, name: '', score: 0, ping: -1 };
    if (pos >= buf.length) {
      break;
    }
    const nameLength = buf[pos];
    pos += 1;
    player.name = decodeString(buf.subarray(pos, pos + nameLength));
    pos += nameLength;
    player.score = buf.readUInt32LE(pos);
    pos += 4;
    if (detailed) {
      player.ping = buf.readUInt32LE(pos);
      pos += 4;
    }
    players.push(player);
  }
  return players;
}

/* ------------------------------------------------------------------ API модуля */

/** Основная информация о сервере. При неудаче возвращает {online:false,error} — без исключений. */
async function info(host, port = DEFAULT_PORT, timeout = DEFAULT_TIMEOUT) {
  try {
    const ip = await resolve(host);
    const { data, rtt } = await exchange(ip, port, 'i', timeout);
    return parseInfo(data, host, port, rtt);
  } catch (err) {
    return { online: false, host, port, error: String(err.message || err), time: Date.now() };
  }
}

async function rules(host, port = DEFAULT_PORT, timeout = DEFAULT_TIMEOUT) {
  const ip = await resolve(host);
  const { data } = await exchange(ip, port, 'r', timeout);
  return parseRules(data);
}

async function players(host, port = DEFAULT_PORT, timeout = DEFAULT_TIMEOUT) {
  const ip = await resolve(host);
  try {
    const { data } = await exchange(ip, port, 'd', timeout);
    return parsePlayers(data, true);
  } catch (err) {
    const { data } = await exchange(ip, port, 'c', timeout);
    return parsePlayers(data, false);
  }
}

/** Задержка по коду 'p' (сервер возвращает отправленные случайные байты). */
async function ping(host, port = DEFAULT_PORT, timeout = DEFAULT_TIMEOUT) {
  const ip = await resolve(host);
  const extra = crypto.randomBytes(4);
  const { data, rtt } = await exchange(ip, port, 'p', timeout, extra);
  for (let i = 0; i < 4; i++) {
    if (data[11 + i] !== extra[i]) {
      throw new Error('Ответ на запрос задержки не совпал с запросом');
    }
  }
  return rtt;
}

/* ------------------------------------------------------------------ кэш */

const cache = new Map();

/**
 * Статус сервера с кэшем: панель и приложение опрашивают один и тот же адрес,
 * а лишние пакеты серверу ни к чему. ttl истёк → опрашиваем заново.
 */
async function cachedInfo(host, port = DEFAULT_PORT, options = {}) {
  const key = host + ':' + port;
  const ttl = options.ttl === undefined ? CACHE_TTL : options.ttl;
  const timeout = options.timeout || DEFAULT_TIMEOUT;
  const cached = cache.get(key);
  if (!options.force && cached && Date.now() - cached.at < ttl) {
    return { ...cached.data, cached: true };
  }
  const fresh = await info(host, port, timeout);
  cache.set(key, { at: Date.now(), data: fresh });
  return { ...fresh, cached: false };
}

function clearCache() {
  cache.clear();
}

module.exports = {
  DEFAULT_PORT,
  DEFAULT_TIMEOUT,
  CACHE_TTL,
  info,
  rules,
  players,
  ping,
  cachedInfo,
  clearCache,
  parseInfo,
  parseRules,
  parsePlayers,
  decodeString,
  buildPacket,
};
