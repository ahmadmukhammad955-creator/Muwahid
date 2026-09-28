-- Muwahid 0.6: encrypted media, audio/video call signalling, notification polling.
-- Run after 001..007.
begin;

insert into storage.buckets (id,name,public,file_size_limit)
values ('mw-media','mw-media',false,26214400)
on conflict (id) do update set public=false,file_size_limit=26214400;

drop policy if exists mw_media_select on storage.objects;
drop policy if exists mw_media_insert on storage.objects;
drop policy if exists mw_media_delete on storage.objects;
create policy mw_media_select on storage.objects for select to authenticated
using (
 bucket_id='mw-media' and exists(
  select 1 from public.mw_chats c
  where c.id = ((storage.foldername(name))[1])::uuid
    and auth.uid() in (c.member_a,c.member_b)
 )
);
create policy mw_media_insert on storage.objects for insert to authenticated
with check (
 bucket_id='mw-media' and exists(
  select 1 from public.mw_chats c
  where c.id = ((storage.foldername(name))[1])::uuid
    and auth.uid() in (c.member_a,c.member_b)
 )
);
create policy mw_media_delete on storage.objects for delete to authenticated
using (
 bucket_id='mw-media' and exists(
  select 1 from public.mw_chats c
  where c.id = ((storage.foldername(name))[1])::uuid
    and auth.uid() in (c.member_a,c.member_b)
 )
);

create table if not exists public.mw_calls (
 id uuid primary key default gen_random_uuid(),
 chat_id uuid not null references public.mw_chats(id) on delete cascade,
 caller_id uuid not null references public.mw_profiles(id) on delete cascade,
 callee_id uuid not null references public.mw_profiles(id) on delete cascade,
 kind text not null check (kind in ('audio','video')),
 status text not null default 'ringing' check (status in ('ringing','accepted','rejected','ended','missed')),
 offer text not null,
 answer text,
 caller_ice jsonb not null default '[]'::jsonb,
 callee_ice jsonb not null default '[]'::jsonb,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now()
);
alter table public.mw_calls enable row level security;
revoke all on public.mw_calls from anon,authenticated;
create index if not exists mw_calls_callee_status_idx on public.mw_calls(callee_id,status,created_at desc);

