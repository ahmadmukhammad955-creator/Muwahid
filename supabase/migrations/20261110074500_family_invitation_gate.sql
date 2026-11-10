-- Apply after the invitation UI is verified. Existing Auth users are preserved.
lock table auth.users in share row exclusive mode;

do $preflight$
begin
  if (select count(*) from mw_private.owner_account o
      join public.mw_profiles p on p.id=o.user_id
      join auth.mfa_factors f on f.user_id=o.user_id and f.status='verified') < 1 then
    raise exception 'Family gate requires the existing owner with a verified MFA factor';
  end if;
end;
$preflight$;

create table mw_private.family_members (
  user_id uuid primary key references auth.users(id) on delete cascade,
  state text not null check (state in ('active','pending','revoked')),
  changed_at timestamptz not null default now()
);

create table mw_private.family_invitations (
  id uuid primary key default gen_random_uuid(),
  token_hash bytea not null unique,
  email text not null,
  expires_at timestamptz not null,
  redeemed_by uuid unique references auth.users(id) deferrable initially deferred,
  redeemed_at timestamptz,
  canceled_at timestamptz
);

create index on mw_private.family_invitations(email) where redeemed_at is null and canceled_at is null;

create table mw_private.family_revoked_sessions (
  session_id uuid primary key references auth.sessions(id) on delete cascade
);

alter table mw_private.family_members enable row level security;
alter table mw_private.family_invitations enable row level security;
alter table mw_private.family_revoked_sessions enable row level security;
revoke all on mw_private.family_members, mw_private.family_invitations, mw_private.family_revoked_sessions
  from public, anon, authenticated;

insert into mw_private.family_members(user_id,state)
  select id,'active' from auth.users;

create function mw_private.family_session_active()
returns boolean language sql stable security definer set search_path = ''
as $$
  select exists (
    select 1 from auth.sessions s
    where s.id=case when pg_input_is_valid(auth.jwt()->>'session_id','uuid')
                    then (auth.jwt()->>'session_id')::uuid end
      and s.user_id=auth.uid()
      and (s.not_after is null or s.not_after>now())
      and not exists (select 1 from mw_private.family_revoked_sessions r where r.session_id=s.id)
  );
$$;

create function mw_private.family_has_access()
returns boolean language sql stable security definer set search_path = ''
as $$
  select mw_private.family_session_active()
    and exists (select 1 from mw_private.family_members m where m.user_id=auth.uid() and m.state='active')
    and (coalesce(auth.jwt()->>'aal','')='aal2'
         or not exists (select 1 from auth.mfa_factors where user_id=auth.uid() and status='verified'));
$$;

create or replace function mw_private.require_mfa()
returns void language plpgsql security definer set search_path = ''
as $$
declare member_state text;
begin
  if auth.uid() is null then raise exception 'MW:Войдите в аккаунт'; end if;
  if not mw_private.family_session_active() then raise exception 'MW:Сеанс отозван. Войдите заново.'; end if;
  select m.state into member_state from mw_private.family_members m where m.user_id=auth.uid();
  if member_state='pending' then raise exception 'MW:Ожидается подтверждение владельца'; end if;
  if member_state is distinct from 'active' then raise exception 'MW:Доступ к Muwahid отозван'; end if;
  if not mw_private.family_has_access() then raise exception 'MW:MFA_REQUIRED'; end if;
end;
$$;

create function mw_private.family_claim_invitation()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare invite_token text := new.raw_user_meta_data->>'family_invite';
begin
  if new.email is null or coalesce(invite_token,'') !~ '^[0-9a-f]{64}$' then
    raise exception 'MW:Требуется действующее семейное приглашение';
  end if;
  update mw_private.family_invitations
    set redeemed_by=new.id, redeemed_at=now()
    where token_hash=sha256(convert_to(invite_token,'UTF8'))
      and email=lower(btrim(new.email))
      and redeemed_at is null and canceled_at is null and expires_at>now();
  if not found then raise exception 'MW:Требуется действующее семейное приглашение'; end if;
  new.raw_user_meta_data := new.raw_user_meta_data - 'family_invite';
  return new;
end;
$$;

create function mw_private.family_add_pending_member()
returns trigger language plpgsql security definer set search_path = ''
as $$
begin
  insert into mw_private.family_members(user_id,state) values(new.id,'pending');
  return new;
end;
$$;

create trigger mw_family_claim_invitation before insert on auth.users
  for each row execute function mw_private.family_claim_invitation();
create trigger mw_family_add_pending_member after insert on auth.users
  for each row execute function mw_private.family_add_pending_member();

