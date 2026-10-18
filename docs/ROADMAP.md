# Roadmap

Current Android alpha priorities, without promised release dates:

- Expand strict permission, lifecycle, network and physical-device checks.
- Check Chromebook input/window behavior and older Android compatibility.
- Validate daily schedules/Today overrides on physical devices; refine parent bonus-time grants.
- Add child profiles and define PIN recovery.
- Refine discovery, browsing and accessibility from testing feedback.
- Establish production signing and distribution after alpha validation.

## iOS exploration

No iOS app exists. Android Java views and Media3 do not build for iOS. A separate
client and extraction/playback approach need investigation, retaining the same
strict content-permission requirements.

A contributor with a Mac and physical iPhone/iPad would be the best first step.
Xcode requires supported macOS. Standard GitHub macOS runners can provide CI for
public repositories but cannot replace physical testing. TestFlight is a future
beta-distribution option requiring Apple account/developer setup; none was created.

References: [Xcode requirements](https://developer.apple.com/xcode/system-requirements),
[GitHub runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners),
[TestFlight](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/).
