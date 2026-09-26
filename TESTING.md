# QuietLink two-phone test checklist

1. Install the same APK on two Android phones and grant requested permissions.
2. Same-router test: host Voice, join with six-digit code, verify connection occurs before Wi-Fi Direct fallback and both devices show the same verification phrase.
3. Wrong-code test: a wrong pairing code must not connect or pass media.
4. Audio coexistence: play YouTube/music while QuietLink is active and confirm playback stays full-quality rather than switching to call audio.
5. Bluetooth A2DP test: playback should remain high-quality while QuietLink prefers the phone microphone.
6. Wi-Fi Direct fallback: test without usable LAN discovery and confirm local encrypted media still connects.
7. Video: verify both devices show HD/H.264 negotiation, sustained ~30 fps motion, front/rear switching, local video on/off, orientation changes, and uninterrupted audio.
8. Video recovery: briefly weaken/block Wi-Fi or create movement congestion; picture should recover quickly on a fresh keyframe without long smearing/freezing.
9. Video fallback: a device without usable H.264 should remain interoperable through JPEG compatibility mode.
10. Baby Monitor: confirm baby audio/video reaches parent, parent mic is inactive until Hold to Talk, and parent can switch baby camera remotely.
11. Sleeping Baby: parent starts muted/video-off, alerts respond to sensitivity, video can be woken on demand, and black/dim baby screen works.
12. Background/overnight: test foreground service, locked screen, battery optimization, reconnect behavior, and several minutes of continuous use.

13. Rotation lock: lock Android orientation, rotate the phone physically, and confirm the small self preview and transmitted video remain in the locked orientation; unlock rotation and confirm normal rotation resumes.
14. Manual disconnect + Auto: with Safe/Auto enabled on both phones, press Disconnect and confirm both return to Nearby/Known without reconnecting until a device is tapped.
15. Mute reconnect: mute both microphones, disconnect/reconnect, and confirm neither transmits audio until explicitly unmuted.
16. Sleeping Baby talk: with Parent Station in Sleeping Baby and normal mic muted, hold the talk button and confirm the Baby Station hears the parent only while held.

17. Self-healing reconnect: establish a Voice call, briefly disable Wi-Fi on one phone, re-enable it, and confirm the existing session returns without tapping Connect.
18. Video recovery: repeat in Video mode and confirm H.264 video/audio resume and camera/mute state is preserved.
19. Baby recovery: repeat in Baby mode, including Sleeping Baby. Confirm Parent/Baby roles remain unchanged; Parent receives the interruption alert in Sleeping Baby and monitoring resumes after recovery.
20. Manual disconnect guard: with Safe/Auto enabled, press Disconnect and confirm neither recovery nor normal Auto reconnect starts until a person deliberately reconnects.
21. Identity guard: while one session is recovering, confirm another Known QuietLink device cannot take over that recovering session.

22. Recovery synchronization: during an active call disable Wi-Fi on one phone, re-enable it, and confirm BOTH phones show reconnecting/reconnected state without waiting for a long heartbeat timeout.
23. Live diagnostics: during Voice/Video/Baby sessions open 🛠 > Live diagnostics and confirm values update every second.
24. RTT/heartbeat: verify Round-trip time appears after several seconds and Heartbeat age resets regularly while connected.
25. Video diagnostics: in H.264 Video mode verify codec shows H.264 1280×720, bitrate changes as adaptation runs, and rendered FPS is near the observed video rate.
26. Loss counters: interrupt Wi-Fi briefly and verify recovery count increments; dropped/lost/concealed counters may increase under the interruption but the session recovers.

27. Manual startup: with both phones set to Auto Resume, close/reopen QuietLink on both phones and confirm neither starts a call automatically.
28. Fresh call approval: tap CONNECT on one phone and confirm the other phone still receives an Accept/Decline prompt.
29. Auto Resume: establish a call between devices with Auto Resume enabled, interrupt Wi-Fi, and confirm the already-active session recovers automatically.
30. Adaptive H.264: start in good Wi-Fi and confirm diagnostics report 1280×720. Create sustained network congestion and confirm bitrate drops first, then resolution may step to 960×540 and 640×360 while targeting 30 FPS.
31. Quality recovery: restore a clean network and leave the call running; confirm the video slowly climbs back toward 1280×720 rather than immediately oscillating tiers.
32. Tier decoder transition: during every resolution change confirm video resumes without app restart, audio stays live, and diagnostics show the new tier.

33. Forget device: in Known, tap ✕ on a saved phone, confirm the warning, and verify it disappears from Known. If it is nearby, verify it still appears under Nearby as a new device.
34. Re-pair forgotten device: connect to the forgotten phone and verify QuietLink treats it as new and asks for security-key verification/acceptance again.
35. Incoming expiry: send a fresh connection request and do nothing. Confirm the receiving dialog counts down from about 30 seconds, closes automatically, and no stale Accept dialog remains.
36. Outgoing expiry: on the calling phone confirm the waiting dialog also counts down and clears at expiry with “Connection request expired”.
37. Expiry recovery: immediately send a new request after the old one expires and verify it works normally.
38. Regression: Auto Resume must still recover an already-active interrupted session, while launching QuietLink must still never start a fresh call automatically.

39. Wi-Fi-off regression: with Auto Resume enabled on either one of the two phones, establish a call, turn Wi-Fi off on one phone, and confirm BOTH phones remain in the session UI showing reconnecting rather than returning to the lobby. Turn Wi-Fi back on and confirm the same session restores.
40. One-sided permission: enable Auto Resume on only one phone, leave it off on the other, repeat the interruption, and confirm recovery still works.
41. Background recovery: establish a Baby/Sleeping Baby session with Auto Resume, background both apps and turn both screens off for at least 15 minutes. Wake the Parent phone and confirm the session is still active.
42. Service recreation: while an Auto Resume session is active, allow Android to reclaim/recreate QuietLink if your device provides a process/background test. Reopen QuietLink and confirm it shows the recovering/current session rather than the normal lobby and authenticates only the same peer.
43. Manual disconnect guard: press Disconnect, close/reopen QuietLink, and confirm the ended session never restores.
44. Recovery state: change mute/camera/Sleeping Baby settings, interrupt Wi-Fi or recreate the service, and confirm those settings return with the session.
45. Long Sleeping Baby test: leave the Baby Station backgrounded/screen-off for 1+ hour and confirm the Parent remains responsive; if connectivity drops, Parent gets the existing connection-lost alert while QuietLink attempts recovery.

46. Hotspot Nearby: enable hotspot on Phone A, connect Phone B to it, open QuietLink on both and confirm both appear under Nearby. From hotspot-host Phone A, tap CONNECT to Phone B and complete the normal Accept flow.
47. Hotspot reverse direction: disconnect manually and have Phone B call hotspot-host Phone A.
48. Hotspot Known: after the pair is Known, verify the hotspot host finds the client and connects manually; simply opening the apps must not start a fresh call.
49. Hotspot Auto Resume: establish an Auto Resume call, briefly disconnect/reconnect Phone B from the hotspot, and confirm the active session stays in Reconnecting and restores.
50. Hotspot Code: start a 6-digit room on one phone and join it from the other while using the phone hotspot LAN.
51. Normal Wi-Fi regression: move both phones back to an ordinary router and verify Nearby, Known, Code, and Auto Resume still work.

