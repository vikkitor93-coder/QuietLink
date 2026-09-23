# QuietLink AI handoff / continuity guide

Updated: **2026-09-23**  
Repository: **vikkitor93-coder/QuietLink**  
Source of truth: **main**  
Android package: **is.quietlink.app**  
Current release: **v0.3.57 / versionCode 69**  
Latest release CI: **passed**

---

## START HERE — resume command

If an AI is taking over this project, give it this instruction:

> **Resume QuietLink from the GitHub `main` branch. Read `AI_HANDOFF.md` first, then `MILESTONES.md`, `README.md`, and `TESTING.md`. Treat the current source and those files as the source of truth. Continue from the NEXT ACTION section in `AI_HANDOFF.md` without redesigning completed systems. Preserve local-first behavior, package/signing continuity, privacy-safe logs, and the current secure protocol.**

The AI should inspect the current source before editing and should not ask the user to reconstruct project history that is already documented here.

---

# 1. Product summary

QuietLink is a native Android peer-to-peer communications app supporting:

- encrypted voice calls
- encrypted video calls
- Baby Monitor mode
- Sleeping Baby mode
- local LAN / hotspot discovery
- Wi-Fi Direct fallback
- known devices
- trusted/self-healing reconnect
- chat
- live diagnostics
- adaptive H.264 video
- privacy-safe diagnostic export
- in-app update checking/install handoff

The project is currently extending the now-permanent production rendezvous into **actual internet P2P session establishment and recovery**.

The user wants online connectivity added incrementally without destabilizing local operation. The Raspberry Pi may now be used **during milestone 7 as test rendezvous infrastructure**. Milestone 8 still means promoting/hardening that Pi deployment for permanent use rather than merely running the test server.

---

# 2. Non-negotiable behavior

## Local-first must always work

The existing local path is authoritative:

1. LAN / same router / hotspot
2. Wi-Fi Direct fallback
3. online reachability only as an additional path

If the online service is down, misconfigured, unreachable, or disabled, QuietLink must continue to work locally.

Do not make app startup, nearby discovery, LAN calls, Wi-Fi Direct calls, Baby Monitor, or Sleeping Baby depend on an internet service.

## Never show a false green online status

The lobby has a small Online status dot.

- Green means the published QuietLink online status explicitly says internet peer calling is usable.
- Red means local-only / online unavailable.
- Tapping the dot gives a useful explanation.
- The status document currently keeps `onlineCallsAvailable=false`, so the Online dot intentionally remains red even though production rendezvous signaling is now enabled.

The dot should only become green when the actual internet P2P path is ready enough to connect peers.

---

# 3. Security model that must be preserved

Current secure protocol is **QL5**.

Important existing properties:

- ephemeral P-256 ECDH per session
- pairing-code hardening
- explicit key confirmation
- directional AES-256-GCM control/media keys
- replay protection
- verification phrase
- P-256 identity validation
- strict frame size bounds
- strict UTF-8 handling
- traffic key / IV epoch rotation every 8,192 packets
- sensitive key array wiping
- bounded unauthenticated incoming handshakes

Do not weaken or bypass the existing `CryptoChannel` authentication merely to make internet connectivity easier.

The rendezvous service is not a trusted peer and must never receive plaintext media/chat or session keys.

---

# 4. Privacy rules

QuietLink intentionally avoids storing or exposing identifying/network information in diagnostic logs.

Existing privacy-safe logging excludes things such as:

- peer/device names
- device IDs
- IP addresses
- room codes
- fingerprints / identity keys
- chat text
- media contents

New online code must follow the same rule.

Important nuance: a real rendezvous/STUN server necessarily sees network source addresses transiently at the network layer. Do not claim the server cannot see an IP address. Instead:

- do not persist client IPs
- do not include IPs in application logs
- do not expose them through `/dev`
- do not add analytics
- retain endpoint data only as long as technically necessary for live connection setup

The standalone rendezvous service currently exposes only aggregate counters/generic log events in its developer status.

---

# 5. Current connection architecture

## Control transport

`CryptoChannel` currently operates over a Java TCP `Socket`.

Encrypted control and chat framing run over this socket.

## Media transport

`MediaTransport` uses UDP via `DatagramSocket`.

It sends encrypted:

- audio
- video

Audio is prioritized over video. Video may be dropped under backlog rather than delaying speech.

## Local discovery

The current local connection system uses:

- Android NSD/LAN discovery
- hotspot/LAN support
- Wi-Fi Direct fallback
- `PeerDiscovery` for remembered-device/recovery paths

## Recovery

Existing self-healing recovery is currently oriented around local discovery.

It authenticates the expected remembered peer before restoring a session.

Internet-path recovery still needs to become transport-independent.

---

# 6. Online P2P work completed


## v0.3.57 — Numbered per-window video tuning + portrait aspect ratios

User feedback after v0.3.56:
- The tuning controls were still conceptually mixed.
- User wants every visible video window to have its own tuning context.
- Requested a 1/2 dropdown with matching visible numbers on the corresponding video windows.
- Mini window needs more aspect options, specifically real portrait ratios such as 9:16 rather than only landscape ratios.

