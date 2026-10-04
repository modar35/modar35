# Грузовичок API

Минимальный API для подключения Android и браузера. Сейчас хранит данные в `server/data.json`, чтобы его можно было запустить без установки зависимостей. Для production замените слой хранения на PostgreSQL и подключите SMS-провайдера.

## Запуск

```bash
cd server
PORT=8080 node index.js
```

Проверка:

```bash
curl http://localhost:8080/api/health
```

## API

- `POST /api/auth/request-code` — запрос SMS-кода (`devCode: 123456` вне production);
- `POST /api/auth/verify-code` — получить JWT-подобный токен;
- `GET /api/orders` — список заказов;
- `POST /api/orders` — создать заказ;
- `PATCH /api/orders/:id` — изменить статус (`accepted`, `in_progress`, `completed`, `cancelled`);
- `GET/POST /api/orders/:id/messages` — чат по заказу.

Пример входа:

```bash
curl -X POST localhost:8080/api/auth/request-code -H 'Content-Type: application/json' -d '{"phone":"+79990000000"}'
curl -X POST localhost:8080/api/auth/verify-code -H 'Content-Type: application/json' -d '{"phone":"+79990000000","code":"123456","role":"driver"}'
```

Для production обязательно добавить HTTPS, PostgreSQL, настоящий JWT, rate limit, хэширование OTP и SMS-провайдера.
