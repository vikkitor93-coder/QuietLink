const http = require("http");

const PORT = Number(process.env.PORT || 8787);
const TTL_MS = 45_000;
const MAX_BODY = 8 * 1024;
const MAX_RELAY_BODY = 32 * 1024;
const MAX_RELAY_DATA_CHARS = 28 * 1024;
const MAX_RELAY_QUEUE_MESSAGES = 64;
const MAX_RELAY_QUEUE_CHARS = 256 * 1024;
const RELAY_WAIT_MS = 4_000;
const MAX_ROOMS = 5000;
const BUILD = process.env.QUIETLINK_RENDEZVOUS_BUILD || "0.2.0";

const rooms = new Map();
const rate = new Map();
const logs = [];
const counters = {
  requests: 0,
  register: 0,
  poll: 0,
  leave: 0,
  relaySend: 0,
  relayPoll: 0,
  relayAck: 0,
  rejected: 0,
  expired: 0
};

function log(event) {
  const safe = String(event || "").replace(/[^a-zA-Z0-9_.:-]/g, "").slice(0, 80);
  logs.push({ time: new Date().toISOString(), event: safe || "event" });
  while (logs.length > 40) logs.shift();
}

function json(res, status, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    "permissions-policy": "camera=(), microphone=(), geolocation=()",
    "content-length": data.length
  });
  res.end(data);
}

function html(res, status, body) {
  const data = Buffer.from(body);
  res.writeHead(status, {
    "content-type": "text/html; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    "permissions-policy": "camera=(), microphone=(), geolocation=()",
    "content-length": data.length
  });
  res.end(data);
}

function validToken(value, max = 128) {
  return typeof value === "string"
    && value.length >= 16
    && value.length <= max
    && /^[A-Za-z0-9_=-]+$/.test(value);
}

function validRelayData(value) {
  return typeof value === "string"
    && value.length >= 1
    && value.length <= MAX_RELAY_DATA_CHARS
    && /^[A-Za-z0-9_-]+$/.test(value);
}

function cleanCandidates(value) {
  if (!Array.isArray(value) || value.length > 8) return null;
  const out = [];
  for (const item of value) {
    if (!item || typeof item !== "object") return null;
    const kind = item.kind;
    const host = item.host;
    const tcpPort = Number(item.tcpPort || 0);
    const udpPort = Number(item.udpPort || 0);
    if (!["srflx", "host"].includes(kind)) return null;
    if (typeof host !== "string" || host.length < 3 || host.length > 128) return null;
    if (!Number.isInteger(tcpPort) || tcpPort < 0 || tcpPort > 65535) return null;
    if (!Number.isInteger(udpPort) || udpPort < 0 || udpPort > 65535) return null;
    out.push({ kind, host, tcpPort, udpPort });
  }
  return out;
}

function allow(peer, limit = 30, bucket = "base") {
  const now = Date.now();
  const key = bucket + ":" + peer;
  const current = rate.get(key) || { window: now, count: 0 };
  if (now - current.window > 10_000) {
    current.window = now;
    current.count = 0;
  }
  current.count++;
  rate.set(key, current);
  return current.count <= limit;
}

function cleanup() {
  const now = Date.now();
  for (const [room, peers] of rooms) {
    for (const [peer, entry] of peers) {
      if (entry.expiresAt <= now) {
        peers.delete(peer);
        counters.expired++;
      }
    }
    if (peers.size === 0) rooms.delete(room);
  }
  if (rate.size > 10000) rate.clear();
}

function activePeerCount() {
  let n = 0;
  for (const peers of rooms.values()) n += peers.size;
  return n;
}

function relayQueue(entry) {
  if (!Array.isArray(entry.relayQueue)) entry.relayQueue = [];
  if (!Number.isInteger(entry.relayChars)) entry.relayChars = 0;
  if (!(entry.relayAck instanceof Map)) entry.relayAck = new Map();
  return entry.relayQueue;
}

function firstRelayMessage(entry, sender) {
  return relayQueue(entry).find(item => item.from === sender) || null;
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function readBody(req, maximum = MAX_BODY) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", chunk => {
      size += chunk.length;
      if (size > maximum) {
        reject(new Error("too_large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch {
        reject(new Error("bad_json"));
      }
    });
    req.on("error", reject);
  });
}

