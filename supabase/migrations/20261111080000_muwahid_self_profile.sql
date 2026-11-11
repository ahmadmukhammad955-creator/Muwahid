-- Read-only, authenticated Muwahid self-profile for the native profile screen.
-- No arbitrary user selector: this can only return the caller's own profile.
create or replace function public.mw_self_profile()
returns jsonb
language plpgsql stable security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
  result jsonb;
begin
  perform mw_private.require_mfa();
  if me is null then raise exception 'not_authenticated'; end if;
  select jsonb_build_object(
    'id',p.id,
    'display_name',p.display_name,
    'username',p.username,
    'bio',coalesce(p.bio,''),
    'online',true,
    'status_text',case when p.status_expires_at is null or p.status_expires_at>now()
      then coalesce(p.status_text,'') else '' end,
    'status_emoji',case when p.status_expires_at is null or p.status_expires_at>now()
      then coalesce(p.status_emoji,'') else '' end
  ) into result
  from public.mw_profiles p
  where p.id=me;
  if result is null then raise exception 'profile_not_found'; end if;
  return result;
end;
$$;
revoke all on function public.mw_self_profile() from public, anon;
grant execute on function public.mw_self_profile() to authenticated;
