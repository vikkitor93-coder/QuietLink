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