52. Network-loss recovery without Auto Resume: turn Auto Resume OFF on both phones, establish a Nearby call, disable Wi-Fi/hotspot connectivity on either phone, and confirm the app remains in the session UI showing Reconnecting instead of returning to the lobby. Restore connectivity and confirm the same authenticated session reconnects.
53. Manual disconnect regression: press Disconnect and confirm it returns to the lobby and does not reconnect automatically.
54. Nearby initial population: put both phones on Nearby and leave them there. Confirm each phone appears on the other without switching Nearby/Known/Code tabs.
55. Nearby late listener: open Phone A on Nearby, then open Phone B on Nearby several seconds later. Confirm both lists populate automatically within a few seconds.
56. Hotspot repeat: host a hotspot on Phone A, connect Phone B, leave both on Nearby, and confirm discovery populates without tab toggles. Then call and repeat the Wi-Fi/hotspot interruption recovery test.

57. Fast reconnect: with both phones connected, disable Wi-Fi on either phone for 2–5 seconds and restore it. Confirm both remain in-session and reconnect within a few seconds rather than waiting for heartbeat timeout.
58. Reverse fingerprint direction: repeat the same test with the other phone being the one that loses Wi-Fi; both directions must recover.
59. AUTO no-ask: mark Phone A as AUTO on Phone B. From Phone A manually tap CONNECT to Phone B. Phone B must accept automatically without an Accept/Decline prompt.
60. SAFE ask: disable AUTO but leave SAFE on Phone B. From Phone A manually CONNECT and confirm Phone B still shows Accept/Decline.
61. Startup regression: with AUTO enabled, simply opening QuietLink on both phones must not originate a call.
62. Hotspot recovery: repeat the fast reconnect test with Phone A hosting a hotspot and Phone B connected to it.

63. Video rotation stability: rotate portrait/landscape repeatedly during H.264 video and confirm video remains live and the call does not crash.
64. Selfie aspect: verify the small front-camera preview keeps natural proportions in portrait and landscape.
65. Intentional disconnect: press Disconnect on one phone. The peer must not enter Reconnecting; it should show “<device> disconnected” at the top of the lobby.
66. Accidental loss regression: disable Wi-Fi without pressing Disconnect and confirm Reconnecting/self-heal still occurs.

68. QL5 compatibility: install v0.3.26 on both phones and verify Nearby, Code, Voice, Video, Baby, reconnect, and hotspot calls establish normally.
69. Version mismatch: if one phone is intentionally left on v0.3.25, confirm the connection is rejected with the protocol-update/mismatch path rather than partially connecting.
70. Long encrypted video: keep a video call running for several minutes so video crosses multiple 8,192-packet key epochs; video/audio must remain uninterrupted.
71. Recovery after rekey: after a multi-minute call, interrupt Wi-Fi and verify recovery performs a fresh authenticated session and resumes normally.
72. Security smoke tests: CI must pass wrong-code rejection, protocol downgrade rejection, signed Nearby identity, replay rejection, rotating-key boundary, oversized control/media rejection, and malformed Nearby length rejection.

73. Screen timeout: start a video call and leave the phone untouched longer than its normal display timeout. Confirm QuietLink keeps the display awake and the call/video remain live.
74. Manual lock/unlock: manually lock one phone during a video call, unlock it, and confirm video refreshes within about 1–2 seconds without toggling camera or entering PiP.
75. Repeated wake: lock/unlock several times and confirm the decoder/camera do not progressively freeze or crash.
76. Selfie landscape orientation: rotate both phones to landscape and verify the small local preview is upright, naturally proportioned, and 16:9 rather than a stretched portrait image rotated right.
77. Selfie portrait orientation: rotate back to portrait and verify the preview becomes 9:16 and remains upright.
78. Fullscreen controls: in normal Video mode confirm the four floating buttons are camera, switch camera, mic, and red hang-up. Tap the video background and verify controls/system bars hide; tap again and verify they return.
79. Floating-control actions: verify camera on/off, camera switch, mic mute/unmute, and intentional hang-up all still work.
80. QL5 long-call regression: leave video running through multiple crypto key epochs and confirm no freeze is correlated with key rotation.

81. Logger privacy: unlock Developer tools, choose Export privacy-safe log, save the text file, and verify it contains technical QuietLink events but no IP address, device/peer name, fingerprint/key, room code, chat text, audio, or image content.
82. Freeze diagnosis: start video, manually lock/unlock either phone repeatedly. Video should resume automatically. If it freezes, wait 5 seconds before changing camera state, then export the log immediately from both phones.
83. Camera-off black: turn Camera off on Phone A. Phone B must immediately show black/VIDEO OFF rather than the last frozen frame. Turn it back on and confirm live video resumes.
84. Landscape remote orientation: rotate Phone A to each landscape direction. Phone B must see Phone A upright, never upside down.
85. Selfie no-stretch: inspect the small self preview in portrait and both landscape directions. A face/circle must keep natural proportions; portrait frame is 9:16 and landscape is 16:9.
86. Video UI flow: enter Video mode and confirm the top menus and mode switcher remain. Tap the embedded video to enter fullscreen floating controls; press Android Back to return to the normal Video screen.
87. Health watchdog: leave a video call running through lock/unlock and several QL5 key-rotation epochs. Confirm no manual camera toggle is required to recover.

81. Fullscreen surface preservation: start a two-way H.264 video call, tap the embedded video to enter fullscreen, then Back to leave fullscreen several times. Both videos must remain live; diagnostic log should show video_fullscreen_enter with preserve_surfaces=1 and must not show remote_destroyed/local_destroyed at each fullscreen transition.
82. Two-way selfie stability: with both cameras on, confirm the small local preview stays upright and unstretched before, during, and after fullscreen transitions.
83. Nearby symmetry: leave both phones on Nearby for at least 10 seconds. Each phone must discover the other without tab switching. Repeat on router Wi-Fi and phone hotspot.
84. Session Back button: verify the top-left Back button works in Voice, Video, Baby, Sleeping Baby Parent, and fullscreen Video without accidentally disconnecting the active service session.
85. Sleeping Baby title: enter Sleeping Baby and verify the Baby Station title changes to “SLEEPING BABY • BABY STATION”.
86. Remote flashlight: from Sleeping Baby Parent toggle LIGHT ON/OFF and verify the Baby Station flashlight follows when the hardware allows it; unsupported hardware must fail safely without ending the call.
87. Remote brightness: from Sleeping Baby Parent toggle BRIGHT ON/OFF and verify the Baby Station QuietLink window becomes bright/restores normal brightness without changing permanent system brightness.
88. Logger regression: clear the privacy-safe log, reproduce a video/discovery issue, export it, and confirm no IP, device name/ID, fingerprint/key, room code, chat text, audio, or video content appears.

89. Update button: on the lobby verify CHECK UPDATE appears next to ? and does not check automatically on app launch.
90. Current version: with the update feed at the same version, CHECK UPDATE must say QuietLink is up to date.
91. New version prompt: publish a feed with a higher versionCode and verify QuietLink shows the new version and asks before downloading.
92. Update integrity: modify the manifest SHA-256 to a wrong value and verify the APK is rejected before PackageInstaller opens.
93. Update signer: test with an APK signed by a different certificate and verify QuietLink rejects it before installation.
94. Unknown source first use: when QuietLink is not approved to install packages, Update opens Android “Allow from this source”; after enabling it and returning, the pending APK automatically proceeds to Android’s update confirmation.
95. In-place update: confirm Android shows Update (not a new-app install) and existing QuietLink data/known devices remain after upgrading.

