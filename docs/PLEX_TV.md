# Plex TV on Android

TrusTube can mix a restricted child's personal Plex TV library with approved YouTube
videos. Movies and Plex's online catalog are not included. No TrusTube server is
needed. Connect one Plex server and one restricted Plex Home child on each device.

Open **Parent**, enter your existing TrusTube Parent PIN, and choose **Connect /
change Plex TV**. Choose Standard sign-in and authorize the displayed code at `plex.tv/link`, check authorization,
and select the child and server. Confirm your normal **HTTPS server origin**, for
example `https://plex.example.com`. TrusTube checks the server's identity before
sending its child credential. An advertised private/container route might not be
reachable; enter your working HTTPS route instead. Use **Retry / refresh Plex TV**
for a failed connection, or reconnect if authorization expired. If Plex cannot verify
a newly selected child with Standard sign-in, an explicit **Compatibility sign-in**
option uses a separate device-code registration. It still verifies the restricted
child and obtains that child's server credential; it never substitutes owner media
access. These methods do not switch silently. Plex remote playback
still requires your Plex server's normal remote setup and subscription eligibility.

All TV available to the selected child is included automatically. Local episode and
show blocks narrow that selection; only Parent can restore blocks. Settings and
connection changes require the existing TrusTube Parent PIN. YouTube channel transfer
files contain YouTube subscriptions and blocks only; they never contain Plex tokens.

Home makes a daily mix of YouTube videos and individual Plex episodes, aiming for equal
representation while both sources have enough candidates. **Reshuffle** makes a new
mix without changing progress or timer usage. Home return, refresh and app restart
preserve that day's identities and relative order. Episodes show their show, season and
episode number, with their own artwork and progress. Episode pages load in small
batches across shows, so a large show does not fill the candidate list first.
Regular episodes appear on Home; specials remain in the season chooser. When a
source finishes loading, its new cards fill gaps in the mix. Temporarily hidden cards
can return to their saved positions when their source is verified again.

Channels retains show browsing. Show cards name the episode they will play. **Continue** resumes or advances through
the show; **Start** starts the first available regular episode. **Start again** does
not clear Plex watched history. Specials are available through the season chooser;
an existing special's resume may also appear on a show card. **Browse episodes**
opens seasons and paged episodes. Channels mixes YouTube channels and TV shows
alphabetically, with round artwork from each show and a TV label.

Episodes use the existing player, Watching allowance, favourites and latest-50
history. Plex positions come from Plex, and actual playback reports go back to the
selected child. The app never replays an offline progress checkpoint blindly. If
Plex progress changes elsewhere, reopen the episode to read the new position.
Completion belongs to Plex's configured played/credits behavior; TrusTube preserves
a server-cleared resume point. This is conservative conflict detection, not an
atomic transaction between simultaneous clients.

Compatible files can play directly; other media uses HLS conversion automatically.
The initial conversion profile is 720p at up to 2 Mbps. Auto/manual bitrate controls
are deferred. Audio and available subtitles can be selected from player options,
keeping the position and paused/playing state. Caption/codec behavior depends on the
actual server media and device and needs representative physical testing.

Saved child credentials are encrypted with Android Keystore. Media and artwork
requests use the verified origin without following redirects; authenticated artwork
is cached only in bounded private memory. Disconnecting invalidates pending work
and clears protected artwork. Playback checks child metadata approximately every ten
seconds; browsing refreshes approximately every thirty seconds. Returning after idle
automatically rechecks the saved child before restoring Plex access. Unverifiable
access stops playback and hides Plex, with Parent retry/reconnect. Network request timeouts,
in-flight requests and already-buffered frames mean external revocation is not
instantaneous. Local blocks and connection changes are checked on each media read.

This integration is an unpublished test build. Passing host tests does not establish
complete playback containment or compatibility on every library/device.
