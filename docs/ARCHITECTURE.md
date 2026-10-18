# Architecture

Java 17, native Android views, NewPipeExtractor for public YouTube metadata/media
sources, and AndroidX Media3 for playback. No backend or account sync is bundled.
The alpha retains application ID `dev.trustedvideos.proof` for prototype updates.

- `MainActivity`: child tabs and PIN-protected parent settings.
- `ChannelSearchDialog`: parent discovery, explicit Add and stale-result invalidation.
- `SourceRepository`: channel/search pagination and verified media sources.
- `ProofState`: one local catalog, favourites, proof measurements and persistence;
  refreshes all approved subscriptions' recent pages after upgrade/after six hours.
- `DailyTimers` / `TimerSchedule` / `DailyUsage`: immutable per-day weekly rules,
  date-scoped Today overrides and persisted monotonic usage split at local midnight.
- `TimerPage`: parent-tab subpages and reusable protected duration bottom sheet.
- `ImageCache`: coalesced artwork fetches, sampled bitmap memory LRU and 64 MiB disk
  cache (14-day retention); image results also check target identity.
- `PermissionPolicy`: canonical allow/block rules with revision checks.
- `PlaybackGate`: rejects stale approvals and mismatched video/creator identity.
- `PlayerActivity` / `PlayerControls`: native playback and synchronized control/rail visibility.

Parent completions are invalidated on leaving Parent or backgrounding. Child
pagination updates approved catalogs without approving channels. Discovery metadata
is not a playable catalog or an approval.

Local SharedPreferences store JSON metadata; backup is disabled. PIN storage uses
a salted verifier. Reinstallation/data clearing, device-owner controls and broader
bypass resistance require further work. One local profile has separate watching
and full-app daily counters. Rules and counters persist independently; diagnostic
proof resets cannot replenish daily usage. Midnight uses local calendar boundaries
(including daylight saving), while interval accounting uses monotonic time. Device
clock/timezone changes are not anti-tamper protected. Multiple profiles remain future work.

Material Components 1.14.0 provides the Material 3 Expressive theme, buttons,
switches and bottom sheets. Audio selection retains each upload's track identity,
defaults to original metadata and re-enters the same video/creator playback gate
when switching. No persistent translated-audio preference is applied to other videos.
