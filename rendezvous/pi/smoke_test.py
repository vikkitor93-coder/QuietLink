#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

PORT = 18787
BASE = "http://127.0.0.1:%d" % PORT


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=2) as response:
        return response.status, json.loads(response.read().decode("utf-8"))


def post(path, payload, content_type="application/json"):
    data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        BASE + path,
        data=data,
        method="POST",
        headers={"Content-Type": content_type, "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=2) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = json.loads(error.read().decode("utf-8"))
        return error.code, body


def expect_http_error(path, status):
    try:
        urllib.request.urlopen(BASE + path, timeout=2)
    except urllib.error.HTTPError as error:
        if error.code != status:
            raise AssertionError("Expected HTTP %d, got %d" % (status, error.code))
        return
    raise AssertionError("Expected HTTP %d" % status)


def main():
    env = dict(os.environ)
    env["PORT"] = str(PORT)
    env["BIND_HOST"] = "127.0.0.1"
    env["QUIETLINK_RENDEZVOUS_BUILD"] = "ci-smoke"
    env.pop("QUIETLINK_DEV_ENDPOINT", None)

    process = subprocess.Popen(
        [sys.executable, "rendezvous/pi/server.py"],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    try:
        deadline = time.time() + 5
        while True:
            try:
                status, health = get("/health")
                break
            except Exception:
                if time.time() >= deadline:
                    raise
                time.sleep(0.1)

        assert status == 200
        assert health["service"] == "quietlink-online"
        assert health["status"] == "ok"
        assert health["onlineCallsAvailable"] is False
        assert health["build"] == "ci-smoke"

        # Public developer diagnostics are disabled unless explicitly enabled.
        expect_http_error("/dev", 404)

        room = "R" * 32
        host_peer = "H" * 24
        join_peer = "J" * 24
        host_candidate = {
            "kind": "srflx",
            "host": "198.51.100.10",
            "tcpPort": 0,
            "udpPort": 41000,
        }
        join_candidate = {
            "kind": "srflx",
            "host": "203.0.113.20",
            "tcpPort": 0,
            "udpPort": 42000,
        }

        status, body = post("/v1/register", {
            "room": room,
            "peer": host_peer,
            "role": "host",
            "protocol": 5,
            "candidates": [host_candidate],
        })
        assert status == 200 and body["ok"] is True

        status, body = post("/v1/register", {
            "room": room,
            "peer": join_peer,
            "role": "join",
            "protocol": 5,
            "candidates": [join_candidate],
        })
        assert status == 200 and body["ok"] is True

        status, body = post("/v1/poll", {"room": room, "peer": host_peer})
        assert status == 200 and body["matched"] is True
        assert body["peer"] == join_peer
        assert body["candidates"] == [join_candidate]

        status, body = post("/v1/poll", {"room": room, "peer": join_peer})
        assert status == 200 and body["matched"] is True
        assert body["peer"] == host_peer
        assert body["candidates"] == [host_candidate]

        # Opaque control relay is reliable: duplicate sends are idempotent,
        # poll does not consume until ACK, and ACK itself is idempotent.
        relay_data = "SGVsbG9RdWlldExpbms"
        status, body = post("/v1/relay-send", {
            "room": room,
            "peer": host_peer,
            "to": join_peer,
            "seq": 1,
            "data": relay_data,
        })
        assert status == 200 and body["ok"] is True

        status, body = post("/v1/relay-send", {
            "room": room,
            "peer": host_peer,
            "to": join_peer,
            "seq": 1,
            "data": relay_data,
        })
        assert status == 200 and body["ok"] is True and body.get("duplicate") is True

        status, body = post("/v1/relay-poll", {
            "room": room,
            "peer": join_peer,
            "from": host_peer,
        })
        assert status == 200 and body["ok"] is True
        assert body["peerPresent"] is True
        assert body["seq"] == 1 and body["data"] == relay_data

        status, body2 = post("/v1/relay-poll", {
            "room": room,
            "peer": join_peer,
            "from": host_peer,
        })
        assert status == 200 and body2["seq"] == 1 and body2["data"] == relay_data

        status, body = post("/v1/relay-ack", {
            "room": room,
            "peer": join_peer,
            "from": host_peer,
            "seq": 1,
        })
        assert status == 200 and body["ok"] is True

        status, body = post("/v1/relay-ack", {
            "room": room,
            "peer": join_peer,
            "from": host_peer,
            "seq": 1,
        })
        assert status == 200 and body["ok"] is True and body.get("duplicate") is True

        # A late retry after ACK must not re-enqueue the same stream chunk.
        status, body = post("/v1/relay-send", {
            "room": room,
            "peer": host_peer,
            "to": join_peer,
            "seq": 1,
            "data": relay_data,
        })
        assert status == 200 and body["ok"] is True and body.get("duplicate") is True

        status, body = post(
            "/v1/poll",
            {"room": room, "peer": join_peer},
            content_type="text/plain",
        )
        assert status == 415 and body["ok"] is False

        status, body = post("/v1/leave", {"room": room, "peer": host_peer})
        assert status == 200 and body["ok"] is True

        print("QuietLink Pi rendezvous smoke test: PASS")
    finally:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


if __name__ == "__main__":
    main()
