'use strict';
/*
 * Проверка опроса SA-MP-серверов (lib/query.js) на настоящем UDP:
 * поднимаем mock-сервер и опрашиваем его так же, как это делает приложение.
 *
 *   npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');

const { createMockServer } = require('../../tools/mock-samp-server.js');
const query = require('../lib/query.js');

function freePort() {
  return new Promise((resolve) => {
    const probe = net.createServer();
    probe.listen(0, '127.0.0.1', () => {
      const port = probe.address().port;
      probe.close(() => resolve(port));
    });
  });
}

test('информация о сервере (код i)', async (t) => {
  const mock = createMockServer({ port: 0, hostname: 'Test | Server', players: 7, maxPlayers: 64 });
  const address = await mock.ready;
  t.after(() => mock.close());

  const info = await query.info('127.0.0.1', address.port, 1500);
  assert.equal(info.online, true);
  assert.equal(info.hostname, 'Test | Server');
  assert.equal(info.gamemode, 'Modar RolePlay');
  assert.equal(info.language, 'Russian');
  assert.equal(info.players, 7);
  assert.equal(info.maxPlayers, 64);
  assert.equal(info.password, false);
  assert.ok(info.rtt >= 0 && info.rtt < 1000, 'задержка в разумных пределах: ' + info.rtt);
});

test('сервер под паролем', async (t) => {
  const mock = createMockServer({ port: 0, password: true, empty: true });
  const address = await mock.ready;
  t.after(() => mock.close());

  const info = await query.info('127.0.0.1', address.port, 1500);
  assert.equal(info.password, true);
  assert.equal(info.players, 0);
});

test('правила сервера (код r)', async (t) => {
  const mock = createMockServer({ port: 0 });
  const address = await mock.ready;
  t.after(() => mock.close());

  const rules = await query.rules('127.0.0.1', address.port, 1500);
  assert.equal(rules.mapname, 'San Andreas');
  assert.equal(rules.version, '0.3.7-R2');
  assert.equal(rules.lagcomp, 'On');
  assert.ok(Object.keys(rules).length >= 7);
});

test('список игроков (код d) с пингом', async (t) => {
  const mock = createMockServer({ port: 0, players: 5 });
  const address = await mock.ready;
  t.after(() => mock.close());

  const players = await query.players('127.0.0.1', address.port, 1500);
  assert.equal(players.length, 5);
  assert.equal(players[0].name, 'ModarAdmin');
  assert.equal(players[0].score, 100);
  assert.equal(players[0].ping, 20);
  assert.equal(players[4].ping, 20 + 4 * 15);
});

test('краткий список игроков (код c) — без пинга', async (t) => {
  const mock = createMockServer({ port: 0, players: 3 });
  const address = await mock.ready;
  t.after(() => mock.close());

  const players = await query.players('127.0.0.1', address.port, 1500);
  assert.equal(players.length, 3);
  assert.equal(players[1].score, 93);
});

test('задержка по коду p', async (t) => {
  const mock = createMockServer({ port: 0 });
  const address = await mock.ready;
  t.after(() => mock.close());

  const rtt = await query.ping('127.0.0.1', address.port, 1500);
  assert.ok(rtt >= 0 && rtt < 1000, 'задержка: ' + rtt);
});

test('оффлайн: сервер закрыт', async () => {
  const port = await freePort();
  const info = await query.info('127.0.0.1', port, 600);
  assert.equal(info.online, false);
  assert.ok(info.error, 'есть описание ошибки');
});

test('русское название в Windows-1251', async (t) => {
  const mock = createMockServer({ port: 0, cp1251: true, hostname: 'Русский Мод | Сервер' });
  const address = await mock.ready;
  t.after(() => mock.close());

  const info = await query.info('127.0.0.1', address.port, 1500);
  assert.equal(info.hostname, 'Русский Мод | Сервер');
});

test('кэш: второй запрос берётся из памяти', async (t) => {
  const mock = createMockServer({ port: 0, hostname: 'Cache Test', players: 2 });
  const address = await mock.ready;
  t.after(() => mock.close());
  t.after(() => query.clearCache());

  const first = await query.cachedInfo('127.0.0.1', address.port, { ttl: 5000 });
  assert.equal(first.cached, false);
  const second = await query.cachedInfo('127.0.0.1', address.port, { ttl: 5000 });
  assert.equal(second.cached, true);
  assert.equal(second.hostname, 'Cache Test');

  const forced = await query.cachedInfo('127.0.0.1', address.port, { ttl: 5000, force: true });
  assert.equal(forced.cached, false);
});

test('пакет запроса собран по протоколу', () => {
  const packet = query.buildPacket('192.168.200.103', 7777, 'i');
  assert.equal(packet.length, 11);
  assert.equal(packet.toString('latin1', 0, 4), 'SAMP');
  assert.deepEqual([...packet.subarray(4, 8)], [192, 168, 200, 103]);
  assert.equal(packet[8], 7777 & 0xFF);
  assert.equal(packet[9], (7777 >> 8) & 0xFF);
  assert.equal(packet[10], 'i'.charCodeAt(0));
  assert.equal(query.buildPacket('10.0.0.1', 27015, 'r')[10], 'r'.charCodeAt(0));
});

test('decodeString: UTF-8 и CP1251', () => {
  assert.equal(query.decodeString(Buffer.from('Привет', 'utf8')), 'Привет');
  const cp1251 = Buffer.from([0xCF, 0xF0, 0xE8, 0xE2, 0xE5, 0xF2]); // «Привет» в CP1251
  assert.equal(query.decodeString(cp1251), 'Привет');
});
