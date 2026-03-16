#!/usr/bin/env python3
"""KidVid Video Server — serves videos over HTTP with mDNS discovery."""

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


class KidVidHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"[KidVid] {self.address_string()} - {format % args}")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/")

        if path == "/videos":
            self._serve_video_list()
        elif path.startswith("/videos/"):
            filename = urllib.parse.unquote(path[len("/videos/"):])
            self._serve_file(filename)
        elif path == "" or path == "/":
            self._serve_index()
        else:
            self._send_error(404, "Not found")

    def _serve_index(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"KidVid Video Server\n\nGET /videos — list videos\nGET /videos/<name> — download video\n")

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
                        "url": f"http://{host}/videos/{urllib.parse.quote(f.name)}"
                    })
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(videos, indent=2).encode())

    def _serve_file(self, filename):
        # Prevent path traversal
        if "/" in filename or "\\" in filename or ".." in filename:
            self._send_error(400, "Invalid filename")
            return

        filepath = Path(VIDEO_DIR) / filename
        if not filepath.exists() or not filepath.is_file():
            self._send_error(404, f"File not found: {filename}")
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

    def _send_error(self, code, message):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"error": message}).encode())


class ThreadedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    allow_reuse_address = True
    daemon_threads = True


# --- mDNS Registration via dns.sd (macOS built-in) ---

def register_mdns():
    """Register _kidvid._tcp service via dns-sd subprocess (macOS)."""
    import subprocess
    hostname = socket.gethostname()
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