Implemented:
- Developer badges identify **Window 1 = main/incoming** and **Window 2 = mini/my camera**.
- Compact panel now uses a Window 1/2 Spinner and renders only that window's controls.
- Window 1: remote direction, remote quarter-turn offset, one aspect control applied consistently inline+fullscreen, frame RX.
- Window 2: preview base, new local-preview quarter-turn offset, aspect, mirror; outbound sender rotation/formula/source/frame-TX are grouped separately below "SEND THIS CAMERA TO THE OTHER PHONE".
- New aspect ratios preserve old stored numeric values and append 9:16, 3:4, 2:3, 5:4, 4:5.
- Quick/full aspect lists now include Auto, 16:9, 9:16, 4:3, 3:4, 3:2, 2:3, 5:4, 4:5, 1:1, Stretch.
- Explicit aspect modes are literal final displayed WIDTH:HEIGHT, independent of quarter-turn direction.
- AUTO remains rotation-aware and derives native 16:9/9:16 from H.264 orientation.
- No network/protocol/signing changes.

Still pending from v0.3.56:
- Human validation that mobile-data-only CODE is restored.
- Final rotation/aspect calibration on both phones.



## v0.3.56 — Internet CODE regression fix + fullscreen/aspect test expansion

User report after v0.3.55:
- Internet connection could no longer be started with Wi-Fi off; UI redirected to Wi-Fi settings / hotspot.
- Starting hotspot still did not satisfy the actual intended separate-network internet use case.
- Compact ROTATE panel itself was useful.
- Panel was not accessible while in fullscreen video.
- Local mini-preview box became 16:9-shaped but the camera contents still appeared stretched.
- User requested aspect-ratio choices for all camera presentations and fullscreen orientation testing.

Root cause of connection regression:
- `MainActivity.startRequested(...)` hard-returned when `hasUsableLocalNetwork()` was false.
- `maybeShowWifiWarning(...)` could also show Wi-Fi/hotspot guidance while CODE was selected.
- This contradicted the v0.3.49+ transport architecture where CODE may use mobile-data rendezvous/relay without any local Wi-Fi interface.

Fix:
- CODE HOST/JOIN no longer requires `hasUsableLocalNetwork()`.
- Wi-Fi warning is now scoped to Nearby/Known only.
- CODE's required permission gate no longer requires nearby-Wi-Fi/location permission; those remain optional for local discovery/fallback.
- No SessionService transport ordering or QL5 cryptography changed.

Fullscreen/video lab:
- Fullscreen normal Video and Parent/Baby fullscreen controls get a developer ROTATE button.
- Dedicated fullscreen rendering also exposes the ROTATE button.
- Fullscreen entry/exit/configuration change explicitly reapplies local+remote transforms.
- Compact panel X close is explicit.
- Aspect modes added independently for local preview, remote inline, fullscreen remote:
  Auto / 16:9 / 4:3 / 3:2 / 1:1 / Stretch.
- Aspect modes alter TextureView presentation matrices; non-Stretch modes preserve the selected target ratio, Stretch intentionally fills/distorts for diagnosis.
- Local mini-preview container also changes shape to the selected local aspect and effective local rotation.

Human test order:
1. Update both phones to v0.3.56.
2. With one phone Wi-Fi OFF + mobile data ON and the other on a separate network, CODE must start without any Wi-Fi/hotspot prompt.
3. Verify the phones reach the production online peer/session path again.
4. Enter fullscreen and use the new ROTATE button; confirm panel remains functional.
5. Use Local Aspect to find which ratio removes the mini-preview stretch.
6. Test Remote Aspect inline and Fullscreen Aspect fullscreen independently.
7. Finish portrait / landscape-left / landscape-right matrix before promoting any orientation/aspect experiment to production defaults.



## v0.3.55 — Compact persistent rotation panel + local preview aspect

Human findings from v0.3.54:
- Full rotation list now renders on both phones.
- User found rotation settings that make **portrait orientation upright on both phones**.
- Iteration was too slow because every lab choice obscured the video and nested choices closed/reopened at the top.
- Small local self-preview still visibly stretched/cropped the camera image.

Implemented:
- Active developer tools adds **ROTATE ONLY • compact live panel**.
- Custom bottom Dialog keeps the live video visible above it and clears background dimming.
- Quick controls: forced sender rotation, remote offset, remote direction, TX formula, local preview transform, rotation source, per-frame TX/RX, local mirror.
- Every quick choice applies immediately, rebuilds within the same open dialog, and restores ScrollView Y position.
- **FULL LAB** remains available for lower-frequency options like Force JPEG.
- Local self-preview TextureView changed from crop to fit behavior.
- Local preview frame aspect now follows effective local preview rotation rather than device Configuration orientation.
- In-place fullscreen exit restores inline preview layout explicitly.
- Fullscreen bitmap fallback local preview changed from CENTER_CROP to FIT_CENTER.

Still pending:
- Confirm v0.3.55 quick panel works as intended on both phones.
- Confirm small self-preview no longer stretches.
- Complete landscape-left and landscape-right rotation matrix, front and back camera.
- Only after those results should a proven subset replace production defaults.



## v0.3.54 — Older-Android rotation-lab dialog compatibility

New human validation:
- Older phone successfully installed v0.3.53 using **CHECK UPDATE** from v0.3.52. The signer-compatibility updater fix is therefore validated.
- Sleeping Baby audio from the older phone as Baby Station works again after v0.3.53. The role-swap regression checkpoint is validated.

New UI regression:
- On the older Android phone, opening **Video rotation lab** showed only:
  - title,
  - "Production defaults",
  - explanatory text,
  - Close.
- None of the selectable list items appeared.
- MainActivity used AlertDialog.Builder with both `setMessage(...)` and `setItems(...)`.
- On this older platform implementation, the message/list combination does not render the list reliably.

Fix:
- Rotation lab is now list-first: remove `setMessage` from the main lab menu.
- Title shows **Video rotation lab • PRODUCTION** or **• TEST**.
- All existing rotation controls/presets are unchanged.
- No video pipeline, audio, updater, protocol, security, or local-first behavior changed.

