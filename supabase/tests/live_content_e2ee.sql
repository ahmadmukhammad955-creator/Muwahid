-- Execute after candidate SQL in ONE transaction, always ROLLBACK.
-- Synthetic room/content only. No message contents or identifying account data are returned.
do $tests$
declare
  owner_id uuid; owner_session uuid; peer_id uuid; peer_session uuid;
  room uuid:=gen_random_uuid(); recipients jsonb; env jsonb; stale jsonb; result jsonb; denied boolean; peer_key text;
begin
  select o.user_id into strict owner_id from mw_private.owner_account o;
  select f.user_id into peer_id from mw_private.family_members f
    where f.state='active' and f.user_id<>owner_id and not mw_private.account_is_suspended(f.user_id) limit 1;
  if peer_id is null then raise exception 'Test needs a second active account'; end if;
  owner_session:=gen_random_uuid();peer_session:=gen_random_uuid();
  insert into auth.sessions(id,user_id,not_after) values(owner_session,owner_id,now()+interval '5 minutes'),(peer_session,peer_id,now()+interval '5 minutes');
  insert into public.mw_voice_rooms(id,title,created_by,access_mode) values(room,'Synthetic LIVE encryption test',owner_id,'closed');
  insert into public.mw_voice_room_members(room_id,user_id) values(room,owner_id);
  perform set_config('request.jwt.claims',jsonb_build_object('sub',owner_id,'session_id',owner_session,'role','authenticated','aal','aal2')::text,true);
  execute 'set local role authenticated';
  recipients:=public.mw_room_secure_recipients(room);
  if jsonb_array_length(recipients)<>1 then raise exception 'Unexpected recipients'; end if;
  env:=jsonb_build_object('v',1,'nonce',gen_random_uuid(),'keys',jsonb_build_object(owner_id::text,jsonb_build_object('wrapped',repeat('A',80))));
  denied:=false;
  begin perform public.mw_room_secure_send(room,'text','Synthetic plaintext',env,recipients);exception when raise_exception then denied:=sqlerrm='MW:Требуется шифрование LIVE';end;
  if not denied then raise exception 'Plaintext accepted'; end if;
  result:=public.mw_room_secure_send(room,'text','mwl1:'||repeat('A',80),env,recipients);
  if result->>'body'<>'mwl1:'||repeat('A',80) then raise exception 'Ciphertext changed'; end if;
  denied:=false;
  begin perform public.mw_room_secure_send(room,'text','mwl1:'||repeat('A',80),env,recipients);exception when raise_exception then denied:=sqlerrm='MW:Это сообщение уже отправлено';end;
  if not denied then raise exception 'Replay accepted'; end if;
  env:=jsonb_set(env,'{nonce}',to_jsonb(gen_random_uuid()));
  denied:=false;
  begin perform public.mw_room_secure_send(room,'text','mwl1:'||repeat('A',80),env||jsonb_build_object('keys',(env->'keys')||jsonb_build_object(peer_id::text,jsonb_build_object('wrapped',repeat('A',80)))),recipients);
  exception when raise_exception then denied:=sqlerrm='MW:Ключи должны быть адресованы только текущим участникам';end;
  if not denied then raise exception 'Extra recipient accepted'; end if;
  denied:=false;
  begin perform public.mw_room_secure_send(room,'image','mwl1:'||repeat('A',80),env||jsonb_build_object('path',room::text||'/e2ee/'||gen_random_uuid()::text||'.bin'),recipients);
  exception when raise_exception then denied:=sqlerrm='MW:Зашифрованный файл не загружен';end;
  if not denied then raise exception 'Absent file accepted'; end if;

  perform set_config('request.jwt.claims',jsonb_build_object('sub',peer_id,'session_id',peer_session,'role','authenticated','aal','aal2')::text,true);
  denied:=false;begin perform public.mw_room_secure_recipients(room);exception when raise_exception then denied:=sqlerrm='MW:Нет доступа к эфиру';end;
  if not denied then raise exception 'Outsider got recipients'; end if;
  denied:=false;begin perform public.mw_room_secure_media_get(room,'test');exception when raise_exception then denied:=sqlerrm='MW:Нет доступа к эфиру';end;
  if not denied then raise exception 'Outsider got file envelope'; end if;
  execute 'reset role';
  insert into public.mw_voice_room_members(room_id,user_id) values(room,peer_id);
  execute 'set local role authenticated';
  perform set_config('request.jwt.claims',jsonb_build_object('sub',owner_id,'session_id',owner_session,'role','authenticated','aal','aal2')::text,true);
  denied:=false;begin perform public.mw_room_secure_send(room,'text','mwl1:'||repeat('A',80),env,recipients);
  exception when raise_exception then denied:=sqlerrm='MW:Состав или ключи участников изменились. Повторите отправку';end;
  if not denied then raise exception 'Stale membership accepted'; end if;
  stale:=public.mw_room_secure_recipients(room);
  execute 'reset role';
  delete from public.mw_voice_room_members where room_id=room and user_id=peer_id;
  execute 'set local role authenticated';
  denied:=false;begin perform public.mw_room_secure_send(room,'text','mwl1:'||repeat('A',80),env,stale);
  exception when raise_exception then denied:=sqlerrm='MW:Состав или ключи участников изменились. Повторите отправку';end;
  if not denied then raise exception 'Removed recipient accepted'; end if;
  perform set_config('request.jwt.claims',jsonb_build_object('sub',peer_id,'session_id',peer_session,'role','authenticated','aal','aal2')::text,true);
  denied:=false;begin perform public.mw_room_secure_background_get(room);exception when raise_exception then denied:=sqlerrm='MW:Нет доступа к эфиру';end;
  if not denied then raise exception 'Removed participant got background'; end if;
  execute 'reset role';
  execute 'set local role anon';
  denied:=false;begin perform public.mw_room_secure_recipients(room);exception when insufficient_privilege then denied:=true;end;
  if not denied then raise exception 'Anonymous RPC permitted'; end if;
  execute 'reset role';
end $tests$;
