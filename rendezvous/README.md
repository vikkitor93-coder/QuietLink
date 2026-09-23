# QuietLink Rendezvous

Small short-lived rendezvous and opaque control-relay service for QuietLink's online P2P milestone.

## Current phase
- Registrations expire after 45 seconds unless refreshed.
- The raw six-digit QuietLink pairing code is never sent; Android derives an opaque room token from the pairing secret.
- STUN/NAT UDP candidates are exchanged through rendezvous.
- v0.3.49 adds a bounded reliable relay for the **QL5 handshake and encrypted control/chat byte stream** so arbitrary inbound internet TCP is not required.
- The service does **not** terminate QuietLink encryption, receive session keys, decrypt chat/control, or relay audio/video in this checkpoint.
- Audio/video remain end-to-end encrypted over direct UDP using exchanged STUN candidates.
- Local LAN/Hotspot/Wi-Fi Direct calling does not depend on this service.

## API
- `GET /health`
- `POST /v1/register`
- `POST /v1/poll`
- `POST /v1/leave`
- `POST /v1/relay-send` — bounded opaque stream chunk with per-direction sequence
- `POST /v1/relay-poll` — short long-poll; message remains until ACK
- `POST /v1/relay-ack` — idempotent receiver acknowledgement
- `GET /dev` — aggregate anonymous service state only; disabled by default on the Pi production deployment

Relay queues are bounded by message count and aggregate bytes. Duplicate sends/ACKs are idempotent, sequence gaps are rejected, and registration expiry removes in-memory relay state.

The portable Node server and Raspberry Pi Python server implement the same contract.

## Privacy
A real HTTPS/STUN service necessarily sees source network addresses transiently at the network layer. QuietLink does not claim otherwise.

The production Pi intentionally suppresses IP-containing access logs, does not persist client IPs, does not expose endpoint values through `/dev`, does not log room/peer tokens or relay payloads, keeps candidate/relay state only in memory for live setup, and has no analytics.

The relay is not a trusted peer. QL5 authentication and end-to-end encryption remain between the two QuietLink devices.
