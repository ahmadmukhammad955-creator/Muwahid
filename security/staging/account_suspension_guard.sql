-- SECURITY STAGING MIGRATION: REVIEW AND TEST BEFORE APPLYING TO PRODUCTION
-- Account suspension guard for RPCs that invoke mw_private.require_mfa().
-- Does not revoke sessions or cover Storage, Realtime, LiveKit or RPCs that skip require_mfa().
begin;

create table if not exists mw_private.account_security_state (
  user_id uuid primary key references auth.users(id) on delete cascade,
  suspended_at timestamptz,
  incident_id uuid,
  reason_code text,
  updated_at timestamptz not null default now(),
  constraint suspension_incident_required check (suspended_at is null or incident_id is not null)
);
alter table mw_private.account_security_state enable row level security;
revoke all on mw_private.account_security_state from public, anon, authenticated;
-- Deliberately no user-facing suspension mutation RPC.
-- Suspension/restore must be performed only by a verified owner/service workflow.

create or replace function mw_private.require_mfa()
returns void language plpgsql security definer set search_path to ''
as $function$
begin
  if auth.uid() is null then
    raise exception 'MW:Войдите в аккаунт';
  end if;
  if exists (
    select 1 from mw_private.account_security_state s
    where s.user_id = auth.uid() and s.suspended_at is not null
  ) then
    raise exception 'MW:ACCOUNT_SUSPENDED' using errcode = '42501';
  end if;
  if exists (
    select 1 from auth.mfa_factors
    where user_id = auth.uid() and status = 'verified'
  ) and coalesce(auth.jwt()->>'aal','') <> 'aal2' then
    raise exception 'MW:MFA_REQUIRED';
  end if;
end
$function$;
revoke all on function mw_private.require_mfa() from public, anon, authenticated;
commit;

-- Verification before rollout:
-- SELECT ... pg_get_functiondef('mw_private.require_mfa()'::regprocedure);
-- Check every SECURITY DEFINER RPC for guard coverage.
-- Test suspended user with still-valid JWT; unsuspended user still works.
