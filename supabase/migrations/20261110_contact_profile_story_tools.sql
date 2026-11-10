-- Contact profile and moderation used by the native story screen.
create unique index if not exists mw_blocks_unique_pair on public.mw_blocks(user_id,person_id);
create or replace function public.mw_contact_profile(p_chat uuid) returns jsonb
 language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); other uuid; result jsonb;
begin
 perform mw_private.require_mfa();
 select case when c.member_a=me then c.member_b else c.member_a end
   into other from public.mw_chats c where c.id=p_chat and me in(c.member_a,c.member_b);
 if other is null then raise exception 'chat_not_accessible'; end if;
 select jsonb_build_object(
    'id',p.id,'chat_id',p_chat,'display_name',p.display_name,'username',p.username,
    'bio',p.bio,'status_text',(case when p.status_expires_at is null or p.status_expires_at>now() then p.status_text else '' end),
    'status_emoji',(case when p.status_expires_at is null or p.status_expires_at>now() then p.status_emoji else '' end),
    'last_seen',p.last_seen,'online',(p.is_online and p.last_seen>now()-interval '35 seconds'),
    'blocked_by_me',exists(select 1 from public.mw_blocks b where b.user_id=me and b.person_id=other))
 into result from public.mw_profiles p where p.id=other;
 return coalesce(result,'{}'::jsonb);
end $$;
revoke all on function public.mw_contact_profile(uuid) from public;
grant execute on function public.mw_contact_profile(uuid) to authenticated;

create or replace function public.mw_contact_set_block(p_chat uuid,p_block boolean) returns boolean
 language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); other uuid;
begin
 perform mw_private.require_mfa();
 select case when c.member_a=me then c.member_b else c.member_a end into other
 from public.mw_chats c where c.id=p_chat and me in(c.member_a,c.member_b);
 if other is null or other=me then raise exception 'chat_not_accessible'; end if;
 if coalesce(p_block,false) then
  insert into public.mw_blocks(user_id,person_id) values(me,other) on conflict(user_id,person_id) do nothing;
 else
  delete from public.mw_blocks where user_id=me and person_id=other;
 end if;
 return true;
end $$;
revoke all on function public.mw_contact_set_block(uuid,boolean) from public;
grant execute on function public.mw_contact_set_block(uuid,boolean) to authenticated;

create or replace function public.mw_story_viewers(p_story uuid) returns jsonb
 language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid(); data jsonb;
begin
 perform mw_private.require_mfa();
 if not exists(select 1 from public.mw_stories s where s.id=p_story and s.owner_id=me) then
  raise exception 'story_not_owned'; end if;
 select coalesce(jsonb_agg(jsonb_build_object(
    'viewer_id',g.viewer_id,'chat_id',g.chat_id,
    'display_name',p.display_name,'viewed_at',v.first_viewed_at,
    'encrypted_reaction',r.encrypted_reaction,'reacted_at',r.updated_at)
   order by v.first_viewed_at desc nulls last),'[]'::jsonb) into data
 from public.mw_story_grants g
 join public.mw_profiles p on p.id=g.viewer_id
 left join public.mw_story_views v on v.story_id=g.story_id and v.viewer_id=g.viewer_id
 left join public.mw_story_reactions r on r.story_id=g.story_id and r.viewer_id=g.viewer_id
 where g.story_id=p_story and (v.first_viewed_at is not null or r.encrypted_reaction is not null);
 return data;
end $$;
revoke all on function public.mw_story_viewers(uuid) from public;
grant execute on function public.mw_story_viewers(uuid) to authenticated;