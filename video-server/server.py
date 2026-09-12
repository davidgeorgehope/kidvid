#!/usr/bin/env python3
"""KidVid Video Server — serves videos over HTTP with mDNS discovery.

Also maintains deletes.json: a durable pending-delete queue so CoS can tee
remote deletes for files devices already downloaded (empty /videos after drain).
"""

import http.server
import json
import os
import socket
import socketserver
import threading
import urllib.parse
from pathlib import Path

PORT = 8642
VIDEO_DIR = os.environ.get("KIDVID_DIR", os.path.expanduser("~/kidvid-videos"))
SERVICE_NAME = "_kidvid._tcp"
KNOWN_DEVICES = ("phone", "fire")
DELETES_LOCK = threading.Lock()


def deletes_path():
    return Path(VIDEO_DIR) / "deletes.json"


def empty_deletes():
    return {d: [] for d in KNOWN_DEVICES}


def load_deletes():
    path = deletes_path()
    if not path.exists():
        return empty_deletes()
    try:
        data = json.loads(path.read_text(encoding="utf-8") or "{}")
    except (OSError, json.JSONDecodeError):
        return empty_deletes()
    out = empty_deletes()
    if isinstance(data, list):
        # Legacy / flat list applies to every device
        for d in KNOWN_DEVICES:
            out[d] = [n for n in data if isinstance(n, str) and n]
        return out
    if isinstance(data, dict):
        for d in KNOWN_DEVICES:
            vals = data.get(d, [])
            if isinstance(vals, list):
                out[d] = [n for n in vals if isinstance(n, str) and n]
    return out


def save_deletes(data):
    path = deletes_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def safe_filename(name):
    if not name or "/" in name or "\\" in name or ".." in name or name in (".", "deletes.json"):
        return None
    return name


class KidVidHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"[KidVid] {self.address_string()} - {format % args}")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/")
        qs = urllib.parse.parse_qs(parsed.query)

        if path == "/videos":
            self._serve_video_list()
        elif path.startswith("/videos/"):
            filename = urllib.parse.unquote(path[len("/videos/"):])
            self._serve_file(filename)
        elif path == "/deletes":
            self._serve_deletes(qs)
        elif path in ("", "/"):
            self._serve_index()
        elif path == "/health":
            self._serve_health()
        else:
            self._send_error(404, "not found")

    def do_PUT(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/")
        qs = urllib.parse.parse_qs(parsed.query)

        if path.startswith("/deletes/"):
            filename = urllib.parse.unquote(path[len("/deletes/"):])
            self._queue_delete(filename, qs)
        elif path == "/deletes":
            self._queue_delete_from_body(qs)
        else:
            self._send_error(404, "not found")

    def do_POST(self):
        # Same as PUT for CoS convenience: tee a pending delete
        self.do_PUT()

    def do_DELETE(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/")
        qs = urllib.parse.parse_qs(parsed.query)

        if path.startswith("/videos/"):
            filename = urllib.parse.unquote(path[len("/videos/"):])
            self._delete_file(filename)
        elif path.startswith("/deletes/"):
            filename = urllib.parse.unquote(path[len("/deletes/"):])
            self._ack_delete(filename, qs)
        else:
            self._send_error(404, "not found")

    def _serve_index(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(
            b"KidVid Video Server\n\n"
            b"GET /videos - list videos\n"
            b"GET /videos/<name> - download video\n"
            b"DELETE /videos/<name> - remove from download queue\n"
            b"GET /deletes?device=phone|fire - pending remote deletes\n"
            b"PUT|POST /deletes/<name>?device=phone|fire - tee pending delete\n"
            b"DELETE /deletes/<name>?device=phone|fire - clear after device applied\n"
        )

    def _serve_health(self):
        self._send_json(200, {"status": "ok", "devices": list(KNOWN_DEVICES)})

    def _serve_video_list(self):
        videos = []
        video_dir = Path(VIDEO_DIR)
        if video_dir.exists():
            for f in sorted(video_dir.iterdir()):
                if f.suffix.lower() == ".mp4" and f.is_file():
                    host = self.headers.get("Host", f"localhost:{PORT}")
                    videos.append({
                        "name": f.name,
                        "size": f.stat().st_size,
                        "url": f"/videos/{urllib.parse.quote(f.name)}"
                    })
        self._send_json(200, videos)

    def _serve_file(self, filename):
        filename = safe_filename(filename)
        if not filename:
            self._send_error(400, "invalid name")
            return

        filepath = Path(VIDEO_DIR) / filename
        if not filepath.exists() or not filepath.is_file():
            self._send_error(404, "not found")
            return

        file_size = filepath.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Content-Length", str(file_size))
        self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.end_headers()

        with open(filepath, "rb") as f:
            while True:
                chunk = f.read(1024 * 1024)  # 1MB chunks
                if not chunk:
                    break
                self.wfile.write(chunk)

    def _delete_file(self, filename):
        filename = safe_filename(filename)
        if not filename:
            self._send_error(400, "invalid name")
            return

        filepath = Path(VIDEO_DIR) / filename
        if not filepath.exists() or not filepath.is_file():
            self._send_error(404, "not found")
            return

        try:
            filepath.unlink()
        except OSError as e:
            self._send_error(500, str(e))
            return

        self._send_json(200, {"deleted": filename})

    def _devices_from_qs(self, qs, default_all=True):
        raw = (qs.get("device") or [None])[0]
        if not raw:
            return list(KNOWN_DEVICES) if default_all else None
        raw = raw.strip().lower()
        if raw not in KNOWN_DEVICES:
            return None
        return [raw]

    def _serve_deletes(self, qs):
        devices = self._devices_from_qs(qs, default_all=True)
        if devices is None:
            self._send_error(400, "invalid device")
            return
        with DELETES_LOCK:
            data = load_deletes()
        if len(devices) == 1:
            self._send_json(200, data.get(devices[0], []))
        else:
            self._send_json(200, data)

    def _read_json_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return None
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None

    def _queue_delete(self, filename, qs):
        filename = safe_filename(urllib.parse.unquote(filename))
        if not filename:
            self._send_error(400, "invalid name")
            return
        devices = self._devices_from_qs(qs, default_all=True)
        if devices is None:
            self._send_error(400, "invalid device")
            return
        with DELETES_LOCK:
            data = load_deletes()
            for d in devices:
                if filename not in data[d]:
                    data[d].append(filename)
            save_deletes(data)
        self._send_json(200, {"queued": filename, "devices": devices})

    def _queue_delete_from_body(self, qs):
        body = self._read_json_body()
        name = None
        device = (qs.get("device") or [None])[0]
        if isinstance(body, dict):
            name = body.get("name") or body.get("filename")
            if body.get("device"):
                device = body.get("device")
        elif isinstance(body, str):
            name = body
        elif isinstance(body, list) and body:
            # Allow ["a.mp4", "b.mp4"]
            devices = self._devices_from_qs(
                {"device": [device]} if device else {}, default_all=True
            )
            if devices is None:
                self._send_error(400, "invalid device")
                return
            queued = []
            with DELETES_LOCK:
                data = load_deletes()
                for item in body:
                    if not isinstance(item, str):
                        continue
                    fn = safe_filename(item)
                    if not fn:
                        continue
                    for d in devices:
                        if fn not in data[d]:
                            data[d].append(fn)
                    queued.append(fn)
                save_deletes(data)
            self._send_json(200, {"queued": queued, "devices": devices})
            return

        if not name:
            self._send_error(400, "name required")
            return
        q = {}
        if device:
            q["device"] = [str(device)]
        self._queue_delete(str(name), q)

    def _ack_delete(self, filename, qs):
        filename = safe_filename(urllib.parse.unquote(filename))
        if not filename:
            self._send_error(400, "invalid name")
            return
        devices = self._devices_from_qs(qs, default_all=True)
        if devices is None:
            self._send_error(400, "invalid device")
            return
        with DELETES_LOCK:
            data = load_deletes()
            for d in devices:
                if filename in data[d]:
                    data[d] = [n for n in data[d] if n != filename]
            save_deletes(data)
        self._send_json(200, {"cleared": filename, "devices": devices})

    def _send_json(self, code, obj):
        payload = json.dumps(obj, indent=2).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _send_error(self, code, message):
        self._send_json(code, {"error": message})


class ThreadedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    allow_reuse_address = True
    daemon_threads = True


# --- mDNS Registration via dns.sd (macOS built-in) ---

def register_mdns():
    """Register _kidvid._tcp service via dns-sd subprocess (macOS)."""
    import subprocess
    try:
        proc = subprocess.Popen(
            ["dns-sd", "-R", "KidVid Server", SERVICE_NAME, "local", str(PORT)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        print(f"[KidVid] mDNS: registered '{SERVICE_NAME}' on port {PORT} (pid {proc.pid})")
        return proc
    except FileNotFoundError:
        print("[KidVid] mDNS: dns-sd not found, skipping registration")
        return None


def main():
    os.makedirs(VIDEO_DIR, exist_ok=True)

    mdns_proc = register_mdns()

    server = ThreadedHTTPServer(("0.0.0.0", PORT), KidVidHandler)
    print(f"[KidVid] Serving videos from: {VIDEO_DIR}")
    print(f"[KidVid] Listening on port {PORT}")
    print(f"[KidVid] Video list: http://localhost:{PORT}/videos")
    print(f"[KidVid] Pending deletes: http://localhost:{PORT}/deletes")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[KidVid] Shutting down...")
    finally:
        server.server_close()
        if mdns_proc:
            mdns_proc.terminate()


if __name__ == "__main__":
    main()
