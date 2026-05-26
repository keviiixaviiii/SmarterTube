# Upstream merge workflow

This fork tracks [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube). The YouTube client engine — `MediaServiceCore`, ExoPlayer, the InnerTube layer — is upstream's code. The phone UI under `smarttubetv/src/stmobile/` is the only fork-specific source set. Keeping the two in sync is mostly mechanical and mostly automated.

## The two-tier model

| Tier | What happens | Where |
|---|---|---|
| Detect & propose | Daily check for new upstream commits; opens a PR (clean merge) or issue (conflicts) | [`.github/workflows/upstream-sync.yml`](../.github/workflows/upstream-sync.yml) |
| Validate | Confirms the 3 integration points are intact and `stmobile` still builds | [`.github/workflows/stmobile-validate.yml`](../.github/workflows/stmobile-validate.yml) |
| Decide & ship | Human reviews diff, merges, cuts a release | This document |

## The 3 integration points

These are the only places where the fork touches upstream code. Every upstream merge has the potential to disturb them; `stmobile-validate.yml` greps for each one.

1. **`smarttubetv/build.gradle`** — `stmobile` product flavor block. Adds `applicationIdSuffix ".mobile"` and `matchingFallbacks ['ststable']`. If a merge removes the flavor block or changes the suffix, the APK ships under the wrong package ID.

2. **`common/build.gradle`** — matching `stmobile` flavor block with `matchingFallbacks ['ststable']` so submodules that don't know about `stmobile` fall back to `ststable`. Without this the build fails resolving common-module dependencies.

3. **`MainApplication.setupViewManager()`** at `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/main/MainApplication.java` — must be `protected`, not `private`. `MobileApplication` overrides it to wire the phone activities into the view-manager. If upstream re-narrows it the override is silently inert.

## What clean merges look like

Most upstream changes touch `common/`, `MediaServiceCore/`, or the leanback UI under `smarttubetv/src/main/`. None of those overlap with our `stmobile` source set, so they merge clean. The bot opens a PR like:

```
Upstream sync 2026-06-15 — clean merge
```

with the upstream commit list in the body. Review:

1. Read the upstream commit list — anything that looks like a UI/UX or playback change is worth eyeballing.
2. Let CI run (`stmobile-validate` builds the APK).
3. Side-load the debug APK and smoke-test: home loads, search works, playback works, sign-in works.
4. Merge and tag a new fork release (see [release-signing memory](../) or `release_signing.md`).

## What conflict merges look like

The bot opens an issue tagged `upstream-sync, conflict` listing the conflicting paths. Typical causes:

- Upstream re-shaped the `productFlavors` block → resolve in `smarttubetv/build.gradle`, keep our `stmobile { ... }`.
- Upstream added a new method to `BrowseView` / `SignInView` / `PlaybackView` → implement it (usually as a no-op) in the corresponding `Mobile*` class. See the rebase runbook for the existing implementations (`updateBadge()`, the 3-arg `showCode(...)`, etc.).
- Upstream renamed an activity referenced from the stmobile manifest → update the manifest.

Manual resolution:

```bash
git fetch upstream master
git checkout -b upstream-sync/manual-YYYY-MM-DD master
git merge upstream/master
# resolve conflicts
git commit
git push origin HEAD
gh pr create --base master --title "Upstream sync — manual merge"
```

Then let `stmobile-validate` run, smoke-test, merge.

## What to do when validate fails

If `stmobile-validate` flags a missing integration point on a PR:

- **Point 1 missing** → restore the `stmobile { ... }` block in `smarttubetv/build.gradle` with `applicationIdSuffix ".mobile"`, `matchingFallbacks ['ststable']`, the `targetSdkVersion`, and the `versionCode`/`versionName` lines (per [release-signing memory](../) — convention is `upstream * 10 + fork iteration`).
- **Point 2 missing** → restore the `stmobile { matchingFallbacks ['ststable'] }` block in `common/build.gradle`.
- **Point 3 missing** → widen `MainApplication.setupViewManager()` from `private` back to `protected`.

If the build step fails:

- Class-not-found / method-not-found in `Mobile*` files → upstream changed an interface; implement the new signature in the corresponding `Mobile*` class.
- Manifest-merger error → check if a TV-only activity referenced from the stmobile manifest got renamed or removed upstream.

## When to bump the version

Each upstream version bump (`31.70` → `31.71`) is a fork release boundary. Convention:

- `versionCode` = upstream code × 10 + fork iteration (`31.70` → 2370 → `23700 + n`).
- `versionName` = `<upstream>-mobile-<maturity><n>` (e.g. `31.71-mobile-alpha1`, `31.70-mobile-beta1`).
- Bump in the `stmobile` flavor block of `smarttubetv/build.gradle` only — never in `defaultConfig` (that's upstream's).

After a clean merge and bump, follow the release-signing memory: `assembleStmobileRelease`, tag, `gh release create --prerelease` with the 4 ABI APKs.