96. Sleeping Baby local camera: enter Sleeping Baby, confirm the Baby Station camera initially turns off, then press CAMERA OFF on the Baby Station and confirm it changes to CAMERA ON and video resumes at Parent Station.
97. Baby local camera synchronization: toggle Baby camera from Parent Station and confirm the Baby Station CAMERA ON/OFF control follows the real camera state.
98. Baby mic synchronization: mute/unmute the Baby microphone from Parent Station and confirm both the Baby Station status line and the large MIC ON/MIC MUTED button change together.
99. Baby local mic: press the large Baby Station MIC button and confirm Parent Station status follows the same state.

100. Chunked/no-length update download: serve the signed APK without Content-Length and verify QuietLink accepts the stream, enforces the byte cap, verifies SHA/package/version/signature, and reaches PackageInstaller.
101. Updater failure reporting: intentionally break checksum and confirm the dialog reports Stage: checksum / Reason: checksum_mismatch without exposing URL, IP, device identity, certificate data, or other private values.
102. v0.3.31 recovery path: manually install v0.3.33 once, then use CHECK UPDATE for the next higher test release to verify subsequent in-app updates work.

103. Installer handoff: manually install v0.3.35, use CHECK UPDATE against a higher version, and confirm Android's native package installer opens instead of showing “Could not start Android update installer”.
104. Provider isolation: confirm the update APK cannot be browsed as public storage and is exposed only through the non-exported read-only update provider with a temporary URI permission.
105. Lobby cleanup: confirm HOST is absent and QuietLink remains on one line with CHECK UPDATE, ?, and the optional developer button visible.

106. Online dot bootstrap: launch the lobby with internet access and confirm the status dot resolves from checking to red while online-status.json reports onlineCallsAvailable=false.
107. Online detail: tap the red dot and confirm the dialog explains that the service is reachable but internet peer calling is not enabled yet, and that local connections remain available.
108. Online outage fallback: disable internet access while keeping a local LAN/hotspot path available; refresh the dot and confirm it remains/red becomes local-only without disrupting Nearby/LAN/Hotspot discovery or calls.
109. Online privacy: inspect the status request and confirm it is a plain HTTPS GET with no QuietLink identity, room code, peer name, media, chat, or diagnostic fields.
110. Online recovery: restore internet access, press REFRESH, and confirm the dot updates from the server response without restarting QuietLink.

111. Rendezvous server syntax: CI must pass node --check rendezvous/server.js.
112. Dormant online path: with onlineCallsAvailable=false, verify Code-mode hosting/joining behaves exactly as before and no rendezvous registration is attempted.
113. Config gating: set a test status document to onlineCallsAvailable=true with no rendezvousUrl and verify QuietLink stays local-only without a crash.
114. HTTPS-only rendezvous: supply a non-HTTPS rendezvousUrl and verify QuietLink rejects it and stays local-only.
115. Short-lived signaling: run the rendezvous server locally/test-hosted, register host and join peers with the same derived room token, and verify they match without sending the raw six-digit code.
116. Expiry: stop refreshing one registration and verify it disappears after approximately 45 seconds.
117. Privacy/logging: verify Android logs expose only generic rendezvous state/protocol/candidate counts; server /dev exposes aggregate counters and generic logs only.

118. Online path test UI: from the lobby tap the online status dot, choose RUN ONLINE PATH TEST, and confirm a result dialog appears without exposing a public IP address or port.
119. Wi-Fi STUN: run the Online Path Test on normal Wi-Fi and record Candidate discovered / Second STUN confirmation / Mapping stable.
120. Mobile-data STUN: disable Wi-Fi, use mobile data, run the same test, and record the three YES/NO results.
121. Privacy: export diagnostics after the tests and confirm no public IP address, mapped UDP port, raw room code, peer identity, or STUN-mapped endpoint is present.
122. Local regression: with internet disabled, verify Nearby/LAN/Hotspot or Wi-Fi Direct behavior still works as before and the online test failure does not interrupt local discovery.
123. Candidate wiring: when a test rendezvous endpoint is later enabled, verify registration contains one bounded srflx UDP candidate and Android logs report only udp=1/count, never the endpoint itself.


124. Developer rendezvous setup: unlock 🛠, open **Online rendezvous test setup**, enter an HTTPS base hostname, SAVE, close/reopen the dialog, and confirm it remains saved locally.
125. HTTPS-only test URL: try an `http://` URL or a URL containing credentials/query/fragment/path and confirm QuietLink refuses to save/use it.
126. Health probe: with the permanent/temporary rendezvous HTTPS endpoint live, press **TEST HEALTH** and confirm QuietLink reports the rendezvous reachable and its safe build label without displaying/logging the hostname, public IP, or port.
127. Production-dot isolation: configure a developer test rendezvous URL while `online-status.json` still reports `onlineCallsAvailable=false`; confirm the lobby online dot remains red/local-only.
128. Pi API CI: CI must launch the Python Pi server and pass health, host registration, join registration, bidirectional match/candidate return, JSON-only request enforcement, leave, and public `/dev` rejection.
129. Two-network candidate exchange: put Phone A on Wi-Fi and Phone B on mobile data (or otherwise separate networks), configure the same HTTPS test rendezvous URL on both, start/join the same six-digit Code room, and confirm both eventually show **Online test • peer matched • candidate received**. This test does not yet require the call to connect.
130. Rendezvous privacy regression: export privacy-safe diagnostics after test 129 and confirm the endpoint hostname, candidate IP/port, raw six-digit code, peer token, room token, and candidate payload are absent.
131. Local-first regression with test override: with the developer test endpoint configured, put both phones back on the same LAN/hotspot and verify local Code-mode connection still wins/works normally.


132. Code tab HOST regression: open the CODE tab and confirm the bottom action row contains both **HOST** and **JOIN**. HOST must open the existing host-code screen and generate a six-digit room code; JOIN must continue using the entered six-digit code. The obsolete standalone HOST button must remain absent from the main lobby header.


133. Updater signer compatibility fallback: on a device/OEM where `PackageInfo.signingInfo` is unavailable for the installed package or downloaded APK, verify QuietLink falls back to the legacy Android package-signature field, still SHA-256 compares both signer sets, and proceeds only when they match.
134. Signer fallback privacy: confirm diagnostics record only whether the installed/archive signer lookup used the legacy compatibility path; certificate bytes/digests must never be exported.
135. Affected-device recovery: manually install v0.3.42 once over the existing same-package build on a phone that previously reported `Stage: package / Reason: signature_unavailable`, then use CHECK UPDATE on a later release and verify the in-app updater no longer fails at signer discovery.


136. Sleeping Baby white-light mode: with Parent/Baby stations connected in Sleeping Baby mode, press **BRIGHT** on Parent Station and confirm the Baby Station becomes an opaque edge-to-edge white display at maximum app-window brightness, with system bars hidden.
137. White-light camera preservation: while BRIGHT is ON, confirm the Parent Station continues receiving the Baby Station camera feed; toggling BRIGHT must not intentionally tear down the camera/video session.
138. White-light restore: press BRIGHT again and confirm the Baby Station returns to its normal QuietLink UI/brightness/system bars without disconnecting. Also verify Disconnect returns brightness/UI to normal.
139. Compact Baby controls: in normal Baby Parent Station mode, confirm SLEEPING BABY, baby mic, camera, switch-camera, Hold to Talk, mute, and Disconnect controls are compact around their text rather than occupying full-width/fixed-height blocks, leaving more vertical space for video.
140. Baby fullscreen video: tap the Baby camera feed and confirm it expands in place without destroying the video TextureView; Back returns to the compact Baby controls.
141. Baby fullscreen remote controls: while Parent Station Baby video is fullscreen, verify camera toggle, switch camera, and mic controls operate the Baby Station, and Disconnect still ends the session.
142. Updater compatibility confirmation: on the phone that required the one-time manual v0.3.42 install after `signature_unavailable`, use **CHECK UPDATE** to install v0.3.43 and confirm the normal in-app update path now succeeds.


