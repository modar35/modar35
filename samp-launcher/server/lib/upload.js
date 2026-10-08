'use strict';
/*
 * Разбор multipart/form-data без сторонних библиотек: файлы пишутся потоком на диск
 * (мод-пак бывает на сотни мегабайт, держать его в памяти нельзя), поля — в объект.
 */

const fs = require('node:fs');
const path = require('node:path');
const U = require('./util');

function boundaryOf(contentType) {
  const match = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || '');
  if (!match) {
    return null;
  }
  return (match[1] || match[2] || '').trim();
}

function parseHeaders(text) {
  const headers = {};
  for (const line of text.split('\r\n')) {
    const colon = line.indexOf(':');
    if (colon > 0) {
      headers[line.slice(0, colon).trim().toLowerCase()] = line.slice(colon + 1).trim();
    }
  }
  return headers;
}

function dispositionValue(header, key) {
  const match = new RegExp(key + '="([^"]*)"', 'i').exec(header || '');
  return match ? match[1] : '';
}

/**
 * Читает поток запроса и возвращает { fields, files }.
 * options: { dir — куда складывать файлы, maxBytes — предел размера, prefix — начало имени файла }
 */
function parseMultipart(req, options = {}) {
  const boundary = boundaryOf(req.headers['content-type']);
  const dir = options.dir;
  const maxBytes = options.maxBytes || 1024 * 1024 * 1024;
  const prefix = options.prefix || '';

  if (!boundary) {
    return Promise.reject(new Error('Ожидался multipart/form-data'));
  }
  fs.mkdirSync(dir, { recursive: true });

  return new Promise((resolve, reject) => {
    const delimiter = Buffer.from('--' + boundary);
    const bodyDelimiter = Buffer.from('\r\n--' + boundary);

    const fields = {};
    const files = [];
    const pendingWrites = [];
    let pending = Buffer.alloc(0);
    let state = 'delimiter';
    let current = null;
    let received = 0;
    let finished = false;

    const cleanup = () => {
      if (current && current.stream) {
        current.stream.destroy();
      }
      for (const file of files) {
        try {
          fs.unlinkSync(file.path);
        } catch (ignored) {
          /* уже нет */
        }
      }
    };

    const stop = (error) => {
      if (finished) {
        return;
      }
      finished = true;
      if (error) {
        cleanup();
        reject(error);
        return;
      }
      // ждём, пока все файлы допишутся на диск, — иначе SHA-256 посчитается по пустому файлу
      Promise.all(pendingWrites)
        .then(() => resolve({ fields, files }))
        .catch((err) => {
          cleanup();
          reject(err);
        });
    };

    const closeCurrent = () => {
      if (current && current.stream) {
        const stream = current.stream;
        const file = {
          field: current.name,
          // имя, под которым файл реально лежит на диске (используется в ссылках),
          // и исходное имя от клиента — для показа человеку
          filename: path.basename(current.path),
          original: current.filename,
          path: current.path,
          size: current.size,
        };
        pendingWrites.push(new Promise((resolve, rejectWrite) => {
          stream.on('error', rejectWrite);
          stream.end(resolve);
        }));
        files.push(file);
      } else if (current && current.name !== undefined && !current.isFile) {
        fields[current.name] = current.value;
      }
      current = null;
    };

    const processPending = () => {
      for (;;) {
        if (state === 'delimiter') {
          const index = pending.indexOf(delimiter);
          if (index < 0) {
            if (pending.length > delimiter.length + 4) {
              pending = pending.subarray(pending.length - delimiter.length - 4);
            }
            return;
          }
          let after = index + delimiter.length;
          // «--» сразу после границы означает конец формы
          if (pending.length >= after + 2 && pending[after] === 0x2D && pending[after + 1] === 0x2D) {
            stop(null);
            return;
          }
          // пропускаем CRLF после границы
          if (pending.length < after + 2) {
            pending = pending.subarray(index);
            return;
          }
          pending = pending.subarray(after + 2);
          state = 'headers';
        }

        if (state === 'headers') {
          const end = pending.indexOf('\r\n\r\n');
          if (end < 0) {
            return;
          }
          const headers = parseHeaders(pending.subarray(0, end).toString('utf8'));
          pending = pending.subarray(end + 4);
          const disposition = headers['content-disposition'] || '';
          const name = dispositionValue(disposition, 'name');
          const filename = dispositionValue(disposition, 'filename');
          if (filename) {
            const safe = U.safeName(filename);
            const target = path.join(dir, prefix + Date.now() + '-' + safe);
            current = {
              name,
              filename: safe,
              path: target,
              stream: fs.createWriteStream(target),
              size: 0,
              isFile: true,
            };
            current.stream.on('error', (err) => stop(err));
          } else {
            current = { name, value: '', isFile: false };
          }
          state = 'body';
        }

        if (state === 'body') {
          const index = pending.indexOf(bodyDelimiter);
          if (index < 0) {
            // сбрасываем на диск всё, кроме «хвоста», в котором может быть начало границы
            const keep = bodyDelimiter.length + 4;
            if (pending.length > keep) {
              const chunk = pending.subarray(0, pending.length - keep);
              if (current && current.isFile) {
                current.stream.write(chunk);
                current.size += chunk.length;
              } else if (current) {
                current.value += chunk.toString('utf8');
              }
              pending = pending.subarray(pending.length - keep);
            }
            return;
          }
          const chunk = pending.subarray(0, index);
          if (current && current.isFile) {
            current.stream.write(chunk);
            current.size += chunk.length;
          } else if (current) {
            current.value += chunk.toString('utf8');
          }
          pending = pending.subarray(index + bodyDelimiter.length);
          closeCurrent();
          state = 'delimiter';
        }
      }
    };

    req.on('data', (chunk) => {
      received += chunk.length;
      if (received > maxBytes) {
        stop(new Error('Файл больше ' + U.sizeLabel(maxBytes)));
        req.destroy();
        return;
      }
      pending = pending.length === 0 ? chunk : Buffer.concat([pending, chunk]);
      try {
        processPending();
      } catch (err) {
        stop(err);
      }
    });
    req.on('end', () => {
      if (finished) {
        return;
      }
      try {
        processPending();
        // если форма оборвалась, но данные были — закрываем последнюю часть
        if (current) {
          closeCurrent();
        }
        stop(null);
      } catch (err) {
        stop(err);
      }
    });
    req.on('error', (err) => stop(err));
  });
}

module.exports = { parseMultipart };
