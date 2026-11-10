# Muwahid — E2EE stories and contact profile: release gate

Reference: the user-supplied 102-second Telegram screen recording, reviewed at 0–100 s in 5-second increments. This is an **implementation and test gate**, not a UI mock-up.

## Verified in code / automated checks
- PostgreSQL RLS scenario with two members of an existing chat and an unrelated user: owner and explicitly granted viewer read the story; unrelated user cannot. Executed inside a rollback-only transaction on 2026-11-10.
- SQL: expired unpinned story is hidden; explicitly pinned story remains visible to the original approved viewer; a block suppresses access. Executed inside a rollback-only transaction.
- Android unit-like instrumentation: AES-256-GCM round-trip, tamper rejection, wrong-key rejection.
- Supabase private bucket accepts only application/octet-stream; file access is owner- or explicit contact-grant-based, including restrictive safeguards against broader legacy Storage policies.
- No unencrypted story bytes or plaintext encryption keys are sent by the official Android story code.
- Real native media player with memory-only decrypted video source; no external viewer.

## Private archive (new in this branch)
- Owner-only `mw_story_archive(p_offset,p_limit)` returns only the caller's own expired published stories, including items previously pinned to the profile; never a contact's expired stories.
- The native **Архив / Сейчас** toggle uses this RPC and loads up to 50 archived items at a time, without plaintext server media or key escrow.
- Confirm owner A can reopen and decrypt an expired history; B and an unrelated account C cannot retrieve that expired story unless it was explicitly pinned for a still-authorized viewer.
- Check archival pagination beyond 50 items, restore-to-profile, deletion, key loss after reinstall, and signed in-place upgrade before release.
- Expired encrypted files are retained for the owner's archive. Do not deploy any automatic TTL file deletion until retention policy and owner export/recovery are defined.

## Required before a public APK update
1. Install a *signed* candidate over existing 0.13.41 without uninstalling; verify saved authorization, local E2EE identity and stored chat data remain intact.
2. On device A and B, sign into distinct active family accounts; open a 1:1 chat on each; manually compare and confirm safety codes on **both** devices.
3. A publishes JPEG, WebP, MP4 (shorter than 60 seconds); B sees only stories explicitly granted to B. Verify image, video playback, mute, hold-to-pause, previous/next and playback speed.
4. B reacts with emoji. A can inspect which contact reacted; the server only stores encrypted reaction text.
5. B replies to A's story; A receives the response as a real E2EE chat message, not an in-memory preview.
6. A pins a story; after simulated expiry it remains visible in the contact profile, and an unpinned story does not. A can still open the unpinned story from **Мой архив**.
7. A blocks B; B can no longer retrieve the story metadata or encrypted media. Unblock and explicitly test existing viewer authorization.
8. C, without a chat or explicit grant, cannot list/download A's content even when knowing the storage object path.
9. A deletes a story; verify database row, grants, reactions and uploaded encrypted object are deleted, not just hidden in the UI.
10. Denied media picker, unsupported MIME, oversized file, offline upload, changed E2EE identity and missing verification produce clear errors without crashes or plaintext fallback.
11. Confirm Android 23, Android 29 and Android 15 target device behavior, especially media playback from MediaDataSource and safe lifecycle during activity recreation.
12. Confirm that no story preview leaks unencrypted attachments into shared photo caches or external apps.

## Not yet a claim of Telegram-complete parity
Still to implement/test before claiming exact parity: actual shared profile-photo avatars; story upload/viewer privacy selector; notifications of new stories/reactions; rich animated custom emoji packs and effects; story-specific sharing controls; an explicit retention/export and eventual storage cleanup policy compatible with the private archive; full multi-device key backup/recovery. The existing 24h RLS gating hides expired stories but does not physically purge their encrypted files automatically.

**Do not merge and do not publish an automatic APK update while this list has unresolved security or device tests.**
