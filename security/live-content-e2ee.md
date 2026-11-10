# LIVE content encryption candidate

This branch implements encryption for LIVE text, stickers sent as text, photos,
videos, photo/video backgrounds and shared PDF contents. Live audio/video streams,
screen sharing and music mixed into those streams are unchanged and are not covered
by this claim. Production stays on the published 0.13.38 until a coordinated release.

## Construction and boundaries

Tink Android 1.24.0 generates a fresh AES-256-GCM keyset per item. Both the descriptor
(text, filename, MIME and file metadata) and file contents use that keyset with
separate associated-data scopes. Room, sender, random item UUID and sorted recipient
IDs are authenticated. A file's path is additionally authenticated.

The keyset is wrapped for each current recipient through the existing verified
personal-chat CryptoBox channel, with an item/recipient-specific scope. The sender's
copy is wrapped with Android Keystore and bound to the same item. Only public keys,
ciphertexts and wrapped content keys leave the phone. The underlying personal-chat
key agreement is inherited from 0.13.38; this change does not make it MLS, Signal,
a ratchet or an independently audited group protocol. No forward-secrecy claim is
made. An independent review of that inherited construction remains outstanding.

Everyone must have a personal chat and verify safety codes with each other before
sending to the whole room. Changed or unavailable keys block sending before upload.
Sender keys survive an Activity restart, but cannot be recovered after app-data
removal or phone replacement. There is no server key escrow. Newly admitted users
cannot decrypt older items; the sender must resend a background/PDF for them.
Removed users retain previously received content, as with any messenger, but are
excluded from subsequent item keys. Participant count remains up to the existing
room capacity; the eight-recipient case is exercised in instrumentation.

Server recipient snapshots contain IDs, chat IDs and public keys. Membership changes
serialize on the room row; key and family-state rows are locked while sending.
The server checks caller access and exact recipient equality, file ownership,
background owner and item replay. Body format/path checks alone cannot prove
cryptography: an intentionally malicious sender can upload plaintext in any
ciphertext-shaped field. The real Android paths accept only typed encrypted uploads.

Server-visible metadata includes room membership, sender, timing, item kind, random
object path, ciphertext length and whether an item is a background or shared book.
Decrypted media opened in an external viewer and intentionally saved recordings
follow existing export/cache behavior; this patch does not encrypt those exports.

## Validation

- GitHub Actions run 42834805749 built debug/test APKs and passed the Android 29
  emulator smoke run, including real Tink and Android Keystore checks.
- Eight recipients (including the sender) decrypt the synthetic descriptor and file.
- Altered ciphertext/file, wrong room/path/sender, changed recipient set and changed
  peer key are rejected. Sender restart succeeds. Removed/late users cannot open
  the corresponding new/old item respectively. Network transport is mocked in this
  crypto test; the cryptographic libraries and Keystore are real.
- Candidate SQL and authorization tests ran against the project in a single
  rolled-back transaction: valid structural ciphertext, plaintext rejection,
  replay, extra recipient, absent file, outsider access, stale membership, removal
  and anonymous RPC access. No test data or DDL was retained.
- Cutover was also transaction-tested: the new send succeeded while the legacy
  plaintext RPC and old file-upload path were rejected. The encrypted file path
  remained writable under existing membership policies. All changes rolled back.
- Physical two-phone interoperability, eight simultaneous devices, API 23,
  release signing/in-place upgrade and post-cutover end-to-end network tests remain
  release gates. LIVE audio quality is not tested by the content encryption tests.

## Rollout

1. Generate/apply the additive migration from candidates/live_content_e2ee.sql.
2. Publish a new version/code signed with the existing release certificate only
   after the release gates pass. Do not replace the 0.13.38 release or its manifest.
3. Update family devices and verify an encrypted item across real sessions.
4. Apply candidates/live_content_e2ee_cutover.sql to reject old plaintext sends
   and old upload paths. Without this cutover, old clients still send plaintext.
5. Verify unauthorized, revoked and waiting users, a membership change during
   upload, and ordinary text/media/PDF/background round trips through Storage.

No data is deleted or automatically re-encrypted. Historical content remains
readable under existing access rules. Keep a backup of function/policy definitions
before cutover. A rollback to accepting plaintext would weaken privacy and must be
an explicit decision, not an automatic fallback.
