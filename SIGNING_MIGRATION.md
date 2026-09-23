# QuietLink signing-key migration

This document contains **no private key material or passwords**.

## Why this migration exists

The original QuietLink Android signing keystore and its password were committed to the public repository on 2026-09-20. That signer must therefore be treated as compromised.

The repository-wide history audit found no other credential categories; the exposed Android signer was the only secret-class finding.

## Certificate pins

Old compromised signer SHA-256:

`90b075695287a8080bda9fcb4cd4dff188e781a25a1d135ddfeeed85f1a09da1`

New QuietLink v2 signer SHA-256:

`da4ff75b61d65a3ff1c335bf87a2173c63bb031711faebfded2b965fb1c851b1`

The new private key is not stored in Git.

## Migration sequence

1. Install v0.3.46 on every existing QuietLink device.
2. Store the private v2 signing backup offline.
3. Add the following repository Actions secrets from the private backup:
   - `QUIETLINK_SIGNING_KEY_B64`
   - `QUIETLINK_SIGNING_STORE_PASSWORD`
   - `QUIETLINK_SIGNING_KEY_PASSWORD`
   - `QUIETLINK_SIGNING_KEY_ALIAS`
4. Build the signer-rotation release with Android `apksigner` proof-of-rotation and `--rotation-min-sdk-version 28`.
5. Confirm every existing device updates in-place to the rotated signer.
6. Switch normal CI signing to v2 secret material only.
7. Delete the old keystore and plaintext passwords from all live refs.
8. Rewrite all reachable Git history to purge the old signing material.
9. Re-run the full-history credential audit and require zero findings.

## Important

Do not delete the old signer from history/current refs before step 4 is ready unless a secure copy is retained temporarily for the rotation proof. Existing sideloaded Android installs require an authenticated signer transition to preserve in-place updates.

Never paste the new keystore, passwords, or base64 secret values into issues, commits, logs, pull requests, documentation, or chat screenshots.


## Current migration state

**COMPLETE for all repository-controlled remediation.**

Completed:
- both existing phones successfully updated in place to v0.3.47 / versionCode 59
- v0.3.47 is signed by the new v2 private signer with the authenticated old→v2 Android signing lineage
- normal CI signs only with the v2 key stored in GitHub Actions Secrets
- the exposed old keystore and plaintext passwords are absent from every live branch
- legacy QuietLink APK/source workflow artifacts were purged
- all five live branches were force-rewritten to a sanitized clean root
- the post-purge full reachable-history credential audit reported: `RESULT: no credential-pattern findings`

GitHub backend-retention note:
- the old commits are no longer reachable from any live branch/tag history, but GitHub may continue serving an orphaned object by its exact SHA until backend garbage collection/support removal occurs
- this does not restore trust to the old key; the old signer remains permanently compromised and must never be used again
- both installed phones and all future CI releases have already moved to the new v2 signer, so possession of the old key cannot produce an accepted future QuietLink update through the approved lineage

The signing lineage is public verification metadata. It does not contain a private signing key or password.
