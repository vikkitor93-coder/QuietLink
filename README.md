# QuietLink 0.3.48

> **AI / project continuation:** read [AI_HANDOFF.md](AI_HANDOFF.md) first, then [MILESTONES.md](MILESTONES.md) and [TESTING.md](TESTING.md). The handoff file contains the current architecture, constraints, release process, unresolved issues, and exact NEXT ACTION.


Native Android local-first encrypted P2P voice/video with Baby Monitor and Sleeping Baby modes.

## Connection order
1. Same router / LAN starts immediately using Android NSD.
2. After a short 1.5-second local head start, CODE host/join automatically starts production rendezvous signaling in parallel when the published endpoint is available.
3. Wi-Fi Direct fallback starts after 8 seconds without an authenticated peer.
4. Local calling never depends on the online service. The permanent rendezvous can fail without breaking LAN/Hotspot/Wi-Fi Direct. Direct internet session establishment remains under milestone-7 validation.

## Security
- Ephemeral P-256 ECDH every session.
- Six-digit code hardened with PBKDF2-HMAC-SHA256 and mixed into the handshake.
- Explicit key confirmation rejects wrong codes before media starts.
- Directional AES-256-GCM control/media keys.
- Media replay protection.
- Human-readable verification phrase.
- Encryption is mandatory.

## 0.3.46 signing-key migration bridge
- This is a **bridge release** for the Android signing-key security migration.
- The old signer was found in public Git history and must be treated as compromised.
- v0.3.46 is still signed with that old signer solely so existing installations can update without uninstalling.
- The updater now pins the exact old signer and the exact new QuietLink v2 signer.
- The next rotated APK is accepted only for the specific old→new signer transition. Arbitrary replacement signers remain rejected.
- On devices where modern Android `SigningInfo` exposes the certificate history, QuietLink also requires the downloaded APK history to contain both pinned certificates and the current APK signer to be the pinned v2 signer.
- On older/OEM PackageManager implementations that only expose the legacy current signer, QuietLink allows only the same pinned old→v2 transition; Android's package installer still performs the platform signature-lineage verification.
- No new private signing material is committed to GitHub.

## 0.3.48 production rendezvous integration
- Promotes `https://rendezvousquietlinkvikman.dpdns.org` from a developer-only test endpoint into QuietLink's normal production rendezvous configuration.
- CODE host/join gives LAN a 1.5-second head start, then starts rendezvous candidate exchange automatically in parallel; Wi-Fi Direct still begins after 8 seconds.
- Existing developer override behavior is preserved for alternate test endpoints.
- Phones that previously saved the now-official production URL as their developer override automatically clear that redundant local override after upgrading.
- Production peer matches now show **Internet peer found • checking connection paths…** without exposing endpoint/candidate details.
- The published online-status document now advertises the permanent rendezvous URL.
- The Online dot remains conservative/red because `onlineCallsAvailable` stays false until the normal cross-network call path is explicitly validated.
- No changes to QL5 encryption, pairing-code secrecy, media encryption, signing, or privacy-safe logging.

## 0.3.47 signing-key rotation
- First stable release signed with the new private QuietLink v2 key held only in GitHub Actions Secrets/offline backup.
- Uses Android authenticated signing lineage from the exposed old signer to v2 with rotation minimum SDK 28.
- Normal CI no longer reads or uses the compromised keystore/password.
- Existing QuietLink installations should update in place through Android's signer-rotation verification.
- After device confirmation, all reachable Git history containing the exposed old keystore/password will be force-rewritten and audited again.

## 0.3.45 Parent fullscreen + older-device Sleeping Baby audio
- Parent Station Baby fullscreen now uses the same **text-labelled Sleeping Baby controls** instead of generic Video-mode icon controls.
- Fullscreen Parent Station includes Alert sensitivity, HEAR ON/OFF, Baby mic, Baby camera, switch Baby camera, LIGHT, BRIGHT, Sleeping Baby/Exit Sleeping, Hold to Talk, Chat, Battery, Disconnect, and Back.
- A fresh Baby Station session no longer inherits a stale mute state from a previous Voice/Video session.
- Audio capture now logs privacy-safe lifecycle state (gate open/closed, recorder start/rebuild/read errors, TX/RX flow) without recording or exporting audio content.
- On a real transmit-gate resume, QuietLink rebuilds Android AudioRecord before capture. This targets older audio HALs that fail to resume a previously stopped recorder after Parent/Baby role changes or Sleeping Baby transitions.
- Recorder start/read failures also self-rebuild instead of leaving the microphone path dead.
- Per-frame JPEG orientation calculation logging was removed because it was flooding the bounded diagnostic trace; the applied rotation report remains.
- Developer tools and Live Diagnostics now include **Report log to GitHub**. QuietLink prepares a privacy-safe `.txt`, opens Android sharing, and can open a pre-filled GitHub issue. No GitHub password/token is embedded or stored.
- v0.3.44 camera fix was verified by the user on both phones: camera swap and camera on/off now work after role swaps.

