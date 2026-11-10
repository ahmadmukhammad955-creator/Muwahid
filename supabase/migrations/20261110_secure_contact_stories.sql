-- E2EE story transport. Additive schema; private to contacts with explicit key grants.
create table if not exists public.mw_stories(
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  media_path text not null unique,
  media_kind text not null check(media_kind in('image','video')),
  display_mime text not null check(display_mime in('image/jpeg','image/png','image/webp','video/mp4','video/webm')),
  encrypted_size bigint not null check(encrypted_size between 33 and 26214400),
  duration_seconds integer check(duration_seconds is null or duration_seconds between 1 and 60),
  own_key_envelope text not null check(left(own_key_envelope,4)='mw1:' and length(own_key_envelope) between 40 and 6000),
  allow_replies boolean not null default true,
  allow_reactions boolean not null default true,
  published boolean not null default false,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now()+interval '24 hours'),
  constraint mw_story_owner_file check(split_part(media_path,'/',1)=owner_id::text and right(media_path,4)='.bin'),
  constraint mw_story_video_length check((media_kind='image' and duration_seconds is null) or (media_kind='video' and duration_seconds is not null))
);
create index if not exists mw_story_active_index on public.mw_stories(owner_id,expires_at desc);
create table if not exists public.mw_story_grants(
  story_id uuid not null references public.mw_stories(id) on delete cascade,
  viewer_id uuid not null references auth.users(id) on delete cascade,
  chat_id uuid not null references public.mw_chats(id) on delete cascade,
  key_envelope text not null check(left(key_envelope,4)='mw1:' and length(key_envelope) between 40 and 6000),
  granted_at timestamptz not null default now(),
  primary key(story_id,viewer_id)
);
create index if not exists mw_story_grant_viewer_idx on public.mw_story_grants(viewer_id,story_id);
create table if not exists public.mw_story_views(
  story_id uuid not null references public.mw_stories(id) on delete cascade,
  viewer_id uuid not null references auth.users(id) on delete cascade,
  first_viewed_at timestamptz not null default now(),
  primary key(story_id,viewer_id)
);
create table if not exists public.mw_story_reactions(
  story_id uuid not null references public.mw_stories(id) on delete cascade,
  viewer_id uuid not null references auth.users(id) on delete cascade,
  encrypted_reaction text not null check(left(encrypted_reaction,4)='mw1:' and length(encrypted_reaction) between 40 and 6000),
  updated_at timestamptz not null default now(),
  primary key(story_id,viewer_id)
);
-- DEFINER access check avoids RLS policy recursion and always checks live blocks.
create or replace function mw_private.story_can_view(p_story uuid,p_viewer uuid)
returns boolean language sql stable security definer set search_path='' as $$
 select exists(
  select 1 from public.mw_stories s where s.id=p_story and p_viewer is not null
    and (s.owner_id=p_viewer or
      (s.published and s.expires_at>now()
       and exists(select 1 from public.mw_story_grants g
                  join public.mw_chats c on c.id=g.chat_id
                  where g.story_id=s.id and g.viewer_id=p_viewer
                    and ((c.member_a=s.owner_id and c.member_b=p_viewer)
                      or (c.member_b=s.owner_id and c.member_a=p_viewer)))
       and not exists(select 1 from public.mw_blocks b
                      where (b.user_id=s.owner_id and b.person_id=p_viewer)
                         or (b.user_id=p_viewer and b.person_id=s.owner_id))
      ))
 );
$$;
revoke all on function mw_private.story_can_view(uuid,uuid) from public;
grant execute on function mw_private.story_can_view(uuid,uuid) to authenticated;
alter table public.mw_stories enable row level security;
alter table public.mw_story_grants enable row level security;
alter table public.mw_story_views enable row level security;
alter table public.mw_story_reactions enable row level security;
create policy mw_stories_read on public.mw_stories for select to authenticated
 using(mw_private.story_can_view(id,(select auth.uid())));
create policy mw_stories_insert_owner on public.mw_stories for insert to authenticated
 with check(owner_id=(select auth.uid()) and not published);
create policy mw_stories_delete_owner on public.mw_stories for delete to authenticated
 using(owner_id=(select auth.uid()));
create policy mw_story_grants_read on public.mw_story_grants for select to authenticated
 using(viewer_id=(select auth.uid()) or exists(select 1 from public.mw_stories s where s.id=story_id and s.owner_id=(select auth.uid())));
create policy mw_story_views_read on public.mw_story_views for select to authenticated
 using(viewer_id=(select auth.uid()) or exists(select 1 from public.mw_stories s where s.id=story_id and s.owner_id=(select auth.uid())));
create policy mw_story_views_insert on public.mw_story_views for insert to authenticated
 with check(viewer_id=(select auth.uid()) and mw_private.story_can_view(story_id,(select auth.uid())));
create policy mw_story_reactions_read on public.mw_story_reactions for select to authenticated
 using(viewer_id=(select auth.uid()) or exists(select 1 from public.mw_stories s where s.id=story_id and s.owner_id=(select auth.uid())));
