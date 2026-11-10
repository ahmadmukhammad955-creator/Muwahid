-- Run as the project SQL administrator with at least one chat and three test users.
-- No changes to chats, calls, accounts or media are persisted.
do $tests$
declare
  chat public.mw_chats%rowtype;
  outside_user uuid;
  attachment_owner uuid;
  delete_filter text;
  deleted_allowed boolean;
begin
  select c.* into strict chat from public.mw_chats c
    where exists (select 1 from public.mw_messages m where m.chat_id=c.id) limit 1;
  select id into strict outside_user from auth.users where id not in (chat.member_a,chat.member_b) limit 1;
  select owner_id::uuid into attachment_owner from storage.objects
    where bucket_id='mw-media' and (storage.foldername(name))[1]=chat.id::text and owner_id is not null limit 1;
  if not has_function_privilege('authenticated','public.mw_call_ice(uuid,jsonb)','execute') then
    raise exception 'Existing app cannot invoke call signaling';
  end if;
  if has_function_privilege('authenticated','public.mw_touch_updated_at()','execute')
    or has_function_privilege('anon','mw_private.broadcast_voice_room_signal()','execute') then
    raise exception 'Trigger function still has a client EXECUTE grant';
  end if;

  select qual into strict delete_filter from pg_policies
    where schemaname='storage' and tablename='objects' and policyname='mw_media_delete';
  execute 'set local role authenticated';
  perform set_config('request.jwt.claims',jsonb_build_object('sub',chat.member_a,'aal','aal2','role','authenticated')::text,true);
  if not public.mw_can_access_chat(chat.id) or jsonb_array_length(public.mw_messages_get(chat.id))=0 then
    raise exception 'Existing chat member lost read access';
  end if;

  if attachment_owner in (chat.member_a,chat.member_b) then
    perform set_config('request.jwt.claims',jsonb_build_object('sub',case when attachment_owner=chat.member_a then chat.member_b else chat.member_a end,'aal','aal2','role','authenticated')::text,true);
    if not exists (select 1 from storage.objects
       where bucket_id='mw-media' and (storage.foldername(name))[1]=chat.id::text and owner_id=attachment_owner::text) then
      raise exception 'Recipient lost read access to attachment';
    end if;
    -- Evaluate the exact DELETE policy as the recipient. Storage blocks direct
    -- SQL DELETE statements, including those which would match zero rows.
    execute format('select exists (select 1 from storage.objects where bucket_id = %L and (storage.foldername(name))[1] = %L and owner_id = %L and (%s))',
      'mw-media',chat.id,attachment_owner,delete_filter) into strict deleted_allowed;
    if deleted_allowed then raise exception 'Recipient could delete the sender attachment'; end if;
  end if;

  perform set_config('request.jwt.claims',jsonb_build_object('sub',outside_user,'aal','aal2','role','authenticated')::text,true);
  if public.mw_can_access_chat(chat.id) then raise exception 'Outsider gained chat access'; end if;
  begin
    perform public.mw_messages_get(chat.id);
    raise exception 'Outsider read another chat';
  exception when raise_exception then
    if sqlerrm <> 'MW:Нет доступа к этому чату' then raise; end if;
  end;
  begin
    perform public.mw_call_start(chat.id,'audio',repeat('s',40));
    raise exception 'Outsider started a call from another chat';
  exception when raise_exception then
    if sqlerrm <> 'MW:Нет доступа к этому чату' then raise; end if;
  end;
  execute 'reset role';
end;
$tests$;
