-- Run after candidate + authorization tests + cutover in the same rolled-back transaction.
do $tests$
declare owner_id uuid; room uuid; sid uuid; recipients jsonb; env jsonb; denied boolean;
begin
 select user_id into strict owner_id from mw_private.owner_account;
 select id into strict room from public.mw_voice_rooms where title='Synthetic LIVE encryption test' and created_by=owner_id;
 select id into sid from auth.sessions where user_id=owner_id and not_after>now() order by not_after desc limit 1;
 perform set_config('request.jwt.claims',jsonb_build_object('sub',owner_id,'session_id',sid,'role','authenticated','aal','aal2')::text,true);
 execute 'set local role authenticated';
 recipients:=public.mw_room_secure_recipients(room);
 env:=jsonb_build_object('v',1,'nonce',gen_random_uuid(),'keys',jsonb_build_object(owner_id::text,jsonb_build_object('wrapped',repeat('A',80))));
 perform public.mw_room_secure_send(room,'text','mwl1:'||repeat('A',80),env,recipients);
 denied:=false;begin perform public.mw_room_message_send(room,'text','Legacy plaintext',null);
 exception when raise_exception then denied:=sqlerrm='MW:Для защищённого чата LIVE обновите Muwahid';end;
 if not denied then raise exception 'Legacy RPC accepted after cutover'; end if;
 denied:=false;begin
   insert into storage.objects(bucket_id,name,owner_id) values('mw-room-media',room::text||'/'||gen_random_uuid()::text||'.bin',owner_id::text);
 exception when insufficient_privilege then denied:=true;end;
 if not denied then raise exception 'Legacy file path accepted after cutover'; end if;
 insert into storage.objects(bucket_id,name,owner_id) values('mw-room-media',room::text||'/e2ee/'||gen_random_uuid()::text||'.bin',owner_id::text);
 execute 'reset role';
end $tests$;