## 0.3.44 older-device Baby video recovery
- Fixes a Camera2 lifecycle race observed on the older test phone in Baby mode.
- Camera restarts are now debounced and generation-tagged so only the newest requested open can become active; stale callbacks close themselves instead of replacing the live camera.
- Genuine Camera2 disconnects, open failures, and JPEG-session failures schedule bounded automatic recovery instead of leaving video permanently dead.
- Baby mic/camera/torch/brightness state synchronization updates the existing controls in place instead of rebuilding the whole call UI and destroying both video TextureViews.
- JPEG receive watchdog now asks the remote sender to recover when the expected video stream stops; the sender treats that request as a bounded camera restart.
- A stale dark frame can no longer leave the Parent Station indefinitely saying **LOW LIGHT**. If frames actually stop, the overlay changes to **VIDEO STALLED • RECOVERING**.
- A genuinely dark but continuously updating camera remains a normal low-light condition and does not by itself count as a crash.
- No changes to QL5 encryption, local-first networking, updater signing, or the online availability gate.

## 0.3.43 Baby/Sleeping Baby display improvements
- Sleeping Baby **BRIGHT** now turns the Baby Station into a full-screen white room light at maximum app brightness.
- The white light is an overlay, so the underlying Baby Station camera/video surfaces remain alive rather than being torn down.
- System bars are hidden while the white light is active and restored when BRIGHT is turned off.
- Normal Baby mode controls are compact chips sized around their labels instead of large full-width/fixed-height buttons, leaving substantially more room for the camera feed.
- The Baby video can now be tapped to enter the same preserved-surface fullscreen presentation used by Video mode.
- In Parent Station Baby fullscreen, the floating camera/mic/switch controls operate the **Baby Station** rather than the parent phone.
- Back exits Baby fullscreen normally.
- Voice and regular Video layouts are otherwise unchanged.

## 0.3.42 updater signer compatibility
- Fixes `Stage: package / Reason: signature_unavailable` on Android/OEM builds that do not populate the newer `SigningInfo` fields consistently.
- QuietLink still tries the modern Android signing API first.
- Only when modern signer data is unavailable, it falls back to Android's legacy package-signature field and SHA-256 compares the installed-app signer with the downloaded APK signer.
- No signature check is bypassed; mismatches are still rejected.
- Privacy-safe diagnostics record only which compatibility path was used, never certificate contents or digests.
- Phones already affected by the old updater need one manual same-package APK install of v0.3.42; future in-app updates should then use the compatibility path automatically.

## 0.3.41 Code-tab HOST restoration
- Restores the missing **HOST** action inside the **CODE** tab.
- CODE now presents **HOST | JOIN** at the bottom.
- HOST opens the existing six-digit room-hosting screen; JOIN continues to use the entered six-digit code.
- The obsolete standalone lobby HOST button remains intentionally removed.
- No networking, crypto, rendezvous, Baby/Sleeping Baby, or updater behavior changed.

## 0.3.40 developer rendezvous test path
- Adds a developer-only HTTPS rendezvous override stored locally on each phone.
- The override is never required for normal/local QuietLink use and does not turn the production online-status dot green.
- Developer tools can health-check the configured rendezvous endpoint without displaying or logging the hostname, public IP, or candidate port.
- Code-mode host/join can use the explicit test endpoint in parallel with LAN/Hotspot/Wi-Fi Direct discovery.
- When two remote peers match, the waiting UI reports **peer matched / candidate received** without exposing candidate values.
- The Raspberry Pi server now binds to localhost by default, disables the public `/dev` endpoint by default, accepts JSON API requests only, adds security headers, and uses a lower room cap.
- CI now performs a real Python rendezvous host/join/candidate exchange smoke test.
- Direct internet dialing is still intentionally not enabled in this release; v0.3.40 validates signaling/candidate exchange first.
- Local calling, Baby/Sleeping Baby, updater, and QL5 encryption behavior remain unchanged.