Human test:
1. Update older phone from v0.3.53 to v0.3.54 using CHECK UPDATE.
2. Open 🛠 -> Video rotation lab.
3. Confirm the full control list is visible.
4. Continue the original rotation preset matrix.



## v0.3.53 — Sleeping Baby apparent audio regression traced to unintended role transition

Evidence from the user's privacy-safe log:
- Older phone as Baby Station: mic unmuted, capture gate opened, recorder started, and `tx_flow active=1`.
- After Sleeping Baby was enabled and Parent Station re-enabled the baby mic, recorder rebuilt/resumed and `tx_flow active=1` continued.
- Later the same phone showed `playback_gate open=1` immediately followed by `capture_gate open=0`, then emitted `remote_baby_mic_request` and PTT events. Those actions are Parent Station-only.
- Therefore the microphone/audio transport was healthy; the phone had transitioned from Baby Station to Parent Station, where continuous capture is intentionally disabled.

Fix:
- SWAP is no longer a one-tap role transition. It opens a confirmation describing exactly what will happen to microphone/camera behavior.
- Only confirmed SWAP invokes `ACTION_SWAP_BABY_ROLE`.
- Service records privacy-safe `baby_role_swap_request source=local|peer from=... to=...` and `baby_role_changed` events so any future unexpected role transition has an attributable source.
- No change was made to the Baby Station continuous microphone transport because the trace proved capture and TX were operating.

Critical next human test:
- v0.3.52 is currently installed manually on the older phone. v0.3.53 should be installed via **CHECK UPDATE** on that phone, specifically to validate the v0.3.52 legacy archive signer compatibility fix.
- Then run Sleeping Baby with the older phone as Baby Station for at least 10 minutes with baby mic enabled, without confirming SWAP. Audio must continue.
- Cancel a SWAP confirmation once and verify the role/audio do not change.
- Optionally confirm one intentional role swap and verify the log attributes it to `source=local`.



## v0.3.52 — Older-Android archive signer compatibility

Observed from the user's privacy-safe log:
- Installed package used modern signing info: `installed_legacy=0`.
- Downloaded APK archive fell back to legacy parsing: `archive_legacy=1`.
- Installed current v2 signer was recognized and installed history count was 2, but archive current v2 could not be observed because the legacy API exposed one certificate.
- Android's official PackageInfo documentation states deprecated `signatures` returns the **oldest reported signing certificate** after certificate rotation for backwards compatibility.

Fix:
- Add one narrow post-rotation compatibility state:
  - installed package must expose modern SigningInfo,
  - installed current signer must be exactly pinned QuietLink v2,
  - installed history must contain both pinned old and v2 certs,
  - archive must be legacy-parsed and expose exactly the pinned original signer.
- Package name, newer version and manifest SHA-256 are verified before this policy.
- Android installer remains final signer-lineage enforcement; an APK lacking the valid old->v2 proof cannot update the already-v2 installed package.
- No arbitrary legacy signer is accepted.
- v0.3.51 rotation-lab behavior is unchanged.

Deployment note:
- The older phone's currently-installed updater still contains the old rejection logic, so v0.3.52 requires one more manual in-place install on that phone. Do not uninstall.
- Once v0.3.52 is installed, the next release should be testable through CHECK UPDATE.



## v0.3.51 — Developer video rotation lab

Reason:
- After the older phone was successfully moved to v0.3.50, the remaining visible issue is camera/video orientation.
- Research against Android Camera2/TextureView guidance and WebRTC showed multiple plausible causes: sender formula convention, TextureView double-rotation, asynchronous control-vs-media rotation timing, front mirroring, and device/HAL quirks.
- Do not guess a production fix yet; v0.3.51 makes those variables directly testable.

Implemented:
- Hidden 🛠 Developer tools now contains **Video rotation lab**, including during an active real Video/Baby session.
- Production defaults remain identical to v0.3.50 until the lab is changed; Reset production defaults clears the local experiment state.
- H.264 TX formula choices: current QuietLink, Android documented relative rotation, WebRTC/JPEG-style, sensor-only.
- Orientation source: display rotation or physical OrientationEventListener sensor. The physical mode deliberately ignores Android rotation lock for diagnosis.
- Local TextureView modes: current stream rotation, Android display-only compensation, no extra rotation, inverse stream rotation.
- Local front-preview mirroring is independently toggleable and local camera facing is surfaced internally so back-camera previews are not accidentally treated as front in the experiment.
- QLH2 H.264 v1 keeps the same header/version but can use unused flag bits to carry optional per-access-unit 0/90/180/270 rotation metadata. Old peers ignore the bits. v0.3.51 receivers can choose whether to use them.
- Manual force-TX rotation, receiver direct/inverse, receiver +0/+90/+180/+270 offset, and Force JPEG are available.
- Force JPEG is a negotiated experiment: it sends fallback/capability controls so both sides can move to JPEG and later restore H.264 without reconnecting.
- Logs remain privacy-safe: only categorical lab settings and degree values are recorded.

Human test needed:
1. Install v0.3.51 on both phones.
2. Start a same-LAN Video call first.
3. Open 🛠 -> Video rotation lab while connected.
4. Test presets in order: Production, Android official + TextureView display-only, WebRTC + per-frame metadata, Android + per-frame metadata.
5. For each promising preset, test front/back camera in portrait, landscape-left, and landscape-right.
6. If self-preview only is wrong, isolate Local preview transform + mirror.
7. If remote only is wrong, isolate sender formula, then remote direct/inverse/offset.
8. Compare per-frame TX+RX ON versus OFF while rotating live.
9. Force JPEG once to determine whether the problem is H.264/TextureView-specific or common to both pipelines.
10. Report the exact settings that are correct on each phone/camera/posture. Then promote only the proven combination to production and reset/remove experimental defaults as appropriate.



