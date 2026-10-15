#!/usr/bin/env node
'use strict';
/*
 * Мок настоящего SA-MP-сервера: отвечает на Query-пакеты по UDP так же, как это делает
 * сервер SA-MP/OpenMP — чтобы проверить лаунчер и сервер лаунчера без живого сервера.
 *
 *   node mock-samp-server.js                  # порт 7777, UTF-8, 3 игрока
 *   node mock-samp-server.js 7799 --cp1251    # русское название в кодировке Windows-1251
 *   node mock-samp-server.js 7800 --empty     # сервер без игроков и под паролем
 *
 * Флаги: --cp1251, --empty, --password, --name "Название", --players N
 *
 * Как модуль: const { createMockServer } = require('./mock-samp-server');
 */

const dgram = require('node:dgram');

/* ------------------------------------------------------------------ кодировка */

const CP1251 = {
  'А': 0xC0, 'Б': 0xC1, 'В': 0xC2, 'Г': 0xC3, 'Д': 0xC4, 'Е': 0xC5, 'Ж': 0xC6, 'З': 0xC7,
  'И': 0xC8, 'Й': 0xC9, 'К': 0xCA, 'Л': 0xCB, 'М': 0xCC, 'Н': 0xCD, 'О': 0xCE, 'П': 0xCF,
  'Р': 0xD0, 'С': 0xD1, 'Т': 0xD2, 'У': 0xD3, 'Ф': 0xD4, 'Х': 0xD5, 'Ц': 0xD6, 'Ч': 0xD7,
  'Ш': 0xD8, 'Щ': 0xD9, 'Ъ': 0xDA, 'Ы': 0xDB, 'Ь': 0xDC, 'Э': 0xDD, 'Ю': 0xDE, 'Я': 0xDF,
  'а': 0xE0, 'б': 0xE1, 'в': 0xE2, 'г': 0xE3, 'д': 0xE4, 'е': 0xE5, 'ж': 0xE6, 'з': 0xE7,
  'и': 0xE8, 'й': 0xE9, 'к': 0xEA, 'л': 0xEB, 'м': 0xEC, 'н': 0xED, 'о': 0xEE, 'п': 0xEF,
  'р': 0xF0, 'с': 0xF1, 'т': 0xF2, 'у': 0xF3, 'ф': 0xF4, 'х': 0xF5, 'ц': 0xF6, 'ч': 0xF7,
  'ш': 0xF8, 'щ': 0xF9, 'ъ': 0xFA, 'ы': 0xFB, 'ь': 0xFC, 'э': 0xFD, 'ю': 0xFE, 'я': 0xFF,
  'ё': 0xB8, 'Ё': 0xA8, '№': 0xB9, '—': 0x97, '«': 0xAB, '»': 0xBB, '…': 0x85,
};

/** Кодирует строку: CP1251 (так делают многие русские серверы) или UTF-8. */
function encode(text, cp1251) {
  if (!cp1251) {
    return Buffer.from(text, 'utf8');
  }
  const out = [];
  for (const ch of text) {
    if (ch.charCodeAt(0) < 0x80) {
      out.push(ch.charCodeAt(0));
    } else if (CP1251[ch] !== undefined) {
      out.push(CP1251[ch]);
    } else {
      out.push(0x3F); // «?»
    }
  }
  return Buffer.from(out);
}

function u16(value) {
  const buf = Buffer.alloc(2);
  buf.writeUInt16LE(value & 0xFFFF, 0);
  return buf;
}

function u32(value) {
  const buf = Buffer.alloc(4);
  buf.writeUInt32LE(value >>> 0, 0);
  return buf;
}

/* ------------------------------------------------------------------ сервер */

/**
 * Поднимает mock-сервер SA-MP.
 * options: { port, hostname, gamemode, language, players, maxPlayers, password, cp1251, rules }
 * Возвращает { server, options } — server привязан к порту, порт можно взять из server.address().
 */
