# Muwahid family security rollout

The Android changes apply to the checked-in 0.13.37 source archive. The candidate keeps the existing Android application ID, secure session preferences and account data. It increments the version to 0.13.38 (73).

## Database

- Migration `20261110074440_harden_messenger_access.sql` is already present in production. The accompanying SQL checks exercise chat authorization, call authorization and the Storage policy with simulated client roles.
- Keep the family gate migration staged until the scheduled database backup or restore point and a separate Storage backup have been verified. Confirm the family owner has a working second factor. Check that Auth email confirmation and SMTP work for an address outside the Supabase organization.
- Test the family gate on a restored or isolated project with email, Google and phone settings that match production. Verify that existing email, Google and phone sign-ins still work. Confirm new Google or phone signup fails and invited email registration and approval work end to end.
- During rollout, apply the gate migration and deploy both updated AI Edge Functions. Keep the existing cleanup Edge Function and its credentials. Run the SQL checks, then exercise Storage, Realtime and AI using actual approved, pending and revoked test sessions.
- The migration initially keeps every existing Auth account active. The owner reviews the member list and removes unfamiliar accounts through the owner panel. No chat or account history is deleted.
- If the gate needs to be suspended, prefer a targeted recovery migration with an audited change to the affected account. Dropping membership or invitations would lose approval state and could reopen signup.

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