143. Older-phone Baby join camera lifecycle: on the phone that previously lost video when entering Baby mode, host/join Baby mode repeatedly and confirm the camera remains live. Diagnostics should show coalesced `camera_start_queued` generations rather than several overlapping live camera opens.
144. Stale callback isolation: force rapid video surface/mode/camera transitions and verify callbacks from an older camera generation are logged as stale/ignored and never replace the current camera.
145. Camera disconnect recovery: if Camera2 reports a real disconnect/open/session failure while Baby Station video is enabled, confirm QuietLink automatically retries and video returns without toggling CAMERA or restarting the call.
146. Baby state surface preservation: toggle/sync Baby mic, camera, flashlight and BRIGHT states and confirm normal state callbacks do not repeatedly destroy/recreate the video TextureViews.
147. JPEG remote recovery: force or simulate a JPEG/fallback stream stall while remote video is expected; Parent Station should request recovery and the sending phone should perform a bounded camera restart.
148. Stale dark-frame UI: allow a dark frame to arrive and then stop video delivery; after roughly four seconds the Parent Station should replace **VIDEO ON • LOW LIGHT** with **VIDEO STALLED • RECOVERING**.
149. Real low-light regression: keep the camera continuously sending frames in a genuinely dark room and verify **LOW LIGHT** can remain visible without falsely treating the live stream as dead.
150. Older-phone endurance: run Baby mode on the affected older phone for at least 15–30 minutes, including fullscreen enter/exit, BRIGHT on/off, camera switch, camera off/on and screen wake, and confirm the video self-recovers without ending the call.

151. Fresh Baby Station mute isolation: mute a previous Voice/Video session, end it, then start a fresh Baby session with that phone as Baby Station. Confirm Baby microphone starts ON rather than inheriting the old mute.
152. Older-phone Sleeping Baby audio: use the affected older phone as Baby Station, enter Sleeping Baby, speak near it, and verify the Parent Station receives room audio. Swap roles and repeat in both directions.
153. Audio gate resume recovery: switch Parent/Baby roles and toggle Baby mic OFF/ON several times. Diagnostics should show `capture_gate`, `recorder_ready reason=gate_resume`, `recorder_started`, and periodic `tx_flow active=1` rather than a permanently silent capture path.
154. Audio read failure recovery: if the recorder reports a start/read failure, verify QuietLink rebuilds AudioRecord and resumes TX without restarting the call.
155. Audio diagnostic privacy: export/report a log after audio testing. Confirm it contains only categorical lifecycle/flow state and no audio samples/content, IP addresses, room codes, device identity, or chat text.
156. Parent Baby fullscreen controls: tap Parent Station Baby video fullscreen and confirm the generic Video camera/mic icon pill is replaced by text-labelled Sleeping Baby controls.
157. Parent fullscreen completeness: verify Alert sensitivity, HEAR, Baby mic, Baby camera, switch Baby camera, LIGHT, BRIGHT, Sleeping/Exit Sleeping, Hold to Talk, Chat, Battery, Disconnect, and Back all work from fullscreen.
158. Parent fullscreen surface preservation: operate the fullscreen controls repeatedly and verify the camera feed remains live without unnecessary TextureView destruction/recreation.
159. GitHub diagnostic report: Developer tools → Report log to GitHub (or Live diagnostics → Report to GitHub) should prepare the current privacy-safe log, open Android's share sheet with the .txt attached, and OPEN ISSUE should open a pre-filled QuietLink GitHub issue.
160. Diagnostic share isolation: confirm the content URI is read-only, temporary/grant-based, exposes only the generated cached .txt, and does not make the app cache generally browsable.
161. Diagnostic trace focus: run a video call for several minutes and confirm per-frame `rotation_calc` spam is absent while useful `rotation_report`, camera, audio, recovery and surface events remain.

162. Signing migration bridge install: update both existing phones from v0.3.45 to v0.3.46 through CHECK UPDATE and confirm Android treats it as a normal in-place update.
163. Bridge signer equality regression: while both installed/downloaded APKs still use the old signer, confirm updater verification remains successful.
164. Rotated signer allowlist test: when testing the future rotated APK, updater must accept only the pinned old signer → pinned v2 signer transition.
165. Rotated signer rejection: an APK with the correct package/version/checksum but a signer other than the pinned current signer or pinned v2 signer must fail with `signature_mismatch`.
166. Modern lineage check: on a device with `SigningInfo`, the rotated archive must report the v2 signer as current and include both old and v2 certificates in signing history.
167. Legacy signer compatibility: on the older phone if modern signer data remains unavailable, the bridge may allow exactly old-current → v2-current; Android installer must still approve the APK lineage before installation.

168. Production rendezvous auto-migration: on a phone that previously saved `https://rendezvousquietlinkvikman.dpdns.org` as the developer rendezvous override, upgrade to v0.3.48 and start a CODE host/join session. Confirm QuietLink automatically promotes that exact URL to production behavior rather than showing TEST-mode status.
169. Local-first timing: with both phones on the same LAN, start the same CODE room and confirm LAN discovery begins immediately; production rendezvous must not prevent the local connection and should only start after the short ~1.5 s head start if the session is still unconnected.
170. Production rendezvous normal flow: put Phone A on Wi-Fi and Phone B on mobile data, use the same six-digit CODE without configuring any developer rendezvous override, and confirm the waiting UI reaches **Internet peer found • checking connection paths…** when rendezvous matches them.
171. Permanent endpoint publication: fetch the published QuietLink `online-status.json` after the release and confirm `rendezvousUrl` is `https://rendezvousquietlinkvikman.dpdns.org` while `onlineCallsAvailable` remains false during validation.
172. Production rendezvous outage regression: stop/unplug the Pi or Cloudflare tunnel, then put both phones on the same LAN and verify CODE calling still succeeds locally despite rendezvous failure.
173. Developer override preservation: save a different valid HTTPS developer rendezvous URL and confirm v0.3.48 still uses that override in TEST mode instead of silently replacing it with production.
174. Production rendezvous privacy: export diagnostics after test 170 and verify no public IP, mapped port, raw six-digit code, room token, peer token, candidate payload, or production endpoint hostname is exposed.

