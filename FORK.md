# NuvioRoid: what is different in this fork

NuvioRoid is a fork of [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop). It adds offline-viewing features to the desktop app and makes the existing download and playback flows easier to follow. Everything here is offered back to the upstream project; nothing has been submitted yet, and each change is kept on its own branch so it can be proposed and reviewed separately.

- **Based on:** upstream `Dev` at `04c94b54` (24 November 2026), upstream version 0.1.39.
- **Everything together:** the `downloads-suite` branch.
- **Scope:** desktop. Built and tested on macOS (Apple silicon). Shared code compiles for desktop only in this fork's test runs; the Android and iOS builds have not been run, and the Linux and Windows player bridges are unchanged.

## Downloads

| Change | What it does | Branch |
|---|---|---|
| Season download | One action on a series page downloads every released episode of a season, two at a time, with Stop and progress on the button. Sources are ranked per episode and the next one is tried when a file turns out to be bad. | `pr/c-season-download` |
| Saved preferences | Quality, maximum bitrate and audio language are remembered per profile and reused. A "Just this time" option keeps a one-off download from changing them. | `pr/c2-download-defaults` |
| Movies and movie sets | The same download dialog for a single movie, and for a collection with a checklist of which titles to fetch. | `pr/c3-movie-download`, `pr/c4-movie-set-download` |
| Auto-download | Per show: keep the next N unwatched episodes downloaded, within a storage limit that applies to automatic downloads only. | `pr/f-auto-download`, `pr/f2-auto-download-storage-limit` |
| Delete after watching | Optional per show. A watched episode is moved to the Trash after a delay, with Keep, a countdown on the row and a "Recently deleted" list. | `pr/g-delete-after-watching` |
| Speed limit | A global cap on download speed. | `pr/d-download-speed-limit` |
| File checks | A finished file is checked before it counts as downloaded: size, container type, running time, the hash or digest the source or server provides, audio languages, and a look at frames near the start and end to catch truncated, blank or single-image files. On macOS the frame look uses the player library the app already ships. | `pr/h-download-frame-check` |
| Safer state | Download state updates are serialised across threads; the stored list is written atomically with a fallback copy and decoded tolerantly; interrupted downloads resume on launch and retry when the connection returns. | `pr/b-downloads-concurrency` and later branches |
| Downloads screen | Rows say what is happening (waiting for the internet, checking the file, paused and why) and how a finished file was verified. Failures give a plain reason and a next step, including "Choose another source". Delete moves files to the Trash and names the item. An unreadable list is reported instead of shown as empty. Controls are named for assistive technology. | `fix/downloads-screen-states` |
| Clearer auto-download | Deleting an auto-downloaded episode does not fetch it again; rule rows open their settings; results are reported only after they are saved; closing the window while downloads are running asks first. | `fix/auto-download-clarity` |
| Complete offline | A download carries what it needs to be watched with no connection: subtitles (now also saved on desktop), intro and credits skip times, the season's episode list for the next-episode card, and a poster and thumbnail, all kept in one folder beside the video. Offline, the episode and show pages are drawn from these saved details. A failed lookup can add to what is saved but never replaces it. Existing downloads are filled in quietly in the background. | `pr/p-offline-skip-times` |
| Not the real video | A download that is only a provider's placeholder clip is recognised: far smaller than the size the source stated, far too small or too short for the title's length, or a still picture. It is shown as a problem with the reason, is not counted as downloaded, and a real copy is fetched beside it. Downloads made before the checks existed are checked again in the background. Nothing already stored is removed automatically. | `fix/recheck-downloads-and-placeholders` |
| Choose folders and drives | Pick where downloads are stored and move all or chosen titles to another folder or drive. The copy is verified before the original goes to the Trash, and a move survives a crash or an unplugged drive. Used and free space is shown per location. | [#54](https://github.com/mischief-merged/NuvioRoid/pull/54) |
| Hotspots and Low Data Mode | On macOS, downloads wait on hotspots and Low Data Mode networks without losing progress and resume on a normal connection. Can be turned off, with a "Download anyway" that ends when the network changes. | [#56](https://github.com/mischief-merged/NuvioRoid/pull/56) |
| Polite downloads | Downloads let other apps go first and wait out very slow or blocking networks, judging each host on its own. | [#60](https://github.com/mischief-merged/NuvioRoid/pull/60) |
| Replace a bad source | A download source that fails or turns out to be a placeholder is replaced by the next untried one, and tried sources are remembered across retries and restarts. | [#62](https://github.com/mischief-merged/NuvioRoid/pull/62) |
| Keep downloading | Downloads carry on when the window closes, with a menu bar progress item and a check before quitting. The Mac stays awake only while data is arriving. | [#64](https://github.com/mischief-merged/NuvioRoid/pull/64) |
| Offline first | Downloaded copies play with no internet, and pages keep working offline. | [#66](https://github.com/mischief-merged/NuvioRoid/pull/66) |
| Link refresh | An expired download link is refreshed and the same file resumes instead of starting over. | [#94](https://github.com/mischief-merged/NuvioRoid/pull/94) |
| Rebuild the list | The downloads list can be rebuilt from the files already on disk, with a "Find downloads on disk" button. | [#98](https://github.com/mischief-merged/NuvioRoid/pull/98) |
| Trip ready | Offline hours at a glance and a "Fill for my trip" button. | [#100](https://github.com/mischief-merged/NuvioRoid/pull/100) |
| Travel mode | Settings for plane and Starlink Wi-Fi, with network rules checked again after the Mac wakes. | [#105](https://github.com/mischief-merged/NuvioRoid/pull/105) |
| Delete after watching, for everything | Delete after watching can also cover downloads you start yourself. Keep can be undone. | [#107](https://github.com/mischief-merged/NuvioRoid/pull/107) |
| Nothing deleted for good | Downloads, partial files, rejected files and subtitle folders always go to the Trash or a holding folder, and a partial is kept until its replacement is safely in place. | [#149](https://github.com/mischief-merged/NuvioRoid/pull/149) |
| Storage limit for everything | One storage limit for all downloads, a clean-up list for watched downloads, an optional free-space floor per drive and an optional make-room mode. Everything goes to the Trash with Undo. | [#152](https://github.com/mischief-merged/NuvioRoid/pull/152) |

## Playback

| Change | What it does | Branch |
|---|---|---|
| Play by preferences | A new auto-play mode, "Best match for my preferences", picks the source using the same saved quality, bitrate and language as downloads. If nothing fits, the source list opens and says so; when a source is picked, the loading screen names it. Manual stays the default. | `pr/i-auto-play-preferred` |
| Preferences in Settings | Settings, Playback, "Quality and language preferences" edits the saved values and the "When I press Play" choice. A one-time prompt offers the same choices to a new profile. | `pr/j-preferences-settings` |
| Source failover | When a source fails to start or dies during playback, the player moves to the next source in the same ranked order and resumes where it was. A short placeholder clip played in place of the real file counts as a failure. Bounded, never retries a failed source, waits when offline, and can be turned off. | `pr/l-playback-failover` |
| Stalled and placeholder sources | A source that never makes progress, or stops responding during playback, is abandoned and the next one is tried. Provider placeholder clips are recognised by file name and link together with a second sign such as a very short length. Pausing, seeking, sleep and going offline do not count. | `pr/m-playback-failure-signals` |
| Source ranking by identity and language | A source that is clearly a different title, episode or film year goes to the back of the list instead of being picked. Audio language is told apart from subtitle language, and an untagged source that looks foreign no longer counts as the chosen language. Ambiguous cases are left alone. | `pr/n-source-ranking` |
| Player basics | Media keys, Now Playing, keyboard shortcuts and controls that hide on their own. | [#102](https://github.com/mischief-merged/NuvioRoid/pull/102) |
| Subtitles ready | Subtitles are fetched before playback starts. | [#104](https://github.com/mischief-merged/NuvioRoid/pull/104) |
| Sleep timer | The player stops on its own after a time you choose. | [#108](https://github.com/mischief-merged/NuvioRoid/pull/108) |
| Mini player | Keep browsing while a video plays in a small window (macOS). | [#135](https://github.com/mischief-merged/NuvioRoid/pull/135) |
| Gamepad in the Sources popup | With a gamepad, focus stays on the chosen source in the player's Sources popup instead of jumping to the header. | [#151](https://github.com/mischief-merged/NuvioRoid/pull/151) |

## Navigation

| Change | What it does | Branch |
|---|---|---|
| Episode page and trail | Each episode has its own page: still, title, air date, runtime, rating, summary, Play or Resume, mark watched, previous and next episode, and the rest of the season. A `Show > Season > Episode` trail leads back. On desktop, clicking an episode opens its page and a Play button on the thumbnail plays it; the show page can open on a given season and episode. Episode downloads, Continue Watching entries and the player title link to the episode, season and show. | `pr/k-episode-navigation` |

## First run

| Change | What it does | Branch |
|---|---|---|
| Profile picker without an account | With no profiles, the picker shows the "Add Profile" tile and a line of guidance instead of an empty screen. Its title, profile cards and buttons are labelled and work from the keyboard. | `fix/profile-picker-first-run` |
| Account data is never wiped | Sign-out, a lost session or an account change no longer wipes desktop data. A different account is offered "Start fresh", which archives the old data instead of deleting it, and quitting waits for downloads and the player to finish saving. | [#133](https://github.com/mischief-merged/NuvioRoid/pull/133) |

## Trakt

| Change | What it does | Branch |
|---|---|---|
| Token refresh | Fixes a case where switching profiles while a Trakt token was being refreshed lost the new token and signed that profile out of Trakt. | `pr/a-trakt-refresh-token` |
| Offline queue | Watches recorded while offline are kept and sent when the connection returns. | `pr/e-trakt-offline-queue` |
| Queue visible | Settings, Tracking shows how many watches are waiting, with Sync now, and warns before disconnecting with watches still unsent. | `fix/trakt-queue-visible` |

## Wording

User-facing English text uses one set of terms ("source", "Auto-download") and plainer language for errors and settings. Only English strings were changed; other languages fall back to their existing text. Branch: `fix/wording-and-feedback`.

## Testing

The desktop test suite on `downloads-suite` runs 5,066 tests. Two fail, and the same two fail on upstream `Dev` without any of these changes:

- `DesktopWindowInsetsTest` (title bar clearance)
- `SettingsNavigationTest` (buffer options by platform and playback engine)

Not covered: Android and iOS builds, Intel Macs, Linux and Windows, and screen-reader testing beyond what the semantics tests assert.

## Keeping up with upstream

The `Dev` branch of this fork mirrors upstream `Dev`. The feature branches are rebased or merged onto it as upstream moves.

## Licence

Same as upstream: GNU General Public License v3.0. See `LICENSE`.