## v0.3.50 — Older-phone updater signer compatibility

User-reported regression:
- The older phone again showed **Stage: package / Reason: signature_mismatch** when using CHECK UPDATE after the v2 signer migration.
- This means download/checksum/package/version checks passed and QuietLink's own signer comparison rejected the APK before Android installer handoff.

Cause/fix:
- Some older/OEM PackageManager implementations can expose the already-rotated installed app and a downloaded rotated APK with different certificate-history shapes even when both current signers are the same v2 certificate.
- QuietLink now has a narrow post-rotation compatibility path that accepts only when both sides' **current signer is exactly the pinned QuietLink v2 signer**.
- Unknown signers, multiple signers, package/version/checksum checks, and the old→v2 migration rules remain unchanged.
- Android's installer still enforces signing-lineage compatibility.
- Diagnostics record only categorical current-v2/source/history-count state and never certificate contents/digests.
- v0.3.49 internet transport behavior is unchanged.

Important deployment consequence:
- A phone whose current updater rejects v0.3.50 cannot use that same broken updater to install the fix. Give it the signed v0.3.50 APK once for a normal manual in-place Android update. After that, CHECK UPDATE should use the repaired comparison path.



## v0.3.49 — Internet session checkpoint: opaque QL5 control relay + direct UDP media

User validation before implementation:
- v0.3.48 normal production CODE flow was confirmed working across Wi-Fi ↔ mobile data with no developer override.
- The waiting UI reached the production rendezvous peer-match stage, so candidate exchange is no longer the blocker.

Implemented:
- Production rendezvous now supports a bounded **opaque reliable control relay**. It forwards raw QL5 handshake bytes and encrypted QL5 control/chat frames but never terminates QuietLink encryption and never receives session keys or media.
- Relay chunks use per-direction sequence numbers, ACK, idempotent retry/deduplication, strict body/chunk bounds, queue count/byte caps, and short-lived room registrations.
- Android adds `RendezvousRelaySocket`, a Socket-shaped reliable byte stream so the existing `CryptoChannel.handshake(...)` and QL5 framing remain unchanged.
- After a production peer match, CODE host/join attempts the existing QL5 handshake over that relay instead of exposing the current TCP listener through NAT.
- Audio/video remain on the existing end-to-end encrypted UDP `MediaTransport` and target the peer's exchanged STUN server-reflexive candidate.
- Both peers send three anonymous content-free `QLP1` UDP warm-up datagrams after QL5 authentication and before encrypted media starts. They carry no identity, room token, pairing code, media, or key material.
- LAN/Hotspot remains immediate, production rendezvous still waits ~1.5 s, and Wi-Fi Direct still begins after 8 s.
- Alternate developer rendezvous overrides remain candidate-test behavior and do not automatically start the production relay.
- Failure of the internet relay path is non-fatal; local search continues.
- The Online dot remains red until real-device validation succeeds.

Local validation before publication:
- Node rendezvous syntax passed.
- Pi Python syntax passed.
- Pi relay smoke test passed, including duplicate-send/ACK behavior.
- Core QL5 crypto smoke tests passed unchanged.

Still pending:
- Deploy the updated Python rendezvous server to the permanent Raspberry Pi.
- Install v0.3.49 on both phones and validate a real encrypted Voice call across Wi-Fi ↔ mobile data, including matching verification phrase, bidirectional audio, and chat.
- Internet-path self-healing/network-transition recovery.
- Opaque encrypted media relay fallback for networks where direct UDP fails.


## v0.3.48 — Permanent production rendezvous integration

Permanent infrastructure now confirmed:
- Raspberry Pi rendezvous is running as a systemd service on localhost port 8787.
- Permanent Cloudflare Tunnel is installed as `cloudflared.service` and starts automatically.
- Public hostname is `https://rendezvousquietlinkvikman.dpdns.org`.
- Cloudflare published route forwards that hostname to `http://127.0.0.1:8787`.
- Public `/health` works.
- Two-phone developer-mode candidate exchange across separate networks was user-confirmed.

Android production integration:
- Published `online-status.json` now carries the permanent rendezvous URL while `onlineCallsAvailable` deliberately remains false.
- CODE host/join starts LAN immediately, waits ~1.5 s, then starts production rendezvous signaling in parallel if still unconnected.
- Wi-Fi Direct still begins after 8 s.
- A saved developer override equal to the newly official production URL is automatically cleared/promoted so existing test phones do not stay stuck in TEST mode.
- Alternate developer override URLs still work.
- A production rendezvous match with candidates shows `Internet peer found • checking connection paths…`.
- Rendezvous failure remains non-fatal; local paths continue.

Important truth:
- Rendezvous candidate exchange is productionized.
- Current control transport is still TCP and media is UDP.
- Do not claim direct internet P2P was complete merely from rendezvous matching. The v0.3.48 normal-flow signaling test was later user-confirmed across Wi-Fi ↔ mobile data, unlocking the v0.3.49 internet-session implementation.

## v0.3.47 — Stable v2 signer cutover

- First stable release built by normal CI with only the secret-held v2 signing key and public Android old→v2 signing lineage.
- versionCode 59 / versionName 0.3.47.
- Old keystore/password are absent from every live branch tip and no longer used by normal CI.
- Both existing phones successfully updated in place to v0.3.47.
- All five live branches were force-rewritten to a sanitized root; legacy build/source artifacts were purged.
- The post-purge full reachable-history audit returned `RESULT: no credential-pattern findings`.
- Old orphaned GitHub commit objects may remain directly addressable by exact SHA until GitHub garbage collection/support removal, but no live ref reaches them and the old signer is permanently retired.