## 0.3.39 STUN / NAT path test
- Adds RFC 5389-style STUN Binding discovery for QuietLink's UDP transport.
- Uses the existing call UDP socket when preparing an online rendezvous candidate.
- Publishes a server-reflexive UDP candidate through the rendezvous signaling client when online calling is enabled.
- Adds a user-visible **RUN ONLINE PATH TEST** action to the lobby online-status dialog.
- The self-test checks Cloudflare STUN first and performs an independent second STUN check when available to indicate whether the NAT mapping appears stable.
- The UI and privacy-safe logs report only success/category state; public IP addresses and mapped ports are not shown or written to QuietLink diagnostics.
- Local LAN / Hotspot / Wi-Fi Direct behavior remains unchanged.
- The online status remains red/local-only until the rendezvous/direct-call path is actually ready.

## 0.3.38 online rendezvous foundation
- Adds a standalone signaling-only rendezvous service implementation under rendezvous/.
- Adds the Android RendezvousClient with short-lived register/poll/leave signaling.
- The raw six-digit pairing code is never sent to the service; QuietLink derives an opaque room token from the pairing secret.
- Registrations expire after 45 seconds unless refreshed.
- QuietLink activates online rendezvous only when the signed/status channel says onlineCallsAvailable=true and provides an HTTPS rendezvousUrl.
- v0.3.38 intentionally does not dial internet candidates yet. NAT/STUN candidate discovery and direct internet P2P are the next milestone.
- Local LAN/Hotspot/Wi-Fi Direct discovery remains authoritative and unchanged when the online service is absent or disabled.

## 0.3.37 online readiness indicator
- Adds a tiny lobby status dot: green only when the QuietLink online service reports that internet peer calling is actually available; red means local-only.
- Tapping the dot explains whether the online service is unreachable or whether internet calling is simply not enabled yet.
- The status probe sends no QuietLink device identity, peer name, room code, media, chat content, or diagnostic payload.
- Nearby/LAN/Hotspot and Wi-Fi Direct behavior is unchanged and remains usable when the online endpoint is unavailable.
- Publishes a small static online-status.json document through the existing QuietLink GitHub Pages update site. This is groundwork for the upcoming cloud rendezvous transport and later Raspberry Pi rendezvous server.

## 0.3.36 Update checker 3
- No functional changes.
- Test target for the v0.3.35 content-URI Android installer handoff.

## 0.3.35 installer compatibility and lobby cleanup
- Replaces the direct PackageInstaller.Session update handoff with a read-only QuietLink content provider and Android's normal package-installer Intent. The APK is still fully verified before Android sees it.
- The update APK stays private inside QuietLink's cache and is shared read-only only for the installer handoff.
- Removes the obsolete HOST button from the lobby completely.
- Tightens the lobby title row so QuietLink no longer gets squeezed into a narrow multi-line column by the update/help/dev controls.

## 0.3.34 Update checker 2
- No functional changes.
- Second deliberate self-update test release for validating the repaired v0.3.33 updater.

## 0.3.33 updater compatibility fix
- Fixes the self-updater rejecting valid HTTPS APK responses when the CDN omits Content-Length or uses chunked transfer.
- The 100 MB streaming hard limit remains enforced while downloading.
- Update failures now show a privacy-safe stage/reason code instead of one generic error, making future updater issues diagnosable without exposing URLs, IPs, device identity, or package signatures.
- Package/version/signature validation remains mandatory before PackageInstaller is invoked.

## 0.3.32 Update checker
- No functional app changes.
- Deliberate self-update test release for validating CHECK UPDATE → download → verified APK → Android in-place Update.
- Update prompt release note: “Update checker — self-update test release.”

## 0.3.31 Sleeping Baby local controls sync
- Baby Station now has its own CAMERA ON/OFF control even while Sleeping Baby mode is active, so it can locally re-enable video after Sleeping Baby initially turns it off.
- The large MIC ON / MIC MUTED button now uses the same authoritative Baby Station mic state as the Baby Station status panel.
- Remote Parent mic changes synchronize the Baby Station's large mic control immediately.
- Remote/local Baby camera changes synchronize the Baby Station's local camera control.
- Selfie preview geometry/orientation is intentionally unchanged in this release pending the supplied visual reference.

