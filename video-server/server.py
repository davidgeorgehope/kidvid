#!/usr/bin/env python3
"""KidVid Video Server — shared library over HTTP with mDNS discovery.

Multi-device model (no delete-on-download):
  - One shared library of .mp4 files under $KIDVID_DIR (flat).
  - GET /videos?device=<id> lists only files that device has not yet acked.
  - PUT /acked/<name>?device=<id> records that a device finished (or already has) a file.
  - Library is permanent by default (no age-out). Optional GC via
    KIDVID_LIBRARY_MAX_AGE_DAYS>0. Parent PIN DELETE still removes files.
  - Parent PIN / CoS may DELETE /videos/<name>; pending /deletes still propagates
    remote deletes to devices that already downloaded a copy.

Also serves /nox/home: LAN-IP locator for Nox Surveillance (Google TV).
State files live beside videos: deletes.json, acks.json, nox-home.json.
"""

import http.server
import ipaddress
import json
import os
import socketserver
import threading
import time
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

PORT = 8643
VIDEO_DIR = os.environ.get("KIDVID_DIR", os.path.expanduser("~/kidvid-videos"))
SERVICE_NAME = "_kidvid._tcp"
# Legacy buckets kept so older clients / CoS scripts still tee phone+fire.
LEGACY_DEVICES = ("phone", "fire")
DELETES_LOCK = threading.Lock()
ACKS_LOCK = threading.Lock()
NOX_HOME_LOCK = threading.Lock()
GC_LOCK = threading.Lock()
DEFAULT_DASHBOARD_PORT = 8091
DEFAULT_GO2RTC_RTSP_PORT = 8080


def _parse_library_max_age_days():
    """Days before optional mtime age-out. 0 / unset / negative = GC disabled (permanent)."""
    raw = (os.environ.get("KIDVID_LIBRARY_MAX_AGE_DAYS") or "0").strip()
    try:
        days = int(raw)
    except ValueError:
        return 0
    return days if days > 0 else 0


# Default 0 = keep full library forever. Set e.g. 7 or 36500 to enable mtime GC.
LIBRARY_MAX_AGE_DAYS = _parse_library_max_age_days()
LIBRARY_MAX_AGE_SECONDS = LIBRARY_MAX_AGE_DAYS * 24 * 3600 if LIBRARY_MAX_AGE_DAYS else 0
GC_INTERVAL_SECONDS = int(os.environ.get("KIDVID_GC_INTERVAL_SECONDS", str(3600)))
PROTECTED_NAMES = frozenset({"deletes.json", "acks.json", "nox-home.json"})
GC_ENABLED = LIBRARY_MAX_AGE_SECONDS > 0


def deletes_path():
    return Path(VIDEO_DIR) / "deletes.json"


def acks_path():
    return Path(VIDEO_DIR) / "acks.json"


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


def empty_deletes(extra_devices=()):
    out = {d: [] for d in LEGACY_DEVICES}
    for d in extra_devices:
        if d and d not in out:
            out[d] = []
    return out


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
        # Legacy / flat list applies to every known bucket
        for d in list(out.keys()):
            out[d] = [n for n in data if isinstance(n, str) and n]
        return out
    if isinstance(data, dict):
        for key, vals in data.items():
            if not isinstance(key, str) or not key:
                continue
            if not isinstance(vals, list):
                continue
            out[key] = [n for n in vals if isinstance(n, str) and n]
    return out


def save_deletes(data):
    path = deletes_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def load_acks():
    """Return {device_id: [filename, ...]}."""
    path = acks_path()
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8") or "{}")
    except (OSError, json.JSONDecodeError):
        return {}
    if not isinstance(data, dict):
        return {}
    out = {}
    for key, vals in data.items():
        if not isinstance(key, str) or not key:
            continue
        if isinstance(vals, list):
            out[key] = [n for n in vals if isinstance(n, str) and n]
    return out


def save_acks(data):
    path = acks_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def known_devices():
    """Union of legacy buckets, delete keys, and ack keys."""
    devices = set(LEGACY_DEVICES)
    with DELETES_LOCK:
        devices.update(load_deletes().keys())
    with ACKS_LOCK:
        devices.update(load_acks().keys())
    return sorted(d for d in devices if d)


def safe_device_id(raw):
    """Allow phone|fire|pixel|iphone-yellow|uuid-ish ids; reject path junk."""
    if not isinstance(raw, str):
        return None
    raw = raw.strip().lower()
    if not raw or len(raw) > 64:
        return None
    if "/" in raw or "\\" in raw or ".." in raw or " " in raw:
        return None
    allowed = set("abcdefghijklmnopqrstuvwxyz0123456789-_")
    if any(c not in allowed for c in raw):
        return None
    return raw


def safe_filename(name):
    if not name or "/" in name or "\\" in name or ".." in name or name in (".",) or name in PROTECTED_NAMES:
        return None
    return name


def iter_library_mp4s():
    """Yield Path objects for shared-library .mp4 files (flat $KIDVID_DIR)."""
    video_dir = Path(VIDEO_DIR)
    if not video_dir.exists():
        return
    for f in sorted(video_dir.iterdir()):
        if not f.is_file():
            continue
        if f.name in PROTECTED_NAMES:
            continue
        if f.suffix.lower() == ".mp4":
            yield f


