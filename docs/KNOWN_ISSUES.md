# SmarterTube Known Issues

Tracked issues for the current beta. Keep this honest and current — it is part of the release
checklist and what makes a beta release trustworthy.

Current release: `v0.4.2-beta.6+st31.94`  ·  Upstream SmartTube base: `31.94`  ·  Channel: beta

## Status / classification

- SmarterTube is **beta**, not stable. Previous `31.xx-mobile-1.x` releases were labelled as
  full releases prematurely; they are superseded by the beta reset (see
  [VERSIONING.md](VERSIONING.md)).
- Many feature-matrix rows are **Unknown** (not re-verified on a device this cycle). Unknowns
  for core flows must be resolved before any 1.0 release.

## Background playback + lockscreen controls (added beta.2)

- **Background audio** is now on by default (BACKGROUND_MODE_SOUND). Locking the phone keeps
  audio playing. Users can change this in player settings.
- **Lockscreen media controls** appear on the Android lockscreen and notification shade, showing
  the current video title and author. Controls update as you navigate between videos.
- **Seeking from the lockscreen** is not supported (the MediaSession does not expose a seek
  action through the notification compact view). This may be added in a later release.

## Known functional gaps

- **Comments are read-only.** Posting comments is blocked on innertube authentication + PoToken
  work; the TV client has no composer. Not planned for the beta line.
- **Notification bell / inbox is not implemented.** The upstream notifications source was dead;
  upload alerts are delivered via a subscriptions-feed poll instead.
- **No in-app APK install.** "Check for updates" detects a newer release and opens the GitHub
  asset/release URL; the user installs the APK manually.
- **Voice search is not implemented** on the phone UI.
- **Casting / Chromecast is not implemented.**

## Layout / orientation (to be audited in Gate C)

- Phone landscape and tablet portrait/landscape layouts are **unverified**. TV/leanback layout
  assumptions may leak into these orientations.

## Shorts

- The TikTok-style Shorts redesign shipped in Gate B (`v0.4.1-beta.1+st31.93`) and is
  **VERIFIED-ON-DEVICE**: swipe pager, tap-to-pause, vertical action rail (like/dislike/comments/
  channel), auto-hide chrome, seek bar above system gesture pill.
- Gate C fixed a loop regression: upstream 31.94 changed the repeat handler from
  `setPositionMs(100)` to `setPositionMs(0)`; the seek to 0ms briefly blanked the surface on
  loop. Fixed in stmobile by covering the seek with the current video's thumbnail poster.
  **VERIFIED-ON-DEVICE Gate C.** No remaining Shorts-specific issues.

## Channel page (native content tabs)

Native content tabs (Videos / Shorts / Live / Playlists — one swipeable 2-column grid per group)
are **VERIFIED-ON-DEVICE**. Remaining items:

- **Shorts / Playlists cards render with the landscape video-card layout.** Shorts are portrait
  and playlist cards differ, but every tab reuses the standard 16:9 card for now. Cosmetic; a
  per-card-type layout is a later refinement.
- **Sort chips (Latest / Popular / Oldest) are not wired.** Upstream exposes them separately from
  the content tabs; the native channel page does not surface them yet.
- **Channels list shows stale content after Back (pre-existing).** Backing out of a channel into
  the subscriptions "Channels" list shows the previous channel's content until pull-to-refresh.
  Root cause is in shared (`common`) navigation/view-state, not the tabs view. Tracked separately.
- **Player ↔ channel Back loop + lingering audio (pre-existing).** Pressing Back during playback
  opens the channel page with audio still playing; Back on the channel page returns to the player.
  Root cause is the shared `ViewManager` parent-view chain + background playback. Tracked separately.

## Updater notes

- The phone "Check for updates" reads the fork's GitHub releases. Its manifest URL / release
  data only reflects releases that have actually been published; before the first beta-reset
  release exists, a check may report "up to date" against whatever is currently published.
- The upstream auto-update check is inert on the phone (the phone `versionCode` sits far above
  upstream SmartTube's), so it does not offer upstream TV APKs. See
  [UPDATER_COMPATIBILITY.md](UPDATER_COMPATIBILITY.md) → As-Built Updater Audit.

## TV functionality

- This is a phone/tablet companion to SmartTube, **not** a TV replacement. The TV/leanback
  interface is intentionally not part of this product; use upstream SmartTube on a TV.