## 0.3.30 in-app updater
- Lobby header includes CHECK UPDATE beside About.
- Manual update check downloads a small HTTPS manifest only when requested.
- Newer builds show version/notes and ask before downloading.
- Downloaded APK is SHA-256 checked, package-name checked, and signing-certificate checked against the installed QuietLink app before Android installation is requested.
- Uses Android PackageInstaller, so no storage permission or external file manager is needed.
- Android may require one-time “Allow from this source” approval for QuietLink; the pending install resumes when the user returns.
- Final Android system update confirmation remains required by the OS.
- CI has a GitHub Pages update-feed publisher containing only update.json and the signed APK; private source remains private.

## 0.3.29 preserved-surface fullscreen and Sleeping Baby controls
- Fullscreen Video no longer rebuilds the call screen or destroys either TextureView. The existing live video frame expands in place, preserving both local Camera2 and remote decoder surfaces.
- Back arrows are visible in normal call headers, Sleeping Baby Parent mode, and fullscreen Video.
- Nearby UDP discovery now answers a received broadcast with a direct local unicast beacon, repairing asymmetric one-way discovery when only one phone receives LAN broadcast/multicast reliably.
- Sleeping Baby gives the Baby Station a clearer “SLEEPING BABY • BABY STATION” title and live parent-control state.
- Parent Station can remotely toggle the Baby Station flashlight and request a bright app screen. Brightness changes only QuietLink's window, not the phone's permanent system-brightness setting.
- Baby flashlight/brightness state is synchronized back to the Parent Station and logged only as anonymous technical state.
- v0.3.28 privacy-safe diagnostic export remains available.

## 0.3.28 diagnostic logging and video stabilization
- Adds a privacy-safe persistent QuietLink diagnostic trace with an Export button in Developer tools. It records app/video/surface/codec/orientation state only.
- Exported logs explicitly omit peer/device names, IP addresses, device IDs, fingerprints/keys, room codes, chat text, and media contents, with a second redaction pass before export.
- Video recovery no longer depends on the Camera2 handler. A post-unlock sequence and recurring health watchdog independently detect stale decoder rendering or encoder output.
- Stalled receive video rebuilds the decoder and requests a fresh IDR. Stalled local H.264 output restarts Camera2 automatically.
- H.264 orientation now uses Android display rotation plus camera sensor orientation, removing the raw orientation-sensor 90/270 ambiguity that caused upside-down landscape video.
- The local selfie preview uses the same upright sensor transform plus mirroring and exact 9:16 portrait / 16:9 landscape frames to eliminate stretch.
- Camera OFF now explicitly tells the peer to hide its video Surface and show black instead of preserving the final decoded frame.
- Normal Video mode again shows the existing top controls, menus, and mode switcher. Tapping the embedded video enters the fullscreen floating-control view; Back returns to the normal Video screen.
- Keep-screen-awake behavior from v0.3.27 remains enabled.

## 0.3.27 video wake and fullscreen controls
- Active calls now set FLAG_KEEP_SCREEN_ON so normal screen timeout no longer locks the phone during a call.
- Returning from lock/background triggers a focused H.264 recovery: decoder rebind, remote IDR request, local keyframe request, and Camera2 restart only if the encoder is actually stalled.
- Normal Video mode now uses a dedicated fullscreen video canvas instead of the old stacked control layout.
- Video controls float in a dark bottom pill with camera, switch-camera, microphone, and red hang-up icons matching the supplied reference.
- Tapping the video hides/shows the floating controls and Android system bars for an unobstructed fullscreen view.
- The local selfie preview changes between 9:16 portrait and 16:9 landscape geometry and uses the inverse front-preview rotation, fixing the 90°-right stretched preview seen in landscape.
- Baby and Sleeping Baby retain their specialized control layouts.

## 0.3.26 security hardening
- Secure transport protocol upgraded from QL4 to QL5; both peers must be updated.
- P-256 public keys are validated as actual 256-bit EC keys before ECDH/signature use.
- Control, chat, and media ciphertext/plaintext sizes now have tight per-channel bounds instead of a broad 1 MB control allowance.
- Decrypted control/chat text is decoded with strict UTF-8 rejection for malformed input.
- Audio/video replay packets are rejected before expensive AES-GCM work when they are already known to be duplicate or outside the replay window.
- Every encrypted traffic channel rotates to a freshly HKDF-derived AES-256-GCM key/IV epoch every 8,192 packets. Rotation is sequence-derived, so packet reordering still works without a separate network handshake.
- Ephemeral ECDH/master/Finished key byte arrays are wiped after session traffic keys and verification text are derived.
- Incoming unauthenticated Nearby/recovery handshakes are bounded to four simultaneous cryptographic handshakes.
- CI crypto tests now cross a real key-rotation boundary and reject oversized control/media frames plus malformed Nearby handshake lengths.

