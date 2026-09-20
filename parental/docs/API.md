# API Modar Family v1

Базовый адрес: `https://ваш-сервер` — все методы под `/api/v1`.
Авторизация: заголовок `Authorization: Bearer <token>`.
* **Токен родителя** выдаётся при регистрации/входе (`/auth/*`).
* **Токен устройства** выдаётся при сопряжении (`/device/register`) и принадлежит телефону ребёнка.

Ответы всегда JSON; ошибка — `{"ok":false,"error":"текст"}`, успех — `{"ok":true,...}`.

---

## Служебные

| Метод | Адрес | Описание |
|---|---|---|
| GET | `/healthz` | состояние сервера (без авторизации) |
| GET | `/api/v1/ping` | проверка доступности, версия, время сервера |

## Родитель

| Метод | Адрес | Тело | Описание |
|---|---|---|---|
| POST | `/api/v1/auth/register` | `{email,password,name,familyName}` | создать семью и родителя → `{token,...}` |
| POST | `/api/v1/auth/login` | `{email,password}` | вход → `{token,...}` |
| POST | `/api/v1/auth/password` | `{current,next}` | сменить пароль (отзывает старые сессии) |
| GET | `/api/v1/me` | — | кто я и в какой семье |
| POST | `/api/v1/paircode` | — | новый код для подключения телефона ребёнка → `{code}` |
| GET | `/api/v1/status` | — | устройства, события, геозоны, код сопряжения |
| GET | `/api/v1/dashboard` | — | сводка по семье для главного экрана |
| GET | `/api/v1/history?deviceId=&hours=24` | — | маршрут (`track`), события, статистика за день |
| POST | `/api/v1/command` | `{deviceId,cmd,arg}` | команда телефону ребёнка (список ниже) |
| POST | `/api/v1/settings` | `{deviceId,settings}` | лимиты, блокировки, расписания, фильтры |
| POST | `/api/v1/device/name` | `{deviceId,name}` | переименовать устройство |
| POST | `/api/v1/device/remove` | `{deviceId}` | отвязать устройство |
| POST | `/api/v1/alerts/clear` | `{deviceId}` | сбросить тревогу (сирену) на телефоне |
| GET | `/api/v1/places` | — | список геозон |
| POST | `/api/v1/places` | `{name,lat,lon,radius,notifyIn,notifyOut,days}` | добавить геозону |
| POST | `/api/v1/places/remove` | `{id}` | удалить геозону |
| GET | `/api/v1/image/latest?deviceId=&kind=screen\|camera` | — | последний снимок экрана / фото |
| POST | `/api/v1/call/start` | `{deviceId,offer}` | начать голосовой вызов → `{callId}` |
| POST | `/api/v1/call/end` | `{callId}` | завершить вызов |
| POST | `/api/v1/push/subscribe` | `{subscription}` | подписка браузера на Web Push |
| GET | `/api/v1/push/key` | — | публичный VAPID-ключ |

### Команды телефону (`cmd`)

| cmd | Что делает |
|---|---|
| `locate` | прислать свежую геопозицию |
| `refresh` | немедленный отчёт (батарея, приложения, экранное время) |
| `sync` | синхронизировать настройки |
| `message` | показать ребёнку сообщение (`arg` — текст) |
| `alarm` | включить громкую сирену |
| `ring` | громко позвонить, чтобы найти телефон |
| `lock` / `unlock` | заблокировать / разблокировать экран |
| `photo` | фото с камеры телефона (`arg`: `front` по умолчанию, `back`) |
| `snapshot` | один снимок экрана (`arg` — секунды) |
| `tour` | серия снимков экрана каждые 2 секунды (`arg` — секунды) |
| `torch` | фонарик (`arg`: `1` — включить, `0` — выключить) |
| `call` | начать голосовой вызов (`arg` — `callId`) |
| `sos_clear` | сбросить экран тревоги |

