# Muwahid: account isolation on confirmed compromise

Status: SECURITY DESIGN / NOT DEPLOYED. Do not merge without integration tests.

## Required behavior
- A confirmed compromise suspends ONLY the affected user account, across all devices. Other family accounts remain operational.
- Deny ALL authenticated API operations for the suspended user: chat list/read/send, media URLs, storage, calls/signaling, rooms, push registration, profile/contact search, key lookup and refresh.
- Revoke Supabase refresh sessions; short-lived existing JWTs must ALSO be denied at each privileged RPC and Storage/Realtime gateway. Revocation alone is not immediate.
- Suspend with a server-side security record keyed by auth.uid(), with suspension reason, timestamp, incident ID and authorized reviewer; default deny for suspended accounts.
- Restrict suspend/restore RPC to verified owner or secure service identity, with step-up MFA and audit log. Never authorize from editable user_metadata or client-side flags.
- Never suspend all family members automatically. Restore only after identity verification and new credentials, new sessions, device review and cryptographic key rotation.
- Remove compromised member from active calls, revoke media tokens and prevent reconnect. Expire signed storage URLs quickly; previously issued URLs and downloaded data cannot be retroactively revoked.
- Encrypt messages/media end-to-end on clients, verify recipient keys, and rotate group keys after member compromise. Existing copied plaintext cannot be erased remotely.
- Client device lock + encrypted local database; offline devices cannot be reliably locked instantly. Do not claim otherwise.
- Avoid automated destructive deletion triggered by failed logins; alert, rate limit, challenge, and require incident confirmation.

## Existing findings (read-only inspection 2026-11-10)
- All inspected public mw_* tables have RLS enabled; many intentionally have no policies and are reached via SECURITY DEFINER RPCs.
- Supabase advisor flags 75 authenticated-executable SECURITY DEFINER functions. Audit individual functions; this count is NOT 75 proven vulnerabilities.
- Selected functions are not executable by anon, but are callable by authenticated; verify authorization per operation.
- Room invitations exist, but are NOT equivalent to invite-only signup.
- The checked mw_messages_get RPC returns the stored body field; server-side key lookup exists, but E2EE implementation is not yet independently proven.
- Password breach protection is disabled according to Security Advisor.
- GitHub repository is public; inspect secrets and history before changing visibility.

## Implementation checklist
1. Inventory all RPCs, Storage policies, Realtime channels, edge functions, auth flows, LiveKit token issuers and client caches.
2. Design transaction-safe account_security_state with indexed uid, suspended_at and audit events in private schema; restrict all writes to verified owner/service.
3. Implement centralized suspension guard, enforce in EVERY RPC and every media/storage gateway, with tests for a still-valid JWT.
4. Integrate Supabase session revocation and LiveKit participant removal/token expiry.
5. Add owner emergency suspension UI with confirmation and incident audit trail.
6. Add secure recovery workflow and session/key rotation.
7. Test two accounts A and B: suspend A, A cannot read/send/join/upload with an old token; B continues to operate; restore A only after verification.
8. Run Security Advisor, Android integration tests, and regression tests before merge/deployment.

## Deployment safety
Do not run broad GRANT/REVOKE changes, delete family accounts, or change live policies without backups and staged tests. Build and test on an isolated environment, then deploy with rollback plan.
