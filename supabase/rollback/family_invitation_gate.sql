-- Emergency rollback for activation. Retains users, chats, files, and family records.
set local lock_timeout = '4s';
lock table auth.users in share row exclusive mode;
create or replace function mw_private.family_claim_invitation()
returns trigger language plpgsql security definer set search_path = ''
as $$ begin return new; end; $$;

create or replace function mw_private.family_add_pending_member()
returns trigger language plpgsql security definer set search_path = ''
as $$ begin return new; end; $$;

do $rollback_policies$
declare target text;
begin
  foreach target in array array[
    'mw_ai_access','mw_ai_usage','mw_complaints','mw_custom_emoji','mw_sticker_assets',
    'mw_sticker_favorites','mw_sticker_pack_items','mw_sticker_packs','mw_sticker_recents','mw_voice_room_members'
  ] loop
    execute format('drop policy if exists mw_family_active on public.%I',target);
  end loop;
end;
$rollback_policies$;

drop policy if exists mw_family_storage_active on storage.objects;
drop policy if exists mw_family_signal_active on realtime.messages;

CREATE OR REPLACE FUNCTION public.mw_can_access_chat(p_chat uuid)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
 select auth.uid() is not null and not mw_private.account_is_suspended(auth.uid())
 and exists(select 1 from public.mw_chats c where c.id=p_chat and auth.uid() in (c.member_a,c.member_b));
$function$;

CREATE OR REPLACE FUNCTION mw_private.privacy_allows(p_owner uuid, p_actor uuid, p_kind text)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
 select p_owner=p_actor or (
  not exists(select 1 from public.mw_blocks b where (b.user_id=p_owner and b.person_id=p_actor) or (b.user_id=p_actor and b.person_id=p_owner))
  and case coalesce((select case p_kind when 'messages' then v.messages when 'calls' then v.calls when 'phone_search' then v.phone_search else 'nobody' end from public.mw_privacy v where v.user_id=p_owner),'everyone')
   when 'everyone' then true
   when 'contacts' then exists(select 1 from public.mw_privacy_contacts c where c.user_id=p_owner and c.person_id=p_actor)
   else false end
 );
$function$;

CREATE OR REPLACE FUNCTION mw_private.require_mfa()
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
 if auth.uid() is null then raise exception 'MW:Войдите в аккаунт'; end if;
 if mw_private.account_is_suspended(auth.uid()) then
  raise exception 'MW:ACCOUNT_SUSPENDED' using errcode='42501';
 end if;
 if exists(select 1 from auth.mfa_factors where user_id=auth.uid() and status='verified')
    and coalesce(auth.jwt()->>'aal','')<>'aal2' then
  raise exception 'MW:MFA_REQUIRED';
 end if;
end $function$;

create or replace function public.mw_family_signup_enabled()
returns boolean language sql stable set search_path = ''
as $$ select false; $$;

revoke execute on function public.mw_family_invite_create(text), public.mw_family_invite_cancel(uuid),
  public.mw_family_owner_list(), public.mw_family_member_set(uuid,text),
  public.mw_family_sessions(), public.mw_family_session_revoke(uuid) from authenticated;
