# QuietLink architecture hardening roadmap

Updated: 2026-09-26
Status: **Phase 0 active; v0.3.65 strengthens evidence/export before Phase 1**
Goal: stop new work in one feature from silently breaking mature core behavior.

This is an incremental hardening track. It is **not** permission to rewrite QuietLink all at once. Every phase must leave the app installable over the previous release, preserve QL5 security/signing continuity, and keep LAN/Wi-Fi Direct usable even when online work fails.

## Design rules

1. **Stable behavior is a contract.** LAN, Wi-Fi Direct, QL5, audio, recovery, Baby Monitor, chat and updater behavior do not change merely because another feature is added.
2. **One owner per state.** A subsystem should not reach into another subsystem's booleans/sockets and silently change them.
3. **Competing transports report candidates; one coordinator chooses.** LAN, Wi-Fi Direct and Internet should not own one shared `connecting` flag.
4. **Authenticated winner only.** A transport is not allowed to cancel the others until QL5 has authenticated the peer and the session is actually accepted.
5. **Capability semantics are versioned.** If the meaning of a wire value changes, use a new capability/token rather than reinterpreting an old token.
6. **Observe before replacing.** New state machines/coordinators run in shadow mode first, compare their decision with current production behavior, then become authoritative in a later release.
7. **Core changes require regression evidence.** CI + Quick App Test + the relevant two-phone route/device checkpoint must pass before a core boundary is considered migrated.
8. **No privacy regression.** New diagnostics may report generic state/path/counters only; never addresses, names, codes, identifiers, keys, chat text or media.

---

## Phase 0 — Regression shield and behavioral baseline

**Started: v0.3.64**

Purpose: make regressions obvious before moving code.

### 0A. In-call Quick App Test
- Developer tools -> **QUICK APP TEST • 6-second scan**.
- Passive only: does not mute, switch camera, disconnect, send chat or alter call state.
- Samples live state twice and checks:
  - active/authenticated session
  - heartbeat + RTT
  - network availability and recovery stability
  - microphone permission
  - audio TX/RX flow, queues and loss
  - video codec/TX/RX/FPS/surfaces/rotation metadata when applicable
  - normalized H.264 orientation state
  - Baby role/settings synchronization when applicable
  - diagnostic file-transfer state
- Produces one privacy-safe copyable report plus a short manual spot-check list.
- Pure-Java evaluator is exercised by CI.

### 0B. Core behavior contract inventory
Protected behaviors to document and continuously test:
- QL5 authentication/encryption/replay/key rotation
- same-router CODE/LAN
- Nearby/Known
- Wi-Fi Direct fallback
- intentional disconnect vs accidental recovery
- Voice audio both directions
- Video H.264/JPEG fallback + camera on/off/switch
- Baby/Sleeping Baby roles + PTT + remote controls
- chat/file transfer
- updater signing/in-place continuity

### 0C. Observability before refactor
- Add generic connection-route/state events where needed.
- Never include IP/MAC/room/peer identifiers.
- Quick App Test and logs become the baseline evidence used by later phases.

**Exit criteria:** CI passes, Quick App Test is usable during real calls, and the current same-WiFi/Wi-Fi Direct/orientation checkpoints remain reproducible.

---

## Phase 1 — Explicit session state machine, initially shadow-only

Create a single model for:
`IDLE -> DISCOVERING -> CONNECTING -> AUTHENTICATING -> ACTIVE -> RECOVERING -> CLOSED`.

### 1A
- Introduce `SessionState` + immutable `SessionSnapshot`.
- Existing booleans remain authoritative.
- Shadow state is derived from them and logs only a generic mismatch event.

### 1B
- Run the normal LAN/Wi-Fi Direct/recovery/Baby tests.
- Fix mismatches in the model, not by changing production behavior.

### 1C
- Only after clean device evidence, make the state machine authoritative.
- Remove duplicated lifecycle decisions gradually.

**Exit criteria:** impossible combinations such as active+stopped or two simultaneous owners are structurally rejected.

