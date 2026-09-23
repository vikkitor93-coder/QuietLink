#!/usr/bin/env python3
import json
import os
import re
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8787"))
BIND_HOST = os.environ.get("BIND_HOST", "127.0.0.1")
TTL_SECONDS = 45
MAX_BODY = 8 * 1024
MAX_ROOMS = 1000
BUILD = os.environ.get("QUIETLINK_RENDEZVOUS_BUILD", "pi-python")
ENABLE_DEV_ENDPOINT = os.environ.get("QUIETLINK_DEV_ENDPOINT", "").lower() in ("1", "true", "yes")
TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]+$")

rooms = {}
rate = {}
logs = deque(maxlen=40)
counters = {
    "requests": 0,
    "register": 0,
    "poll": 0,
    "leave": 0,
    "rejected": 0,
    "expired": 0,
}
lock = threading.RLock()


def safe_log(event):
    safe = re.sub(r"[^a-zA-Z0-9_.:-]", "", str(event or ""))[:80] or "event"
    with lock:
        logs.append({
            "time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "event": safe,
        })


def valid_token(value, maximum=128):
    return (
        isinstance(value, str)
        and 16 <= len(value) <= maximum
        and TOKEN_RE.fullmatch(value) is not None
    )


def clean_candidates(value):
    if not isinstance(value, list) or len(value) > 8:
        return None
    out = []
    for item in value:
        if not isinstance(item, dict):
            return None
        kind = item.get("kind")
        host = item.get("host")
        try:
            tcp_port = int(item.get("tcpPort") or 0)
            udp_port = int(item.get("udpPort") or 0)
        except (TypeError, ValueError):
            return None
        if kind not in ("srflx", "host"):
            return None
        if not isinstance(host, str) or not (3 <= len(host) <= 128):
            return None
        if not (0 <= tcp_port <= 65535 and 0 <= udp_port <= 65535):
            return None
        out.append({
            "kind": kind,
            "host": host,
            "tcpPort": tcp_port,
            "udpPort": udp_port,
        })
    return out


def allow(peer):
    now = time.monotonic()
    with lock:
        current = rate.get(peer)
        if current is None or now - current["window"] > 10:
            current = {"window": now, "count": 0}
        current["count"] += 1
        rate[peer] = current
        return current["count"] <= 30


def cleanup():
    now = time.monotonic()
    with lock:
        empty_rooms = []
        for room, peers in list(rooms.items()):
            expired = [
                peer for peer, entry in peers.items()
                if entry["expiresAt"] <= now
            ]
            for peer in expired:
                del peers[peer]
                counters["expired"] += 1
            if not peers:
                empty_rooms.append(room)
        for room in empty_rooms:
            rooms.pop(room, None)
        if len(rate) > 10000:
            rate.clear()


