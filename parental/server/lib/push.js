'use strict';
/*
 * Web Push без сторонних библиотек (RFC 8291 + RFC 8188 «aes128gcm» + VAPID ES256).
 *
 * Нужен, чтобы телефон родителя получал уведомление сразу — тревога SOS, «ребёнок
 * пришёл в школу», низкий заряд — даже когда приложение родителя закрыто.
 *
 * Включается автоматически, если заданы переменные окружения:
 *   VAPID_PUBLIC_KEY=<base64url>   (создать: node tools/gen-vapid.js)
 *   VAPID_PRIVATE_KEY=<base64url>
 *   VAPID_SUBJECT=mailto:you@example.com   (необязательно)
 * Если ключей нет — модуль молча ничего не делает, всё остальное работает как обычно.
 */

const crypto = require('node:crypto');

const publicKey = process.env.VAPID_PUBLIC_KEY || '';
const privateKey = process.env.VAPID_PRIVATE_KEY || '';

function b64u(buffer) {
  return Buffer.from(buffer).toString('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromB64u(text) {
  const value = String(text || '').replace(/-/g, '+').replace(/_/g, '/');
  const pad = value.length % 4 ? '='.repeat(4 - (value.length % 4)) : '';
  return Buffer.from(value + pad, 'base64');
}

/* HKDF (RFC 5869) на HMAC-SHA256. */
function hkdf(salt, ikm, info, length) {
  const prk = crypto.createHmac('sha256', salt).update(ikm).digest();
  const out = crypto.createHmac('sha256', prk)
    .update(Buffer.concat([Buffer.from(info), Buffer.from([1])]))
    .digest();
  return out.slice(0, length);
}

let cachedKey = null;

function vapidKeyObject() {
  if (cachedKey) return cachedKey;
  const pub = fromB64u(publicKey);            // 65 байт: 0x04 || X(32) || Y(32)
  const d = fromB64u(privateKey);             // 32 байта — приватный скаляр
  if (pub.length !== 65 || d.length !== 32) {
    throw new Error('VAPID-ключи должны быть в base64url: публичный 65 байт, приватный 32 байта');
  }
  cachedKey = crypto.createPrivateKey({
    key: {
      kty: 'EC',
      crv: 'P-256',
      x: b64u(pub.slice(1, 33)),
      y: b64u(pub.slice(33, 65)),
      d: b64u(d),
    },
    format: 'jwk',
  });
  return cachedKey;
}

function vapidHeader(audience) {
  const header = b64u(Buffer.from(JSON.stringify({ typ: 'JWT', alg: 'ES256' })));
  const payload = b64u(Buffer.from(JSON.stringify({
    aud: audience,
    exp: Math.floor(Date.now() / 1000) + 12 * 3600,
    sub: process.env.VAPID_SUBJECT || 'mailto:admin@example.com',
  })));
  const data = Buffer.from(header + '.' + payload, 'utf8');
  const signature = crypto.sign('sha256', data, {
    key: vapidKeyObject(),
    dsaEncoding: 'ieee-p1363',                 // JOSE ждёт r||s, а не DER
  });
  return 'vapid t=' + header + '.' + payload + '.' + b64u(signature) + ', k=' + publicKey;
}

/** Шифрует полезную нагрузку для конкретной подписки браузера. */
function encryptPayload(subscription, payload) {
  const clientPub = fromB64u(subscription.keys.p256dh);
  const authSecret = fromB64u(subscription.keys.auth);

  const ecdh = crypto.createECDH('prime256v1');
  ecdh.generateKeys();
  const serverPub = ecdh.getPublicKey();                    // 65 байт, без сжатия
  const shared = ecdh.computeSecret(clientPub);

  const ikm = hkdf(
    authSecret,
    shared,
    Buffer.concat([Buffer.from('WebPush: info\0', 'utf8'), clientPub, serverPub]),
    32
  );

  const salt = crypto.randomBytes(16);
  const cek = hkdf(salt, ikm, Buffer.from('Content-Encoding: aes128gcm\0', 'utf8'), 16);
  const nonce = hkdf(salt, ikm, Buffer.from('Content-Encoding: nonce\0', 'utf8'), 12);

  const plain = Buffer.concat([Buffer.from(payload, 'utf8'), Buffer.from([2])]); // 0x02 — запись-«хвост»
  const cipher = crypto.createCipheriv('aes-128-gcm', cek, nonce);
  const body = Buffer.concat([cipher.update(plain), cipher.final(), cipher.getAuthTag()]);

  const rs = Buffer.alloc(4);
  rs.writeUInt32BE(4096, 0);
  return Buffer.concat([salt, rs, Buffer.from([serverPub.length]), serverPub, body]);
}

/** Отправляет одно уведомление. Возвращает true | 'gone' | false. */
async function sendOne(subscription, message) {
  if (!publicKey || !privateKey) return false;
  try {
    const audience = new URL(subscription.endpoint).origin;
    const body = encryptPayload(subscription, message || '');
    const res = await fetch(subscription.endpoint, {
      method: 'POST',
      headers: {
        Authorization: vapidHeader(audience),
        'Content-Encoding': 'aes128gcm',
        'Content-Type': 'application/octet-stream',
        TTL: '86400',
        Urgency: 'high',
      },
      body,
    });
    if (res.status === 404 || res.status === 410) return 'gone';
    if (!res.ok) console.error('[push] сервис ответил', res.status);
    return res.ok;
  } catch (e) {
    console.error('[push] не отправилось:', e.message);
    return false;
  }
}

/** Рассылает уведомление во все браузеры семьи. */
async function pushToFamily(store, familyId, message) {
  if (!publicKey || !privateKey) return 0;
  const subs = (store.data.pushSubs || []).filter((s) => s.familyId === familyId);
  if (!subs.length) return 0;
  let sent = 0;
  const dead = [];
  for (const sub of subs) {
    const result = await sendOne(sub, JSON.stringify({
      title: message.title || 'Modar Family',
      body: message.body || '',
      ts: Date.now(),
    }));
    if (result === 'gone') dead.push(sub.endpoint);
    else if (result) sent += 1;
  }
  if (dead.length) {
    store.data.pushSubs = (store.data.pushSubs || []).filter((s) => dead.indexOf(s.endpoint) < 0);
    store.touch();
  }
  return sent;
}

module.exports = { pushToFamily, sendOne, encryptPayload, vapidHeader, publicKey, b64u, fromB64u };
