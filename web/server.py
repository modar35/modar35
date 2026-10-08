#!/usr/bin/env python3
"""Rift Arena static preview and lightweight profile API.

The API is suitable for a prototype/demo only. Put it behind HTTPS and a proper
reverse proxy/rate limiter before exposing it as a production service.
"""
from collections import defaultdict, deque
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from shutil import copyfileobj
from urllib.parse import urlsplit
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import time

ROOT = Path(__file__).resolve().parent
APK_PATHS = {
    "/download/RiftArena-0.1.0.apk": ROOT.parent / "apk" / "RiftArena-0.1.0.apk",
    "/download/RiftArena-0.2.0.apk": ROOT.parent / "apk" / "RiftArena-0.2.0.apk",
}
DB_PATH = Path(os.environ.get("RIFT_DB_PATH", str(ROOT.parent / "runtime" / "riftarena.sqlite3")))
TOKEN_TTL = 30 * 24 * 60 * 60
PASSWORD_ROUNDS = 310_000
MAX_BODY = 8192
RATE_LIMIT = 12
RATE_WINDOW = 60
RATE_LOCK = threading.Lock()
REQUESTS = defaultdict(deque)
DB_LOCK = threading.Lock()
os.chdir(ROOT)

MEDALS = {
    "first_match": {"name": "Первый бой", "description": "Завершить первый матч", "icon": "✦"},
    "first_win": {"name": "Первая победа", "description": "Победить в матче", "icon": "♛"},
    "slayer": {"name": "Охотник", "description": "Совершить 5 убийств за матч", "icon": "⚔"},
    "unbroken": {"name": "Несокрушимый", "description": "Победить, погибнув не больше одного раза", "icon": "⬡"},
    "veteran": {"name": "Ветеран", "description": "Сыграть 10 матчей", "icon": "⌖"},
    "champion": {"name": "Чемпион", "description": "Одержать 5 побед", "icon": "♜"},
}


def connect_db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("PRAGMA busy_timeout = 10000")
    return connection


