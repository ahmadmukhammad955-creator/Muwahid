# LIVE content encryption — Muwahid 0.13.41

This branch implements encryption for LIVE text, stickers sent as text, photos,
videos, photo/video backgrounds and shared PDF contents. Live audio/video streams,
screen sharing and music mixed into those streams are unchanged and are not covered
by this claim. The release is integrated with the existing 0.13.40 sticker features. The additive
server migration 20261110173535_live_content_e2ee is deployed; plaintext cutover is
separate and must follow publication of the signed 0.13.41 APK.

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
- Real HTTPS/Auth/Storage round trips passed 19 checks using three isolated synthetic
  accounts. Two recipients exchanged Tink ciphertext and independently recovered
  exact text, metadata and file bytes. Outsider/anonymous/waiting users were denied;
  replay, plaintext and stale recipient snapshots were rejected. After removal,
  the member could not fetch the key envelope, room history or a new Storage object.
  This harness uses Python Tink with the same wire format; native Android crypto is
  tested separately by instrumentation. It is not a physical two-phone test.
- Previously downloaded ciphertext was still returned by the Storage CDN after
  member removal (HTTP 200). The app checks the uncached RPC before opening an
  encrypted file, and future items omit the removed user's wrapped key. New uploads
  use max-age=0 for client caching; this does not purge the CDN. Already delivered
  plaintext/keys cannot be recalled. See the Supabase private-storage cache notice:
  https://supabase.com/kb/troubleshooting/storage-private-bucket-files-still-accessible-after-remove-access-or-logout
- Physical two-phone interoperability, eight simultaneous devices and API 23 remain
  untested. LIVE audio quality is not tested by content encryption tests.
- The release workflow tests 0.13.40 → 0.13.41 with the same certificate and checks
  that preferences and Android-Keystore-encrypted session-shaped test data survive.
  This uses synthetic data on an emulator, not a real family's signed-in session.

## Rollout

1. Additive migration is applied and recorded in supabase/migrations/20261110173535_live_content_e2ee.sql.
2. Publish a new version/code signed with the existing release certificate only
   after build, signing, emulator and network checks pass. Existing releases are not overwritten.
3. Update family devices and verify an encrypted item across real sessions.
4. Apply candidates/live_content_e2ee_cutover.sql to reject old plaintext sends
   and old upload paths. Without this cutover, old clients still send plaintext.
5. Verify unauthorized, revoked and waiting users, a membership change during
   upload, and ordinary text/media/PDF/background round trips through Storage.

No data is deleted or automatically re-encrypted. Historical content remains
readable under existing access rules. Keep a backup of function/policy definitions
before cutover. A rollback to accepting plaintext would weaken privacy and must be
an explicit decision, not an automatic fallback.