revoke all on public.mw_stories,public.mw_story_grants,public.mw_story_views,public.mw_story_reactions from anon;
grant select,insert,delete on public.mw_stories to authenticated;
grant select on public.mw_story_grants to authenticated;
grant select,insert on public.mw_story_views to authenticated;
grant select on public.mw_story_reactions to authenticated;
-- Ciphertext-only private bucket (no public or signed-public URLs).
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
 values('mw-stories','mw-stories',false,26214400,array['application/octet-stream'])
 on conflict(id) do update set public=false,file_size_limit=26214400,allowed_mime_types=excluded.allowed_mime_types;
create policy mw_stories_upload on storage.objects for insert to authenticated
 with check(bucket_id='mw-stories' and split_part(name,'/',1)=(select auth.uid())::text and right(name,4)='.bin');
create policy mw_stories_download_owner on storage.objects for select to authenticated
 using(bucket_id='mw-stories' and split_part(name,'/',1)=(select auth.uid())::text);
create policy mw_stories_download_grantee on storage.objects for select to authenticated
 using(bucket_id='mw-stories' and exists(select 1 from public.mw_stories s where s.media_path=name and mw_private.story_can_view(s.id,(select auth.uid()))));
-- RESTRICTIVE guards prevent earlier permissive media policies bypassing these rules.
create policy mw_stories_storage_select_guard on storage.objects as restrictive for select to authenticated
 using(bucket_id<>'mw-stories' or split_part(name,'/',1)=(select auth.uid())::text
       or exists(select 1 from public.mw_stories s where s.media_path=name and mw_private.story_can_view(s.id,(select auth.uid()))));
create policy mw_stories_storage_insert_guard on storage.objects as restrictive for insert to authenticated
 with check(bucket_id<>'mw-stories' or (split_part(name,'/',1)=(select auth.uid())::text and right(name,4)='.bin'));
create policy mw_stories_storage_update_guard on storage.objects as restrictive for update to authenticated
 using(bucket_id<>'mw-stories' or split_part(name,'/',1)=(select auth.uid())::text)
 with check(bucket_id<>'mw-stories' or (split_part(name,'/',1)=(select auth.uid())::text and right(name,4)='.bin'));
create policy mw_stories_storage_delete_guard on storage.objects as restrictive for delete to authenticated
 using(bucket_id<>'mw-stories' or split_part(name,'/',1)=(select auth.uid())::text);
create policy mw_stories_owner_delete on storage.objects for delete to authenticated
 using(bucket_id='mw-stories' and split_part(name,'/',1)=(select auth.uid())::text);

-- Publishing is atomic. The server sees ciphertext and E2EE key envelopes only.
create or replace function public.mw_story_publish(
 p_path text,p_kind text,p_mime text,p_encrypted_size bigint,p_own_key_envelope text,
 p_grants jsonb,p_duration_seconds integer default null,p_allow_replies boolean default true,p_allow_reactions boolean default true)
 returns uuid language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); sid uuid; item jsonb; viewer uuid; cid uuid; wrapped text;
begin
 perform mw_private.require_mfa();
 if me is null then raise exception 'not_authenticated'; end if;
 if p_path is null or p_path !~ ('^'||me::text||'/[a-f0-9-]{36}\.bin$') then raise exception 'invalid_story_path'; end if;
 if p_kind not in('image','video') or p_mime not in('image/jpeg','image/png','image/webp','video/mp4','video/webm') then raise exception 'invalid_story_type'; end if;
 if p_encrypted_size not between 33 and 26214400 then raise exception 'invalid_story_size'; end if;
 if (p_kind='video' and (p_duration_seconds is null or p_duration_seconds not between 1 and 60))
    or (p_kind='image' and p_duration_seconds is not null) then raise exception 'invalid_story_duration'; end if;
 if p_own_key_envelope not like 'mw1:%' or length(p_own_key_envelope) not between 40 and 6000 then raise exception 'invalid_story_key'; end if;
 if jsonb_typeof(p_grants)<>'array' or jsonb_array_length(p_grants) not between 1 and 200 then raise exception 'story_requires_verified_contacts'; end if;
 if not exists(select 1 from storage.objects o where o.bucket_id='mw-stories' and o.name=p_path) then raise exception 'missing_story_media'; end if;
 insert into public.mw_stories(owner_id,media_path,media_kind,display_mime,encrypted_size,
   duration_seconds,own_key_envelope,allow_replies,allow_reactions)
 values(me,p_path,p_kind,p_mime,p_encrypted_size,p_duration_seconds,p_own_key_envelope,
   coalesce(p_allow_replies,true),coalesce(p_allow_reactions,true)) returning id into sid;
 for item in select value from jsonb_array_elements(p_grants) loop
  viewer:=(item->>'viewer_id')::uuid; cid:=(item->>'chat_id')::uuid; wrapped:=item->>'key_envelope';
  if viewer is null or viewer=me or cid is null or wrapped not like 'mw1:%' or length(wrapped) not between 40 and 6000 then raise exception 'bad_story_recipient'; end if;
  if not exists(select 1 from public.mw_chats c where c.id=cid
     and ((c.member_a=me and c.member_b=viewer) or (c.member_b=me and c.member_a=viewer))) then raise exception 'not_a_contact'; end if;
  if exists(select 1 from public.mw_blocks b where (b.user_id=me and b.person_id=viewer) or (b.user_id=viewer and b.person_id=me)) then raise exception 'blocked_contact'; end if;
  insert into public.mw_story_grants(story_id,viewer_id,chat_id,key_envelope) values(sid,viewer,cid,wrapped);
 end loop;
 update public.mw_stories set published=true where id=sid;
 return sid;