175. v0.3.48 production rendezvous confirmation: with Phone A on Wi-Fi and Phone B on mobile data, no developer override, same CODE host/join must reach **Internet peer found • checking connection paths…**. This was user-confirmed before v0.3.49 implementation.
176. Pi relay reliability smoke: CI must verify relay send, duplicate-send idempotence, non-consuming poll, ACK, duplicate ACK, and late duplicate retry after ACK.
177. Pi relay deployment: after updating the permanent Pi, local and public /health must report phase **control-relay-test** and build **pi-python-control-relay**.
178. v0.3.49 internet Voice: Phone A on Wi-Fi and Phone B on mobile data, no developer override, same CODE host/join. Both phones should reach **Connected • Voice • Online**.
179. QL5 internet authentication: both phones must show the same verification phrase. A wrong six-digit code must still fail authentication.
180. Direct UDP audio: speak both ways and verify bidirectional audio. Diagnostics may show TX/RX counters but must not expose candidate addresses or ports.
181. Relayed encrypted control/chat: send chat both ways and toggle microphone state; control operations must remain responsive while the QL5 stream uses the opaque relay.
182. Internet interruption checkpoint: interrupt the online path after connection. v0.3.49 is not expected to provide internet self-healing yet; it must fail safely without weakening authentication.
183. Local-first regression: with both phones on the same LAN, CODE must still connect locally whether the Pi/control relay is healthy or unavailable.
184. Internet privacy regression: exported diagnostics must contain no public IP, mapped port, raw code, room/peer token, candidate payload, relay payload, session key, chat text, audio, or video content.
185. UDP fallback decision: if both phones reach **Connected • Voice • Online** but audio RX remains zero, record the privacy-safe diagnostics. That indicates the direct UDP NAT path failed and the next checkpoint is opaque encrypted media relay fallback.

186. Older-phone rotated signer regression: from the older phone already on a v2-signed QuietLink build, CHECK UPDATE to v0.3.50 must not fail with **Stage: package / Reason: signature_mismatch** merely because installed/archive signing histories have different shapes.
187. Pinned-current-signer compatibility: the compatibility path may pass only when both installed and archive current signer resolve to the exact pinned QuietLink v2 signer; unknown/multiple signers must still fail closed.
188. Android installer lineage guard: after QuietLink's pre-install signer check passes, Android must still present a normal in-place Update and preserve existing app data.
189. Signer diagnostic privacy: exported diagnostics may include only categorical flags/counts such as current-v2 yes/no, legacy-source yes/no, and history counts; certificate bytes/digests must remain absent.

190. Rotation lab access: unlock developer mode by tapping the About QuietLink title three times, open 🛠, and verify **Video rotation lab** is available both idle and during a real Video/Baby session.
191. Production baseline: select **Production v0.3.50 baseline** / Reset production defaults and verify behavior matches v0.3.50.
192. Sender formula matrix: with H.264 active, test Current QuietLink, Android relative, WebRTC/JPEG-style, and Sensor-only using front and back cameras in portrait, landscape-left, landscape-right, and (where supported) 180°.
193. TextureView matrix: independently test Stream rotation, Display-only, No extra rotation, and Inverse stream rotation. Confirm this changes only the local H.264 self-preview transform.
194. Mirror isolation: with the front camera, toggle Local front mirror and verify it does not alter transmitted remote orientation; repeat with the back camera and verify the dev mirror gate uses the reported local facing.
195. Rotation source matrix: compare Display rotation with Physical orientation sensor. Physical-sensor mode may rotate through system rotation lock by design; Reset production must restore lock-respecting behavior.
196. Per-frame H.264 metadata: enable TX on the sending v0.3.51 phone and RX on the receiving v0.3.51 phone. Rotate between portrait/landscape while video is flowing and verify receiver orientation follows frame-bound quarter-turn metadata without requiring a reconnect.
197. Metadata backward compatibility: with per-frame TX enabled toward a pre-v0.3.51 H.264 receiver, video packets must remain parseable because the header size/version is unchanged and unknown rotation flag bits are ignored.
198. Manual sender override: test Auto, 0°, 90°, 180°, and 270° and verify each produces the corresponding receiver orientation signal.
199. Manual receiver correction: test Direct vs Inverse and +0/+90/+180/+270° offsets without reconnecting.
200. Codec comparison: toggle Force JPEG during a connected video call and verify both peers renegotiate to JPEG; toggle it back off and verify the H.264 capability request/response can restore hardware H.264 when supported.
201. Privacy regression: exported logs may record formula labels, front/back, display/sensor/result quarter-turns, and lab toggle state, but must contain no frame contents, screenshots, camera images, public network endpoints, room tokens, or codes.
202. Orientation selection report: record which preset/settings are correct for each phone/camera/posture before changing production defaults.

203. v0.3.52 older-phone updater regression: from the older phone already manually updated to a v2-signed build, CHECK UPDATE must no longer fail merely because installed signingInfo is modern while archive signingInfo falls back to legacy GET_SIGNATURES.
204. Legacy archive policy: approve only when installed current signer is the exact pinned v2 certificate, installed history contains both pinned old+v2 certificates, archive legacy signatures contains exactly the pinned original certificate, and package/version/checksum checks already passed.
205. Legacy archive fail-closed cases: reject unknown archive legacy signer, missing old+v2 installed history, multiple current signers, installed legacy mode, wrong package, non-newer version, or checksum mismatch.
206. Platform lineage guard: after the compatibility pre-check passes, Android's package installer must still enforce the authenticated old→v2 signing lineage and complete an in-place update without data loss.
207. Older-phone forward-update check: after one manual in-place install of v0.3.52, use CHECK UPDATE on the next release to confirm this compatibility path works without another manual APK.

208. v0.3.53 updater continuity: with the older phone on v0.3.52, use **CHECK UPDATE** to install v0.3.53. This is the first forward-update validation of the documented legacy archive oldest-signer compatibility path; do not manually install first unless CHECK UPDATE fails.
209. Baby Station continuous audio regression: connect Sleeping Baby with the older phone as Baby Station, enable Baby microphone, and verify recorder/capture TX remains active for at least 10 minutes unless the user explicitly disables the mic or changes roles.
210. Role-swap guard: tapping **SWAP** once must open a confirmation and must not change roles until the user presses the dialog's SWAP action.
211. Cancelled role swap: tap SWAP then Cancel; Baby Station must remain Baby Station and continuous audio TX must remain active.
212. Confirmed Baby→Parent swap: confirm the dialog; logs must record `baby_role_swap_request source=local from=baby to=parent` followed by `baby_role_changed`, and continuous Baby microphone capture may then close by design.
213. Peer role-swap logging: when the other phone confirms a role swap, the receiving phone must record `source=peer` before its resulting role transition.
214. Privacy regression: role logs may contain only source/local role names and tell-peer state; they must contain no device IDs, peer names, addresses, codes, media, or keys.

215. v0.3.54 updater continuity: on the older phone running v0.3.53, install v0.3.54 through **CHECK UPDATE** rather than manual APK.
216. Older-Android rotation lab UI: open 🛠 -> Video rotation lab on the older phone and verify the selectable list is visible immediately, including Preset, sender formula, rotation source, local preview transform, mirror, per-frame TX/RX, forced sender rotation, remote handling, codec test, instructions, and reset.
217. Rotation lab title: verify it shows **PRODUCTION** before any experiment and **TEST** after changing a setting/preset.
218. Rotation-lab dialog regression: no device should show only explanatory text with an empty controls area.
219. Existing v0.3.53 Sleeping Baby confirmation: keep older phone as Baby Station and verify continuous audio still works after updating to v0.3.54.

