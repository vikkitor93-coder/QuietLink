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
MAX_RELAY_BODY = 32 * 1024
MAX_RELAY_DATA_CHARS = 28 * 1024
MAX_RELAY_QUEUE_MESSAGES = 64
MAX_RELAY_QUEUE_CHARS = 256 * 1024
RELAY_WAIT_SECONDS = 4.0
MAX_ROOMS = 1000
BUILD = os.environ.get("QUIETLINK_RENDEZVOUS_BUILD", "pi-python")
ENABLE_DEV_ENDPOINT = os.environ.get("QUIETLINK_DEV_ENDPOINT", "").lower() in ("1", "true", "yes")
TOKEN_RE = re.compile(r"^[A-Za-z0-9_=-]+$")
RELAY_DATA_RE = re.compile(r"^[A-Za-z0-9_-]+$")

rooms = {}
rate = {}
logs = deque(maxlen=40)
counters = {
    "requests": 0,
    "register": 0,
    "poll": 0,
    "leave": 0,
    "relaySend": 0,
    "relayPoll": 0,
    "relayAck": 0,
    "rejected": 0,
    "expired": 0,
}
lock = threading.RLock()
relay_condition = threading.Condition(lock)


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


def valid_relay_data(value):
    return (
        isinstance(value, str)
        and 1 <= len(value) <= MAX_RELAY_DATA_CHARS
        and RELAY_DATA_RE.fullmatch(value) is not None
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


def allow(peer, limit=30, bucket="base"):
    now = time.monotonic()
    key = bucket + ":" + peer
    with lock:
        current = rate.get(key)
        if current is None or now - current["window"] > 10:
            current = {"window": now, "count": 0}
        current["count"] += 1
        rate[key] = current
        return current["count"] <= limit


def cleanup_locked(now=None):
    if now is None:
        now = time.monotonic()
    empty_rooms = []
    changed = False
    for room, peers in list(rooms.items()):
        expired = [
            peer for peer, entry in peers.items()
            if entry["expiresAt"] <= now
        ]
        for peer in expired:
            del peers[peer]
            counters["expired"] += 1
            changed = True
        if not peers:
            empty_rooms.append(room)
    for room in empty_rooms:
        rooms.pop(room, None)
        changed = True
    if len(rate) > 10000:
        rate.clear()
    if changed:
        relay_condition.notify_all()


def cleanup():
    with relay_condition:
        cleanup_locked()


def active_peer_count():
    with lock:
        return sum(len(peers) for peers in rooms.values())


def relay_queue_for(entry):
    queue = entry.get("relayQueue")
    if queue is None:
        queue = []
        entry["relayQueue"] = queue
    entry.setdefault("relayChars", 0)
    entry.setdefault("relayAck", {})
    return queue


def first_relay_message(entry, sender):
    for item in relay_queue_for(entry):
        if item["from"] == sender:
            return item
    return None


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

    def read_json(self, maximum=MAX_BODY):
        raw_length = self.headers.get("Content-Length")
        try:
            length = int(raw_length or "0")
        except ValueError:
            raise ValueError("invalid_length")
        if length <= 0 or length > maximum:
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
                    "phase": "control-relay-test",
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
                "<p>This service helps peers find each other and can forward opaque "
                "QuietLink control bytes. Media and session keys are not terminated here. "
                "Local QuietLink calling does not depend on it.</p>",
            )

        if self.path == "/dev":
            if not ENABLE_DEV_ENDPOINT:
                return self.send_json(404, {"ok": False, "error": "not_found"})
            with lock:
                payload = {
                    "service": "quietlink-online",
                    "phase": "control-relay-test",
                    "build": BUILD,
                    "activeRooms": len(rooms),
                    "activePeers": active_peer_count(),
                    "counters": dict(counters),
                    "latestLogs": list(logs)[-20:],
                }
            return self.send_json(200, payload)

        return self.send_json(404, {"ok": False, "error": "not_found"})

    def do_POST(self):
        allowed_paths = (
            "/v1/register", "/v1/poll", "/v1/leave",
            "/v1/relay-send", "/v1/relay-poll", "/v1/relay-ack",
        )
        if self.path not in allowed_paths:
            return self.send_json(404, {"ok": False, "error": "not_found"})

        with lock:
            counters["requests"] += 1

        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            with lock:
                counters["rejected"] += 1
            return self.send_json(415, {"ok": False, "error": "content_type"})

        try:
            body = self.read_json(MAX_RELAY_BODY if self.path == "/v1/relay-send" else MAX_BODY)
        except Exception:
            with lock:
                counters["rejected"] += 1
            return self.send_json(400, {"ok": False, "error": "invalid_request"})

        room = body.get("room")
        peer = body.get("peer")
        relay_request = self.path.startswith("/v1/relay-")
        if (
            not valid_token(room, 256)
            or not valid_token(peer, 128)
            or not allow(peer, 100 if relay_request else 30, "relay" if relay_request else "base")
        ):
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
                return self.send_json(400, {"ok": False, "error": "invalid_registration"})

            with relay_condition:
                peers = rooms.get(room)
                if peers is None:
                    if len(rooms) >= MAX_ROOMS:
                        counters["rejected"] += 1
                        return self.send_json(503, {"ok": False, "error": "capacity"})
                    peers = {}
                    rooms[room] = peers

                existing = peers.get(peer) or {}
                peers[peer] = {
                    "role": role,
                    "protocol": protocol,
                    "candidates": candidates,
                    "expiresAt": time.monotonic() + TTL_SECONDS,
                    "relayQueue": existing.get("relayQueue", []),
                    "relayChars": existing.get("relayChars", 0),
                    "relayAck": existing.get("relayAck", {}),
                }
                counters["register"] += 1
                relay_condition.notify_all()

            return self.send_json(200, {"ok": True, "expiresInSeconds": TTL_SECONDS})

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

        if self.path == "/v1/relay-send":
            target = body.get("to")
            seq = body.get("seq")
            data = body.get("data")
            if (
                not valid_token(target, 128)
                or not isinstance(seq, int)
                or isinstance(seq, bool)
                or seq <= 0
                or seq > 2**63 - 1
                or not valid_relay_data(data)
            ):
                with lock:
                    counters["rejected"] += 1
                return self.send_json(400, {"ok": False, "error": "invalid_relay"})

            with relay_condition:
                peers = rooms.get(room)
                sender = peers.get(peer) if peers else None
                recipient = peers.get(target) if peers else None
                if sender is None or recipient is None or sender["role"] == recipient["role"]:
                    counters["rejected"] += 1
                    return self.send_json(409, {"ok": False, "error": "relay_unavailable"})

                queue = relay_queue_for(recipient)
                acked = int(recipient["relayAck"].get(peer, 0))
                if seq <= acked:
                    counters["relaySend"] += 1
                    return self.send_json(200, {"ok": True, "duplicate": True})

                existing = next((item for item in queue if item["from"] == peer and item["seq"] == seq), None)
                if existing is not None:
                    if existing["data"] != data:
                        counters["rejected"] += 1
                        return self.send_json(409, {"ok": False, "error": "relay_sequence_conflict"})
                    counters["relaySend"] += 1
                    return self.send_json(200, {"ok": True, "duplicate": True})

                highest = acked
                for item in queue:
                    if item["from"] == peer and item["seq"] > highest:
                        highest = item["seq"]
                if seq != highest + 1:
                    counters["rejected"] += 1
                    return self.send_json(409, {"ok": False, "error": "relay_sequence_gap"})

                if len(queue) >= MAX_RELAY_QUEUE_MESSAGES or recipient["relayChars"] + len(data) > MAX_RELAY_QUEUE_CHARS:
                    counters["rejected"] += 1
                    return self.send_json(429, {"ok": False, "error": "relay_backlog"})

                queue.append({"from": peer, "seq": seq, "data": data})
                recipient["relayChars"] += len(data)
                counters["relaySend"] += 1
                relay_condition.notify_all()
            return self.send_json(200, {"ok": True})

        if self.path == "/v1/relay-poll":
            sender = body.get("from")
            if not valid_token(sender, 128):
                with lock:
                    counters["rejected"] += 1
                return self.send_json(400, {"ok": False, "error": "invalid_relay"})

            deadline = time.monotonic() + RELAY_WAIT_SECONDS
            with relay_condition:
                counters["relayPoll"] += 1
                while True:
                    cleanup_locked()
                    peers = rooms.get(room)
                    me = peers.get(peer) if peers else None
                    remote = peers.get(sender) if peers else None
                    if me is None:
                        return self.send_json(409, {"ok": False, "error": "relay_unavailable"})
                    if remote is None or me["role"] == remote["role"]:
                        return self.send_json(200, {"ok": True, "peerPresent": False})
                    message = first_relay_message(me, sender)
                    if message is not None:
                        return self.send_json(200, {
                            "ok": True,
                            "peerPresent": True,
                            "seq": message["seq"],
                            "data": message["data"],
                        })
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        return self.send_json(200, {"ok": True, "peerPresent": True, "data": ""})
                    relay_condition.wait(timeout=remaining)

        if self.path == "/v1/relay-ack":
            sender = body.get("from")
            seq = body.get("seq")
            if (
                not valid_token(sender, 128)
                or not isinstance(seq, int)
                or isinstance(seq, bool)
                or seq <= 0
                or seq > 2**63 - 1
            ):
                with lock:
                    counters["rejected"] += 1
                return self.send_json(400, {"ok": False, "error": "invalid_relay"})

            with relay_condition:
                peers = rooms.get(room)
                me = peers.get(peer) if peers else None
                remote = peers.get(sender) if peers else None
                if me is None or remote is None or me["role"] == remote["role"]:
                    counters["rejected"] += 1
                    return self.send_json(409, {"ok": False, "error": "relay_unavailable"})

                queue = relay_queue_for(me)
                acked = int(me["relayAck"].get(sender, 0))
                if seq <= acked:
                    counters["relayAck"] += 1
                    return self.send_json(200, {"ok": True, "duplicate": True})
                if seq != acked + 1:
                    counters["rejected"] += 1
                    return self.send_json(409, {"ok": False, "error": "relay_ack_gap"})

                index = next((i for i, item in enumerate(queue)
                              if item["from"] == sender and item["seq"] == seq), None)
                if index is None:
                    counters["rejected"] += 1
                    return self.send_json(409, {"ok": False, "error": "relay_message_missing"})
                item = queue.pop(index)
                me["relayChars"] = max(0, me["relayChars"] - len(item["data"]))
                me["relayAck"][sender] = seq
                counters["relayAck"] += 1
                relay_condition.notify_all()
            return self.send_json(200, {"ok": True})

        with relay_condition:
            counters["leave"] += 1
            peers = rooms.get(room)
            if peers:
                peers.pop(peer, None)
                if not peers:
                    rooms.pop(room, None)
            relay_condition.notify_all()
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
