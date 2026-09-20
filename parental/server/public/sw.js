/* Modar Family — service worker: показывает push-уведомления родителю. */
'use strict';

self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('push', (event) => {
  let payload = { title: 'Modar Family', body: 'Новое событие' };
  try {
    if (event.data) payload = Object.assign(payload, event.data.json());
  } catch (e) {
    if (event.data) payload.body = event.data.text();
  }
  event.waitUntil(self.registration.showNotification(payload.title || 'Modar Family', {
    body: payload.body || '',
    icon: '/icon.png',
    badge: '/icon.png',
    tag: 'modar-' + (payload.ts || Date.now()),
    vibrate: [200, 100, 200],
    data: { url: '/' },
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(self.clients.matchAll({ type: 'window' }).then((list) => {
    for (const client of list) {
      if ('focus' in client) return client.focus();
    }
    return self.clients.openWindow('/');
  }));
});