220. v0.3.55 updater continuity: install on both phones through **CHECK UPDATE** from v0.3.54.
221. Quick-panel access: during a live Video/Baby session open 🛠 -> **ROTATE ONLY • compact live panel** and verify it appears at the bottom rather than covering most of the video.
222. Quick-panel persistence: tap several sender/remote/preview options. The same panel must remain open after every tap and must preserve its inner scroll position.
223. Sender quick choices: Auto / 0° / 90° / 180° / 270° must apply live.
224. Receiver quick choices: Direct/Inverse and +0°/+90°/+180°/+270° must apply live without reconnecting.
225. Formula/preview/source quick choices: QL/Android/WebRTC/Sensor, Stream/Display/None/Inverse, and Display/Physical must match the full lab settings.
226. Frame/mirror quick toggles: per-frame TX, per-frame RX and Mirror must toggle live and visibly update their selected state without dismissing the panel.
227. Video visibility: while the compact panel is open, enough of the remote/local video must remain visible to judge orientation immediately.
228. Small self-preview aspect: test portrait and landscape camera output and verify the small local preview preserves facial/object proportions; it may letterbox rather than crop, but must not stretch.
229. Self-preview rotation sizing: when local preview rotation changes between portrait and landscape quarter turns, the small preview box must switch between portrait-like 90x160 and landscape-like 160x90 proportions.
230. Fullscreen round trip: enter and leave in-place fullscreen; the small local preview must return to bottom-right inline placement with correct aspect.
231. Orientation result capture: retain the working portrait settings for each phone and continue landscape-left/landscape-right testing before promoting any experiment to production defaults.

232. v0.3.56 internet CODE regression: with Wi-Fi OFF on one phone and mobile data ON, CODE HOST/JOIN must be allowed to start; QuietLink must not redirect to Wi-Fi settings or require a hotspot.
233. CODE warning scope: while CODE tab is selected, foreground/resume and pressing HOST/JOIN must not show the Wi-Fi/hotspot warning. Nearby/Known must still show their local-network warning when appropriate.
234. CODE permission independence: a CODE session must not be blocked solely because nearby-Wi-Fi/location permission is unavailable; microphone/camera/notification permissions remain mode-specific as required.
235. Local-first regression: with both phones on the same LAN/hotspot, CODE must still prefer the existing local path rather than forcing rendezvous.
236. Internet transport security regression: QL5 handshake/control/chat and encrypted media behavior are unchanged; no plaintext media/chat/session keys may be introduced by this UI fix.
237. Fullscreen rotation access: enter fullscreen Video and Baby views and verify a developer **ROTATE** button opens the compact panel without leaving fullscreen.
238. Fullscreen rotation live apply: change sender/receiver rotation while fullscreen and verify the visible fullscreen video updates immediately; repeat after rotating the device and after exiting/re-entering fullscreen.
239. Compact panel close: verify the visible **X** closes the bottom panel in inline and fullscreen modes.
240. Aspect controls: verify Local Aspect, Remote Aspect, and Fullscreen Aspect each expose Auto/16:9/4:3/3:2/1:1/Stretch and apply without dismissing the compact panel.
241. Local aspect independence: changing Local Aspect must alter only the self-preview presentation/box; front/back camera switching must keep the selected local aspect option.
242. Remote inline aspect independence: changing Remote Aspect must affect the main non-fullscreen remote TextureView without changing Fullscreen Aspect.
243. Fullscreen aspect independence: while fullscreen, Fullscreen Aspect must affect the remote fullscreen presentation; returning inline must restore the Remote Aspect presentation.
244. Stretch diagnostic: Stretch is intentionally allowed to distort; all non-Stretch aspect modes must preserve a fixed target ratio and may letterbox rather than fill.
245. Mini-preview distortion test: use a face/circular object and compare 16:9, 4:3, 3:2, 1:1 until circles/facial proportions are visually correct on each phone; record the working local ratio.
246. Fullscreen orientation matrix: portrait, landscape-left and landscape-right must be testable directly from the fullscreen ROTATE panel without closing fullscreen.

247. v0.3.57 updater continuity: install on both phones through CHECK UPDATE from v0.3.56.
248. Window identity: in a live visual session with developer mode enabled, the large incoming video must show badge **1** and the mini/self preview must show badge **2**.
249. Window dropdown: open ROTATE/VIDEO TUNE and switch the Window selector between **1 • MAIN / INCOMING** and **2 • MINI / MY CAMERA**; the visible control set must change without closing the panel.
250. Window 1 isolation: change incoming rotation offset/direction/aspect and confirm the main incoming window changes while the mini/self window settings remain unchanged.
251. Window 1 fullscreen continuity: enter fullscreen and verify the same Window 1 rotation/aspect setting remains active; changes made fullscreen must still apply after returning inline.
252. Window 2 isolation: change mini rotation base/offset/aspect/mirror and confirm the mini/self window changes without changing Window 1 incoming presentation.
253. Window 2 manual offset: +0/+90/+180/+270 must independently fine-tune the mini preview after its selected Stream/Display/None/Inverse base.
254. Portrait aspect menu: Window 2 must offer at least 9:16, 3:4, 2:3 and 4:5 in addition to landscape reciprocal ratios.
255. Literal portrait ratio: select **9:16** for Window 2 and verify its container remains tall 9:16 even when the effective video rotation is 90/270; it must not silently become 16:9.
256. Aspect sweep: compare Auto, 16:9, 9:16, 4:3, 3:4, 3:2, 2:3, 5:4, 4:5 and 1:1 using a face/circle until proportions are correct. Stretch is diagnostic only.
257. Sender section clarity: Window 2 must visibly separate the controls that affect the local mini preview from the **SEND THIS CAMERA TO THE OTHER PHONE** controls.
258. Existing v0.3.56 internet regression checkpoint remains required: Wi-Fi-off/mobile-data CODE must start without a Wi-Fi/hotspot prerequisite.

259. v0.3.58 update continuity: install on both phones through CHECK UPDATE from v0.3.57.
260. Scrollable aspect selector: open VIDEO TUNE -> Window 1 and Window 2 aspect controls on both the newest and older phone; the ratio list must scroll through Auto, 16:9, 9:16, 4:3, 3:4, 3:2, 2:3, 5:4, 4:5, 1:1 and Stretch.
261. Profile slots: verify separate save state for VIDEO INLINE, VIDEO FULLSCREEN, BABY INLINE and BABY FULLSCREEN.
262. Inline/fullscreen independence: choose visibly different Window 1 rotation/aspect settings inline vs fullscreen, SAVE CURRENT in each slot, leave/re-enter each presentation and verify the matching saved profile auto-loads.
263. Video/Baby independence: save a VIDEO INLINE profile, then a different BABY INLINE profile; switching modes must load the correct mode-specific profile without overwriting the other.
264. Profile content: verify save/load includes Window 1 direction/offset/aspect/frame RX, Window 2 preview base/offset/aspect/mirror, sender rotation/formula/source/frame TX and codec experiment state.
265. Profile report: SHOW / COPY PROFILE must produce a selectable/copyable summary that accurately states Window 1, Window 2 and sender values without identifiers, network addresses, codes or keys.
266. Chat diagnostic UI: with developer mode unlocked and a live connection, chat must show **SEND MY LOG**.
267. Diagnostic attachment send: tap SEND MY LOG on Phone A; Phone B must see one QuietLink-diagnostic-log.txt attachment, not base64/chunk messages. Phone A must also show the sent .txt attachment.
268. Diagnostic attachment open: tapping the attachment must open readable UTF-8 diagnostic text inside QuietLink.
269. Diagnostic attachment privacy: received text must preserve QuietLog's defensive exclusions for IPs, peer/device names, IDs, fingerprints/keys, room codes, chat contents and audio/video contents.
270. Diagnostic attachment integrity: transfers are SHA-256 verified; malformed/out-of-order/oversized or digest-mismatched transfers must never appear as a completed attachment.
271. Diagnostic attachment bounds: a privacy-safe log up to the configured 900 KiB transfer limit must use bounded 4 KiB chunks and remain below CryptoChannel's encrypted chat-frame plaintext limit.
272. Bad-link behavior: interrupt a log transfer mid-send. The partial file must not appear as a completed attachment; ordinary chat/control/session recovery must remain functional.
273. Wi-Fi Direct regression: with no shared router and required nearby permission granted, CODE should be allowed to reach Android Wi-Fi Direct fallback without requiring a manually-created hotspot; QL5 authentication remains mandatory after the P2P group forms.