def initialize_db():
    with DB_LOCK, connect_db() as db:
        db.execute("PRAGMA journal_mode = WAL")
        db.executescript("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                password_salt BLOB NOT NULL,
                password_hash BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                matches INTEGER NOT NULL DEFAULT 0,
                wins INTEGER NOT NULL DEFAULT 0,
                losses INTEGER NOT NULL DEFAULT 0,
                kills INTEGER NOT NULL DEFAULT 0,
                deaths INTEGER NOT NULL DEFAULT 0,
                assists INTEGER NOT NULL DEFAULT 0,
                xp INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                expires_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS user_medals (
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                medal_id TEXT NOT NULL,
                earned_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, medal_id)
            );
            CREATE INDEX IF NOT EXISTS sessions_expiry_idx ON sessions(expires_at);
        """)


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def token_digest(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def public_profile(db, user_id):
    user = db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if not user:
        return None
    medals = db.execute(
        "SELECT medal_id, earned_at FROM user_medals WHERE user_id = ? ORDER BY earned_at DESC",
        (user_id,),
    ).fetchall()
    total_matches = user["matches"]
    return {
        "username": user["username"],
        "level": min(100, max(1, user["xp"] // 500 + 1)),
        "xp": user["xp"],
        "nextLevelXp": 500 - user["xp"] % 500,
        "matches": total_matches,
        "wins": user["wins"],
        "losses": user["losses"],
        "winRate": round(user["wins"] / total_matches * 100) if total_matches else 0,
        "kills": user["kills"],
        "deaths": user["deaths"],
        "assists": user["assists"],
        "medals": [
            {"id": row["medal_id"], **MEDALS.get(row["medal_id"], {}), "earnedAt": row["earned_at"]}
            for row in medals
        ],
    }


def valid_username(username):
    return isinstance(username, str) and bool(re.fullmatch(r"[\w.-]{3,20}", username, flags=re.UNICODE))


def validate_match(body):
    if not isinstance(body, dict) or body.get("result") not in ("win", "loss"):
        return None
    values = {}
    for key, ceiling in (("kills", 100), ("deaths", 100), ("assists", 100), ("duration", 7200), ("gold", 1_000_000)):
        value = body.get(key, 0)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0 or value > ceiling:
            return None
        values[key] = value
    hero = body.get("hero", "")
    if not isinstance(hero, str) or len(hero) > 40:
        return None
    values["result"] = body["result"]
    values["hero"] = hero
    return values


def allowed_request(ip):
    now = time.monotonic()
    with RATE_LOCK:
        bucket = REQUESTS[ip]
        while bucket and bucket[0] < now - RATE_WINDOW:
            bucket.popleft()
        if len(bucket) >= RATE_LIMIT:
            return False
        bucket.append(now)
        return True


class Handler(SimpleHTTPRequestHandler):
    server_version = "RiftArenaPreview/0.2"

    def _send_bytes(self, code, payload, content_type="application/json; charset=utf-8", extra_headers=None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        if extra_headers:
            for name, value in extra_headers.items():
                self.send_header(name, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def _send_json(self, code, payload):
        self._send_bytes(code, json_bytes(payload))

    def _serve_apk(self, route, send_body=True):
        apk_path = APK_PATHS.get(route)
        if not apk_path or not apk_path.is_file():
            self.send_error(404, "APK not found")
            return
        size = apk_path.stat().st_size
        filename = apk_path.name
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.send_header("Content-Length", str(size))
        self.end_headers()
        if send_body:
            with apk_path.open("rb") as apk:
                copyfileobj(apk, self.wfile)

    def _read_json(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return None
        if length <= 0 or length > MAX_BODY:
            return None
        try:
            value = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None
        return value if isinstance(value, dict) else None

    def _bearer_user(self, db):
        authorization = self.headers.get("Authorization", "")
        if not authorization.startswith("Bearer "):
            return None
        token = authorization[7:].strip()
        if len(token) < 32 or len(token) > 128:
            return None
        digest = token_digest(token)
        row = db.execute(
            "SELECT user_id, expires_at FROM sessions WHERE token_hash = ?", (digest,)
        ).fetchone()
        if not row:
            return None
        if row["expires_at"] <= int(time.time()):
            db.execute("DELETE FROM sessions WHERE token_hash = ?", (digest,))
            return None
        return row["user_id"]

    def _api_path(self):
        return urlsplit(self.path).path

    def do_OPTIONS(self):
        if self._api_path().startswith("/api/"):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Access-Control-Max-Age", "600")
            self.end_headers()
            return
        self.send_error(404)

    def do_GET(self):
        path = self._api_path()
        if path in APK_PATHS:
            self._serve_apk(path)
            return
        if path == "/api/health":
            self._send_json(200, {"ok": True, "service": "rift-arena-profile", "version": "0.2.0"})
            return
        if path == "/api/profile":
            with DB_LOCK, connect_db() as db:
                user_id = self._bearer_user(db)
                profile = public_profile(db, user_id) if user_id else None
            if profile is None:
                self._send_json(401, {"error": "Необходим вход в аккаунт."})
            else:
                self._send_json(200, {"profile": profile})
            return
        if path.startswith("/api/"):
            self._send_json(404, {"error": "Неизвестный API-маршрут."})
            return
        super().do_GET()

    def do_HEAD(self):
        path = self._api_path()
        if path in APK_PATHS:
            self._serve_apk(path, send_body=False)
            return
        super().do_HEAD()

    def do_POST(self):
        path = self._api_path()
        if not path.startswith("/api/"):
            self.send_error(404)
            return
        if not allowed_request(self.client_address[0]):
            self._send_json(429, {"error": "Слишком много запросов. Попробуйте позже."})
            return
        body = self._read_json()
        if body is None:
            self._send_json(400, {"error": "Ожидался корректный JSON небольшого размера."})
            return

        if path == "/api/register":
            username = body.get("username", "")
            password = body.get("password", "")
            if not valid_username(username):
                self._send_json(400, {"error": "Имя: 3–20 букв, цифр, точек, дефисов или подчёркиваний."})
                return
            if not isinstance(password, str) or len(password) < 8 or len(password) > 128:
                self._send_json(400, {"error": "Пароль должен содержать от 8 до 128 символов."})
                return
            salt = secrets.token_bytes(16)
            password_hash = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PASSWORD_ROUNDS)
            raw_token = secrets.token_urlsafe(40)
            now = int(time.time())
            try:
                with DB_LOCK, connect_db() as db:
                    cursor = db.execute(
                        "INSERT INTO users(username, password_salt, password_hash, created_at) VALUES(?, ?, ?, ?)",
                        (username.strip(), salt, password_hash, now),
                    )
                    user_id = cursor.lastrowid
                    db.execute(
                        "INSERT INTO sessions(token_hash, user_id, expires_at) VALUES(?, ?, ?)",
                        (token_digest(raw_token), user_id, now + TOKEN_TTL),
                    )
                    profile = public_profile(db, user_id)
            except sqlite3.IntegrityError:
                self._send_json(409, {"error": "Это имя уже занято."})
                return
            self._send_json(201, {"token": raw_token, "profile": profile})
            return

        if path == "/api/login":
            username = body.get("username", "")
            password = body.get("password", "")
            if not isinstance(username, str) or not isinstance(password, str) or len(password) > 128:
                self._send_json(400, {"error": "Проверьте имя и пароль."})
                return
            raw_token = secrets.token_urlsafe(40)
            with DB_LOCK, connect_db() as db:
                user = db.execute("SELECT * FROM users WHERE username = ? COLLATE NOCASE", (username.strip(),)).fetchone()
                if not user:
                    salt = bytes(16)
                    expected = bytes(32)
                    supplied = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PASSWORD_ROUNDS)
                    hmac.compare_digest(supplied, expected)
                    self._send_json(401, {"error": "Неверное имя или пароль."})
                    return
                supplied = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), user["password_salt"], PASSWORD_ROUNDS)
                if not hmac.compare_digest(supplied, user["password_hash"]):
                    self._send_json(401, {"error": "Неверное имя или пароль."})
                    return
                now = int(time.time())
                db.execute("DELETE FROM sessions WHERE user_id = ? AND expires_at <= ?", (user["id"], now))
                db.execute(
                    "INSERT INTO sessions(token_hash, user_id, expires_at) VALUES(?, ?, ?)",
                    (token_digest(raw_token), user["id"], now + TOKEN_TTL),
                )
                profile = public_profile(db, user["id"])
            self._send_json(200, {"token": raw_token, "profile": profile})
            return

        if path == "/api/logout":
            with DB_LOCK, connect_db() as db:
                token = self.headers.get("Authorization", "")[7:].strip() if self.headers.get("Authorization", "").startswith("Bearer ") else ""
                if token:
                    db.execute("DELETE FROM sessions WHERE token_hash = ?", (token_digest(token),))
            self._send_json(200, {"ok": True})
            return

        if path == "/api/match":
            match = validate_match(body)
            if not match:
                self._send_json(400, {"error": "Некорректный результат матча."})
                return
            with DB_LOCK, connect_db() as db:
                user_id = self._bearer_user(db)
                if not user_id:
                    self._send_json(401, {"error": "Необходим вход в аккаунт."})
                    return
                is_win = int(match["result"] == "win")
                xp_gain = 100 + match["kills"] * 18 + match["assists"] * 8 + is_win * 120
                db.execute(
                    "UPDATE users SET matches=matches+1, wins=wins+?, losses=losses+?, kills=kills+?, deaths=deaths+?, assists=assists+?, xp=xp+? WHERE id=?",
                    (is_win, 1 - is_win, match["kills"], match["deaths"], match["assists"], xp_gain, user_id),
                )
                current = db.execute("SELECT matches, wins FROM users WHERE id=?", (user_id,)).fetchone()
                earned = []
                if current["matches"] == 1:
                    earned.append("first_match")
                if is_win and current["wins"] == 1:
                    earned.append("first_win")
                if match["kills"] >= 5:
                    earned.append("slayer")
                if is_win and match["deaths"] <= 1:
                    earned.append("unbroken")
                if current["matches"] >= 10:
                    earned.append("veteran")
                if current["wins"] >= 5:
                    earned.append("champion")
                now = int(time.time())
                for medal_id in earned:
                    db.execute("INSERT OR IGNORE INTO user_medals(user_id, medal_id, earned_at) VALUES(?, ?, ?)", (user_id, medal_id, now))
                profile = public_profile(db, user_id)
            self._send_json(200, {"profile": profile, "xpEarned": xp_gain, "newMedals": earned})
            return

        self._send_json(404, {"error": "Неизвестный API-маршрут."})

    def end_headers(self):
        if self._api_path().startswith("/api/"):
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Vary", "Origin")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy", "frame-ancestors *")
        super().end_headers()

    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))


if __name__ == "__main__":
    initialize_db()
    port = int(os.environ.get("PORT", "8080"))
    httpd = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("Rift Arena preview + profile API on http://0.0.0.0:%s (SQLite: %s)" % (port, DB_PATH), flush=True)
    httpd.serve_forever()
