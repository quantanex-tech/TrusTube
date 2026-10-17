# Testing the Android alpha

Install the APK from a GitHub pre-release. Setup and subscriptions live on each
device; no YouTube sign-in is used. Choose a six-digit parent PIN and retain it
outside the child's device. PIN recovery is absent. Clearing app data removes the
PIN, subscriptions, blocks, favourites and measurements.

Parent settings relock on leaving/backgrounding. Add a trusted channel accepts
names, handles and channel URLs. Results are metadata only; choose Add to approve
one. Child Search remains within approved subscriptions.

Useful checks:

- Search **checker tobi**, identify and add the intended channel. Compare with
  `@checkerwelt`. Try cancel, a poor query, no results and a network error.
- Check all five tabs, icons and thumbnails; try resized windows/mouse/keyboard.
- Save/remove favourites, restart and check addition order/persistence.
- Play an approved ordinary video, seek, pause and check original audio/sync.
- Controls should hide after about five seconds while playing. Tap to reveal
  them together; pause and check that controls/time stay still.
- Load more listings/rail pages and check newest-upload-first ordering.
- Cancel a block, then confirm a video/channel block. Playback should stop and
  return Home. Retry/restart and check favourites/search/rail: blocked content must
  stay unavailable. Only Parent can restore it.
- Leave/background Parent during search/load: old results must not approve channels.
- Try network loss, retry and short proof timers. Disable an expired test timer
  in Parent before ordinary viewing.

This build uses one profile and cumulative test timers. Daily schedules, bonus time,
multiple profiles and PIN recovery are absent; the daily readout is unconfigured.

Earlier interface smoke checks passed on a Nothing Phone 2, Android 16/API 36.
Original playback was also tried on a Galaxy A12, Android 12/API 31. The wider
permission/device/input matrix remains incomplete. New channel discovery has host
checks; consult release notes for physical checks on the exact build.

Alpha APKs use development signing. Other machines/CI may generate different keys
and fail to update an existing installation. Prefer published release APKs for
friend testing. Never share signing keys or app data in an issue.
