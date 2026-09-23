# QuietLink milestones

Updated: 2026-09-23  
Current release: **v0.3.50**  
Source of truth: **GitHub main**

## Milestone guide

1. ✅ Self-healing reconnect

2. ✅ Live diagnostics

3. ✅ Adaptive video

4. ✅ Known-device management + request expiry

5. ✅ Background watchdog/recovery
   - ✅ Hotspot/LAN support added
   - ✅ Older-device Camera2 restart serialization/debounce
   - ✅ Automatic Camera2 disconnect/session recovery
   - ✅ Baby state updates preserve video surfaces
   - ✅ Remote JPEG stall requests sender-side camera recovery
   - ✅ v0.3.44 camera recovery verified on both phones (swap + on/off)
   - ✅ Fresh Baby Station no longer inherits an old mute
   - ✅ AudioRecord gate-resume rebuild/self-recovery implemented
   - ✅ Privacy-safe audio lifecycle/flow diagnostics added
   - ⏳ Verify older-phone Sleeping Baby room audio in v0.3.45

6. ✅ Security hardening
   - ✅ QL5 session crypto hardening
   - ✅ Full reachable-history credential audit completed
   - ✅ Audit found only the exposed Android signing keystore/password
   - ✅ New v2 signing key generated outside GitHub
   - ✅ Four v2 GitHub Actions secrets added and verified by CI
   - ✅ Signing-key migration bridge pins exact old→v2 transition
   - ✅ Android old→v2 proof-of-rotation lineage generated and verified
   - ✅ Future v3 builds verified with v2 key + public lineage only
   - ✅ Old keystore/password removed from current main
   - ✅ All live feature branches fast-forwarded to cleaned main
   - ✅ Normal CI now signs only with secret-held v2 key
   - ✅ v0.3.46 bridge installed on both phones
   - ✅ v0.3.47 v2 signer-rotation release published
   - ✅ Both phones updated in place to v2 signer
   - ✅ All five live branches rewritten to sanitized history
   - ✅ Legacy APK/source artifacts purged
   - ✅ Post-purge full-history audit: zero credential-pattern findings
   - ✅ v0.3.50 older-OEM rotated-v2 signer history-shape compatibility fix

7. ⏳ Online P2P + rendezvous
   - ✅ Online availability/status dot with helpful local-only fallback
   - ✅ Android rendezvous signaling client
   - ✅ Standalone short-lived rendezvous server implementation
   - ✅ Opaque room token derived from the pairing secret; raw six-digit code is not sent to the server
   - ✅ 45-second expiring presence/register/poll/leave signaling contract
   - ✅ HTTPS-only activation and local-first gating
   - ✅ v0.3.38 Android build, crypto smoke test, and rendezvous server syntax validation passed
   - ✅ Public STUN / NAT candidate discovery implemented
   - ✅ Candidate publishing wired through rendezvous
   - ✅ Wi-Fi Online Path Test passed: candidate YES / second STUN YES / stable mapping YES
   - ✅ Mobile-data Online Path Test passed: candidate YES / second STUN YES / stable mapping YES
   - ✅ Raspberry Pi local rendezvous verified healthy on Bullseye armhf / Python 3.9.2
   - ✅ Pi hardened server updated on test Raspberry Pi
   - ✅ Permanent delegated domain acquired: rendezvousquietlinkvikman.dpdns.org
   - ✅ Cloudflare zone onboarding accepted; DNS review reached successfully
   - ✅ DigitalPlat nameservers changed to Cloudflare-assigned nameservers
   - ✅ Cloudflare zone active
   - ✅ Temporary Cloudflare Quick Tunnel reached the Pi successfully
   - ✅ Permanent Cloudflare Tunnel installed as a Pi systemd service
   - ✅ Permanent published route: rendezvousquietlinkvikman.dpdns.org → 127.0.0.1:8787
   - ✅ Public HTTPS /health verified
   - ✅ Developer-only local rendezvous URL override implemented
   - ✅ In-app HTTPS rendezvous health test implemented
   - ✅ Test-mode candidate-match status visible without endpoint disclosure
   - ✅ Pi public /dev disabled by default; JSON-only API and security headers added
   - ✅ CI real host/join/candidate exchange smoke test added
   - ✅ Live two-peer candidate exchange against the permanent Pi rendezvous
   - ✅ Production rendezvous URL published to normal QuietLink configuration
   - ✅ Local-first 1.5 s rendezvous fallback integration implemented
   - ✅ Saved developer override for the official URL auto-migrates to production behavior
   - ✅ v0.3.48 normal CODE flow validated across Wi-Fi ↔ mobile data with no developer override
   - ✅ Reliable opaque QL5 control relay implemented on Android + rendezvous server
   - ✅ Relay sequencing / ACK / deduplication / bounded-backlog smoke coverage
   - ✅ Direct encrypted UDP media candidate path + anonymous NAT warm-up implemented
   - ⏳ Deploy v0.3.49 control-relay API to permanent Pi
   - ⏳ Validate v0.3.49 encrypted cross-network Voice + bidirectional audio
   - ⏳ Direct internet P2P dialing/acceptance validated on real networks
   - ⏳ Internet-path self-healing recovery
   - ⏳ Encrypted media relay fallback for NATs that block direct UDP

