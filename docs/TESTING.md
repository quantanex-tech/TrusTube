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
- With a short device screen timeout, play without touching the screen for longer
  than two timeout periods. The screen should stay awake, including while buffering.
  Pause, finish the video, leave the player, trigger a block or let a viewing timer
  expire: normal screen timeout should resume. Background/return must stay paused;
  the power button must still lock the phone.
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

Version 0.5 adds [channel export/import](CHANNEL_TRANSFER.md). Check both devices:

- Export subscriptions from Parent, share the file and select it on the destination.
  Settings must relock after the picker. Review and merge only after entering the PIN again.
- Import twice: subscriptions must not duplicate. Existing blocked videos/channels,
  favourites, PIN, timers and daily usage must remain unchanged. Blocked source channels
  must remain blocked after transfer.
- Reject malformed files, invalid channel IDs and unsupported versions without partial
  approvals. Cancel/background the confirmation and check its old action cannot import.
- Restart and check imported subscriptions persist; refresh their videos online.

Version 0.6 improves loading after subscribing to many channels:

- On a fresh or stale catalog, videos should appear as each channel's first page
  arrives. Switch tabs while other channels load; the interface must stay responsive.
- Scroll Home, Channels and the player rail. Artwork loads when visible, releases
  offscreen bitmaps and reuses cached images on return. Backgrounding or leaving a
  screen cancels its artwork work; late results must not replace a different image.
- Block/remove a channel during a refresh: late pages must not restore approval.
  Load more still pages through approved content, with newest uploads first.

Daily timers still require physical checks for expiry/rollover and decoded audio
switching. Host tests and native fixture renders do not establish device acceptance.

Version 0.7 adds device-local resume playback:

- Watch beyond five seconds, star the video, pause or leave, and reopen it from
  Favorites, Home, Search, a channel or the player rail. It should resume at the
  saved time and show a purple thumbnail bar.
- Restart the app and repeat. Switch between two videos and check each keeps its
  own position. Removing a favourite must not erase its progress.
- Seek backwards and leave; reopening should use the newer, earlier position.
  Finishing a video clears progress but keeps its favourite. The final two percent,
  capped at ten seconds, counts as finished; replay starts at the beginning.
- Try loading failure, retry, audio selection and backgrounding. Failed or
  unfinished loads must not overwrite progress. Returning from background stays
  paused. Blocking the saved video/channel or expiring the timer must still deny
  playback.
- Progress is limited to 200 recently updated videos, independent of the catalog.
  Checkpoints save about every 15 seconds and on playback transitions, without
  refreshing listings or requesting artwork. Test responsiveness on a slower phone.
  Progress cannot be recovered for viewing that happened before this update.

Earlier interface smoke checks passed on a Nothing Phone 2, Android 16/API 36.
Original playback was also tried on a Galaxy A12, Android 12/API 31. The wider
permission/device/input matrix remains incomplete. New channel discovery has host
checks; consult release notes for physical checks on the exact build.

Alpha APKs use development signing. Other machines/CI may generate different keys
and fail to update an existing installation. Prefer published release APKs for
friend testing. Never share signing keys or app data in an issue.