end $$;
revoke all on function public.mw_story_publish(text,text,text,bigint,text,jsonb,integer,boolean,boolean) from public;
grant execute on function public.mw_story_publish(text,text,text,bigint,text,jsonb,integer,boolean,boolean) to authenticated;
create or replace function public.mw_story_feed(p_person uuid default null) returns jsonb
 language plpgsql security definer set search_path='' as $$
declare result jsonb; me uuid:=auth.uid();
begin
 perform mw_private.require_mfa();
 if me is null then raise exception 'not_authenticated'; end if;
 select coalesce(jsonb_agg(to_jsonb(t) order by t.created_at desc),'[]'::jsonb) into result
 from (select s.id,s.owner_id,p.display_name,p.username,s.media_path,s.media_kind,s.display_mime,
          s.duration_seconds,s.created_at,s.expires_at,s.allow_replies,s.allow_reactions,
          (case when s.owner_id=me then s.own_key_envelope else g.key_envelope end) as key_envelope,
          (case when s.owner_id=me then null else g.chat_id end) as chat_id,
          (v.first_viewed_at is not null) as viewed,
          (select count(*)::integer from public.mw_story_views w where w.story_id=s.id) as view_count,
          (select count(*)::integer from public.mw_story_reactions r where r.story_id=s.id) as reaction_count,
          (select r.encrypted_reaction from public.mw_story_reactions r where r.story_id=s.id and r.viewer_id=me) as my_encrypted_reaction
       from public.mw_stories s
       join public.mw_profiles p on p.id=s.owner_id
       left join public.mw_story_grants g on g.story_id=s.id and g.viewer_id=me
       left join public.mw_story_views v on v.story_id=s.id and v.viewer_id=me
       where s.published and s.expires_at>now()
         and (p_person is null or s.owner_id=p_person)
         and mw_private.story_can_view(s.id,me)
       order by s.created_at desc limit 100) t;
 return result;
end $$;
revoke all on function public.mw_story_feed(uuid) from public;
grant execute on function public.mw_story_feed(uuid) to authenticated;
create or replace function public.mw_story_mark_viewed(p_story uuid) returns boolean
 language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();
begin
 perform mw_private.require_mfa();
 if me is null or not mw_private.story_can_view(p_story,me) then raise exception 'story_not_accessible'; end if;
 if not exists(select 1 from public.mw_stories s where s.id=p_story and s.owner_id=me) then
  insert into public.mw_story_views(story_id,viewer_id) values(p_story,me) on conflict do nothing;
 end if;
 return true;
end $$;
revoke all on function public.mw_story_mark_viewed(uuid) from public;
grant execute on function public.mw_story_mark_viewed(uuid) to authenticated;
create or replace function public.mw_story_react(p_story uuid,p_ciphertext text) returns boolean
 language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); enabled boolean;
begin
 perform mw_private.require_mfa();
 if me is null or not mw_private.story_can_view(p_story,me) then raise exception 'story_not_accessible'; end if;
 select s.allow_reactions into enabled from public.mw_stories s where s.id=p_story and s.owner_id<>me;
 if not coalesce(enabled,false) then raise exception 'story_reactions_disabled'; end if;
 if p_ciphertext not like 'mw1:%' or length(p_ciphertext) not between 40 and 6000 then raise exception 'encrypted_reaction_required'; end if;
 insert into public.mw_story_reactions(story_id,viewer_id,encrypted_reaction)
 values(p_story,me,p_ciphertext)
 on conflict(story_id,viewer_id) do update set encrypted_reaction=excluded.encrypted_reaction,updated_at=now();
 return true;
end $$;
revoke all on function public.mw_story_react(uuid,text) from public;
grant execute on function public.mw_story_react(uuid,text) to authenticated;
create or replace function public.mw_story_delete(p_story uuid) returns text
 language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); object_path text;
begin
 perform mw_private.require_mfa();
 delete from public.mw_stories where id=p_story and owner_id=me returning media_path into object_path;
 if object_path is null then raise exception 'story_not_owned'; end if;
 return object_path;
end $$;
revoke all on function public.mw_story_delete(uuid) from public;
grant execute on function public.mw_story_delete(uuid) to authenticated;