## v0.3.46 — Signing-key migration bridge

Security audit result:
- Repository is public.
- Full reachable-history scan found only two secret-related categories: `app/quietlink-debug.jks` and plaintext signing-password assignments in `app/build.gradle.kts`.
- No OpenAI, Cloudflare, GitHub PAT, AWS, Google API, Slack, Stripe, PEM private-key or `.env` secret patterns were found by the redacted audit.
- The signing keystore first entered public history in commit `5b05be360399b48537faef9b61a1d9bcce0ab239` on 2026-09-20.
- Plaintext signing passwords were added in commit `4c8daca1f667ad2879559b4f360810efd129b8c6`.
- All current branch tips contained the compromised signing material at audit time.

Signer migration:
- Old signer SHA-256: `90b075695287a8080bda9fcb4cd4dff188e781a25a1d135ddfeeed85f1a09da1`.
- New v2 signer SHA-256: `da4ff75b61d65a3ff1c335bf87a2173c63bb031711faebfded2b965fb1c851b1`.
- New v2 private key was generated outside GitHub and exported only to the user as a private backup artifact. Never commit it.
- v0.3.46 remains signed with the old signer solely as an in-place bridge.
- UpdateManager now pins the exact old and v2 certificates. Exact same-signer updates still work. The future rotated APK is accepted only for old-current → v2-current and, when SigningInfo is available, only when archive history contains both pinned certs.
- Legacy PackageManager fallback allows only the same pinned old→v2 transition; Android PackageInstaller still enforces the actual platform signing-lineage update.

Next security steps:
1. User installs v0.3.46 on both phones.
2. User stores the private v2 backup safely and adds its four values to GitHub Actions Secrets.
3. Build a rotated signer release using `apksigner` proof-of-rotation with `--rotation-min-sdk-version 28` because QuietLink minSdk is 28.
4. Verify both phones update in place to the v2 signer.
5. Switch all future CI signing to v2 secret material only.
6. Remove old keystore/passwords from current branches and rewrite all reachable Git history.

## v0.3.45 — Parent fullscreen and older-device audio recovery

User verification of v0.3.44:
- The previously failing camera now works on both phones.
- Swapping Parent/Baby roles, switching cameras, and camera ON/OFF all work.

New user-reported issue:
- Newer phone transmitted room audio in Sleeping Baby mode.
- Older phone did not transmit room audio when used as Baby Station.
- The supplied privacy-safe log contained no AudioRecord/microphone lifecycle events, so it could not distinguish a stale mute, recorder resume failure, or transport flow failure.

Two concrete risks were found in source:
1. A fresh Baby Station copied the sticky `SessionBus.localMicMuted` value used by earlier Voice/Video sessions. It could therefore begin Baby mode muted.
2. `AudioEngine` stopped one `AudioRecord` object while transmit permission was closed and later tried to restart that same object. Older Android audio HALs can fail on stop→restart cycles.

Fix:
- Fresh, non-recovery Baby Station sessions explicitly start unmuted.
- Resumed authenticated sessions still preserve their intended prior Baby mic state.
- AudioRecord is rebuilt when the transmit gate re-opens after being closed, so Parent→Baby role swaps / PTT / Baby mic transitions do not depend on an old stopped recorder.
- Start/read failures rebuild the recorder automatically.
- Privacy-safe log events now cover session audio state, capture gate, recorder ready/start/error, TX flow, RX flow, playback gate, PTT, listen state and Baby mic control. No audio content is logged.
- High-frequency per-frame `rotation_calc` logging was removed to stop video events from crowding audio/recovery events out of the bounded trace.

Parent fullscreen change:
- Parent Station Baby fullscreen no longer uses generic Video-mode icon controls.
- It uses Sleeping Baby-style labelled controls for Alert sensitivity, HEAR, Baby mic/camera, switch camera, LIGHT, BRIGHT, Sleeping/Exit Sleeping, Hold to Talk, Chat, Battery, Disconnect and Back.

GitHub diagnostics:
- Developer tools and Live Diagnostics include **Report log to GitHub**.
- QuietLink generates the existing privacy-safe text export into app cache and exposes only that file through the existing non-exported read-only grant-URI provider.
- SHARE LOG launches Android sharing with the .txt attached.
- OPEN ISSUE launches a pre-filled issue at the public QuietLink repository.
- No GitHub credential/token is embedded or stored. GitHub authentication remains with the user's GitHub app/browser.

Verification still needed:
- Install v0.3.45 on both phones.
- Put the older phone in Baby Station + Sleeping Baby and confirm room audio reaches Parent.
- Swap roles and repeat.
- If audio fails, use Report log to GitHub; the new AUDIO events should identify gate vs AudioRecord vs TX/RX flow.

## v0.3.44 — Older-device Baby video recovery

User-confirmed regression:
- On the older phone, Baby mode video could disappear and not return.
- The Parent Station could remain on **VIDEO ON • LOW LIGHT**, which was only the luminance classification of the last received frame and was misleading after the stream stopped.
- The exact newer privacy-safe diagnostic log showed bursts of overlapping `camera_start` / `camera_opened` followed by `camera_disconnected`, consistent with an older Camera2 HAL being reconfigured too aggressively.
- The same log showed Baby aux/state UI updates destroying and recreating remote/local video surfaces.