## 0.3.25 rotation and disconnect stabilization
- Live portrait/landscape changes no longer rebuild the video TextureViews, avoiding repeated Camera2/H.264 teardown.
- Selfie preview uses an aspect-preserving mirrored transform rather than stretched scaling.
- Remote video uses an aspect-preserving non-mirrored transform.
- Intentional Disconnect sends a named encrypted BYE before teardown; the peer bypasses recovery and shows “<device> disconnected” at the top of the lobby.

## 0.3.24 fast recovery and Auto no-ask
- Fixes a recovery deadlock caused by deterministic dialer ordering. The preferred fingerprint still dials first, but the other side performs a delayed authenticated sync probe if needed.
- A still-healthy peer answers the sync probe with RECOVER_SYNC and immediately enters recovery, eliminating the long wait for heartbeat timeout.
- Recovery discovery restarts after 4 seconds instead of 15 seconds if the network changed underneath Android discovery.
- Failed fallback probes retry after a short delay.
- AUTO is restored to “no ask” semantics for explicit trusted calls: it never originates a fresh call when the app opens, but a manually initiated call from a verified device marked AUTO is accepted automatically.
- SAFE remains “ask every time.”
- Brief network drops still self-heal for every authenticated active session; AUTO is not required for that.

## 0.3.23 reconnect and Nearby refresh fix
- Brief Wi-Fi/hotspot/network loss now always enters authenticated recovery for an already-established session. It no longer depends on the Known-device Auto Resume toggle.
- Fresh calls remain manual and explicit local/remote Disconnect still terminates the logical session and prevents recovery.
- Auto Resume is now scoped to durable #5 restoration after Android recreates the service/process; it is not required for ordinary network-drop recovery.
- Recovery beacon/discovery resources now remain available for every authenticated active session, fixing the path that previously returned one phone to the lobby when Wi-Fi was disabled.
- Nearby discovery republishes its current snapshot periodically, so a peer already found before the activity listener attached becomes visible without tab switching.
- MainActivity attaches the SessionBus listener before starting lobby discovery, closing the initial discovery/UI race.

## 0.3.22 hotspot LAN support
- Nearby/Known discovery now uses Android NSD plus a local UDP broadcast fallback.
- The fallback works across private IPv4 Wi-Fi/hotspot interfaces and does not carry call media or bypass QuietLink authentication.
- A phone providing an Android hotspot can discover and call a QuietLink phone connected to that hotspot even when Android reports cellular as the hotspot host's active network.
- Code-mode LAN discovery uses the same fallback.
- The lobby recognizes a hotspot/LAN interface as a valid local network instead of blocking the hotspot host as “Wi-Fi is off.”
- The actual call still uses the existing authenticated encrypted QuietLink session protocol.

## 0.3.21 background watchdog and durable recovery
- Fixes the v0.3.20 Wi-Fi-off regression: Auto Resume permission is negotiated into the active session instead of re-reading a fragile local flag at disconnect time.
- Enabling Auto Resume on either phone authorizes that pair's active session to recover; fresh app startup still never starts a new call automatically.
- Recoverable sessions persist a minimal checkpoint containing peer public identity and session mode/settings, but no media, chat history, pairing code, or session encryption keys.
- SessionService is sticky for active/recovering sessions. If Android recreates the service/process, QuietLink restores the checkpoint, enters Reconnecting, performs a fresh authenticated handshake with the exact previous device, and rebuilds audio/video.
- A 5-second in-process watchdog refreshes the checkpoint and restarts recovery resources if they stall.
- Baby-mode wake/Wi-Fi reliability locks remain held while the logical session is recovering.
- Manual Disconnect and normal full teardown delete the checkpoint, so deliberately ended calls cannot resurrect.
- Recovery checkpoints expire after 24 hours and are not used to silently start a fresh call after a reboot/force-stop workflow.

