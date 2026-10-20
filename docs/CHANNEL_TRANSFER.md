# Transfer subscribed channels

In version 0.5 and later, unlock **Parent** and choose **Export subscribed channels**.
Save the JSON file and share it with your other device using your usual file-sharing
method. On that device, unlock **Parent → Import subscribed channels** and select
the file. The document picker locks parent settings; enter the PIN again, choose
**Review channel import** and confirm **Merge channels**.

Import adds subscriptions without duplicates. Existing subscriptions, blocks,
favourites, timers, usage and the parent PIN stay in place. A blocked subscription
in the file remains blocked on the destination, and importing an allowed channel
never clears a block already on that device. Parent settings can restore a block
separately. Video listings refresh online after import; loading failures do not
remove the saved subscriptions. This is a one-time transfer, without ongoing sync.

Files contain channel IDs, names and channel block status. They do not contain PINs,
accounts, favourite videos, individual video blocks, timers or usage. Share them
with people you intend to share your subscriptions with.

The portable format is UTF-8 JSON, up to 1 MB and 2,000 channels:

```json
{
  "format": "trustube.channels",
  "version": 1,
  "channels": [
    {
      "id": "UCaaaaaaaaaaaaaaaaaaaaaa",
      "name": "Example channel",
      "blocked": false
    }
  ]
}
```

IDs must be canonical YouTube channel IDs (`UC` followed by 22 URL-safe characters).
Names are display labels with no control characters and at most 256 characters.
Duplicate IDs merge; any blocked duplicate remains blocked. Unknown format versions,
malformed files and imports exceeding the destination's channel limit are rejected
before changing subscriptions. Editing a channel name does not change its identity.
