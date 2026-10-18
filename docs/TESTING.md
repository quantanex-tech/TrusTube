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

Use **Parent → Manage timers** for daily limits:

- Add a named Watching timer. Use Weekdays or Weekend, tap a day, adjust in
  15-minute steps or tap the duration for exact minutes, then Save timer.
- Add a Full app timer. Pause/buffering should stop Watching usage while Full app
  continues in the foreground. Backgrounding stops both; returning never autoplays.
- Check overlapping schedules: the smallest matching allowance per type applies.
  Both types run together. Zero minutes blocks that activity; a disabled day is unlimited.
- In Today choose Watching or Full app and change its limit. This replaces the
  weekly limit for that type only. Daily limit off grants unlimited time today;
  Use weekly schedule removes the override. At local midnight the weekly schedule
  resumes, or no limit if none exists.
- Edit, disable and delete timers; spent time must remain spent. Restart the app
  and check usage/settings survive. Reset proof measurements must not reset daily usage.
- Watching expiry prevents playback while browsing remains available. Full app
  expiry locks child browsing. Parent PIN access stays available for changing limits.
- Leave/background Parent with a timer editor open; the editor must close and its
  old Done action must not save changes. Settings must require the PIN again.
- Check the player menu's two Time left today readouts. Select Audio language on
  a multilingual upload; original should be the default. Switching should preserve
  position and play/pause state. Reopening a video starts with original audio again.
- Navigate repeatedly between Home/Channels/player and restart offline: cached
  thumbnails/icons should remain available until evicted or stale (14 days).

One local profile remains. Bonus-time grants, multiple profiles and PIN recovery
are absent. Daily resets follow the device's local date/timezone; clock changes,
app-data clearing and reinstalling are not tamper protected. Short proof timers
remain separate diagnostics; disable them after testing.

Version 0.4 requires physical checks for daily expiry/rollover and decoded audio
switching. Host tests and native fixture renders do not establish device acceptance.

Earlier interface smoke checks passed on a Nothing Phone 2, Android 16/API 36.
Original playback was also tried on a Galaxy A12, Android 12/API 31. The wider
permission/device/input matrix remains incomplete. New channel discovery has host
checks; consult release notes for physical checks on the exact build.

Alpha APKs use development signing. Other machines/CI may generate different keys
and fail to update an existing installation. Prefer published release APKs for
friend testing. Never share signing keys or app data in an issue.
