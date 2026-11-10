# Muwahid family security rollout

The Android changes apply to the checked-in 0.13.37 source archive. The candidate keeps the existing Android application ID, secure session preferences and account data. It increments the version to 0.13.38 (73).

## Database

- Migration `20261110074440_harden_messenger_access.sql` is already present in production. The accompanying SQL checks exercise chat authorization, call authorization and the Storage policy with simulated client roles.
- Migration `20261110122101_family_invitation_gate.sql` is active in production. The transaction checks covered existing sessions, missing, expired and replayed invitations, owner approval, session revocation and account suspension. Existing Auth accounts were retained.
- Both AI Edge Functions require an active family session and retain their existing JWT requirements.
- The server-side registration tests simulate the database and session roles. Verify actual email delivery and invitation redemption with an invited relative before relying on the flow.
- An emergency rollback is documented in `supabase/rollback/family_invitation_gate.sql`. It retains family records and restores the pre-activation database authorization functions. If restoring the earlier behavior of the AI services is required, restore their pre-activation versions separately.

## Android release

The CI verifies the baseline archive, applies the tracked patch, builds the Android apps and runs the existing instrumentation checks. A signed release needs the existing signing configuration. Before publishing, install the previous signed APK on a test device, create representative local data and update in place using the signed candidate. Verify the app ID, same signing certificate, newer version, retained session and local history. Test rejection of an APK with a changed hash, app ID, version or signer.

The family signup form checks that the gate is available before requesting registration. Publish only after validating the gate and device tests. Android may require permission to install from Muwahid and a confirmation in the system installer.

## Family use after release

1. The owner opens **Settings → Two-factor verification → Owner panel → Family access**, then creates an invitation bound to a relative's email. Share it directly with the intended recipient.
2. The recipient opens the family invitation screen, enters the same email and invitation code, chooses a password and confirms email. The owner confirms the resulting pending account.
3. In **Settings → Devices**, review sessions and revoke any unfamiliar device. If a family account loses access to its phone, the owner can revoke that account while recovery is arranged.
4. In **Settings → Privacy**, enable the phone's screen-lock credential for app access. Keep the update notification setting enabled and use the in-app update prompt.

## Remaining security work

Existing personal messages use the app's original encryption format. Legacy plaintext stays readable and should be migrated separately after defining a recovery and compatibility path. The public-key exchange requires identity verification, rotation and multi-device support before stronger E2EE claims. Existing WebRTC calls and LIVE rooms have no verified LiveKit SFU integration. Test eight devices on varied networks, camera switching, microphone permissions and ongoing media shutdown after revocation. Server signaling revocation alone cannot terminate a peer-to-peer connection that is already established on modified clients. Previously downloaded copies remain on the recipient's device.