274. v0.3.59 updater continuity: update both phones from v0.3.58 through CHECK UPDATE without uninstalling.
275. Transfer crash regression: connect both phones, open chat on Phone A and tap SEND LOG + PROFILES. Neither activity/service may crash, close, restart, or force the call into reconnect solely because the file transfer is running.
276. Transfer progress: sender must show a compact Sending diagnostic log progress indicator advancing from 0 toward 100; receiver must show Receiving diagnostic log progress when chat is open. Completed/failed state must be clear rather than leaving the user guessing.
277. Transfer speed: compare the same diagnostic trace with v0.3.58; v0.3.59 should send gzip-compressed wire bytes and normally require materially fewer encrypted chunks for repetitive log data.
278. Profile inclusion: save at least VIDEO INLINE and VIDEO FULLSCREEN profiles, SEND LOG + PROFILES, open the received .txt and confirm the Saved video calibration profiles section includes those exact Window 1 / Window 2 / Sender summaries.
279. Transfer failure isolation: interrupt/impair the link during the diagnostic transfer. The partial attachment must not appear complete, but an optional file-transfer exception must not itself call handleConnectionLoss; normal heartbeat/control detection remains authoritative.
280. Transfer integrity/bounds: receiver must reject bad order, mismatched compressed/raw size, unsupported encoding, oversized compressed/decompressed data, wrong chunk count or SHA-256 mismatch.
281. Crash fingerprint: after a controlled developer-only uncaught crash test (if performed), the next privacy-safe export may contain only exception class + QuietLink source site/line, never Throwable message, IP, peer/device identity, room code, keys, chat/media content.
282. Wi-Fi Direct optional permission: with Wi-Fi radio ON and Nearby Wi-Fi Devices (Android 13+) or Fine Location (Android 12 and below) not granted, starting CODE should request that optional permission. Denying it must still let internet/LAN CODE continue; granting it must enable P2P fallback.
283. Wi-Fi Direct stale-state regression: after a prior P2P call or aborted P2P negotiation, start a new CODE session. Stale group/request/service state must be cleaned and must not permanently leave createGroup/connect in BUSY.
284. Wi-Fi Direct connect-failure retry: force one WifiP2pManager.connect failure/BUSY if reproducible. The next matching service response/discovery cycle must be allowed to connect; expectedPort must not remain permanently locked.
285. Wi-Fi Direct one-shot regression: arrange for a LAN socket attempt to be active at the 8-second fallback point. Wi-Fi Direct discovery must still start and remain available after the LAN attempt fails.
286. Wi-Fi Direct no-router checkpoint: enable Wi-Fi radio on both phones but do not join the same router and do not create a hotspot. With required P2P permission granted and internet path unavailable/disabled for the test, CODE should form a Wi-Fi Direct group and reach QL5 encrypted session establishment.
287. Wi-Fi Direct privacy logs: diagnostic events may include state/reason/attempt counts only; do not log peer MAC/device address, group-owner IP, room code or derived room token.

288. v0.3.60 Developer tools: verify **Export log + profiles (.txt)** opens Android's document picker and writes a readable privacy-safe text file.
289. Developer tools: verify **Export profiles only (.txt)** writes a compact calibration report containing current display/reported/resolved rotations plus all saved profile slots.
290. Chat: while connected, verify both **EXPORT LOG + PROFILES** and **EXPORT PROFILES** are available independently of SEND LOG + PROFILES.
291. Export independence: local export must still work when the peer connection is poor or after peer transfer previously failed; it must not use the network transport.
292. Profile evidence: save visibly different INLINE and FULLSCREEN offsets/aspects, export profiles, and verify the report preserves the exact values for later root-cause analysis.
293. Privacy regression: neither export may contain IP/MAC addresses, room codes, peer/device names, fingerprints/keys, chat text, or media content.

294. v0.3.61 updater continuity: update both phones from v0.3.60 through CHECK UPDATE; saved video calibration profiles must remain present.
295. Portrait lock: rotate each phone physically to landscape-left and landscape-right during lobby, Voice, Video, Baby, fullscreen Video, and chat. QuietLink activity/video presentation must remain portrait and must not rebuild into a landscape UI.
296. Portrait profile preservation: open VIDEO INLINE and FULLSCREEN after update and verify the previously saved per-device profile still loads and portrait orientation remains correct.
297. Chat attachment download: receive QuietLink-diagnostic-log.txt, tap it, then tap DOWNLOAD. Android's document picker must open and save a readable .txt outside QuietLink's cache.
298. Chat download isolation: DOWNLOAD must copy the already-received cached attachment locally; it must not trigger another peer transfer or require the peer to remain connected.
299. P2P state gating Android 10+: with Wi-Fi radio on, start a no-router CODE test. If requestP2pState initially reports disabled, QuietLink must show a waiting/startup status and must not immediately spam createGroup/discoverServices BUSY failures.
300. P2P delayed-enable regression: if Android reports disabled then enabled several seconds later, pending host group creation or join discovery must start immediately after enabled without restarting the QuietLink session.
301. P2P radio-off status: with Wi-Fi radio actually off, status must clearly say Wi-Fi Direct is waiting for the Wi-Fi radio rather than claiming an unsupported/permission failure; LAN/internet CODE remain eligible.
302. P2P privacy: wifi_direct_state may log enabled, wifi_radio and source=query|broadcast only; no MAC, peer name, address, room code or key material.
303. Orientation evidence checkpoint: keep the uploaded old/new phone profile offsets documented; do not replace them with guessed defaults until the canonical camera transform architecture is addressed.

304. v0.3.62 update continuity: update each calibrated phone from v0.3.61 without uninstalling. Existing VIDEO/BABY saved profiles must remain stored.
305. Canonical capability negotiation: connect two v0.3.62 phones in Video mode with H.264 available. Diagnostics must log peer canonical capability and `SessionBus.canonicalVideoRotation` must become active only when ROT_CW1 is present on both effective sides.
306. Mixed-version compatibility: connect v0.3.62 to a pre-v0.3.62 peer. Canonical mode must stay off; legacy saved profile behavior must remain available and unknown ROT_CW1 tokens must not break older peers.
307. Fresh fourth-phone test: on a phone with no saved rotation profile, install v0.3.62, connect to another v0.3.62 phone, enter Video in portrait, and do not open Video Tune. Incoming main video and local mini preview should both start upright automatically.
308. Front-camera portrait: canonical sender logs should show sensor/display/facing plus `formula=Canonical clockwise canonical=1`. Common front sensor=270/display=0 should resolve to a 90-degree clockwise remote-display correction.
309. Back-camera portrait: switch to the rear camera. Common back sensor=90/display=0 should resolve to a 90-degree clockwise remote-display correction and the remote view must stay upright.
310. Local TextureView double-rotation regression: in canonical mode, local preview transform must use only inverse display rotation (0 degrees under portrait lock), while its aspect derives from canonical source orientation. It must not apply the H.264 sensor angle again.
311. Canonical aspect: local mini and remote main views must preserve proportions with AUTO. A 90/270-degree canonical stream must be treated as portrait 9:16 without manual 9:16 selection or stretching.
312. Per-frame metadata: canonical H.264 must transmit and accept rotation metadata regardless of the legacy FRAME TX/RX developer toggles, so the angle follows the access unit that needs it.
313. Rotate-and-crop opt-out API31+: if NONE is supported, diagnostic events should show `rotate_crop_request canonical=1 none_supported=1` and a one-time capture result. A request/result failure must not crash or disable video.
314. Legacy override: while two v0.3.62 peers are connected, select **LEGACY PROFILE • FORCED**. Both ends must renegotiate without ROT_CW1, canonical state must turn off, and the existing saved per-device profile must become effective. Turning the override back off must allow canonical mode to renegotiate.
315. Portrait lock remains: physically rotate the phone landscape-left/right during the canonical test; the QuietLink activity stays portrait.
316. Calibration export: exported profile/log text must state `Canonical H.264 rotation active: YES/NO` so fourth-phone results can be diagnosed without screenshots.
317. Privacy: canonical capability/orientation logs may contain sensor/display angles, camera facing category, rotate/crop mode, and capability booleans only; never camera content, network identifiers, pairing code, keys or peer identity.