8. ⏳ Raspberry Pi rendezvous server
   - ✅ Pi promoted to the permanent rendezvous deployment
   - ✅ Stable production HTTPS hostname/tunnel
   - ✅ quietlink-rendezvous and cloudflared configured as systemd services
   - ⏳ Deploy v0.3.49 opaque control-relay server update
   - ⏳ Reboot/power-loss automatic recovery validation
   - ⏳ Long-duration Android client validation against the permanent endpoint
   - ✅ Privacy-safe operational logging only

9. ⏳ HTML/WebRTC client
   - ⏳ Browser-compatible signaling
   - ⏳ WebRTC transport integration
   - ⏳ Browser ↔ Android interoperability
   - ⏳ Browser call UI and recovery behavior

10. ⏳ Party/group mode
   - ⏳ Multi-peer session model
   - ⏳ Group signaling
   - ⏳ Group UI / participant state
   - ⏳ Group recovery and disconnect handling

## Current truth

v0.3.48 production rendezvous matching is user-validated. v0.3.49 added the first actual encrypted internet session path: QL5 control/chat through a bounded opaque relay plus direct encrypted UDP media. v0.3.50 carries the same internet-session work plus an older-phone updater signer-compatibility fix. Permanent-Pi deployment and a real two-phone Voice/audio test remain the next networking checkpoint.

The Online status dot remains conservative/red while `onlineCallsAvailable=false`, but the published status document now supplies the permanent production `rendezvousUrl` for automatic signaling.

Local LAN / Hotspot / Wi-Fi Direct remains the working/default path and must continue working even if every online component is unavailable.

## Milestone display rule

Use this nested format for progress reports:

1. numbered main milestone with ✅ or ⏳
   - indented sub-milestones with ✅ or ⏳

Keep the smaller/intermediate milestones directly underneath the main milestone they belong to, like:

5. ✅ Background watchdog/recovery
   - ✅ Hotspot/LAN support added

Do not split the active milestone into a separate progress section unless extra detail is genuinely useful. Do not use progress bars or percentages unless the user asks.

## AI continuation

For a complete handoff, read **AI_HANDOFF.md** before making project changes.

### Simple resume command

> Resume QuietLink from the GitHub `main` branch. Read `AI_HANDOFF.md` first, then `MILESTONES.md`, `README.md`, and `TESTING.md`. Treat the current source and those files as the source of truth. Continue from the **NEXT ACTION** section in `AI_HANDOFF.md` without redesigning completed systems.
