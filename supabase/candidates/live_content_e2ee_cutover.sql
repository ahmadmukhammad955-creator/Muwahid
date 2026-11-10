-- Apply only AFTER publishing and validating the upgraded client.
-- Old clients receive an explicit update error; historical rows/files stay intact.
create or replace function public.mw_room_message_send(p_room uuid,p_kind text,p_body text default '',p_media jsonb default null)
returns jsonb language plpgsql security definer set search_path='' as $$
begin
  perform mw_private.require_mfa();
  raise exception 'MW:Для защищённого чата LIVE обновите Muwahid';
end $$;

create or replace function mw_private.live_require_ciphertext()
returns trigger language plpgsql security invoker set search_path='' as $$
begin
  if new.body !~ '^mwl1:[A-Za-z0-9+/]+={0,2}$' or new.media->>'v' is distinct from '1'
    or jsonb_typeof(new.media->'keys') is distinct from 'object' then
    raise exception 'MW:Для защищённого чата LIVE обновите Muwahid';
  end if;
  return new;
end $$;
revoke all on function mw_private.live_require_ciphertext() from public,anon,authenticated;
create trigger live_require_ciphertext before insert or update on public.mw_voice_room_messages
for each row execute function mw_private.live_require_ciphertext();

-- Adds restrictions to the existing membership/owner/session policies, never replaces them.
-- A path marker is a format gate, NOT proof of file encryption. Encryption is tested in Android.
create policy mw_live_encrypted_upload on storage.objects as restrictive for insert to authenticated
with check(bucket_id not in('mw-room-media','mw-room-books') or
  name ~ '^[0-9a-f-]{36}/(background/|books/)?e2ee/[0-9a-f-]{36}\.[a-z0-9]{2,5}$');
create policy mw_live_no_file_overwrite on storage.objects as restrictive for update to authenticated
using(bucket_id not in('mw-room-media','mw-room-books'))
with check(bucket_id not in('mw-room-media','mw-room-books'));