---

## Phase 2 — Isolate connection transports

Target shape:

```
SessionService
    |
ConnectionRouter
    |-- LanConnector
    |-- WifiDirectConnector
    |-- InternetConnector
    |-- NearbyConnector
```

Each connector may:
- start
- stop
- report CANDIDATE / CONNECTING / FAILED
- hand back an unauthenticated socket/endpoint candidate

It may **not**:
- stop another connector
- decide global session state
- change media state
- mark the session authenticated

`ConnectionRouter` owns candidate arbitration. QL5 authentication remains the final gate.

**Critical regression rule:** a failed Internet attempt cannot consume/block LAN or Wi-Fi Direct, and a late Wi-Fi Direct candidate cannot destroy a healthy authenticated LAN session.

---

## Phase 3 — Transport-independent recovery

Split "who is the authenticated peer?" from "which route currently carries it?"

- RecoveryController owns recovery policy and expected peer identity.
- LAN/Wi-Fi Direct/Internet are interchangeable recovery candidates.
- Manual disconnect creates an explicit terminal guard that recovery cannot override.
- Network transitions (Wi-Fi <-> cellular) can recover without mode/media reset.

---

## Phase 4 — Separate media/control controllers from session transport

Move stable feature logic behind narrow interfaces:
- `AudioController`
- `VideoController`
- `ChatController`
- `BabyMonitorController`

The connection layer only provides an authenticated control channel + encrypted media transport. It does not know camera orientation, Baby brightness, chat UI, etc.

Start with wrappers around existing classes; do not rewrite codecs/audio first.

---

## Phase 5 — Freeze mature core interfaces

Create explicit compatibility boundaries for:
- CryptoChannel / QL5
- MediaTransport framing
- AudioEngine
- video capability negotiation
- updater signer/version contract
- discovery connector contract

Changes inside a protected core module require:
1. a stated contract change,
2. new/updated regression test,
3. device evidence when Android/OEM behavior is involved.

Feature work outside that module should not edit it.

---

## Phase 6 — Release gates and test matrix

Every release touching core behavior must satisfy the relevant matrix:

| Layer | Gate |
|---|---|
| Pure Java | crypto + state/router + Quick App Test self-tests |
| Android compile | signed release build |
| Live call | Quick App Test report |
| Same Wi-Fi | LAN wins before Wi-Fi Direct fallback |
| No router | Wi-Fi Direct fallback succeeds |
| Recovery | accidental loss restores; manual disconnect never restores |
| Video | front/back/fullscreen orientation + camera state |
| Baby | role/mic/camera/Sleeping Baby control sync |
| Long run | heartbeat/rekey/background stability |
| Update | signer/checksum/in-place install continuity |

A feature that cannot exercise a device-only gate must leave that gate explicitly pending rather than assuming success.

---

## Phase 7 — Remove legacy coupling

Only after earlier phases are proven:
- remove obsolete shared flags
- remove duplicate connection/recovery branches
- delete compatibility shims whose supported-version window has ended
- shrink `SessionService` into lifecycle/orchestration rather than feature implementation

Final target: adding an Internet feature should normally touch InternetConnector/ConnectionRouter and tests—not Wi-Fi Direct, LAN discovery, audio, video or Baby Monitor internals.

---

## Current next work

1. Update phones 1, 2 and 4 to v0.3.65 and export one Quick App Test from each so version + live-session evidence are unambiguous.
2. Retry no-router Wi-Fi Direct with the Wi-Fi radio ON on both phones; the v0.3.64 failure evidence showed the host radio OFF, while the joiner was already searching.
3. Re-run the fourth-phone front/rear/fullscreen orientation checkpoint with all participating phones on the same release.
4. Keep the already-proven same-WiFi LAN room-probe path as a regression gate.
5. Only after those are clean, begin **Phase 1A shadow session state**.
6. Do not begin authoritative connection-router replacement until the shadow state and Quick App Test baseline are clean.
