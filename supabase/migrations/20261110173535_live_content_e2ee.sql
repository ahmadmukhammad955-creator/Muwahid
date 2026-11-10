-- Candidate: apply only with the tested client. No rows are deleted or rewritten.
-- The cutover script is separate so old clients continue working during rollout.
create or replace function public.mw_room_secure_recipients(p_room uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
  perform mw_private.require_mfa();
  if not exists(select 1 from public.mw_voice_rooms r join public.mw_voice_room_members m on m.room_id=r.id
    where r.id=p_room and r.active and m.user_id=auth.uid()) then raise exception 'MW:Нет доступа к эфиру'; end if;
  select coalesce(jsonb_agg(jsonb_build_object('id',m.user_id,'chat_id',c.id,'public_key',k.public_key) order by m.user_id),'[]'::jsonb)
    into result
  from public.mw_voice_room_members m
  join mw_private.family_members f on f.user_id=m.user_id and f.state='active'
  left join public.mw_chats c on (c.member_a=auth.uid() and c.member_b=m.user_id) or (c.member_b=auth.uid() and c.member_a=m.user_id)
  left join public.mw_e2ee_keys k on k.user_id=m.user_id
  where m.room_id=p_room and not mw_private.account_is_suspended(m.user_id);
  return result;
end $$;
revoke all on function public.mw_room_secure_recipients(uuid) from public,anon;
grant execute on function public.mw_room_secure_recipients(uuid) to authenticated;

-- Lock membership transitions against send's recipient snapshot. Heartbeats do not need this lock.
create or replace function mw_private.live_membership_lock()
returns trigger language plpgsql security definer set search_path='' as $$
begin
  if tg_op='UPDATE' and new.room_id=old.room_id and new.user_id=old.user_id then return new; end if;
  if tg_op<>'INSERT' then perform 1 from public.mw_voice_rooms where id=old.room_id for update; end if;
  if tg_op<>'DELETE' then perform 1 from public.mw_voice_rooms where id=new.room_id for update;return new; end if;
  return old;
end $$;
revoke all on function mw_private.live_membership_lock() from public,anon,authenticated;
create trigger live_membership_lock before insert or update or delete on public.mw_voice_room_members
for each row execute function mw_private.live_membership_lock();

-- Ciphertext can exceed the former 4000-character plaintext limit.
alter table public.mw_voice_room_messages drop constraint mw_voice_room_messages_body_check;
alter table public.mw_voice_room_messages add constraint mw_voice_room_messages_body_check check(char_length(body)<=32768);

create or replace function public.mw_room_secure_send(p_room uuid,p_kind text,p_body text,p_media jsonb,p_recipients jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare recipients jsonb; recipient jsonb; wrapped jsonb; mid bigint; result jsonb; path text; actual_ids jsonb; expected_ids jsonb;
begin
  perform mw_private.require_mfa();
  perform 1 from public.mw_voice_rooms where id=p_room and active for update;
  if not found then raise exception 'MW:Эфир закрыт'; end if;
  -- Prevent a concurrent key replacement/revocation after snapshot validation.
  perform 1 from public.mw_e2ee_keys where user_id in(select user_id from public.mw_voice_room_members where room_id=p_room) for share;
  perform 1 from mw_private.family_members where user_id in(select user_id from public.mw_voice_room_members where room_id=p_room) for share;
  recipients:=public.mw_room_secure_recipients(p_room);
  if p_recipients is distinct from recipients then raise exception 'MW:Состав или ключи участников изменились. Повторите отправку'; end if;
  if jsonb_array_length(recipients) not between 1 and 12 then raise exception 'MW:Некорректный состав эфира'; end if;
  if p_kind is null or p_kind not in('text','image','video','file') then raise exception 'MW:Некорректный тип сообщения'; end if;
  if p_body is null or length(p_body) not between 45 and 32768 or p_body !~ '^mwl1:[A-Za-z0-9+/]+={0,2}$' then raise exception 'MW:Требуется шифрование LIVE'; end if;
  if p_media is null or jsonb_typeof(p_media)<>'object' or octet_length(p_media::text)>32768
    or p_media->>'v' is distinct from '1' or jsonb_typeof(p_media->'keys') is distinct from 'object'
    or coalesce(p_media->>'nonce','') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    then raise exception 'MW:Некорректный конверт LIVE'; end if;
  select jsonb_agg(key order by key) into actual_ids from jsonb_object_keys(p_media->'keys') key;
  select jsonb_agg(value->>'id' order by value->>'id') into expected_ids from jsonb_array_elements(recipients);
  if actual_ids is distinct from expected_ids then raise exception 'MW:Ключи должны быть адресованы только текущим участникам'; end if;
  for recipient in select value from jsonb_array_elements(recipients) loop
    wrapped:=p_media->'keys'->(recipient->>'id');
    if jsonb_typeof(wrapped)<>'object' or coalesce(length(wrapped->>'wrapped'),0) not between 40 and 4096
      then raise exception 'MW:Нет зашифрованного ключа участника'; end if;
    if recipient->>'id'<>auth.uid()::text and (
      recipient->>'public_key' is null or recipient->>'chat_id' is null or wrapped->>'chat' is distinct from recipient->>'chat_id'
      or wrapped->>'wrapped' not like 'mw1:%') then raise exception 'MW:Требуется проверенный личный чат'; end if;
  end loop;
  path:=p_media->>'path';
  if p_kind<>'text' then
    if path is null or path !~ ('^'||p_room::text||'/(background/|books/)?e2ee/[0-9a-f-]{36}\.[a-z0-9]{2,5}$')
      then raise exception 'MW:Некорректный путь зашифрованного файла'; end if;
    if not exists(select 1 from storage.objects o where o.name=path and o.owner_id=auth.uid()::text
      and o.bucket_id=case when p_media->>'live_book'='true' then 'mw-room-books' else 'mw-room-media' end)
      then raise exception 'MW:Зашифрованный файл не загружен'; end if;
  end if;
  if p_media->>'live_background'='true' and not exists(select 1 from public.mw_voice_rooms where id=p_room and created_by=auth.uid())
    then raise exception 'MW:Фон меняет ведущий'; end if;
  if exists(select 1 from public.mw_voice_room_messages where room_id=p_room and sender_id=auth.uid() and media->>'nonce'=p_media->>'nonce')
    then raise exception 'MW:Это сообщение уже отправлено'; end if;
  insert into public.mw_voice_room_messages(room_id,sender_id,kind,body,media) values(p_room,auth.uid(),p_kind,p_body,p_media) returning id into mid;
  select to_jsonb(m)||jsonb_build_object('sender_name',p.display_name) into result
    from public.mw_voice_room_messages m join public.mw_profiles p on p.id=m.sender_id where m.id=mid;
  return result;
end $$;
revoke all on function public.mw_room_secure_send(uuid,text,text,jsonb,jsonb) from public,anon;
grant execute on function public.mw_room_secure_send(uuid,text,text,jsonb,jsonb) to authenticated;

create or replace function public.mw_room_secure_media_get(p_room uuid,p_path text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
  perform public.mw_room_secure_recipients(p_room);
  select to_jsonb(m)||jsonb_build_object('sender_name',p.display_name) into result
    from public.mw_voice_room_messages m join public.mw_profiles p on p.id=m.sender_id
    where m.room_id=p_room and m.media->>'path'=p_path order by m.id desc limit 1;
  return result;
end $$;
revoke all on function public.mw_room_secure_media_get(uuid,text) from public,anon;
grant execute on function public.mw_room_secure_media_get(uuid,text) to authenticated;

create or replace function public.mw_room_secure_background_get(p_room uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
  perform public.mw_room_secure_recipients(p_room);
  select to_jsonb(m)||jsonb_build_object('sender_name',p.display_name) into result
    from public.mw_voice_room_messages m join public.mw_profiles p on p.id=m.sender_id
    join public.mw_voice_rooms r on r.id=m.room_id and r.created_by=m.sender_id
    where m.room_id=p_room and m.media->>'live_background'='true' order by m.id desc limit 1;
  return result;
end $$;
revoke all on function public.mw_room_secure_background_get(uuid) from public,anon;
grant execute on function public.mw_room_secure_background_get(uuid) to authenticated;
