-- SQL integration checks against a project with an active private chat.
-- The nested transaction rolls back all test writes, including on success.
do $tests$
declare
  chat public.mw_chats%rowtype;
  media_chat public.mw_chats%rowtype;
  member_with_mfa uuid;
  active_session uuid;
  outsider uuid;
  media_outsider uuid;
  outsider_session uuid := gen_random_uuid();
  callee_claims jsonb;
  member_claims jsonb;
  caller_claims jsonb;
  fresh_call uuid;
  expired_call uuid;
  blocked_call uuid;
  nonce uuid := gen_random_uuid();
  sample text := 'mw1:' || repeat('A',16) || ':' || repeat('A',23) || '=';
begin
  select c.* into strict chat from public.mw_chats c
  where mw_private.privacy_allows(c.member_b,c.member_a,'calls')
    and mw_private.privacy_allows(c.member_b,c.member_a,'messages')
  limit 1;
  select c.* into strict media_chat from public.mw_chats c
  where exists (select 1 from storage.objects o
    where o.bucket_id='mw-media' and (storage.foldername(o.name))[1]=c.id::text)
    and exists (select 1 from auth.sessions s join auth.mfa_factors f on f.user_id=s.user_id
      where s.user_id in(c.member_a,c.member_b) and f.status='verified'
        and (s.not_after is null or s.not_after>now()))
  limit 1;
  select s.user_id,s.id into strict member_with_mfa,active_session
  from auth.sessions s join auth.mfa_factors f on f.user_id=s.user_id
  where s.user_id in(media_chat.member_a,media_chat.member_b) and f.status='verified'
    and (s.not_after is null or s.not_after>now())
  limit 1;
  select id into strict outsider from auth.users
    where id not in (chat.member_a,chat.member_b) limit 1;
  select id into strict media_outsider from auth.users
    where id not in (media_chat.member_a,media_chat.member_b) limit 1;

  member_claims := jsonb_build_object('role','authenticated','sub',member_with_mfa,'session_id',active_session,'aal','aal2');
  caller_claims := jsonb_build_object('role','authenticated','sub',chat.member_a,'aal','aal2');
  callee_claims := jsonb_build_object('role','authenticated','sub',chat.member_b,'aal','aal2');
  begin
    insert into auth.sessions(id,user_id) values(outsider_session,media_outsider);
    insert into public.mw_calls(chat_id,caller_id,callee_id,kind,offer)
      values(chat.id,chat.member_a,chat.member_b,'video',repeat('s',40)) returning id into fresh_call;
    insert into public.mw_calls(chat_id,caller_id,callee_id,kind,offer,created_at)
      values(chat.id,chat.member_a,chat.member_b,'video',repeat('s',40),now()-interval '91 seconds') returning id into expired_call;
    insert into public.mw_calls(chat_id,caller_id,callee_id,kind,offer)
      values(chat.id,chat.member_a,chat.member_b,'video',repeat('s',40)) returning id into blocked_call;

    set local role authenticated;
    perform set_config('request.jwt.claims',member_claims::text,true);
    if not exists (select 1 from storage.objects where bucket_id='mw-media'
        and (storage.foldername(name))[1]=media_chat.id::text) then
      raise exception 'Verified chat member lost access to attachments';
    end if;
    perform set_config('request.jwt.claims',(member_claims || '{"aal":"aal1"}')::text,true);
    if exists (select 1 from storage.objects where bucket_id='mw-media'
        and (storage.foldername(name))[1]=media_chat.id::text) then
      raise exception 'Attachment access bypassed MFA';
    end if;
    perform set_config('request.jwt.claims',(member_claims || jsonb_build_object('session_id',gen_random_uuid()))::text,true);
    if exists (select 1 from storage.objects where bucket_id='mw-media'
        and (storage.foldername(name))[1]=media_chat.id::text) then
      raise exception 'Attachment access accepted a missing or revoked session';
    end if;

    perform set_config('request.jwt.claims',(member_claims || jsonb_build_object(
      'sub',media_outsider,'session_id',outsider_session))::text,true);
    if exists (select 1 from storage.objects where bucket_id='mw-media'
        and (storage.foldername(name))[1]=media_chat.id::text) then
      raise exception 'Outsider gained attachment access';
    end if;
    perform set_config('request.jwt.claims',jsonb_build_object('role','authenticated','sub',outsider,'aal','aal2')::text,true);
    begin
      perform public.mw_messages_get(chat.id);
      raise exception 'Outsider read the chat';
    exception when raise_exception then
      if sqlerrm <> 'MW:Нет доступа к этому чату' then raise; end if;
    end;
    begin
      perform public.mw_call_get(fresh_call);
      raise exception 'Outsider got call signaling';
    exception when raise_exception then
      if sqlerrm <> 'MW:Звонок не найден' then raise; end if;
    end;
    begin
      perform public.mw_call_answer(fresh_call,repeat('s',40));
      raise exception 'Outsider answered the call';
    exception when raise_exception then
      if sqlerrm <> 'MW:Звонок уже завершён' then raise; end if;
    end;

    perform set_config('request.jwt.claims',caller_claims::text,true);
    begin
      perform public.mw_send(chat.id,'plaintext test',nonce);
      raise exception 'Server accepted a plaintext message';
    exception when raise_exception then
      if sqlerrm <> 'MW:Для личных сообщений требуется шифрование' then raise; end if;
    end;
    if public.mw_send(chat.id,sample,nonce)->>'body' is distinct from sample then
      raise exception 'Server rejected the Android message envelope';
    end if;

    perform set_config('request.jwt.claims',callee_claims::text,true);
    if public.mw_call_answer(fresh_call,repeat('s',40))->>'status' is distinct from 'accepted' then
      raise exception 'Invited callee could not answer a current call';
    end if;
    begin
      perform public.mw_call_answer(expired_call,repeat('s',40));
      raise exception 'Callee answered an expired call';
    exception when raise_exception then
      if sqlerrm <> 'MW:Звонок уже завершён' then raise; end if;
    end;

    reset role;
    insert into public.mw_blocks(user_id,person_id) values(chat.member_b,chat.member_a);
    set local role authenticated;
    begin
      perform public.mw_call_answer(blocked_call,repeat('s',40));
      raise exception 'Callee answered a call after blocking its caller';
    exception when raise_exception then
      if sqlerrm <> 'MW:Звонок уже завершён' then raise; end if;
    end;
    raise exception using errcode='MW001', message='rollback test fixtures';
  exception when sqlstate 'MW001' then
    null;
  end;
end;
$tests$;

select 'private chat, attachments and call access: passed' as result;