create function public.mw_family_invite_create(p_email text)
returns text language plpgsql security definer set search_path = ''
as $$
declare
  normalized text := lower(btrim(p_email));
  token text := replace(gen_random_uuid()::text,'-','') || replace(gen_random_uuid()::text,'-','');
begin
  perform mw_private.require_mfa();
  perform mw_private.require_owner();
  if normalized is null or char_length(normalized)>254 or normalized !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then
    raise exception 'MW:Укажите действующий email';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(normalized,0));
  if exists(select 1 from auth.users u where lower(u.email)=normalized) then
    raise exception 'MW:Этот аккаунт уже существует';
  end if;
  update mw_private.family_invitations set canceled_at=now()
    where email=normalized and redeemed_at is null and canceled_at is null;
  insert into mw_private.family_invitations(token_hash,email,expires_at)
    values(sha256(convert_to(token,'UTF8')),normalized,now()+interval '24 hours');
  return token;
end;
$$;

create function public.mw_family_invite_cancel(p_invite uuid)
returns boolean language plpgsql security definer set search_path = ''
as $$
begin
  perform mw_private.require_mfa();
  perform mw_private.require_owner();
  update mw_private.family_invitations set canceled_at=now()
    where id=p_invite and redeemed_at is null and canceled_at is null;
  return found;
end;
$$;

create function public.mw_family_owner_list()
returns jsonb language plpgsql security definer set search_path = ''
as $$
begin
  perform mw_private.require_mfa();
  perform mw_private.require_owner();
  return jsonb_build_object(
    'members',coalesce((
      select jsonb_agg(jsonb_build_object('id',m.user_id,'name',p.display_name,'email',u.email,
        'state',m.state,'confirmed',u.confirmed_at is not null,
        'owner',exists(select 1 from mw_private.owner_account o where o.user_id=m.user_id)) order by m.changed_at desc)
      from mw_private.family_members m join auth.users u on u.id=m.user_id
      left join public.mw_profiles p on p.id=m.user_id
    ),'[]'::jsonb),
    'invitations',coalesce((
      select jsonb_agg(jsonb_build_object('id',i.id,'email',i.email,'expires_at',i.expires_at) order by i.expires_at desc)
      from mw_private.family_invitations i
      where i.redeemed_at is null and i.canceled_at is null and i.expires_at>now()
    ),'[]'::jsonb)
  );
end;
$$;

create function public.mw_family_member_set(p_user uuid, p_state text)
returns boolean language plpgsql security definer set search_path = ''
as $$
declare previous_state text;
begin
  perform mw_private.require_mfa();
  perform mw_private.require_owner();
  if p_state is null or p_state not in ('active','revoked') then raise exception 'MW:Недопустимый статус'; end if;
  if exists(select 1 from mw_private.owner_account o where o.user_id=p_user) then
    raise exception 'MW:Статус владельца защищён';
  end if;
  if p_state='active' and not exists(select 1 from auth.users u where u.id=p_user and u.confirmed_at is not null) then
    raise exception 'MW:Участник должен подтвердить свою почту';
  end if;
  select state into previous_state from mw_private.family_members where user_id=p_user for update;
  if previous_state is null or previous_state=p_state then return false; end if;
  update mw_private.family_members set state=p_state, changed_at=now() where user_id=p_user;
  if p_state='revoked' or previous_state='revoked' then
    insert into mw_private.family_revoked_sessions(session_id)
      select id from auth.sessions where user_id=p_user on conflict do nothing;
  end if;
  if p_state='revoked' then
    delete from mw_private.mw_device_push_tokens where user_id=p_user;
    update public.mw_calls set status='ended',updated_at=now()
      where p_user in (caller_id,callee_id) and status in ('ringing','accepted');
    delete from public.mw_voice_room_waiting where user_id=p_user;
    delete from public.mw_voice_room_members where user_id=p_user;
  end if;
  return true;
end;
$$;

create function public.mw_family_access_status()
returns text language sql stable security definer set search_path = ''
as $$
  select case
    when m.state='pending' then 'pending'
    when m.state is distinct from 'active' then 'revoked'
    when not mw_private.family_session_active() then 'session_revoked'
    when mw_private.family_has_access() then 'active'
    else 'mfa_required' end
  from (select auth.uid() as user_id) caller
  left join mw_private.family_members m on m.user_id=caller.user_id;
$$;

