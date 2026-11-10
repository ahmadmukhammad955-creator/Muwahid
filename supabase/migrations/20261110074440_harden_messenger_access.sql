-- Only the uploader can remove a personal attachment. Both chat members still read it.
alter policy mw_media_delete on storage.objects
  using (
    bucket_id = 'mw-media'
    and owner_id = (select auth.uid())::text
    and public.mw_can_access_chat((storage.foldername(name))[1]::uuid)
  );

-- The two routines are invoked by triggers. They need no client EXECUTE.
alter function public.mw_touch_updated_at() set search_path = '';
revoke execute on function public.mw_touch_updated_at() from public, anon, authenticated;
revoke execute on function mw_private.broadcast_voice_room_signal() from public, anon, authenticated;

-- Serialize concurrent ICE appends and ignore late candidates after hang-up.
CREATE OR REPLACE FUNCTION public.mw_call_ice(p_call uuid, p_candidate jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare c public.mw_calls; result jsonb; current_count integer;
begin
  perform mw_private.require_mfa();
  if p_candidate is null or jsonb_typeof(p_candidate)<>'object' then
    raise exception 'MW:Некорректный ICE-кандидат';
  end if;
  if octet_length(p_candidate::text)>16384 then
    raise exception 'MW:ICE-кандидат слишком большой';
  end if;
  select * into c from public.mw_calls
  where id=p_call and auth.uid() in(caller_id,callee_id) for update;
  if c.id is null then raise exception 'MW:Звонок не найден'; end if;
  if c.status not in ('ringing','accepted') then
    return to_jsonb(c);
  end if;
  if auth.uid()=c.caller_id then
    current_count:=jsonb_array_length(coalesce(c.caller_ice,'[]'::jsonb));
    if current_count>=256 then raise exception 'MW:Слишком много ICE-кандидатов'; end if;
    update public.mw_calls
      set caller_ice=caller_ice||jsonb_build_array(p_candidate),updated_at=now()
      where id=p_call;
  else
    current_count:=jsonb_array_length(coalesce(c.callee_ice,'[]'::jsonb));
    if current_count>=256 then raise exception 'MW:Слишком много ICE-кандидатов'; end if;
    update public.mw_calls
      set callee_ice=callee_ice||jsonb_build_array(p_candidate),updated_at=now()
      where id=p_call;
  end if;
  select to_jsonb(x) into result from public.mw_calls x where id=p_call;
  return result;
end
$function$;