Fix:
- Camera starts are debounced (~240 ms) and tagged by an incrementing generation.
- Only the newest generation may become the active camera; stale `onOpened`/disconnect/error callbacks cannot replace or disturb it.
- Real Camera2 disconnect/error/open/session failures schedule bounded automatic retries.
- Baby settings/aux state callbacks update existing controls in place and no longer call `showSession()`, preventing unnecessary TextureView destruction.
- JPEG receive health is now tracked. If expected remote JPEG video stalls, the receiver sends the existing key-frame/recovery control; a JPEG sender interprets that as a bounded camera restart.
- Parent UI tracks last actual received/rendered frame and changes a stale low-light frame to **VIDEO STALLED • RECOVERING** after roughly four seconds.
- H.264 watchdog behavior remains intact.
- QL5 crypto, local-first discovery, updater verification and online status gating remain unchanged.

Validation before release packaging:
- rendezvous server smoke test passed
- core crypto smoke test passed
- Android compile/package passed after restoring two unchanged camera-selection helper methods accidentally removed during the refactor

## v0.3.43 — Baby/Sleeping Baby display improvements

- Sleeping Baby **BRIGHT** on Parent Station now drives an opaque full-screen white overlay on Baby Station at window brightness 1.0, hiding system bars for maximum useful light.
- The overlay sits above the existing call hierarchy so Baby camera/video surfaces remain alive underneath.
- Turning BRIGHT off restores prior brightness/system UI/colors; leaving the session also resets it.
- Normal Baby Parent Station controls are compact wrap-content chips to maximize camera area.
- Baby camera feed is tappable for preserved-surface fullscreen, with Back restoration.
- Parent Station fullscreen camera/mic/switch controls target the Baby Station.
- Voice/regular Video layouts intentionally remain unchanged.
- This release is also the first convenient real-world CHECK UPDATE test after the v0.3.42 legacy signer fallback on the affected Android/OEM phone.

Cloudflare test update:
- User reported the temporary Cloudflare Quick Tunnel path worked.
- DigitalPlat nameservers were changed to the Cloudflare-assigned pair; Cloudflare zone activation was still pending at last report.
- Do not confuse successful rendezvous/tunnel reachability with direct encrypted internet calling; direct candidate dialing is still pending.

## v0.3.42 — Updater signer compatibility

- One Android phone updated successfully while another failed with `Stage: package / Reason: signature_unavailable`.
- Root cause: the updater relied only on the newer Android `SigningInfo` fields; some Android/OEM PackageManager implementations can return the package but leave signer data unavailable there.
- Fix: modern signer lookup remains first; when unavailable, QuietLink re-queries using the legacy Android package-signature field and SHA-256 compares signer sets. Mismatches still fail closed.
- Logs record only categorical legacy-fallback use, never cert contents/digests.
- An already-affected phone cannot self-update to this fix because its old updater fails before installer handoff. It needs one manual same-package install of v0.3.42. After that, future CHECK UPDATE flows should work.
- v0.3.41 CODE-tab HOST restoration remains included.

## v0.3.41 — Code-tab HOST restoration

- Restores the missing **HOST** action inside the CODE tab as a sibling of JOIN.
- HOST opens the existing six-digit host-room screen; JOIN remains unchanged.
- The old standalone lobby HOST button remains intentionally removed.
- No online/network/crypto behavior changed.

## v0.3.40 — Developer rendezvous test path

Added:
- developer-only HTTPS rendezvous URL stored locally on each phone
- in-app HTTPS health probe
- explicit test-mode rendezvous use without changing the production online-status dot
- visible **peer matched / candidate received** state for two-network signaling verification
- Pi localhost-only default retained
- public Pi developer endpoint disabled by default
- JSON-only POST API and security headers
- CI real host/join/candidate exchange smoke test

Release validation passed: server smoke test, core crypto smoke test, Android build/package, APK/source artifacts, and update-feed publication.

Direct internet candidate dialing is still intentionally not implemented. The next milestone is to create the permanent Cloudflare Tunnel, validate two real phones exchanging candidates through the Pi, then implement direct encrypted internet dialing.

## v0.3.37 — Online readiness indicator

Added:

- tiny lobby online status dot
- green/red state
- tap-for-details dialog
- refresh action
- helpful local-only explanation
- static online service status document through the existing GitHub Pages update site

A failed online check does not affect local connectivity.

## v0.3.38 — Rendezvous foundation

Added Android class:

`app/src/main/java/is/quietlink/app/RendezvousClient.java`

Its current API behavior:

- HTTPS-only base URL
- short-lived register
- poll for peer
- leave
- refresh approximately every 15 seconds
- server registration TTL approximately 45 seconds
- bounded request/response sizes
- privacy-safe generic logging

The raw six-digit pairing code is **not** sent to the service.

QuietLink derives an opaque room token from the existing pairing secret.

Added standalone service:

`rendezvous/server.js`

Related files:

- `rendezvous/package.json`
- `rendezvous/README.md`

Current endpoints:

- `GET /health`
- `POST /v1/register`
- `POST /v1/poll`
- `POST /v1/leave`
- `GET /dev`

Current server behavior:

- signaling only
- no media relay
- no chat relay
- no session keys
- registration expiry
- size validation
- basic rate limiting
- aggregate-only developer status
- intended to be portable so the later Raspberry Pi can implement/use the same contract

CI now includes:

`node --check rendezvous/server.js`

v0.3.38 passed:

- rendezvous server syntax validation
- core crypto smoke test
- Android compilation/build
- APK/source packaging
- update publication pipeline

---

# 7. How v0.3.38 is gated

`OnlineStatus.Result` now supports a `rendezvousUrl`.

QuietLink now starts production rendezvous signaling when:
1. the online status endpoint is reachable
2. a non-empty HTTPS `rendezvousUrl` is supplied
3. the current session has a valid six-digit code
4. a call is not already established

