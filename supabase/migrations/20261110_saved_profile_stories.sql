-- Telegram-like saved profile stories: explicitly pinned publications remain visible
-- after normal 24h story expiry. Never grant access to a new viewer implicitly.
alter table public.mw_stories
 add column if not exists visible_on_profile boolean not null default false;

create or replace function mw_private.story_can_view(p_story uuid,p_viewer uuid)
returns boolean language sql stable security definer set search_path='' as $$
 select exists(
  select 1 from public.mw_stories s where s.id=p_story and p_viewer is not null
    and (s.owner_id=p_viewer or
      (s.published and (s.expires_at>now() or s.visible_on_profile)
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

create or replace function public.mw_story_set_profile(p_story uuid,p_visible boolean) returns boolean
language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();
begin
 perform mw_private.require_mfa();
 update public.mw_stories
 set visible_on_profile=coalesce(p_visible,false)
 where id=p_story and owner_id=me;
 if not found then raise exception 'story_not_owned'; end if;
 return true;
end $$;
revoke all on function public.mw_story_set_profile(uuid,boolean) from public;
grant execute on function public.mw_story_set_profile(uuid,boolean) to authenticated;

create or replace function public.mw_story_feed(p_person uuid default null) returns jsonb
 language plpgsql security definer set search_path='' as $$
declare result jsonb; me uuid:=auth.uid();
begin
 perform mw_private.require_mfa();
 if me is null then raise exception 'not_authenticated'; end if;
 select coalesce(jsonb_agg(to_jsonb(t) order by t.created_at desc),'[]'::jsonb) into result
 from (select s.id,s.owner_id,p.display_name,p.username,s.media_path,s.media_kind,s.display_mime,
          s.duration_seconds,s.created_at,s.expires_at,s.visible_on_profile,s.allow_replies,s.allow_reactions,
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
       where s.published and (s.expires_at>now() or s.visible_on_profile)
         and (p_person is null or s.owner_id=p_person)
         and mw_private.story_can_view(s.id,me)
       order by s.created_at desc limit 100) t;
 return result;
end $$;
