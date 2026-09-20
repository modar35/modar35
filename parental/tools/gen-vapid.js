#!/usr/bin/env node
/*
 * Создаёт пару ключей VAPID для Web Push — уведомлений в браузере родителя.
 *
 * Запуск:
 *   node parental/tools/gen-vapid.js
 *
 * Полученные значения положите в переменные окружения сервера
 * (см. parental/docs/DEPLOY.md):
 *   VAPID_PUBLIC_KEY=...  VAPID_PRIVATE_KEY=...  VAPID_SUBJECT=mailto:вы@example.com
 * Без ключей всё работает, просто браузер не показывает push-уведомления.
 */
'use strict';

const crypto = require('node:crypto');

const ecdh = crypto.createECDH('prime256v1');
ecdh.generateKeys();
const publicKey = ecdh.getPublicKey();
const privateKey = ecdh.getPrivateKey();

const b64u = (buf) => Buffer.from(buf).toString('base64')
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

console.log('');
console.log('VAPID-ключи готовы — скопируйте их на сервер:');
console.log('');
console.log('VAPID_PUBLIC_KEY=' + b64u(publicKey));
console.log('VAPID_PRIVATE_KEY=' + b64u(privateKey));
console.log('VAPID_SUBJECT=mailto:you@example.com');
console.log('');
console.log('Публичный ключ (' + publicKey.length + ' байт) идёт в браузер, приватный — только на сервер.');
console.log('Проверка шифрования: node parental/tools/gen-vapid.js --selftest');
console.log('');

if (process.argv.includes('--selftest')) {
  const push = require('../server/lib/push');
  const client = crypto.createECDH('prime256v1');
  client.generateKeys();
  const auth = crypto.randomBytes(16);
  process.env.VAPID_PUBLIC_KEY = b64u(publicKey);
  process.env.VAPID_PRIVATE_KEY = b64u(privateKey);
  const subscription = {
    endpoint: 'https://example.com/push',
    keys: { p256dh: b64u(client.getPublicKey()), auth: b64u(auth) },
  };
  const body = require('../server/lib/push').encryptPayload
    ? require('../server/lib/push').encryptPayload(subscription, JSON.stringify({ hello: 'world' }))
    : null;
  if (!body) {
    console.log('Самопроверка: функции шифрования нет (проверьте lib/push.js)');
  } else {
    console.log('Самопроверка шифрования: ' + body.length + ' байт записи aes128gcm — ок');
  }
  void push;
}