### Настройки (`settings`)

```jsonc
{
  "blockApps": ["com.instagram.android"],     // запрещённые приложения
  "allowOnly": null,                          // или «разрешены только эти»
  "appLimits": {"com.google.android.youtube": 60}, // минуты в день на приложение
  "dailyLimitMin": 180,                       // общий лимит экрана в день (0 — выключен)
  "blockNewApps": false,                      // запретить новые установки
  "schoolMode": {"enabled": true, "from": "08:00", "to": "14:00", "days": [1,2,3,4,5]},
  "bedtime":    {"enabled": true, "from": "21:30", "to": "07:00"},
  "internetOff":{"enabled": false,"from": "23:00", "to": "06:00"},
  "webFilter":  {"adult": true, "gambling": true, "custom": ["example.com"]},
  "alerts":     {"sos": true, "lowBattery": 15, "geofence": true, "appInstall": true, "offlineMinutes": 30},
  "reportSeconds": 300,                       // как часто телефон ребёнка присылает отчёт
  "voiceEnabled": true,                       // разрешить интернет-звонки родителю
  "kiosk": false                              // режим «только учёба»
}
```

Дни недели: `0` — понедельник, `6` — воскресенье. Окно может пересекать полночь.

## Телефон ребёнка

| Метод | Адрес | Тело | Описание |
|---|---|---|---|
| POST | `/api/v1/device/register` | `{code,name,model,androidId,version}` | сопряжение по коду → `{deviceToken,deviceId}` |
| POST | `/api/v1/device/report` | см. ниже | отчёт + получение команд |
| POST | `/api/v1/device/image` | `{kind:"screen"\|"camera",data:"data:image/jpeg;base64,..."}` | снимок экрана или фото |
| POST | `/api/v1/voice/probe` | — | можно ли сейчас звонить (`{allowed}`) |
| GET | `/api/v1/call/poll?callId=&since=` | — | состояние вызова и новые ICE-кандидаты |
| POST | `/api/v1/call/answer` | `{callId,accepted,answer}` | принять/отклонить вызов |
| POST | `/api/v1/call/ice` | `{callId,side,candidate}` | обмен сетевыми кандидатами |

### Тело отчёта `/device/report`

```jsonc
{
  "name": "Телефон Алисы",
  "model": "Samsung Galaxy A54",
  "version": "2.1.0",
  "battery": 62, "charging": false, "screenOn": true, "steps": 4210,
  "lat": 52.3585, "lon": 4.8686, "acc": 12, "speed": 0,
  "accessibility": true, "admin": true, "vpn": true,
  "notificationsPermission": true, "locationPermission": true,
  "apps": [{"pkg": "com.google.android.youtube", "label": "YouTube", "system": false}],
  "day": "2026-09-20",
  "usage": [{"pkg": "com.google.android.youtube", "label": "YouTube", "ms": 2400000}],
  "notifications": [{"app": "WhatsApp", "title": "Мама", "text": "Позвони"}],
  "webBlocked": [{"host": "example-casino.net", "reason": "gambling"}],
  "events": [{"type": "sos", "message": "Тревога SOS", "level": "alert"}],
  "ackIds": ["cmd_123"]
}
```

Ответ:

```jsonc
{
  "ok": true,
  "serverTime": 1789911225540,
  "settings": { /* актуальные настройки для применения */ },
  "commands": [{"id": "cmd_123", "cmd": "alarm", "arg": null}]
}
```

Получив команды, телефон отправляет их `id` в `ackIds` следующего отчёта — так сервер
не повторяет доставку. Недоставленные команды живут 10 минут.

---

## События (`type`)

`sos` · `geofence` · `web` · `battery` · `offline` · `notification` · `app` · `limit` ·
`camera` · `call` · `cmd` · `pair` · `settings` · `info`

Уровень `level`: `info` (обычное), `warn` (требует внимания), `alert` (тревога, уходит push-ом).
