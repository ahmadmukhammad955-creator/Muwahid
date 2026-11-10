-- Private story archive: expired stories belong to the author only.
-- Never expose expired media, envelopes, or metadata to former recipients.
-- The owner can still read encrypted media from their private Storage path.
create or replace function public.mw_story_archive(p_offset integer default 0,p_limit integer default 50)
returns jsonb language plpgsql security definer set search_path='' as $$
declare
  me uuid := auth.uid();
  result jsonb;
begin
  perform mw_private.require_mfa();
  if me is null then raise exception 'not_authenticated'; end if;
  if p_offset is null or p_offset < 0 or p_offset > 100000
     or p_limit is null or p_limit < 1 or p_limit > 50 then
    raise exception 'invalid_archive_page';
  end if;
  select coalesce(jsonb_agg(to_jsonb(t) order by t.created_at desc,t.id desc),'[]'::jsonb)
    into result
    from (
      select s.id,s.owner_id,p.display_name,p.username,s.media_path,
             s.media_kind,s.display_mime,s.duration_seconds,s.created_at,
             s.expires_at,s.visible_on_profile,s.allow_replies,s.allow_reactions,
             s.own_key_envelope as key_envelope,
             null::uuid as chat_id,
             false as viewed,
             (select count(*)::integer from public.mw_story_views v
               where v.story_id=s.id) as view_count,
             (select count(*)::integer from public.mw_story_reactions r
               where r.story_id=s.id) as reaction_count,
             null::text as my_encrypted_reaction
        from public.mw_stories s
        join public.mw_profiles p on p.id=s.owner_id
       where s.owner_id=me
         and s.published
         and s.expires_at<=now()
       order by s.created_at desc,s.id desc
       limit p_limit offset p_offset
    ) t;
  return result;
end $$;
revoke all on function public.mw_story_archive(integer,integer) from public,anon;
grant execute on function public.mw_story_archive(integer,integer) to authenticated;
