-- Show mutually saved contacts only to a member of this direct chat.
begin;
create or replace function public.mw_profile_mutual_contacts(p_chat uuid)
returns jsonb language plpgsql stable security definer set search_path='' as $$
declare me uuid := auth.uid(); peer uuid; people jsonb;
begin
 perform mw_private.require_mfa();
 select case when member_a=me then member_b else member_a end into peer
 from public.mw_chats where id=p_chat and me in (member_a,member_b);
 if peer is null then raise exception 'chat_not_accessible'; end if;
 select coalesce(jsonb_agg(jsonb_build_object('display_name',p.display_name,'username',p.username)
        order by p.display_name,p.id),'[]'::jsonb) into people
 from public.mw_privacy_contacts mine
 join public.mw_privacy_contacts theirs on theirs.person_id=mine.person_id and theirs.user_id=peer
 join public.mw_profiles p on p.id=mine.person_id
 where mine.user_id=me
 and exists(select 1 from public.mw_privacy_contacts c where c.user_id=p.id and c.person_id=me)
 and exists(select 1 from public.mw_privacy_contacts c where c.user_id=p.id and c.person_id=peer)
 and not exists(select 1 from public.mw_blocks b where b.user_id in (me,peer,p.id)
                and b.person_id in (me,peer,p.id));
 return people;
end $$;
revoke all on function public.mw_profile_mutual_contacts(uuid) from public,anon;
grant execute on function public.mw_profile_mutual_contacts(uuid) to authenticated;
commit;
