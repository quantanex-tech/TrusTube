# Architecture

Java 17, native Android views, NewPipeExtractor for public YouTube metadata/media
sources, and AndroidX Media3 for playback. No backend or account sync is bundled.
The alpha retains application ID `dev.trustedvideos.proof` for prototype updates.

- `MainActivity`: child tabs and PIN-protected parent settings.
- `ChannelSearchDialog`: parent discovery, explicit Add and stale-result invalidation.
- `SourceRepository`: channel/search pagination and verified media sources.
- `ProofState`: one local catalog, favourites, measurements and persistence.
- `PermissionPolicy`: canonical allow/block rules with revision checks.
- `PlaybackGate`: rejects stale approvals and mismatched video/creator identity.
- `PlayerActivity` / `PlayerControls`: native playback and synchronized control/rail visibility.

Parent completions are invalidated on leaving Parent or backgrounding. Child
pagination updates approved catalogs without approving channels. Discovery metadata
is not a playable catalog or an approval.

Local SharedPreferences store JSON metadata; backup is disabled. PIN storage uses
a salted verifier. Reinstallation/data clearing, device-owner controls and broader
bypass resistance require further work. Production daily accounting and multiple
profiles are not implemented; keep proof measurements separate from daily allowances.
