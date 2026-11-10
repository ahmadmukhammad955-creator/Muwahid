-- Run with the migration in a single transaction and roll it back.
do $tests$
declare
  owner_id uuid;
  owner_session uuid;
  new_id uuid := gen_random_uuid();
  new_session uuid := gen_random_uuid();
  new_other_session uuid := gen_random_uuid();
  current_member record;
  test_email text := new_id::text || '@example.invalid';
  token text;
  expired_token text;
begin
  select o.user_id into strict owner_id from mw_private.owner_account o;
  select s.id into owner_session from auth.sessions s where s.user_id=owner_id
    and (s.not_after is null or s.not_after>now()) limit 1;
  if owner_session is null then
    owner_session := gen_random_uuid();
    insert into auth.sessions(id,user_id,not_after) values(owner_session,owner_id,now()+interval '5 minutes');
  end if;

  for current_member in
    select distinct on (s.user_id) s.id,s.user_id from auth.sessions s
    where s.not_after is null or s.not_after>now() order by s.user_id,s.id
  loop
    execute 'set local role authenticated';
    perform set_config('request.jwt.claims',jsonb_build_object('sub',current_member.user_id,'session_id',current_member.id,'aal','aal2','role','authenticated')::text,true);
    if public.mw_family_access_status()<>'active' then raise exception 'Existing session was blocked'; end if;
    perform public.mw_chat_list();
    execute 'reset role';
  end loop;

  begin
    insert into auth.users(id,email,raw_user_meta_data) values(gen_random_uuid(),test_email,'{}');
    raise exception 'Uninvited signup was accepted';
  exception when raise_exception then
    if sqlerrm <> 'MW:Требуется действующее семейное приглашение' then raise; end if;
  end;

  execute 'set local role authenticated';
  perform set_config('request.jwt.claims',jsonb_build_object('sub',owner_id,'session_id',owner_session,'aal','aal2','role','authenticated')::text,true);
  expired_token := public.mw_family_invite_create(test_email);
  execute 'reset role';
  update mw_private.family_invitations set expires_at=now()-interval '1 minute'
    where token_hash=sha256(convert_to(expired_token,'UTF8'));
  begin
    insert into auth.users(id,email,raw_user_meta_data)
      values(new_id,test_email,jsonb_build_object('family_invite',expired_token));
    raise exception 'Expired invitation was accepted';
  exception when raise_exception then
    if sqlerrm <> 'MW:Требуется действующее семейное приглашение' then raise; end if;
  end;

  execute 'set local role authenticated';
  token := public.mw_family_invite_create(test_email);
  execute 'reset role';
  begin
    insert into auth.users(id,email,raw_user_meta_data)
      values(new_id,'other-'||test_email,jsonb_build_object('family_invite',token));
    raise exception 'Invitation was accepted for a different email';
  exception when raise_exception then
    if sqlerrm <> 'MW:Требуется действующее семейное приглашение' then raise; end if;
  end;
  insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data)
    values(new_id,test_email,now(),jsonb_build_object('family_invite',token,'display_name','Security test'));
  if exists(select 1 from auth.users where id=new_id and raw_user_meta_data ? 'family_invite') then
    raise exception 'Invitation leaked to user metadata';
  end if;
  begin
    insert into auth.users(id,email,raw_user_meta_data)
      values(gen_random_uuid(),test_email,jsonb_build_object('family_invite',token));
    raise exception 'Replayed invitation was accepted';
  exception when raise_exception then
    if sqlerrm <> 'MW:Требуется действующее семейное приглашение' then raise; end if;
  end;
  insert into auth.sessions(id,user_id,not_after)
    values(new_session,new_id,now()+interval '5 minutes'),(new_other_session,new_id,now()+interval '5 minutes');

  execute 'set local role authenticated';
  perform set_config('request.jwt.claims',jsonb_build_object('sub',new_id,'session_id',new_session,'aal','aal2','role','authenticated')::text,true);
  if public.mw_family_access_status()<>'pending' then raise exception 'New member skipped approval'; end if;
  begin
    perform public.mw_chat_list();
    raise exception 'Pending member accessed chats';
  exception when raise_exception then
    if sqlerrm <> 'MW:Ожидается подтверждение владельца' then raise; end if;
  end;

  perform set_config('request.jwt.claims',jsonb_build_object('sub',owner_id,'session_id',owner_session,'aal','aal2','role','authenticated')::text,true);
  if not public.mw_family_member_set(new_id,'active') then raise exception 'Owner could not approve member'; end if;
  perform set_config('request.jwt.claims',jsonb_build_object('sub',new_id,'session_id',new_session,'aal','aal2','role','authenticated')::text,true);
  if public.mw_family_access_status()<>'active' then raise exception 'Approved member lacks access'; end if;
  perform public.mw_chat_list();
  begin
    perform public.mw_family_invite_create('unauthorized@example.invalid');
    raise exception 'Regular member was able to create an invitation';
  exception when raise_exception then
    if sqlerrm <> 'MW:Доступ только для владельца' then raise; end if;
  end;

  if not public.mw_family_session_revoke(new_other_session) then raise exception 'Device revocation failed'; end if;
  perform set_config('request.jwt.claims',jsonb_build_object('sub',new_id,'session_id',new_other_session,'aal','aal2','role','authenticated')::text,true);
  if public.mw_family_access_status()<>'session_revoked' then raise exception 'Revoked device retained access'; end if;

  perform set_config('request.jwt.claims',jsonb_build_object('sub',owner_id,'session_id',owner_session,'aal','aal2','role','authenticated')::text,true);
  if not public.mw_family_member_set(new_id,'revoked') then raise exception 'Owner could not revoke member'; end if;
  perform set_config('request.jwt.claims',jsonb_build_object('sub',new_id,'session_id',new_session,'aal','aal2','role','authenticated')::text,true);
  if public.mw_family_access_status()<>'revoked' then raise exception 'Revoked member retained access'; end if;
  begin
    perform public.mw_room_list();
    raise exception 'Revoked member accessed LIVE';
  exception when raise_exception then
    if sqlerrm <> 'MW:Сеанс отозван. Войдите заново.' and sqlerrm <> 'MW:Доступ к Muwahid отозван' then raise; end if;
  end;
  execute 'reset role';
end;
$tests$;
set constraints all immediate;
