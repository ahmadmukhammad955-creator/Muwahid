# Account suspension coverage audit — 2026-11-10

Read-only inspection of the live Supabase project; **no production migrations applied**.

## Inventory
- 92 `public.mw_*` RPC functions inspected.
- 19 authenticated-executable functions do **not directly** call `mw_private.require_mfa()`.
- This does not mean all 19 bypass protection: some delegate to other functions which do call it, or serve public configuration.
- The staged `account_suspension_guard.sql` will only protect paths that actually call `require_mfa()`. It is not a complete account suspension feature.

## Priority paths
| RPC | Direct MFA guard? | Observed behavior | Action |
| --- | --- | --- | --- |
| mw_can_access_chat | No | SECURITY DEFINER membership lookup; can return true for suspended user | Add suspension check; preserve boolean contract |
| mw_owner_status | No | Returns owner/MFA state to authenticated user | Deny suspended user |
| mw_role_status | No | Returns role based on authenticated identity | Deny suspended user |
| mw_admin_list, mw_owner_admin_set, mw_owner_complaints, mw_owner_overview | No | Call `mw_private.require_owner()` | Add suspension check centrally to `require_owner()` |
| mw_push_unregister | No | Removes caller's own push token | Consider whether to allow cleanup during suspension |
| mw_room_create | No | Delegates to `mw_live_room_create()` | Check delegated guard; do not assume bypass |
| mw_find_person_by_qr | No | Delegates to `mw_private.find_person_by_qr()`, which DOES call require_mfa | Covered indirectly |
| Sticker and config functions | Mostly No | Mix of invoker rights and public catalogs | Audit RLS/storage and decide which should be blocked |

## Confirmed authorization properties
- `mw_send`, `mw_messages_get`, `mw_chat_list`, `mw_call_start` call `require_mfa()`.
- `mw_private.require_owner()` checks a fixed owner identity plus aal2 but currently does not invoke `require_mfa()`.
- `mw_can_access_chat` checks chat membership but not suspension.

## Remaining mandatory work
- Inventory and enforce all storage policies, Realtime subscriptions, Edge functions, and LiveKit token issuance. SQL RPC guard alone cannot guarantee global suspension.
- Verify no RPC bypasses central guard through direct table access or separate role.
- Define a safe, authenticated incident-response endpoint that checks owner MFA and authorization, logs incidents, revokes refresh sessions, expires media tokens, and broadcasts client lock.
- Build and run multi-account tests with still-valid JWTs, including calls/media and offline clients.
- Do not deploy partial protection as if it were complete; it may create false assurance.
