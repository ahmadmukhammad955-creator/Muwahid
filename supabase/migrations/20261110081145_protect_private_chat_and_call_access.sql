-- Keep personal attachments behind the same MFA boundary as chat history,
-- and reject tokens for sessions that no longer exist or have expired.
create function mw_private.can_access_personal_media()
returns boolean language sql stable security definer set search_path = ''
as $function$
  select exists (
    select 1 from auth.sessions s
    where s.id = case when pg_input_is_valid(auth.jwt()->>'session_id', 'uuid')
                      then (auth.jwt()->>'session_id')::uuid end
      and s.user_id = auth.uid()
      and (s.not_after is null or s.not_after > now())
  ) and (
    coalesce(auth.jwt()->>'aal', '') = 'aal2'
    or not exists (
      select 1 from auth.mfa_factors f
      where f.user_id = auth.uid() and f.status = 'verified'
    )
  );
$function$;

revoke all on function mw_private.can_access_personal_media() from public, anon;
grant execute on function mw_private.can_access_personal_media() to authenticated;

create policy mw_personal_media_verified_session on storage.objects
  as restrictive for all to authenticated
  using (bucket_id <> 'mw-media' or (select mw_private.can_access_personal_media()))
  with check (bucket_id <> 'mw-media' or (select mw_private.can_access_personal_media()));

-- Accept the existing Android encrypted message envelope. This checks the
-- wire format only; authentication of the ciphertext happens on the device.
CREATE OR REPLACE FUNCTION public.mw_send(p_chat uuid, p_body text, p_nonce uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare result jsonb;
begin
  perform mw_private.require_mfa();
 if auth.uid() is null or not exists(
   select 1 from public.mw_chats
   where id=p_chat and auth.uid() in(member_a,member_b)
 ) then raise exception 'MW:Нет доступа к этому чату'; end if;
 if not mw_private.privacy_allows((select case when member_a=auth.uid() then member_b else member_a end from public.mw_chats where id=p_chat),auth.uid(),'messages') then raise exception 'MW:Пользователь ограничил сообщения'; end if;
 if p_body is null or char_length(btrim(p_body)) not between 1 and 16000 then
  raise exception 'MW:Сообщение слишком большое';
 end if;
 if btrim(p_body) !~ '^mw1:[A-Za-z0-9+/]{16}:[A-Za-z0-9+/]{23,}={0,2}$' then
  raise exception 'MW:Для личных сообщений требуется шифрование';
 end if;
 if p_nonce is null then raise exception 'MW:Некорректный идентификатор сообщения'; end if;
 select to_jsonb(m) into result
 from public.mw_messages m
 where sender_id=auth.uid() and client_nonce=p_nonce;
 if result is not null then
  if result->>'chat_id' <> p_chat::text or result->>'body' <> btrim(p_body) then
   raise exception 'MW:Конфликт повторной отправки';
  end if;
  return result;
 end if;
 if (select count(*) from public.mw_messages
     where sender_id=auth.uid() and created_at>now()-interval '1 minute')>=60 then
  raise exception 'MW:Слишком много сообщений. Подождите минуту.';
 end if;
 insert into public.mw_messages(chat_id,sender_id,client_nonce,body)
 values(p_chat,auth.uid(),p_nonce,btrim(p_body))
 on conflict(sender_id,client_nonce) do nothing;
 select to_jsonb(m) into result
 from public.mw_messages m
 where sender_id=auth.uid() and client_nonce=p_nonce;
 if result->>'chat_id' <> p_chat::text or result->>'body' <> btrim(p_body) then
  raise exception 'MW:Конфликт повторной отправки';
 end if;
 return result;
end $function$;

-- Check expiry and current call permissions atomically when accepting.
CREATE OR REPLACE FUNCTION public.mw_call_answer(p_call uuid, p_answer text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare result jsonb;
begin
  perform mw_private.require_mfa();
 if auth.uid() is null then raise exception 'MW:Войдите в аккаунт'; end if;
 if p_answer is null or char_length(p_answer) not between 20 and 100000 then
  raise exception 'MW:Некорректный ответ звонка';
 end if;
 update public.mw_calls set answer=p_answer,status='accepted',updated_at=now()
 where id=p_call and callee_id=auth.uid() and status='ringing'
   and created_at > now()-interval '90 seconds'
   and mw_private.privacy_allows(callee_id,caller_id,'calls');
 if not found then raise exception 'MW:Звонок уже завершён'; end if;
 select to_jsonb(c) into result from public.mw_calls c where c.id=p_call;
 return result;
end $function$;
