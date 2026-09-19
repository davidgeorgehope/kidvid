#!/usr/bin/env python3
"""KidVid Video Server — serves videos over HTTP with mDNS discovery.

Also maintains deletes.json: a durable pending-delete queue so CoS can tee
remote deletes for files devices already downloaded (empty /videos after drain).

Also serves /nox/home: a tiny LAN-IP locator so Nox Surveillance (Google TV)
can rediscover the Mac mini after network resets. State is stored in
$KIDVID_DIR/nox-home.json (same directory as deletes.json).
"""

import http.server
import ipaddress
import json
import os
import socket
import socketserver
import threading
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

PORT = 8643
VIDEO_DIR = os.environ.get("KIDVID_DIR", os.path.expanduser("~/kidvid-videos"))
SERVICE_NAME = "_kidvid._tcp"
KNOWN_DEVICES = ("phone", "fire")
DELETES_LOCK = threading.Lock()
NOX_HOME_LOCK = threading.Lock()
DEFAULT_DASHBOARD_PORT = 8091
DEFAULT_GO2RTC_RTSP_PORT = 8080


def deletes_path():
    return Path(VIDEO_DIR) / "deletes.json"


def nox_home_path():
    """Durable locator file beside videos (same dir as deletes.json)."""
    return Path(VIDEO_DIR) / "nox-home.json"


def load_nox_home():
    path = nox_home_path()
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8") or "{}")
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict) or not data.get("lan_ip"):
        return None
    return data


def save_nox_home(data):
    path = nox_home_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def valid_ipv4(value):
    """Return True if value is a plausible IPv4 address string (no hostnames)."""
    if not isinstance(value, str) or not value or "/" in value or "\\" in value:
        return False
    try:
        ipaddress.IPv4Address(value)
    except (ValueError, ipaddress.AddressValueError):
        return False
    return True


def parse_port(value, default):
    if value is None:
        return default
    try:
        port = int(value)
    except (TypeError, ValueError):
        return None
    if port < 1 or port > 65535:
        return None
    return port


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
    if not name or "/" in name or "\\" in name or ".." in name or name in (".", "deletes.json", "nox-home.json"):
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
        elif path == "/nox/home":
            self._serve_nox_home()
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

        if path == "/nox/home":
            self._put_nox_home()
        elif path.startswith("/deletes/"):
            filename = urllib.parse.unquote(path[len("/deletes/"):])
            self._queue_delete(filename, qs)
        elif path == "/deletes":
            self._queue_delete_from_body(qs)
        else:
            self._send_error(404, "not found")

    def do_POST(self):
        # Same as PUT: tee pending deletes or publish /nox/home
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
            b"GET /nox/home - Mac mini LAN IP locator (public)\n"
            b"PUT|POST /nox/home - publish LAN IP (Bearer NOX_HOME_TOKEN)\n"
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

    def _require_nox_home_token(self):
        """Authorize writes. Missing/empty NOX_HOME_TOKEN => 503 (never open)."""
        expected = os.environ.get("NOX_HOME_TOKEN") or ""
        if not expected:
            self._send_error(503, "NOX_HOME_TOKEN not configured")
            return False
        auth = self.headers.get("Authorization") or ""
        if auth != f"Bearer {expected}":
            self._send_error(401, "unauthorized")
            return False
        return True

    def _serve_nox_home(self):
        with NOX_HOME_LOCK:
            data = load_nox_home()
        if not data:
            self._send_error(404, "not set")
            return
        self._send_json(200, data)

    def _put_nox_home(self):
        if not self._require_nox_home_token():
            return
        body = self._read_json_body()
        if not isinstance(body, dict):
            self._send_error(400, "JSON object required")
            return
        lan_ip = body.get("lan_ip")
        if not valid_ipv4(lan_ip):
            self._send_error(400, "invalid lan_ip")
            return
        dashboard_port = parse_port(
            body.get("dashboard_port"), DEFAULT_DASHBOARD_PORT
        )
        if dashboard_port is None:
            self._send_error(400, "invalid dashboard_port")
            return
        go2rtc_rtsp_port = parse_port(
            body.get("go2rtc_rtsp_port"), DEFAULT_GO2RTC_RTSP_PORT
        )
        if go2rtc_rtsp_port is None:
            self._send_error(400, "invalid go2rtc_rtsp_port")
            return
        saved = {
            "lan_ip": lan_ip,
            "dashboard_port": dashboard_port,
            "go2rtc_rtsp_port": go2rtc_rtsp_port,
            "updated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
        with NOX_HOME_LOCK:
            save_nox_home(saved)
        self._send_json(200, saved)

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
    print(f"[KidVid] Nox home: http://localhost:{PORT}/nox/home")
    if not (os.environ.get("NOX_HOME_TOKEN") or ""):
        print("[KidVid] NOX_HOME_TOKEN unset — PUT/POST /nox/home will return 503")

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
