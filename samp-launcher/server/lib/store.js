'use strict';
/* Хранилище данных сервера: обычные JSON-файлы в каталоге data/ (никаких библиотек). */

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const U = require('./util');

class Store {
  constructor(dataDir) {
    this.dir = dataDir;
    fs.mkdirSync(this.dir, { recursive: true });
    this.ensureConfig();
  }

  file(name) {
    return path.join(this.dir, name + '.json');
  }

  read(name, fallback) {
    try {
      const text = fs.readFileSync(this.file(name), 'utf8');
      const parsed = JSON.parse(text);
      return parsed === null || parsed === undefined ? fallback : parsed;
    } catch (err) {
      return fallback;
    }
  }

  write(name, value) {
    const target = this.file(name);
    const tmp = target + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(value, null, 2), 'utf8');
    fs.renameSync(tmp, target);
    return value;
  }

  /** Конфигурация: пароль администратора и секрет для токенов панели. */
  ensureConfig() {
    let config = this.read('config', null);
    if (!config || !config.secret) {
      const previous = config || {};
      config = {
        adminPassword: previous.adminPassword || 'modar',
        secret: previous.secret || crypto.randomBytes(16).toString('hex'),
        createdAt: Date.now(),
      };
      this.write('config', config);
    }
    return config;
  }

  config() {
    return this.ensureConfig();
  }

  saveConfig(patch) {
    const config = { ...this.config(), ...patch };
    return this.write('config', config);
  }

  /* ---------------------------------------------------------- коллекции */

  list(name) {
    const items = this.read(name, []);
    return Array.isArray(items) ? items : [];
  }

  save(name, items) {
    return this.write(name, items);
  }

  create(name, item, idPrefix = 'item') {
    const items = this.list(name);
    const created = {
      id: item.id || U.randomId(idPrefix),
      createdAt: Date.now(),
      ...item,
    };
    items.push(created);
    this.save(name, items);
    return created;
  }

  update(name, id, patch) {
    const items = this.list(name);
    const index = items.findIndex((item) => item.id === id);
    if (index < 0) {
      return null;
    }
    items[index] = { ...items[index], ...patch, id };
    this.save(name, items);
    return items[index];
  }

  remove(name, id) {
    const items = this.list(name);
    const left = items.filter((item) => item.id !== id);
    if (left.length === items.length) {
      return false;
    }
    this.save(name, left);
    return true;
  }

  /* ---------------------------------------------------------- демо-данные */

  /** DEMO=1 — наполняем сервер примерами, чтобы панель не была пустой. */
  seedDemo() {
    if (this.list('servers').length === 0) {
      this.save('servers', [
        {
          id: 'demo-aurora', name: 'Aurora RP', host: '127.0.0.1', port: 7777,
          gamemode: 'RolePlay', note: 'демо-сервер (mock)', tags: ['rp', 'mobile'], password: '',
          createdAt: Date.now(),
        },
        {
          id: 'demo-freeroam', name: 'Freeroam Test', host: '127.0.0.1', port: 7778,
          gamemode: 'Freeroam', note: 'второй демо-сервер', tags: ['freeroam'], password: '',
          createdAt: Date.now(),
        },
      ]);
    }
    if (this.list('news').length === 0) {
      this.save('news', [{
        id: 'demo-news', title: 'Лаунчер запущен',
        text: 'Список серверов, мод-паки и новости раздаются этим сервером. '
          + 'Откройте панель, добавьте свои серверы и загрузите моды — приложение подхватит их само.',
        tag: 'Старт', date: Date.now(), pinned: true,
      }]);
    }
    if (this.list('clients').length === 0) {
      this.save('clients', [
        {
          id: 'demo-client', name: 'SA-MP Mobile (открытый исходный код)',
          description: 'Клиент SA-MP для Android', pkg: 'com.gta.game',
          url: 'https://github.com/kuzia15/SAMP-Mobile', createdAt: Date.now(),
        },
        {
          id: 'demo-launcher', name: 'Официальный сайт SA-MP',
          description: 'Клиент для ПК и информация о мультиплеере', pkg: '',
          url: 'https://www.sa-mp.mp/', createdAt: Date.now(),
        },
      ]);
    }
    const launcher = this.read('launcher', null);
    if (!launcher) {
      this.write('launcher', {
        version: '1.0', versionCode: 1, url: '', sha256: '', size: 0,
        notes: 'Первая версия: список серверов, подключение, мод-паки.',
        minSupported: 1, updatedAt: Date.now(),
      });
    }
  }
}

module.exports = { Store };