async function handleApi(req, res) {
  counters.requests++;
  let body;
  try {
    body = await readBody(req, req.url === "/v1/relay-send" ? MAX_RELAY_BODY : MAX_BODY);
  } catch {
    counters.rejected++;
    return json(res, 400, { ok: false, error: "invalid_request" });
  }

  const room = body.room;
  const peer = body.peer;
  const relayRequest = req.url.startsWith("/v1/relay-");
  if (!validToken(room, 256) || !validToken(peer, 128)
      || !allow(peer, relayRequest ? 100 : 30, relayRequest ? "relay" : "base")) {
    counters.rejected++;
    return json(res, 400, { ok: false, error: "invalid_request" });
  }

  cleanup();

  if (req.url === "/v1/register") {
    const role = body.role;
    const protocol = Number(body.protocol);
    const candidates = cleanCandidates(body.candidates);
    if (!["host", "join"].includes(role)
        || !Number.isInteger(protocol) || protocol < 1 || protocol > 32
        || candidates === null) {
      counters.rejected++;
      return json(res, 400, { ok: false, error: "invalid_registration" });
    }

    let peers = rooms.get(room);
    if (!peers) {
      if (rooms.size >= MAX_ROOMS) {
        counters.rejected++;
        return json(res, 503, { ok: false, error: "capacity" });
      }
      peers = new Map();
      rooms.set(room, peers);
    }

    const existing = peers.get(peer) || {};
    peers.set(peer, {
      role,
      protocol,
      candidates,
      expiresAt: Date.now() + TTL_MS,
      relayQueue: existing.relayQueue || [],
      relayChars: existing.relayChars || 0,
      relayAck: existing.relayAck instanceof Map ? existing.relayAck : new Map()
    });
    counters.register++;
    return json(res, 200, { ok: true, expiresInSeconds: 45 });
  }

  if (req.url === "/v1/poll") {
    counters.poll++;
    const peers = rooms.get(room);
    if (!peers || !peers.has(peer)) return json(res, 200, { ok: true, matched: false });

    const me = peers.get(peer);
    let match = null;
    for (const [otherPeer, entry] of peers) {
      if (otherPeer === peer) continue;
      if (entry.role === me.role) continue;
      match = { otherPeer, entry };
      break;
    }

    if (!match) return json(res, 200, { ok: true, matched: false });
    return json(res, 200, {
      ok: true,
      matched: true,
      peer: match.otherPeer,
      protocol: match.entry.protocol,
      candidates: match.entry.candidates
    });
  }

  if (req.url === "/v1/relay-send") {
    const target = body.to;
    const seq = Number(body.seq);
    const data = body.data;
    if (!validToken(target, 128) || !Number.isSafeInteger(seq) || seq <= 0 || !validRelayData(data)) {
      counters.rejected++;
      return json(res, 400, { ok: false, error: "invalid_relay" });
    }
    const peers = rooms.get(room);
    const sender = peers && peers.get(peer);
    const recipient = peers && peers.get(target);
    if (!sender || !recipient || sender.role === recipient.role) {
      counters.rejected++;
      return json(res, 409, { ok: false, error: "relay_unavailable" });
    }

    const queue = relayQueue(recipient);
    const acked = Number(recipient.relayAck.get(peer) || 0);
    if (seq <= acked) {
      counters.relaySend++;
      return json(res, 200, { ok: true, duplicate: true });
    }
    const existing = queue.find(item => item.from === peer && item.seq === seq);
    if (existing) {
      if (existing.data !== data) {
        counters.rejected++;
        return json(res, 409, { ok: false, error: "relay_sequence_conflict" });
      }
      counters.relaySend++;
      return json(res, 200, { ok: true, duplicate: true });
    }
    let highest = acked;
    for (const item of queue) {
      if (item.from === peer && item.seq > highest) highest = item.seq;
    }
    if (seq !== highest + 1) {
      counters.rejected++;
      return json(res, 409, { ok: false, error: "relay_sequence_gap" });
    }
    if (queue.length >= MAX_RELAY_QUEUE_MESSAGES || recipient.relayChars + data.length > MAX_RELAY_QUEUE_CHARS) {
      counters.rejected++;
      return json(res, 429, { ok: false, error: "relay_backlog" });
    }
    queue.push({ from: peer, seq, data });
    recipient.relayChars += data.length;
    counters.relaySend++;
    return json(res, 200, { ok: true });
  }

  if (req.url === "/v1/relay-poll") {
    const sender = body.from;
    if (!validToken(sender, 128)) {
      counters.rejected++;
      return json(res, 400, { ok: false, error: "invalid_relay" });
    }
    counters.relayPoll++;
    const deadline = Date.now() + RELAY_WAIT_MS;
    while (true) {
      cleanup();
      const peers = rooms.get(room);
      const me = peers && peers.get(peer);
      const remote = peers && peers.get(sender);
      if (!me) return json(res, 409, { ok: false, error: "relay_unavailable" });
      if (!remote || me.role === remote.role) {
        return json(res, 200, { ok: true, peerPresent: false });
      }
      const message = firstRelayMessage(me, sender);
      if (message) {
        return json(res, 200, {
          ok: true,
          peerPresent: true,
          seq: message.seq,
          data: message.data
        });
      }
      if (Date.now() >= deadline) {
        return json(res, 200, { ok: true, peerPresent: true, data: "" });
      }
      await delay(80);
    }
  }

  if (req.url === "/v1/relay-ack") {
    const sender = body.from;
    const seq = Number(body.seq);
    if (!validToken(sender, 128) || !Number.isSafeInteger(seq) || seq <= 0) {
      counters.rejected++;
      return json(res, 400, { ok: false, error: "invalid_relay" });
    }
    const peers = rooms.get(room);
    const me = peers && peers.get(peer);
    const remote = peers && peers.get(sender);
    if (!me || !remote || me.role === remote.role) {
      counters.rejected++;
      return json(res, 409, { ok: false, error: "relay_unavailable" });
    }
    const queue = relayQueue(me);
    const acked = Number(me.relayAck.get(sender) || 0);
    if (seq <= acked) {
      counters.relayAck++;
      return json(res, 200, { ok: true, duplicate: true });
    }
    if (seq !== acked + 1) {
      counters.rejected++;
      return json(res, 409, { ok: false, error: "relay_ack_gap" });
    }
    const index = queue.findIndex(item => item.from === sender && item.seq === seq);
    if (index < 0) {
      counters.rejected++;
      return json(res, 409, { ok: false, error: "relay_message_missing" });
    }
    const [item] = queue.splice(index, 1);
    me.relayChars = Math.max(0, me.relayChars - item.data.length);
    me.relayAck.set(sender, seq);
    counters.relayAck++;
    return json(res, 200, { ok: true });
  }

  if (req.url === "/v1/leave") {
    counters.leave++;
    const peers = rooms.get(room);
    if (peers) {
      peers.delete(peer);
      if (peers.size === 0) rooms.delete(room);
    }
    return json(res, 200, { ok: true });
  }

  counters.rejected++;
  return json(res, 404, { ok: false, error: "not_found" });
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url === "/health") {
      cleanup();
      return json(res, 200, {
        service: "quietlink-online",
        status: "ok",
        onlineCallsAvailable: false,
        phase: "control-relay-test",
        build: BUILD,
        activeRooms: rooms.size,
        activePeers: activePeerCount()
      });
    }

    if (req.method === "GET" && req.url === "/") {
      return html(res, 200,
        "<!doctype html><meta charset=utf-8><title>QuietLink rendezvous</title>"
        + "<h1>QuietLink rendezvous service</h1>"
        + "<p>This service helps peers find each other and can forward opaque QuietLink control bytes. "
        + "Media and session keys are not terminated here. Local QuietLink calling does not depend on it.</p>");
    }

    if (req.method === "GET" && req.url === "/dev") {
      cleanup();
      return json(res, 200, {
        service: "quietlink-online",
        phase: "control-relay-test",
        build: BUILD,
        activeRooms: rooms.size,
        activePeers: activePeerCount(),
        counters,
        latestLogs: logs.slice(-20)
      });
    }

    if (req.method === "POST"
        && ["/v1/register", "/v1/poll", "/v1/leave",
            "/v1/relay-send", "/v1/relay-poll", "/v1/relay-ack"].includes(req.url)) {
      return handleApi(req, res);
    }

    return json(res, 404, { ok: false, error: "not_found" });
  } catch {
    counters.rejected++;
    log("server_error");
    return json(res, 500, { ok: false, error: "server_error" });
  }
});

setInterval(cleanup, 10_000).unref();
server.listen(PORT, "0.0.0.0", () => log("server_started"));
