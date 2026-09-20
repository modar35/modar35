# Развёртывание сервера Modar Family

Сервер — это «мозг» родительского контроля: телефоны и панель родителей общаются только
с ним. Разворачивается один раз, работает круглосуточно, занимает ~40 МБ памяти.

Требования: **Node.js 18+** (проверено на 18–22). Больше ничего: ни базы данных, ни
сторонних библиотек — данные лежат в одном файле `db.json`.

---

## 1. Быстрый старт локально (проверить, что всё работает)

```bash
cd parental/server
DEMO=1 node server.js
```

Откройте <http://localhost:8080> → вкладка **Демо** → войдите `demo@modar.family` / `demo1234`.
Вы увидите живую панель: карта с маршрутом, экранное время, тревоги, команды.

Обычный запуск (без демо-данных):

```bash
node server.js            # порт 8080
PORT=9000 node server.js  # свой порт
```

---

## 2. Бесплатный хостинг

### Вариант A. Render.com (проще всего, бесплатный тариф)

1. Залейте репозиторий на GitHub (он уже там).
2. <https://dashboard.render.com> → **New** → **Web Service** → подключите репозиторий.
3. Настройки:
   * **Root Directory**: `parental/server`
   * **Runtime**: Node
   * **Build Command**: `npm install` (зависимостей нет, но команда нужна)
   * **Start Command**: `node server.js`
   * **Instance Type**: Free
4. **Environment** → добавьте:
   * `DATA_DIR=/var/data` (см. диск ниже)
   * `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` — необязательно, для push-уведомлений
     (создать: `node parental/tools/gen-vapid.js`)
   * `MODAR_DEMO=0` — демо-семью создавать не нужно
5. **Disks** → **Add Disk**: Name `data`, Mount Path `/var/data`, Size 1 GB — иначе база
   пропадёт при перезапуске.
6. Готово: сервер получит адрес вида `https://modar-family.onrender.com`.

> На бесплатном тарифе Render «засыпает» после 15 минут без запросов и просыпается ~30 секунд.
> Телефон ребёнка сам «разбудит» сервер очередным отчётом, но тревоги могут прийти с задержкой.
> Если нужна мгновенная реакция — тариф Starter (~7 $/мес) или вариант B.

### Вариант B. Свой VPS (100–200 ₽/мес, работает всегда)

```bash
# на сервере (Ubuntu/Debian), от root
apt update && apt install -y nodejs npm git
git clone https://github.com/USER/REPO.git /opt/modar
cd /opt/modar/parental/server

# systemd-юнит
cat > /etc/systemd/system/modar-family.service <<'EOF'
[Unit]
Description=Modar Family — сервер родительского контроля
After=network.target

[Service]
WorkingDirectory=/opt/modar/parental/server
ExecStart=/usr/bin/node server.js
Environment=PORT=8080
Environment=DATA_DIR=/var/lib/modar-family
Restart=always
RestartSec=5
User=www-data

[Install]
WantedBy=multi-user.target
EOF

mkdir -p /var/lib/modar-family && chown www-data /var/lib/modar-family
systemctl daemon-reload && systemctl enable --now modar-family
curl -s localhost:8080/healthz
```

HTTPS и домен — через Caddy (сам получает сертификат Let's Encrypt):

```bash
apt install -y caddy
cat > /etc/caddy/Caddyfile <<'EOF'
family.example.com {
    reverse_proxy 127.0.0.1:8080
}
EOF
systemctl reload caddy
```

### Вариант C. Docker (где угодно)

```bash
cd parental/server
docker build -t modar-family .
docker run -d --name modar-family -p 8080:8080 \
  -v /opt/modar-data:/data -e DATA_DIR=/data \
  -e VAPID_PUBLIC_KEY=... -e VAPID_PRIVATE_KEY=... \
  modar-family
```

### Вариант D. Raspberry Pi / домашний компьютер

```bash
cd parental/server && node server.js
```

Домашний вариант работает, но телефон ребёнка должен «видеть» этот компьютер из интернета:
либо проброс порта на роутере, либо туннель (Cloudflare Tunnel):

```bash
cloudflared tunnel --url http://localhost:8080
```

---

## 3. Что вводить в приложении

| Где | Значение |
|---|---|
| Приложение родителя | адрес сервера + e-mail/пароль (или регистрация) |
| Приложение ребёнка | адрес сервера + код из 6 цифр (родитель: «＋ Подключить телефон») |
| Веб-панель | тот же адрес сервера, что у приложения, в браузере |

Код сопряжения живёт 24 часа; если ребёнок вводил код и опоздал — родитель нажимает
«Сгенерировать новый код».

Текст без HTTPS: если адрес начинается с `http://` (например `http://2.3.4.5:8080`),
данные передаются открыто — для проверки годится, для постоянной работы поставьте HTTPS
(Caddy из варианта B или домен Render/Railway с сертификатом).

---

## 4. Уведомления в браузере (Web Push)

1. `node parental/tools/gen-vapid.js` — получите две строки ключей.
2. Пропишите их в переменные окружения сервера и перезапустите.
3. В панели откройте «🔔 Уведомления» → разрешите уведомления.
4. Теперь тревога SOS, «ребёнок пришёл в школу», низкий заряд и «нет связи» придут push-ом
   даже при закрытой панели.

---

## 5. Обслуживание

| Задача | Команда |
|---|---|
| Состояние | `curl https://ваш-домен/healthz` |
| Резервная копия | `cp /var/lib/modar-family/db.json backup-$(date +%F).json` |
| Логи | `journalctl -u modar-family -f` |
| Обновление | `git pull && systemctl restart modar-family` |
| Сменить пароль родителя | в панели нет — через API: `POST /api/v1/auth/password` (`current`, `next`) |

Данные семьи: `db.json` (устройства, настройки, события, точки маршрута — до 4000 точек
и 4000 событий, старые вытесняются автоматически).