function createMockServer(options = {}) {
  const cp1251 = Boolean(options.cp1251);
  const password = options.password !== undefined ? Boolean(options.password) : Boolean(options.empty);
  const hostname = options.hostname || 'Modar Test Server | Mobile';
  const gamemode = options.gamemode || 'Modar RolePlay';
  const language = options.language || 'Russian';
  const maxPlayers = options.maxPlayers || 100;
  const playerCount = options.players !== undefined ? Number(options.players) : (options.empty ? 0 : 3);

  const players = [];
  for (let i = 0; i < playerCount; i++) {
    players.push({
      id: i,
      name: i === 0 ? 'ModarAdmin' : 'Игрок' + (i + 1),
      score: 100 - i * 7,
      ping: 20 + i * 15,
    });
  }

  const rules = new Map([
    ['mapname', 'San Andreas'],
    ['version', '0.3.7-R2'],
    ['lagcomp', 'On'],
    ['weather', '10'],
    ['worldtime', '12:00'],
    ['weburl', 'https://example.com'],
    ['gravity', '0.008'],
  ]);
  for (const [key, value] of Object.entries(options.rules || {})) {
    rules.set(key, String(value));
  }

  /* строка с длиной в 4 байта — для ответа 'i' */
  const longStr = (text) => {
    const body = encode(text, cp1251);
    return Buffer.concat([u32(body.length), body]);
  };

  /* строка с длиной в 1 байт — для ответов 'r', 'c', 'd' */
  const shortStr = (text) => {
    const body = encode(text, cp1251);
    return Buffer.concat([Buffer.from([body.length & 0xFF]), body]);
  };

  const server = dgram.createSocket('udp4');

  server.on('message', (msg, rinfo) => {
    if (msg.length < 11 || msg.toString('latin1', 0, 4) !== 'SAMP') {
      return;
    }
    const header = msg.subarray(0, 11);
    const opcode = String.fromCharCode(msg[10]);
    let payload = null;

    if (opcode === 'i') {
      payload = Buffer.concat([
        Buffer.from([password ? 1 : 0]),
        u16(playerCount),
        u16(maxPlayers),
        longStr(hostname),
        longStr(gamemode),
        longStr(language),
      ]);
    } else if (opcode === 'r') {
      const parts = [u16(rules.size)];
      for (const [key, value] of rules) {
        parts.push(shortStr(key), shortStr(value));
      }
      payload = Buffer.concat(parts);
    } else if (opcode === 'c') {
      const parts = [u16(playerCount)];
      for (const player of players) {
        parts.push(shortStr(player.name), u32(player.score));
      }
      payload = Buffer.concat(parts);
    } else if (opcode === 'd') {
      const parts = [u16(playerCount)];
      for (const player of players) {
        parts.push(Buffer.from([player.id]), shortStr(player.name), u32(player.score), u32(player.ping));
      }
      payload = Buffer.concat(parts);
    } else if (opcode === 'p') {
      payload = msg.subarray(11, 15); // возвращаем присланные четыре байта
    }

    if (payload === null) {
      return; // неизвестный код — молчим, как настоящий сервер
    }
    server.send(Buffer.concat([header, payload]), rinfo.port, rinfo.address);
  });

  const port = Number(options.port || 0);
  const ready = new Promise((resolve) => {
    server.bind(port, () => resolve(server.address()));
  });

  return {
    server,
    ready,
    info: { hostname, gamemode, language, playerCount, maxPlayers, password, cp1251, rules: Object.fromEntries(rules) },
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

/* ------------------------------------------------------------------ CLI */

if (require.main === module) {
  const args = process.argv.slice(2);
  const portArg = args.find((a) => /^\d+$/.test(a));
  const nameIndex = args.indexOf('--name');
  const playersIndex = args.indexOf('--players');
  const empty = args.includes('--empty');

  const mock = createMockServer({
    port: portArg ? Number(portArg) : 7777,
    cp1251: args.includes('--cp1251'),
    empty,
    password: empty || args.includes('--password'),
    hostname: nameIndex >= 0 ? args[nameIndex + 1] : undefined,
    players: playersIndex >= 0 ? Number(args[playersIndex + 1]) : undefined,
  });

  mock.ready.then((address) => {
    console.log('[mock-samp] слушаем ' + address.address + ':' + address.port
      + ' — «' + mock.info.hostname + '», '
      + (mock.info.playerCount === 0 ? 'пусто' : mock.info.playerCount + ' игроков')
      + (mock.info.password ? ', с паролем' : ', без пароля')
      + (mock.info.cp1251 ? ', CP1251' : ', UTF-8'));
  });
}

module.exports = { createMockServer };
