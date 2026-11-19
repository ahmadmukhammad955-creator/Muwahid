# Muwahid Fresh Native

Independent Android Java project, not based on prior Muwahid source. Native profile, stories, archive, messaging, WebRTC signaling and experimental audio/video calling. Profile-media privacy and story access use Supabase row-level security. 

**Status:** Android 15 login-screen launch smoke tested in preceding revision. This revision includes story privacy/pinning/deletion and QR profile deep links and must pass new CI. WebRTC on two physical phones, real account authorization, media uploads and calling over strict NAT are **not validated**. A STUN-only configuration is not reliable across all mobile networks without TURN. No false demonstration data.

Debug APK is ephemeral signed. Stable signing uses repo-protected `MUWAHID_KEYSTORE_*` secrets; new package id `com.muwahid.fresh` differs from legacy app.
