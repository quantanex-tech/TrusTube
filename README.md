# TrusTube

An open-source Android viewer where parents choose the YouTube channels their
children can browse. Native Android views, NewPipeExtractor and Media3; no YouTube
account or API key required.

**Early alpha for supervised testing.** Channel approvals and video/channel blocks
are implemented, but complete playback containment has not been verified. Test
with an adult present and report unexpected access. TrusTube is independent of
YouTube and Google.

## Try it

Download the APK from [GitHub Releases](https://github.com/quantanex-tech/TrusTube/releases).
Pre-release versions are listed there; select the latest alpha. Allow installation
from your browser/file manager when Android prompts. Create a six-digit parent PIN,
then open **Parent → Add a trusted channel**. Search by name, `@handle` or channel URL,
check the result and choose **Add**. Try **checker tobi** rather than needing to know
`@checkerwelt`.

See the [testing guide](docs/TESTING.md). The alpha uses a development signing key;
future production signing/package changes may require reinstalling and setting up again.

## What works

- Parent PIN setup and settings that relock on leaving or backgrounding.
- Parent-only channel discovery and explicit approval of a selected result.
- Home, Favorites, Search, Channels and Parent tabs.
- Persistent favourites and child search within approved subscriptions.
- Upload-date ordering and additional channel/video pages.
- Native landscape playback, remaining video time and a subscribed-video rail.
- Controls that hide while playing and stay visible while paused.
- Confirmation before blocking, with parent-only restoration.
- Named weekly watching/full-app timers, Today overrides and persistent daily usage.
- Original-audio default and explicit audio-language selection in the player menu.
- Bounded memory/disk thumbnail and channel-icon caching.
- Material 3 Expressive timer controls.

There is currently **one local profile**. Open **Parent → Manage timers** to add
weekly schedules or change just today's limit. Watching counts actual foreground
playback; Full app includes browsing. Both can apply together. When schedules of
the same type overlap, the smallest allowance applies. Today replaces that type's
weekly limit until local midnight; changing a limit keeps time already used.
Bonus-time grants, multiple profiles, PIN recovery and iOS are not implemented.
Source extraction can break when YouTube changes.

## Build and check

Install JDK 17, Android SDK platform 36 and build-tools 35.0.0. Set `ANDROID_HOME`
or use an ignored `local.properties` containing `sdk.dir=…`.

```sh
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug
```

The wrapper pins Gradle 8.13 and its distribution checksum. APKs are generated at
`app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/release/app-release.apk`.
The minified release variant uses development signing for alpha testing. Android
6/API 23 is the current minimum; broader compatibility is still being tested.

Optional probes contact public YouTube endpoints and run separately from CI:

```sh
./gradlew :app:channelSearchProbe -PsearchTerm="checker tobi"
./gradlew :app:extractionProbe -PprobeChannel=@CrunchLabs
```

These inspect metadata/source resolution, without decoding video or establishing
containment. Signed media URLs are not logged.

## Contribute

Bug reports, device testing and focused pull requests are welcome. See
[CONTRIBUTING.md](CONTRIBUTING.md), [architecture](docs/ARCHITECTURE.md) and
[roadmap](docs/ROADMAP.md). Sensitive access-control problems: [SECURITY.md](SECURITY.md).

Application source: GPL-3.0-or-later, see [LICENSE](LICENSE). Basil artwork by
Craftwork is CC BY 4.0 and retains its separate license. See
[third-party notices](THIRD_PARTY_NOTICES.md) and [licenses](licenses/README.md).
