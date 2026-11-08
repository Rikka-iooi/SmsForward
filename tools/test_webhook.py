"""Tiny local webhook sink used only for end-to-end testing over `adb reverse`."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import pathlib
import time

OUT = pathlib.Path(__file__).resolve().parent.parent / "build" / "webhook_requests.jsonl"
OUT.parent.mkdir(parents=True, exist_ok=True)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        record = {
            "received_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "path": self.path,
            "content_type": self.headers.get("Content-Type"),
            "body": raw.decode("utf-8", "replace"),
        }
        with OUT.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        print(json.dumps(record, ensure_ascii=False), flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    print(f"listening on 127.0.0.1:8765, writing {OUT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