`onlineCallsAvailable` remains a separate, stricter UI/production-readiness signal. It can remain false/red while rendezvous candidate exchange runs automatically. This lets signaling be productionized without falsely presenting full internet calling as complete.

When any connection succeeds, rendezvous should be closed to avoid stale presence.

---

# 8. Hosting status

The rendezvous service implementation exists in the repo.

An attempt to create the temporary service using the connected Replit account was blocked because that account requires an active subscription for this operation.

The project has now intentionally switched to using the Raspberry Pi as the **milestone-7 test rendezvous host** instead of waiting until all of milestone 7 is complete. This directly helps test candidate exchange and avoids building a disposable hosted service elsewhere.

Pi setup files:
- `rendezvous/PI_SETUP.md`
- `rendezvous/pi/server.py`
- `rendezvous/pi/install.sh`
- `rendezvous/pi/quietlink-rendezvous.service`

The current user's Pi is **Raspberry Pi OS Bullseye, armhf (32-bit)**. Bullseye installed Node 12.22.12, so the Pi runtime was changed to a Python 3 standard-library implementation of the same rendezvous API. Do not require Node.js on this Pi. The portable Node server remains available for other hosts.

Mobile-data Online Path Test also passed on 2026-09-22:
- candidate discovered: YES
- second STUN confirmation: YES
- mapping stable across checks: YES
- screenshot showed 5G/mobile data active
- no public IP/port exposed in UI/logs

The user also updated the Raspberry Pi checkout/service to the current hardened Python rendezvous server.

Milestone-7 Pi local verification succeeded on 2026-09-22:
- Python 3.9.2
- systemd service active/running
- `GET http://127.0.0.1:8787/health` returned `service=quietlink-online`, `status=ok`, `phase=rendezvous-bootstrap`, `build=pi-python-test`
- activeRooms=0 / activePeers=0 before public testing

Permanent Cloudflare setup is complete: `cloudflared.service` is active/enabled and the published application route maps `rendezvousquietlinkvikman.dpdns.org` to `http://127.0.0.1:8787`. Public `/health` works.

Security decision: the Pi Python server now binds to **127.0.0.1 only**, not 0.0.0.0. The temporary tunnel URL must **not** be committed to GitHub. The Android test build should receive it through a developer-only local override stored on-device. The production status document remains unchanged/red until internet calling is genuinely ready.



v0.3.40 preparation while DNS propagates:
- Android developer tools now include **Online rendezvous test setup**.
- The test base URL is HTTPS-only, validated, and stored locally in `quietlink_online_test` SharedPreferences; do not log it.
- An explicit developer test URL bypasses only the production availability gate for Code-mode rendezvous testing. It does **not** change the production online dot/status.
- The app can health-check `/health` and reports only categorical reachability/build.
- In test mode, rendezvous status becomes user-visible and a match with at least one candidate shows **Online test • peer matched • candidate received**.
- Direct candidate dialing is still not implemented; candidate exchange must be validated first.
- Pi server hardening: localhost bind default, public `/dev` disabled unless explicitly enabled, JSON-only POST API, security headers, room cap reduced to 1000.
- CI includes `rendezvous/pi/smoke_test.py` to perform an actual host/join candidate match.
- Next user-verifiable step after the Cloudflare zone becomes Active: create permanent Tunnel to `http://127.0.0.1:8787`, enter the HTTPS hostname on both v0.3.40 phones, pass TEST HEALTH, then run the two-network same-code candidate exchange.
Cloudflare domain status correction: the user initially entered the domain in the wrong Cloudflare flow and saw **"TLD is not supported"**, but then successfully onboarded **rendezvousquietlinkvikman.dpdns.org** as a Full DNS zone and reached the DNS review page. Treat the domain as accepted. No A/AAAA/MX/www records are required yet for QuietLink; continue onboarding, delegate DigitalPlat nameservers to Cloudflare, wait for zone Active, then create a permanent named Cloudflare Tunnel directly to `http://127.0.0.1:8787`. Never commit tunnel tokens/credentials.

For the first live test, a temporary HTTPS tunnel is acceptable. Milestone 8 is still reserved for promoting the Pi setup to a stable/permanent production deployment with persistent hostname/tunnel, full service hardening, recovery, and long-duration validation.

---

# 9. Important Internet/NAT reality

Do not assume STUN/hole punching will work for every phone/network.

Mobile carriers frequently use CGNAT and some networks use symmetric NAT.

A robust design will eventually require an encrypted relay fallback for cases where direct P2P cannot be established.

That relay, if added, should forward opaque encrypted traffic and should not terminate QuietLink session encryption.

Also note the current transport split:

- control = TCP
- media = UDP

UDP hole punching is generally easier than making the current inbound TCP control socket reachable through arbitrary NAT.

Do not claim “direct internet P2P is complete” merely because UDP candidates can be discovered.

Before enabling the green status dot, both session control/authentication and usable media transport must be solved and tested across separate networks.

---

# 10. NEXT ACTION

## Primary next checkpoint: validate v0.3.57 per-window calibration and the v0.3.56 internet fix

After v0.3.57 CI passes:
1. Update both phones through CHECK UPDATE.
2. Confirm badges: **1** on the large incoming window, **2** on the mini/self-camera window.
3. Open **VIDEO TUNE / ROTATE** and use the dropdown to select Window 1 or Window 2.
4. Window 2 first: try **9:16**, then 3:4 / 2:3 / 4:5 as needed. Use a face or circular object and report which ratio stops the stretch.
5. Fine-tune Window 2 rotation using its new +0/+90/+180/+270 offset without altering Window 1.
6. Window 1: tune incoming direction/offset/aspect; confirm the same Window 1 setting carries into fullscreen.
7. Finish portrait, landscape-left, landscape-right on both phones.
8. Also complete the still-pending internet regression test: Wi-Fi OFF on one phone + mobile data ON, other phone on another network, CODE HOST/JOIN must start without Wi-Fi/hotspot prompt and reach rendezvous/relay.
9. Report the final Window 1 / Window 2 settings for each phone.
10. Promote only the proven common subset to production defaults after both devices pass.