318. v0.3.63 updater continuity: update v0.3.62 phones in place through CHECK UPDATE where possible; package id, data, known devices and saved legacy video profiles must remain intact.
319. ROT_REL2 negotiation: connect two v0.3.63 phones with H.264. Diagnostics must show canonical capability active only for the new ROT_REL2 contract. A v0.3.63 ↔ v0.3.62 pair must stay on legacy behavior rather than activating mismatched canonical semantics.
320. Fresh fourth-phone front camera: with no saved profile and no Video Tune changes, front sensor=270/display=0 should log formula=Canonical relative and result=270; incoming main video and local mini preview must be upright and naturally proportioned.
321. Fresh fourth-phone rear camera: switch to rear camera. A common rear sensor=90/display=0 should log result=90 and remain upright remotely without manual offsets.
322. Canonical fullscreen continuity: enter/leave fullscreen repeatedly on the fourth phone. Front/rear orientation and Auto aspect must remain correct; API31+ rotate/crop NONE request/result must not regress.
323. Same-router CODE route: put both phones on the same ordinary Wi-Fi router, HOST/JOIN the same code, and confirm the encrypted session establishes through LAN before the 8-second Wi-Fi Direct fallback. The join-side privacy-safe log should show lan_candidate then code_connect_success path=lan.
324. Active room probe: on same Wi-Fi, confirm the joiner can emit udp_probe_tx kind=room and the host can receive the query/reply without exposing the opaque room token or six-digit code in logs.
325. NSD conflict suffix: if Android renames the advertised service to an expected conflict form such as "(2)", the matching room must still resolve/connect rather than being rejected by strict service-name equality.
326. Wi-Fi Direct fallback regression: make infrastructure LAN unavailable while keeping Wi-Fi/P2P usable; CODE must still fall back to Wi-Fi Direct and establish QL5 normally after the local-first head start.
327. LAN-route privacy: new lan_candidate/code_connect diagnostics may contain only source/path/generic failure class. They must never contain IP/MAC, peer name, room id/token, six-digit code, key/fingerprint or media/chat content.


328. v0.3.64 updater continuity: update from v0.3.63 in place; package data, known devices, saved calibration profiles and signing continuity must remain intact.
329. Quick App Test access: during a real active call with developer mode unlocked, open 🛠 and confirm **QUICK APP TEST • 6-second scan** is the first live-session option.
330. Passive-scan safety: start Quick App Test and confirm it does not change mic mute, camera state/facing, listening state, Baby role/settings, chat contents, fullscreen state, or connection route.
331. Quick App Test healthy Voice: leave a stable Voice call running for at least 10 seconds, run the scan, and confirm session/heartbeat/audio-flow rows are populated and no video-only row is treated as a required failure.
332. Quick App Test healthy Video: run during H.264 Video with both cameras expected. Confirm codec, TX/RX packet flow, rendered FPS, surfaces, normalized-orientation state, rotation metadata and queue/loss rows appear.
333. Quick App Test Baby: run once from Parent and once from Baby Station. Confirm the report identifies the role and does not require Parent continuous microphone TX when PTT is idle.
334. Quick App Test copy: tap COPY REPORT after completion and paste it into a text field. It must contain version, PASS/WARN/FAIL/N/A rows and manual spot checks, but no peer name, IP/MAC, room code/token, key/fingerprint, chat text or media content.
335. Quick App Test fault visibility: during a deliberate network interruption/recovery, run the scan. Reconnecting/heartbeat/recovery rows must visibly WARN/FAIL rather than producing an all-pass result.
336. Quick App Test repeat: tap RUN AGAIN without leaving the call and confirm a fresh 6-second sample is collected rather than reusing the previous counters.
337. CI evaluator gate: GitHub Actions must compile/run `DevQuickTestSelfTest` before the Android build and fail the workflow if the evaluator's healthy/broken fixtures no longer behave as expected.
338. v0.3.64 rotation regression: repeat tests 320-322; ROT_REL2 front/rear/fullscreen behavior must remain unchanged by the developer-test addition.
339. v0.3.64 same-WiFi regression: repeat tests 323-327; LAN must still win before Wi-Fi Direct when both phones share a normal router.


340. v0.3.65 updater continuity: update from v0.3.64 in place; package data, known devices, calibration profiles, latest Quick App Test report and signing continuity must remain intact.
341. Quick App Test footer visibility: on the smallest test phone, run the in-call Quick App Test and confirm RUN AGAIN, SAVE .TXT, COPY and CLOSE remain visible without scrolling to an unreachable area.
342. Quick App Test persistence: complete a scan, close the dialog, leave/disconnect the call, reopen Developer tools and confirm Export latest Quick App Test (.txt) is still available.
343. Quick App Test file export: save QuietLink-quick-app-test.txt through Android ACTION_CREATE_DOCUMENT and confirm it contains generation time, app version, PASS/WARN/FAIL/N/A results and manual spot checks.
344. Quick App Test privacy: exported report must contain no peer/device name, IP/MAC, room code/token, fingerprint/key, chat text or media content.
345. Combined export: after at least one scan, Export log + profiles (.txt) must include a LATEST QUICK APP TEST section in addition to calibration + trace data.
346. Version marker: start v0.3.65 and confirm privacy-safe diagnostics contain APP version_start version=0.3.65 without device/network identifiers.
347. Wi-Fi Direct radio-off CTA: start a no-router CODE session with one phone's Wi-Fi radio OFF. QuietLink must show TURN WI-FI ON FOR DIRECT and the status must clarify that no router is required.
348. Wi-Fi panel handoff: tap TURN WI-FI ON FOR DIRECT, enable Wi-Fi in Android's panel, return to QuietLink and confirm the existing P2P retry loop continues without restarting the pairing code/session.
349. No-router Direct regression: with Wi-Fi radio ON on both phones but neither joined to a router, HOST/JOIN the same CODE and confirm Wi-Fi Direct establishes the QL5 session.
350. Same-router LAN regression: repeat the v0.3.64 room-probe test and confirm LAN still connects before the 8-second Wi-Fi Direct fallback.
351. Core-change guard: v0.3.65 must not alter QL5 protocol, media framing, LanDiscovery selection, WifiDirectHelper discovery/connect retry algorithm, manual-disconnect guard or recovery ownership beyond the radio-off status wording/UI handoff.