## 0.3.20 device management and request expiry
- Known devices now have a dedicated Forget action with confirmation. Forget removes the stored identity, Safe state, and Auto Resume relationship.
- A forgotten phone that is still nearby appears as a new untrusted device and must be security-key verified again on the next connection.
- Incoming and outgoing fresh connection requests now have a shared 30-second lifetime.
- Both request dialogs show a live countdown. On expiry both phones clear the request and return to pairing/search state instead of leaving stale dialogs or sockets.
- Accept is rejected if the request has already expired.

## 0.3.19 adaptive video
- Fresh Nearby/Known connections are never started automatically when QuietLink opens. A person must choose CONNECT; the receiving phone must approve the fresh call.
- The former Auto setting is now Auto Resume and is used only to restore an already-active authenticated session after an unexpected interruption.
- H.264 keeps the 30 FPS target while adapting through 1280×720 HD, 960×540 Balanced, and 640×360 Smooth tiers.
- Bitrate adapts continuously inside each tier. Sustained congestion steps resolution down; sustained clean conditions slowly restore higher resolution.
- H.264 config packets carry the active dimensions so the receiver rebuilds its hardware decoder cleanly when a tier changes.
- Live diagnostics and the video overlay report the actual active H.264 tier instead of assuming 720p.

## 0.3.18 live diagnostics
- Healthy calls advertise a tiny authenticated recovery beacon, so when one phone has already entered recovery its request immediately synchronizes the other phone instead of waiting for the full heartbeat timeout.
- The unlocked 🛠 menu is now available during live calls.
- Live diagnostics update continuously with network type, heartbeat age, control RTT, recovery count, audio/video packet counts and queues, concealed/dropped audio, H.264 bitrate, encode/render FPS, dropped video packets, incomplete RX video units, and keyframe requests.

## 0.3.17 self-healing reconnect
- Unexpected local-network drops keep the logical session alive and automatically retry the exact previously authenticated peer.
- Recovery uses the device identity signature/public key from the established call; another Known device cannot take over the recovering session.
- Voice/Video/Baby role, Sleeping Baby state, mute, listen state, and local/remote camera enable state are preserved across recovery.
- Manual Disconnect explicitly disables recovery and retains the v0.3.16 Auto-reconnect pause behavior.
- Sleeping Baby still raises the Parent Station connection-loss alert while QuietLink attempts recovery.

## 0.3.16 stability fixes
- Camera orientation respects Android rotation lock; locked devices no longer rotate the live/self preview from physical sensor movement.
- Deliberate disconnect pauses trusted Auto reconnect on both phones until a person explicitly reconnects.
- Local microphone mute survives service teardown/reconnect so the UI state matches the actual transmit gate.
- Parent Station hold-to-talk works in Baby and Sleeping Baby modes even when the normal call mic toggle is muted.

## HD video
- Hardware Camera2 → H.264/AVC Surface encoding at 1280×720 / 30 fps when both phones support it.
- Starts around 4 Mbps and adapts from ~0.8 to 6.5 Mbps while prioritizing audio.
- Hardware decode renders directly to a Surface/TextureView; JPEG remains the compatibility fallback.
- Repeated codec configuration no longer rebuilds the decoder every keyframe.
- Packet-loss/decoder-backlog recovery requests a fresh keyframe and resynchronizes instead of displaying a broken reference chain.

## High-quality audio policy
QuietLink deliberately keeps Android on the normal media path:
- 48 kHz 16-bit mono PCM.
- USAGE_MEDIA + CONTENT_TYPE_MUSIC playback.
- No telephony/communication audio mode.
- No exclusive audio focus.
- No Bluetooth SCO/HFP request.
- Built-in phone microphone preferred so Bluetooth playback can remain high-quality A2DP.

## Modes
- Voice
- Video
- Baby Monitor: host is baby; parent mic captures only while Hold to Talk is held; parent can switch baby camera remotely.
- Sleeping Baby: parent starts silent with video off, configurable sound alert sensitivity, on-demand video, Hold to Talk.

## Raspberry Pi test rendezvous

Milestone 7 can use a Raspberry Pi as the live test rendezvous before milestone 8 production hardening. See `rendezvous/PI_SETUP.md` for the guided setup and temporary HTTPS test-tunnel workflow.

## Build
Package: is.quietlink.app
Min SDK: 28
Target/compile SDK: 36
Android Gradle Plugin: 8.13.2
Gradle: 8.13
JDK: 17

GitHub Actions builds a debug APK and uploads it as the `QuietLink-debug-apk` artifact.