def active_peer_count():
    with lock:
        return sum(len(peers) for peers in rooms.values())


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "QuietLinkRendezvous"
    sys_version = ""

    def log_message(self, fmt, *args):
        # Intentionally suppress BaseHTTPRequestHandler access logs because
        # they contain client IP addresses and request metadata.
        return

    def send_json(self, status, body):
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_html(self, status, body):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_json(self):
        raw_length = self.headers.get("Content-Length")
        try:
            length = int(raw_length or "0")
        except ValueError:
            raise ValueError("invalid_length")
        if length <= 0 or length > MAX_BODY:
            raise ValueError("invalid_length")
        raw = self.rfile.read(length)
        return json.loads(raw.decode("utf-8"))

    def do_GET(self):
        cleanup()
        if self.path == "/health":
            with lock:
                payload = {
                    "service": "quietlink-online",
                    "status": "ok",
                    "onlineCallsAvailable": False,
                    "phase": "rendezvous-bootstrap",
                    "build": BUILD,
                    "activeRooms": len(rooms),
                    "activePeers": active_peer_count(),
                }
            return self.send_json(200, payload)

        if self.path == "/":
            return self.send_html(
                200,
                "<!doctype html><meta charset=utf-8>"
                "<title>QuietLink rendezvous</title>"
                "<h1>QuietLink rendezvous service</h1>"
                "<p>This service only helps peers find each other. "
                "Local QuietLink calling does not depend on it.</p>",
            )

        if self.path == "/dev":
            if not ENABLE_DEV_ENDPOINT:
                return self.send_json(404, {"ok": False, "error": "not_found"})
            with lock:
                payload = {
                    "service": "quietlink-online",
                    "phase": "rendezvous-bootstrap",
                    "build": BUILD,
                    "activeRooms": len(rooms),
                    "activePeers": active_peer_count(),
                    "counters": dict(counters),
                    "latestLogs": list(logs)[-20:],
                }
            return self.send_json(200, payload)

        return self.send_json(404, {"ok": False, "error": "not_found"})

    def do_POST(self):
        if self.path not in ("/v1/register", "/v1/poll", "/v1/leave"):
            return self.send_json(404, {"ok": False, "error": "not_found"})

        with lock:
            counters["requests"] += 1

        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            with lock:
                counters["rejected"] += 1
            return self.send_json(415, {"ok": False, "error": "content_type"})

        try:
            body = self.read_json()
        except Exception:
            with lock:
                counters["rejected"] += 1
            return self.send_json(400, {"ok": False, "error": "invalid_request"})

        room = body.get("room")
        peer = body.get("peer")
        if not valid_token(room, 256) or not valid_token(peer, 128) or not allow(peer):
            with lock:
                counters["rejected"] += 1
            return self.send_json(400, {"ok": False, "error": "invalid_request"})

        cleanup()

        if self.path == "/v1/register":
            role = body.get("role")
            protocol = body.get("protocol")
            candidates = clean_candidates(body.get("candidates"))
            if (
                role not in ("host", "join")
                or not isinstance(protocol, int)
                or isinstance(protocol, bool)
                or not (1 <= protocol <= 32)
                or candidates is None
            ):
                with lock:
                    counters["rejected"] += 1
                return self.send_json(
                    400, {"ok": False, "error": "invalid_registration"}
                )

            with lock:
                peers = rooms.get(room)
                if peers is None:
                    if len(rooms) >= MAX_ROOMS:
                        counters["rejected"] += 1
                        return self.send_json(
                            503, {"ok": False, "error": "capacity"}
                        )
                    peers = {}
                    rooms[room] = peers

                peers[peer] = {
                    "role": role,
                    "protocol": protocol,
                    "candidates": candidates,
                    "expiresAt": time.monotonic() + TTL_SECONDS,
                }
                counters["register"] += 1

            return self.send_json(
                200, {"ok": True, "expiresInSeconds": TTL_SECONDS}
            )

        if self.path == "/v1/poll":
            with lock:
                counters["poll"] += 1
                peers = rooms.get(room)
                if not peers or peer not in peers:
                    return self.send_json(200, {"ok": True, "matched": False})

                me = peers[peer]
                match_peer = None
                match_entry = None
                for other_peer, entry in peers.items():
                    if other_peer == peer or entry["role"] == me["role"]:
                        continue
                    match_peer = other_peer
                    match_entry = entry
                    break

                if match_entry is None:
                    return self.send_json(200, {"ok": True, "matched": False})

                payload = {
                    "ok": True,
                    "matched": True,
                    "peer": match_peer,
                    "protocol": match_entry["protocol"],
                    "candidates": match_entry["candidates"],
                }
            return self.send_json(200, payload)

        with lock:
            counters["leave"] += 1
            peers = rooms.get(room)
            if peers:
                peers.pop(peer, None)
                if not peers:
                    rooms.pop(room, None)
        return self.send_json(200, {"ok": True})


def cleanup_loop():
    while True:
        time.sleep(10)
        try:
            cleanup()
        except Exception:
            safe_log("cleanup_error")


if __name__ == "__main__":
    safe_log("server_started")
    thread = threading.Thread(target=cleanup_loop, name="QuietLinkCleanup", daemon=True)
    thread.start()
    server = ThreadingHTTPServer((BIND_HOST, PORT), Handler)
    server.serve_forever()
