# Muwahid profile and story archive — approved visual reference, 2026-11-11

This document locks the requirements of the TWO user-supplied smartphone screens. Match their hierarchy and proportion; do not invent a new design. The reference is the user's image from the conversation, NOT a production asset and NOT permission to copy the depicted man's portrait into an account.

## Left screen: own profile
- Dark navy full-screen UI. Back arrow and "Профиль" in top bar; QR icon, edit pencil, overflow menu (QR must be functional before enabled).
- Full-width dusk mosque skyline photographic **cover**, with a centered circular **real account avatar** (white outline) overlapping the bottom of the cover; green online indicator at avatar edge where presence is actually known. If no real avatar/cover exists, use neutral placeholders rather than presenting another person's photograph as the user.
- Centered display name, @username, green "Онлайн" when supported by live presence data.
- Four horizontally arranged **compact, equal-size** action tiles: Чат, Звонок, Видео, Поделиться, with white outline icons and small captions. No giant buttons.
- "Истории" title on left, "Архив" link on right. A single horizontal strip: one "Добавить историю" tile and real story thumbnails with duration remaining at upper corner. No excessive empty spacer after the strip.
- Five real, click-through rows at bottom, in this exact order: "О себе"; "Медиа, файлы и ссылки"; "Общие контакты"; "Конфиденциальность"; "Настройки профиля". Each row needs truthful live data and a functional destination. Do not display a row merely as a nonfunctional imitation. Do not show "Блокировка контакта" in the owner's profile.
- Existing authorized editing, messaging, calling, sharing, stories and security behaviors must remain working.

## Right screen: story archive
- Separate screen reached by tapping "Архив" on profile. Dark navy background.
- Back arrow, "Архив историй", overflow menu at top.
- Two-segment control: "Мои истории" / "Архив", blue filled selection.
- Three-column, rounded portrait-thumbnail grid with a narrow gutter, time marker (hours/days) in top right, genuine view counts only when available at bottom. Image shows six example cards (3 × 2), but data must come from the signed-in user's account; no fabricated cards/counters.
- Preserve real encrypted story access and private archive semantics, including empty/loading/error states.

## Deferred at user's explicit request
- Interactive **replace circle avatar** and **replace cover photograph** are required features, but user said "будем потом" on 2026-11-11; don't mix this work into the current density/layout patch.
- Avatar/cover currently not provided by mw_self_profile and need a secure owner-only upload/storage/update pipeline before claiming they work.

## Current reality and release gate
- Layout density adjustments and self-profile identification are implemented in StoriesActivity.java in the branch of this requirement.
- Missing real media/file/link and shared-contact destinations, as well as real stored avatar/cover and QR action, are NOT declared implemented.
- CI must compile; Android 10 and Android 15 UI tests must pass. Owner's screenshots must be visually compared against the reference on an actual device.
- Do not merge/release an APK claiming pixel-perfect parity or full functions before these are verified. Previous signed 0.13.43 is unaffected by this PR until merged and rebuilt.
