# QuietLink Rendezvous

Small signaling-only service for QuietLink's online P2P milestone.

## Current phase
- Registration and short-lived matching API is implemented.
- Registrations expire after 45 seconds unless refreshed.
- The raw six-digit QuietLink pairing code is never sent to the service.
- The service does not relay audio, video, chat, or encrypted call traffic.
- Direct internet media is not enabled yet because STUN/NAT candidates still need to be added to the Android client.

## API
- `GET /health`
- `POST /v1/register`
- `POST /v1/poll`
- `POST /v1/leave`
- `GET /dev` — aggregate anonymous service state only.

The same API is intended to be used later by the Raspberry Pi rendezvous deployment.