create function public.mw_family_sessions()
returns jsonb language plpgsql security definer set search_path = ''
as $$
begin
  perform mw_private.require_mfa();
  return (select coalesce(jsonb_agg(jsonb_build_object(
    'id',s.id,'created_at',s.created_at,
    'last_active',coalesce(s.refreshed_at::timestamptz,s.updated_at,s.created_at),
    'current',s.id::text=auth.jwt()->>'session_id') order by s.created_at desc),'[]'::jsonb)
    from auth.sessions s where s.user_id=auth.uid()
      and (s.not_after is null or s.not_after>now())
      and not exists(select 1 from mw_private.family_revoked_sessions r where r.session_id=s.id));
end;
$$;

create function public.mw_family_session_revoke(p_session uuid)
returns boolean language plpgsql security definer set search_path = ''
as $$
begin
  perform mw_private.require_mfa();
  if p_session::text=auth.jwt()->>'session_id' then raise exception 'MW:Для текущего устройства используйте выход'; end if;
  insert into mw_private.family_revoked_sessions(session_id)
    select id from auth.sessions where id=p_session and user_id=auth.uid()
    on conflict do nothing;
  return found;
end;
$$;

revoke execute on function mw_private.family_session_active(), mw_private.family_has_access(),
  mw_private.family_claim_invitation(), mw_private.family_add_pending_member()
  from public, anon, authenticated;
grant execute on function mw_private.family_has_access() to authenticated;

revoke execute on function public.mw_family_invite_create(text), public.mw_family_invite_cancel(uuid),
  public.mw_family_owner_list(), public.mw_family_member_set(uuid,text), public.mw_family_access_status(),
  public.mw_family_sessions(), public.mw_family_session_revoke(uuid) from public, anon, authenticated;
grant execute on function public.mw_family_invite_create(text), public.mw_family_invite_cancel(uuid),
  public.mw_family_owner_list(), public.mw_family_member_set(uuid,text), public.mw_family_access_status(),
  public.mw_family_sessions(), public.mw_family_session_revoke(uuid) to authenticated;

-- Restrictive policies preserve all existing resource-specific checks.
do $policies$
declare target text;
begin
  foreach target in array array[
    'mw_ai_access','mw_ai_usage','mw_complaints','mw_custom_emoji','mw_sticker_assets',
    'mw_sticker_favorites','mw_sticker_pack_items','mw_sticker_packs','mw_sticker_recents','mw_voice_room_members'
  ] loop
    execute format('create policy mw_family_active on public.%I as restrictive for all to authenticated
      using ((select mw_private.family_has_access())) with check ((select mw_private.family_has_access()))',target);
  end loop;
end;
$policies$;

create policy mw_family_storage_active on storage.objects as restrictive for all to authenticated
  using (bucket_id not in ('mw-media','mw-room-media','mw-room-books','mw-stickers') or (select mw_private.family_has_access()))
  with check (bucket_id not in ('mw-media','mw-room-media','mw-room-books','mw-stickers') or (select mw_private.family_has_access()));

create policy mw_family_signal_active on realtime.messages as restrictive for all to authenticated
  using (split_part(realtime.topic(),':',1)<>'mw-room' or (select mw_private.family_has_access()))
  with check (split_part(realtime.topic(),':',1)<>'mw-room' or (select mw_private.family_has_access()));

-- Helper RPCs must deny a stale or revoked token even when invoked outside RLS.
create or replace function public.mw_can_access_chat(p_chat uuid)
returns boolean language sql stable security definer set search_path = ''
as $$
  select mw_private.family_has_access() and exists (
    select 1 from public.mw_chats c where c.id=p_chat and auth.uid() in(c.member_a,c.member_b)
  );
$$;

-- Clients verify that the server gate is active before submitting signup credentials.
create function public.mw_family_signup_enabled()
returns boolean language sql stable set search_path = ''
as $$ select true; $$;
revoke execute on function public.mw_family_signup_enabled() from public;
grant execute on function public.mw_family_signup_enabled() to anon, authenticated;

-- Revoked accounts are no longer eligible targets for a new call or message.
CREATE OR REPLACE FUNCTION mw_private.privacy_allows(p_owner uuid, p_actor uuid, p_kind text)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
 select exists (select 1 from mw_private.family_members m where m.user_id=p_owner and m.state='active')
 and (p_owner=p_actor or (
  not exists(select 1 from public.mw_blocks b where (b.user_id=p_owner and b.person_id=p_actor) or (b.user_id=p_actor and b.person_id=p_owner))
  and case coalesce((select case p_kind when 'messages' then v.messages when 'calls' then v.calls when 'phone_search' then v.phone_search else 'nobody' end from public.mw_privacy v where v.user_id=p_owner),'everyone')
   when 'everyone' then true
   when 'contacts' then exists(select 1 from public.mw_privacy_contacts c where c.user_id=p_owner and c.person_id=p_actor)
   else false end
 ));
$function$;
