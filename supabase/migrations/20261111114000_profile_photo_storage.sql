-- Muwahid owner-updatable avatar and cover. Additive; retains existing accounts.
alter table public.mw_profiles add column if not exists avatar_path text;
alter table public.mw_profiles add column if not exists cover_path text;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values ('mw-profile-images','mw-profile-images',false,6291456,array['application/octet-stream'])
on conflict(id) do update set public=false,file_size_limit=6291456,allowed_mime_types=excluded.allowed_mime_types;

-- Storage SELECT is scoped to current photo paths and direct authorized chat peers.
-- SECURITY DEFINER avoids granting direct SELECT on the private mw_chats table.
create or replace function mw_private.mw_can_read_profile_photo(p_owner uuid,p_path text)
returns boolean language sql stable security definer set search_path='' as $$
  select auth.uid() is not null and (
    p_owner=auth.uid()
    or (
      exists(select 1 from public.mw_profiles p
             where p.id=p_owner and p_path in (p.avatar_path,p.cover_path))
      and exists(select 1 from public.mw_chats c
                 where (c.member_a=auth.uid() and c.member_b=p_owner)
                    or (c.member_b=auth.uid() and c.member_a=p_owner))
      and not exists(select 1 from public.mw_blocks b
                     where (b.user_id=auth.uid() and b.person_id=p_owner)
                        or (b.person_id=auth.uid() and b.user_id=p_owner))
    )
  )
$$;
revoke all on function mw_private.mw_can_read_profile_photo(uuid,text) from public,anon;
grant execute on function mw_private.mw_can_read_profile_photo(uuid,text) to authenticated;

drop policy if exists mw_profile_image_insert on storage.objects;
create policy mw_profile_image_insert on storage.objects for insert to authenticated
with check (
  bucket_id='mw-profile-images'
  and name ~ ('^'||(select auth.uid())::text||'/(avatar|cover)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$')
  and owner_id=(select auth.uid())::text
);

drop policy if exists mw_profile_image_select on storage.objects;
create policy mw_profile_image_select on storage.objects for select to authenticated
using (
  bucket_id='mw-profile-images'
  and mw_private.mw_can_read_profile_photo(
    nullif(substring(name from '^([0-9a-f-]{36})/(avatar|cover)/'),'')::uuid,name)
);

-- The profile itself decides which object is current; users can only update their own.
create or replace function public.mw_profile_set_image(p_kind text,p_path text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare v_me uuid:=auth.uid();
begin
  perform mw_private.require_mfa();
  if v_me is null then raise exception 'not_authenticated'; end if;
  if p_kind not in ('avatar','cover') then raise exception 'invalid_profile_image_kind'; end if;
  if p_path is null or p_path !~ ('^'||v_me::text||'/'||p_kind||'/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$')
    then raise exception 'invalid_profile_image_path'; end if;
  if not exists(select 1 from storage.objects o where o.bucket_id='mw-profile-images'
                   and o.name=p_path and o.owner_id=v_me::text and o.metadata->>'mimetype'='application/octet-stream')
    then raise exception 'profile_image_not_uploaded'; end if;
  if p_kind='avatar' then
    update public.mw_profiles set avatar_path=p_path where id=v_me;
  else
    update public.mw_profiles set cover_path=p_path where id=v_me;
  end if;
  if not found then raise exception 'profile_not_found'; end if;
  return jsonb_build_object('kind',p_kind,'path',p_path);
end $$;
revoke all on function public.mw_profile_set_image(text,text) from public,anon;
grant execute on function public.mw_profile_set_image(text,text) to authenticated;

-- Include persisted media paths in the existing MFA-protected self-profile RPC.
create or replace function public.mw_self_profile()
returns jsonb language plpgsql stable security definer set search_path='' as $$
declare me uuid:=auth.uid(); result jsonb;
begin
  perform mw_private.require_mfa();
  if me is null then raise exception 'not_authenticated'; end if;
  select jsonb_build_object(
    'id',p.id,'display_name',p.display_name,'username',p.username,
    'bio',coalesce(p.bio,''),'avatar_path',p.avatar_path,'cover_path',p.cover_path,
    'online',(p.is_online and p.last_seen>now()-interval '35 seconds'),
    'status_text',case when p.status_expires_at is null or p.status_expires_at>now()
      then coalesce(p.status_text,'') else '' end,
    'status_emoji',case when p.status_expires_at is null or p.status_expires_at>now()
      then coalesce(p.status_emoji,'') else '' end
  ) into result from public.mw_profiles p where p.id=me;
  if result is null then raise exception 'profile_not_found'; end if;
  return result;
end $$;
revoke all on function public.mw_self_profile() from public,anon;
grant execute on function public.mw_self_profile() to authenticated;

-- Peer image paths visible only through an existing authorized chat RPC.
create or replace function public.mw_contact_profile(p_chat uuid)
returns jsonb language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); other uuid; result jsonb;
begin
 perform mw_private.require_mfa();
 select case when c.member_a=me then c.member_b else c.member_a end
   into other from public.mw_chats c where c.id=p_chat and me in(c.member_a,c.member_b);
 if other is null then raise exception 'chat_not_accessible'; end if;
 select jsonb_build_object(
    'id',p.id,'chat_id',p_chat,'display_name',p.display_name,'username',p.username,
    'bio',p.bio,'avatar_path',p.avatar_path,'cover_path',p.cover_path,
    'status_text',(case when p.status_expires_at is null or p.status_expires_at>now() then p.status_text else '' end),
    'status_emoji',(case when p.status_expires_at is null or p.status_expires_at>now() then p.status_emoji else '' end),
    'last_seen',p.last_seen,'online',(p.is_online and p.last_seen>now()-interval '35 seconds'),
    'blocked_by_me',exists(select 1 from public.mw_blocks b where b.user_id=me and b.person_id=other))
 into result from public.mw_profiles p where p.id=other;
 return coalesce(result,'{}'::jsonb);
end $$;
revoke all on function public.mw_contact_profile(uuid) from public,anon;
grant execute on function public.mw_contact_profile(uuid) to authenticated;