def remove_ack_filename(filename):
    """Drop a filename from every device's ack list (after library delete/GC)."""
    with ACKS_LOCK:
        data = load_acks()
        changed = False
        for device, names in list(data.items()):
            if filename in names:
                data[device] = [n for n in names if n != filename]
                changed = True
        if changed:
            save_acks(data)


def gc_library(now=None):
    """Optional age-out of shared-library media older than LIBRARY_MAX_AGE_SECONDS.

    Disabled by default (LIBRARY_MAX_AGE_DAYS=0): library is permanent until
    parent/CoS DELETE. When enabled, age source is filesystem mtime of the .mp4.
    Only deletes *.mp4 and matching *.jpg thumbs. Never touches state JSON files.
    """
    if not GC_ENABLED:
        return []
    if now is None:
        now = time.time()
    removed = []
    with GC_LOCK:
        for mp4 in list(iter_library_mp4s()):
            try:
                age = now - mp4.stat().st_mtime
            except OSError:
                continue
            if age < LIBRARY_MAX_AGE_SECONDS:
                continue
            name = mp4.name
            try:
                mp4.unlink()
            except OSError as e:
                print(f"[KidVid] GC: failed to remove {name}: {e}")
                continue
            thumb = mp4.with_suffix(".jpg")
            if thumb.is_file() and thumb.name not in PROTECTED_NAMES:
                try:
                    thumb.unlink()
                except OSError as e:
                    print(f"[KidVid] GC: failed to remove thumb {thumb.name}: {e}")
            remove_ack_filename(name)
            removed.append(name)
            print(f"[KidVid] GC: aged out {name} (mtime age {int(age / 86400)}d)")
    return removed


def start_gc_thread():
    if not GC_ENABLED:
        return None

    def loop():
        while True:
            try:
                gc_library()
            except Exception as e:
                print(f"[KidVid] GC loop error: {e}")
            time.sleep(max(60, GC_INTERVAL_SECONDS))

    t = threading.Thread(target=loop, name="kidvid-gc", daemon=True)
    t.start()
    return t


class KidVidHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"[KidVid] {self.address_string()} - {format % args}")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/")
        qs = urllib.parse.parse_qs(parsed.query)

        if path == "/videos":
            self._serve_video_list(qs)
        elif path.startswith("/videos/"):
            filename = urllib.parse.unquote(path[len("/videos/"):])
            self._serve_file(filename)
        elif path == "/deletes":
            self._serve_deletes(qs)
        elif path == "/acked":
            self._serve_acks(qs)
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
        elif path.startswith("/acked/"):
            filename = urllib.parse.unquote(path[len("/acked/"):])
            self._ack_download(filename, qs)
        elif path == "/acked" or path == "/receipts":
            self._ack_download_from_body(qs)
        elif path.startswith("/deletes/"):
            filename = urllib.parse.unquote(path[len("/deletes/"):])
            self._queue_delete(filename, qs)
        elif path == "/deletes":
            self._queue_delete_from_body(qs)
        else:
            self._send_error(404, "not found")

    def do_POST(self):
        # Same as PUT: tee pending deletes, acks, or publish /nox/home
        self.do_PUT()

    def do_DELETE(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/")
        qs = urllib.parse.parse_qs(parsed.query)

        if path.startswith("/videos/"):
            filename = urllib.parse.unquote(path[len("/videos/"):])
            if not self._parent_delete_authorized(qs):
                # Guard: bare DELETE from old sync clients must not wipe the shared library.
                self._send_error(403, "parent delete required (?parent=1 or X-KidVid-Action: parent-delete)")
                return
            self._delete_file(filename, qs)
        elif path.startswith("/deletes/"):
            filename = urllib.parse.unquote(path[len("/deletes/"):])
            self._ack_delete(filename, qs)
        else:
            self._send_error(404, "not found")

    def _parent_delete_authorized(self, qs):
        """True if this DELETE is an intentional parent/CoS library remove."""
        parent = (qs.get("parent") or [None])[0]
        if parent is not None and str(parent).strip() in ("1", "true", "yes"):
            return True
        action = (self.headers.get("X-KidVid-Action") or "").strip().lower()
        return action == "parent-delete"

    def _serve_index(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(
            b"KidVid Video Server (shared library)\n\n"
            b"GET /videos - list shared library (all .mp4)\n"
            b"GET /videos?device=<id> - list files not yet acked by that device\n"
            b"GET /videos/<name> - download video\n"
            b"DELETE /videos/<name>?parent=1 - parent/CoS remove (or X-KidVid-Action: parent-delete)\n"
            b"PUT|POST /acked/<name>?device=<id> - mark downloaded for device\n"
            b"GET /acked?device=<id> - list acked filenames for device\n"
            b"GET /deletes?device=<id> - pending remote deletes\n"
            b"PUT|POST /deletes/<name>?device=<id> - tee pending delete\n"
            b"DELETE /deletes/<name>?device=<id> - clear after device applied\n"
            b"GET /nox/home - Mac mini LAN IP locator (public)\n"
            b"PUT|POST /nox/home - publish LAN IP (Bearer NOX_HOME_TOKEN)\n"
            b"\nLibrary is permanent by default (no age-out GC).\n"
            b"Bare DELETE /videos/<name> returns 403 (blocks old delete-on-download clients).\n"
        )

    def _serve_health(self):
        self._send_json(200, {
            "status": "ok",
            "mode": "shared-library",
            "library_max_age_days": LIBRARY_MAX_AGE_DAYS if GC_ENABLED else None,
            "library_gc": "enabled" if GC_ENABLED else "disabled",
            "devices": known_devices(),
        })

    def _serve_video_list(self, qs):
        device_raw = (qs.get("device") or [None])[0]
        device = None
        if device_raw:
            device = safe_device_id(device_raw)
            if device is None:
                self._send_error(400, "invalid device")
                return

        acked = set()
        if device:
            with ACKS_LOCK:
                acked = set(load_acks().get(device, []))

        videos = []
        for f in iter_library_mp4s():
            if device and f.name in acked:
                continue
            videos.append({
                "name": f.name,
                "size": f.stat().st_size,
                "url": f"/videos/{urllib.parse.quote(f.name)}",
            })
        self._send_json(200, videos)

    def _serve_acks(self, qs):
        device_raw = (qs.get("device") or [None])[0]
        if not device_raw:
            with ACKS_LOCK:
                self._send_json(200, load_acks())
            return
        device = safe_device_id(device_raw)
        if device is None:
            self._send_error(400, "invalid device")
            return
        with ACKS_LOCK:
            self._send_json(200, load_acks().get(device, []))

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

    def _delete_file(self, filename, qs):
        """Parent/CoS library delete. Does not run on normal client sync."""
        filename = safe_filename(filename)
        if not filename:
            self._send_error(400, "invalid name")
            return

        filepath = Path(VIDEO_DIR) / filename
        if filepath.exists() and filepath.is_file():
            try:
                filepath.unlink()
            except OSError as e:
                self._send_error(500, str(e))
                return
            thumb = filepath.with_suffix(".jpg")
            if thumb.is_file():
                try:
                    thumb.unlink()
                except OSError:
                    pass
        # Always clear acks for this name; tee pending deletes so devices drop local copies.
        remove_ack_filename(filename)
        devices = self._devices_from_qs(qs, default_all=True)
        if devices is None:
            self._send_error(400, "invalid device")
            return
        with DELETES_LOCK:
            data = load_deletes()
            for d in devices:
                if d not in data:
                    data[d] = []
                if filename not in data[d]:
                    data[d].append(filename)
            save_deletes(data)

        self._send_json(200, {"deleted": filename, "pending_devices": devices})

    def _devices_from_qs(self, qs, default_all=True):
        raw = (qs.get("device") or [None])[0]
        if not raw:
            return known_devices() if default_all else None
        device = safe_device_id(raw)
        if device is None:
            return None
        return [device]

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
            # Include empty buckets for requested/known devices
            out = {d: data.get(d, []) for d in devices}
            self._send_json(200, out)

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

    def _ack_download(self, filename, qs):
        filename = safe_filename(urllib.parse.unquote(filename))
        if not filename:
            self._send_error(400, "invalid name")
            return
        devices = self._devices_from_qs(qs, default_all=False)
        if not devices:
            self._send_error(400, "device required")
            return
        device = devices[0]
        with ACKS_LOCK:
            data = load_acks()
            names = data.get(device, [])
            if filename not in names:
                names.append(filename)
            data[device] = names
            save_acks(data)
        self._send_json(200, {"acked": filename, "device": device})

    def _ack_download_from_body(self, qs):
        body = self._read_json_body()
        name = None
        device = (qs.get("device") or [None])[0]
        if isinstance(body, dict):
            name = body.get("name") or body.get("filename")
            if body.get("device"):
                device = body.get("device")
        elif isinstance(body, str):
            name = body
        if not name:
            self._send_error(400, "name required")
            return
        q = {}
        if device:
            q["device"] = [str(device)]
        self._ack_download(str(name), q)

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
                if d not in data:
                    data[d] = []
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
                        if d not in data:
                            data[d] = []
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
                if filename in data.get(d, []):
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

    if GC_ENABLED:
        removed = gc_library()
        if removed:
            print(f"[KidVid] Startup GC removed {len(removed)} aged file(s)")
        start_gc_thread()
        print(f"[KidVid] Library GC: mtime older than {LIBRARY_MAX_AGE_DAYS} days")
    else:
        print("[KidVid] Library GC: disabled (permanent library until parent DELETE)")

    mdns_proc = register_mdns()

    server = ThreadedHTTPServer(("0.0.0.0", PORT), KidVidHandler)
    print(f"[KidVid] Shared library from: {VIDEO_DIR}")
    print(f"[KidVid] Listening on port {PORT}")
    print(f"[KidVid] Video list: http://localhost:{PORT}/videos?device=<id>")
    print(f"[KidVid] Acks: http://localhost:{PORT}/acked")
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