---

# 11. Release/build rules

Preserve:

- package ID: `is.quietlink.app`
- current signing configuration
- in-place Android update compatibility
- version increments
- update feed
- GitHub Actions build flow

Current build stack:

- minSdk 28
- target/compile SDK 36
- Android Gradle Plugin 8.13.2
- Gradle 8.13
- JDK 17

When making an actual release:

1. update Android versionCode/versionName
2. update `version.properties`
3. update `update-channel.txt`
4. update `update-notes.txt`
5. update README release notes
6. update TESTING.md
7. update MILESTONES.md / this handoff if architecture/progress changed
8. use a commit beginning with `QuietLink v...` when the normal publication workflow should publish the update
9. confirm CI passes before calling the release complete

The workflow builds an update-compatible signed APK and source artifact.

---

# 12. Important source files

Start with these when resuming:

- `app/src/main/java/is/quietlink/app/SessionService.java` — session lifecycle, local connection, recovery, online bootstrap
- `app/src/main/java/is/quietlink/app/CryptoChannel.java` — secure handshake/control/media crypto
- `app/src/main/java/is/quietlink/app/MediaTransport.java` — UDP encrypted audio/video
- `app/src/main/java/is/quietlink/app/PeerDiscovery.java` — local peer discovery
- `app/src/main/java/is/quietlink/app/RendezvousClient.java` — internet rendezvous signaling
- `app/src/main/java/is/quietlink/app/OnlineStatus.java` — online status/gating
- `app/src/main/java/is/quietlink/app/KnownDeviceStore.java`
- `app/src/main/java/is/quietlink/app/DeviceIdentity.java`
- `app/src/main/java/is/quietlink/app/MainActivity.java` — lobby/status UI and call UI
- `rendezvous/server.js` — standalone signaling server
- `.github/workflows/android-debug-apk.yml` — build/update publication

Reference docs:

- `MILESTONES.md`
- `README.md`
- `TESTING.md`
- `AI_HANDOFF.md`

---

# 13. UX rules already decided

- Online status should remain visually small.
- Green = online calling truly usable.
- Red = local-only / online unavailable.
- Tapping status should explain the reason in human language.
- User should never feel local calling is “broken” merely because internet service is down.
- Frequent controls stay visible; infrequent developer/settings actions should remain secondary.
- Preserve existing Baby Monitor / Sleeping Baby controls and behavior while networking changes.

---

# 14. Known watch items outside the current milestone

These should not derail Online P2P unless a regression appears, but do not forget them:

- long-duration Sleeping Baby stability previously showed a baby-station crash and parent-side frozen controls during a 1+ hour test
- high-quality/high-framerate video remains an important quality goal
- online recovery must eventually work across network transitions such as Wi-Fi to cellular
- browser support requires WebRTC or another browser-compatible transport; browser JavaScript cannot directly use QuietLink's arbitrary native UDP protocol

If a diagnostic investigation of the prior long-session failure is needed, search the user's available project files/logs before asking them to upload it again.

---

# 15. Milestone display style for user updates

Use the same nested structure as `MILESTONES.md`:

1. ✅ Self-healing reconnect

2. ✅ Live diagnostics

3. ✅ Adaptive video

4. ✅ Known-device management + request expiry

5. ✅ Background watchdog/recovery
   - ✅ Hotspot/LAN support added

6. ✅ Security hardening

7. ⏳ Online P2P + rendezvous
   - ✅ Online availability/status dot with helpful local-only fallback
   - ✅ Android rendezvous signaling client
   - ✅ Standalone short-lived rendezvous server implementation
   - ✅ Opaque room token; raw six-digit pairing code is not sent to the rendezvous service
   - ✅ 45-second expiring presence/register/poll/leave signaling
   - ✅ HTTPS-only activation and local-first gating
   - ✅ v0.3.38 build + crypto + rendezvous syntax validation
   - ⏳ Public STUN / NAT candidate discovery
   - ⏳ Candidate exchange through rendezvous
   - ⏳ Direct internet P2P dialing/acceptance
   - ⏳ Internet-path self-healing recovery
   - ⏳ Temporary hosted rendezvous production test

8. ⏳ Raspberry Pi rendezvous server

9. ⏳ HTML/WebRTC client

10. ⏳ Party/group mode

The main milestones are **numbered**. Intermediate milestones are **indented bullets directly underneath their parent milestone**, each with its own ✅ or ⏳.

As work advances, switch completed sub-milestones from ⏳ to ✅ and insert newly discovered substeps beneath the correct parent milestone.

Do not replace this with a separate progress-bar layout, percentages, or ASCII diagrams unless the user explicitly asks for them.

---

# 16. Working style

When the user says **continue**, normally continue implementation from the current NEXT ACTION rather than returning another roadmap.

Before editing:

- inspect current `main`
- check whether a newer release/commit already changed the relevant files
- preserve finished systems unless the active task requires touching them

After meaningful updates:

- explain what changed
- show milestone progress using ✅ / ⏳
- state what is being worked on next
- keep `MILESTONES.md` and `AI_HANDOFF.md` current when project state materially changes

This file is intended to make project continuation possible without relying on chat history.
