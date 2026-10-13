#!/usr/bin/env python3
"""Статический сервер для Ёлка-симулятора 2026→2027 (tree3d).

Запуск:  python3 server.py   (PORT=8080 по умолчанию)
"""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
os.chdir(ROOT)


class Handler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy", "frame-ancestors *")
        super().end_headers()

    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    httpd = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("Ёлка-симулятор 2026→2027: http://0.0.0.0:%s" % port, flush=True)
    httpd.serve_forever()
