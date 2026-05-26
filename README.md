# SmarterTube

**A phone and tablet YouTube client for Android.**

SmarterTube is a fork of [SmartTube](https://github.com/yuliskov/SmartTube) by yuliskov. It adds a native portrait UI — drawer navigation, search, channel pages, settings, sign-in — on top of SmartTube's YouTube client engine, which is merged from upstream unchanged on every release. Upstream SmartTube is TV-only by design; this fork exists for phones and tablets.

> **Alpha.** Core browsing and playback work. Some upstream features haven't been plumbed into the phone UI yet.

<p align="center">
  <img src="images/phone_drawer.png" width="270" alt="Navigation drawer"/>
  &nbsp;&nbsp;&nbsp;
  <img src="images/phone_search.png" width="270" alt="Search results"/>
</p>

---

## Download

[**GitHub Releases →**](https://github.com/CodeSculptor/SmarterTube/releases)

Pick the APK for your device:

| ABI | Who needs it |
|---|---|
| `arm64-v8a` | Most Android phones made after 2016 |
| `armeabi-v7a` | Older 32-bit devices |
| `x86` | Emulators |
| `universal` | Everything — larger file |

SmarterTube installs as `app.smarttube.mobile` and is **co-installable** with the upstream SmartTube TV build (`app.smarttube`). They do not conflict.

Do **not** download APKs from app stores or third-party sites.

---

## What works

### Phone UI (this fork adds)
- Portrait home screen with drawer navigation (Home, Shorts, Kids, Sports, LIVE, Gaming, News, Music, Channels, Subscriptions, History, Playlists, My videos)
- Search with suggestions and results grid
- Channel pages and channel uploads
- Portrait settings screen
- Sign in / sign out — multiple accounts, OAuth device-code flow via in-app browser tab
- About screen (drawer footer)
- Playback: landscape for regular videos, portrait for Shorts

### From upstream SmartTube (YouTube client engine, unchanged)
- SponsorBlock integration
- Return YouTube Dislike
- DeArrow
- Adjustable playback speed
- Up to 8K / 60fps / HDR
- No Google Play Services required
- No ads

---

## What is not here

- **TV / leanback interface** — install [upstream SmartTube](https://github.com/yuliskov/SmartTube) for Android TV boxes and sticks.
- **F-Droid listing** — not yet; sideload from Releases.
- **Casting / Chromecast** — not plumbed into the phone UI.
- **Voice search** — keyboard search only.

---

## Building

Requires JDK 11 and Android SDK.

```bash
# Debug
./gradlew :smarttubetv:assembleStmobileDebug

# Release (needs keystore.properties + smartertube-release.jks at repo root)
./gradlew :smarttubetv:assembleStmobileRelease
```

Output APKs land in `smarttubetv/build/outputs/apk/stmobile/`.

All phone-specific code lives under `smarttubetv/src/stmobile/` — no changes to `src/main` (TV code) except bug fixes that benefit both targets, which are submitted upstream.

---

## Upstream relationship

This fork tracks [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube). The YouTube client engine (MediaServiceCore, ExoPlayer, InnerTube API code) is upstream's work and is merged from upstream on every release. Bug fixes that apply to both targets are submitted upstream — see open PRs for current patches.

Licensed under [MIT](LICENSE), same as upstream.

---

## Privacy

See [PRIVACY.md](PRIVACY.md).