create or replace function public.mw_call_start(p_chat uuid,p_kind text,p_offer text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare peer uuid; new_id uuid; result jsonb;
begin
 perform mw_private.require_mfa();
 if p_kind not in ('audio','video') then raise exception 'MW:Некорректный тип звонка'; end if;
 if char_length(p_offer) not between 20 and 100000 then raise exception 'MW:Некорректное описание звонка'; end if;
 select case when member_a=auth.uid() then member_b else member_a end into peer
 from public.mw_chats where id=p_chat and auth.uid() in(member_a,member_b);
 if peer is null then raise exception 'MW:Нет доступа к этому чату'; end if;
 update public.mw_calls set status='missed',updated_at=now()
 where callee_id=peer and status='ringing' and created_at<now()-interval '90 seconds';
 insert into public.mw_calls(chat_id,caller_id,callee_id,kind,offer)
 values(p_chat,auth.uid(),peer,p_kind,p_offer) returning id into new_id;
 select to_jsonb(c) into result from public.mw_calls c where c.id=new_id;
 return result;
end $$;

create or replace function public.mw_call_get(p_call uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
 perform mw_private.require_mfa();
 update public.mw_calls set status='missed',updated_at=now() where id=p_call and status='ringing' and created_at<now()-interval '90 seconds';
 select to_jsonb(c) || jsonb_build_object('peer_name',p.display_name) into result
 from public.mw_calls c
 join public.mw_profiles p on p.id=case when c.caller_id=auth.uid() then c.callee_id else c.caller_id end
 where c.id=p_call and auth.uid() in(c.caller_id,c.callee_id);
 if result is null then raise exception 'MW:Звонок не найден'; end if;
 return result;
end $$;

create or replace function public.mw_call_answer(p_call uuid,p_answer text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
 perform mw_private.require_mfa();
 if char_length(p_answer) not between 20 and 100000 then raise exception 'MW:Некорректный ответ звонка'; end if;
 update public.mw_calls set answer=p_answer,status='accepted',updated_at=now()
 where id=p_call and callee_id=auth.uid() and status='ringing';
 if not found then raise exception 'MW:Звонок уже завершён'; end if;
 select to_jsonb(c) into result from public.mw_calls c where c.id=p_call;
 return result;
end $$;

create or replace function public.mw_call_ice(p_call uuid,p_candidate jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare c public.mw_calls; result jsonb;
begin
 perform mw_private.require_mfa();
 if p_candidate is null or jsonb_typeof(p_candidate)<>'object' then raise exception 'MW:Некорректный ICE-кандидат'; end if;
 select * into c from public.mw_calls where id=p_call and auth.uid() in(caller_id,callee_id);
 if c.id is null then raise exception 'MW:Звонок не найден'; end if;
 if auth.uid()=c.caller_id then
  update public.mw_calls set caller_ice=caller_ice||jsonb_build_array(p_candidate),updated_at=now() where id=p_call;
 else
  update public.mw_calls set callee_ice=callee_ice||jsonb_build_array(p_candidate),updated_at=now() where id=p_call;
 end if;
 select to_jsonb(x) into result from public.mw_calls x where id=p_call;
 return result;
end $$;

create or replace function public.mw_call_end(p_call uuid,p_status text default 'ended') returns jsonb
language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
 perform mw_private.require_mfa();
 if p_status not in ('ended','rejected','missed') then p_status:='ended'; end if;
 update public.mw_calls set status=p_status,updated_at=now()
 where id=p_call and auth.uid() in(caller_id,callee_id) and status in ('ringing','accepted');
 select to_jsonb(c) into result from public.mw_calls c where c.id=p_call and auth.uid() in(c.caller_id,c.callee_id);
 return coalesce(result,jsonb_build_object('ok',true));
end $$;

create or replace function public.mw_events_poll(p_after bigint default 0) returns jsonb
language plpgsql security definer set search_path='' as $$
declare messages jsonb; calls jsonb; cursor bigint;
begin
 perform mw_private.require_mfa();
 select coalesce(max(m.id),0) into cursor
 from public.mw_messages m join public.mw_chats c on c.id=m.chat_id
 where auth.uid() in(c.member_a,c.member_b);
 select coalesce(jsonb_agg(to_jsonb(x) order by x.id),'[]'::jsonb) into messages from (
  select m.id,m.chat_id,m.sender_id,p.display_name as sender_name,m.created_at
  from public.mw_messages m
  join public.mw_chats c on c.id=m.chat_id
  join public.mw_profiles p on p.id=m.sender_id
  where auth.uid() in(c.member_a,c.member_b) and m.sender_id<>auth.uid() and m.id>p_after
  order by m.id desc limit 20
 ) x;
 update public.mw_calls set status='missed',updated_at=now()
 where callee_id=auth.uid() and status='ringing' and created_at<now()-interval '90 seconds';
 select coalesce(jsonb_agg(to_jsonb(x) order by x.created_at desc),'[]'::jsonb) into calls from (
  select c.id,c.chat_id,c.kind,c.created_at,p.display_name as caller_name
  from public.mw_calls c join public.mw_profiles p on p.id=c.caller_id
  where c.callee_id=auth.uid() and c.status='ringing' and c.created_at>now()-interval '90 seconds'
  order by c.created_at desc limit 5
 ) x;
 return jsonb_build_object('cursor',cursor,'messages',messages,'calls',calls);
end $$;

revoke all on function public.mw_call_start(uuid,text,text),public.mw_call_get(uuid),public.mw_call_answer(uuid,text),public.mw_call_ice(uuid,jsonb),public.mw_call_end(uuid,text),public.mw_events_poll(bigint) from public,anon,authenticated;
grant execute on function public.mw_call_start(uuid,text,text),public.mw_call_get(uuid),public.mw_call_answer(uuid,text),public.mw_call_ice(uuid,jsonb),public.mw_call_end(uuid,text),public.mw_events_poll(bigint) to authenticated;

commit;


-- Muwahid 0.7: phone identity and exact phone-number discovery.
-- Requires Supabase Auth -> Phone enabled with an SMS provider.
begin;

alter table public.mw_profiles add column if not exists phone_e164 text;
create unique index if not exists mw_profiles_phone_e164_uidx
  on public.mw_profiles(phone_e164) where phone_e164 is not null;

do $$
begin
 if not exists (
  select 1 from pg_constraint
  where conname='mw_profiles_phone_e164_check'
    and conrelid='public.mw_profiles'::regclass
 ) then
  alter table public.mw_profiles
   add constraint mw_profiles_phone_e164_check
   check (phone_e164 is null or phone_e164 ~ '^\+[1-9][0-9]{7,14}$');
 end if;
end $$;

-- Keep phone identity on new users while preserving the existing generated username.
create or replace function public.mw_create_profile() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 insert into public.mw_profiles(id,username,display_name,phone_e164)
 values(
  new.id,
  'u_' || replace(new.id::text,'-',''),
  left(coalesce(nullif(btrim(new.raw_user_meta_data->>'display_name'),''),'Пользователь'),60),
  case when coalesce(new.phone,'') ~ '^\+[1-9][0-9]{7,14}$' then new.phone else null end
 );
 return new;
end $$;

-- Backfill phone numbers for accounts that existed before this migration.
update public.mw_profiles p
set phone_e164=u.phone
from auth.users u
where p.id=u.id
  and p.phone_e164 is null
  and coalesce(u.phone,'') ~ '^\+[1-9][0-9]{7,14}$';

create or replace function public.mw_sync_my_phone() returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_phone text;
begin
 perform mw_private.require_mfa();
 select phone into v_phone from auth.users where id=auth.uid();
 if coalesce(v_phone,'') !~ '^\+[1-9][0-9]{7,14}$' then
  raise exception 'MW:Номер телефона не подтверждён';
 end if;
 update public.mw_profiles set phone_e164=v_phone where id=auth.uid();
 return public.mw_me();
end $$;

-- Exact lookup only. The returned object deliberately does not expose the peer phone number.
create or replace function public.mw_find_person_any(p_query text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare q text; normalized_phone text; result jsonb;
begin
 perform mw_private.require_mfa();
 q:=btrim(coalesce(p_query,''));
 if char_length(q) < 4 or char_length(q) > 40 then
  raise exception 'MW:Введите номер телефона или имя пользователя';
 end if;
 normalized_phone:=regexp_replace(q,'[[:space:]()\-]','','g');
 if normalized_phone ~ '^\+[1-9][0-9]{7,14}$' then
  select jsonb_build_object('id',p.id,'display_name',p.display_name,'username',p.username)
   into result
  from public.mw_profiles p
  where p.phone_e164=normalized_phone and p.id<>auth.uid();
 else
  q:=lower(regexp_replace(q,'^@','',''));
  select jsonb_build_object('id',p.id,'display_name',p.display_name,'username',p.username)
   into result
  from public.mw_profiles p
  where p.username=q and p.id<>auth.uid();
 end if;
 return result;
end $$;

revoke all on function public.mw_sync_my_phone(), public.mw_find_person_any(text)
 from public,anon,authenticated;
grant execute on function public.mw_sync_my_phone(), public.mw_find_person_any(text)
 to authenticated;

commit;
