# Changelog

All notable user-facing changes to NuvioRoid are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Version numbers use the upstream Nuvio Desktop version plus a fourth fork release number (see [docs/RELEASING.md](docs/RELEASING.md)).

## [Unreleased]

### Added

- A download source that fails or turns out to be a placeholder is replaced by the next untried source by itself, and tried sources are remembered across retries and restarts ([#62](https://github.com/mischief-merged/NuvioRoid/pull/62)).
- Delete after watching can now cover downloads you start yourself, not only automatic ones; a finished title moves to the Trash after the delay unless you tap Keep, and Keep can be undone ([#107](https://github.com/mischief-merged/NuvioRoid/pull/107)).
- Downloads let other apps go first and wait on very slow or blocking networks, judging each host on its own so a good connection is never held back ([#60](https://github.com/mischief-merged/NuvioRoid/pull/60)).
- Automatic source failover during playback: when a source fails to start, stops working mid-play, or turns out to be a short placeholder clip, the player moves to the next ranked source and resumes where playback left off, with a setting to turn it off (on by default on desktop) ([#42](https://github.com/mischief-merged/NuvioRoid/pull/42)).
- Stall and placeholder detection during playback: sources that make no progress at startup or stop responding mid-play are abandoned for the next ranked source, and provider placeholder clips are flagged using link and file-name signs paired with a second check such as a very short length; pausing, seeking, sleep, end-of-file, and going offline do not count as stalls ([#44](https://github.com/mischief-merged/NuvioRoid/pull/44)).
- Complete offline downloads: downloaded titles now keep their subtitles (including on desktop), intro and credits skip times, the season's episode list for the next-episode card, and poster and thumbnail artwork in a folder beside the video, with existing downloads filled in quietly in the background ([#48](https://github.com/mischief-merged/NuvioRoid/pull/48)).
- Offline show and episode pages now render from saved download details when there is no connection, and trailers are hidden while offline ([#48](https://github.com/mischief-merged/NuvioRoid/pull/48)).
- Placeholder detection and re-checking for downloads: transfers whose announced size is far below the source's stated size are stopped early to try the next source, and completed or existing downloads are flagged only when decisive evidence or two corroborating weaker signs agree (far smaller than a clear size claim even at full length, absurdly small for the title's running time, both small and far shorter than the title, or a still/blank picture paired with either a short length or a small file whose length is unknown); a single weak sign on its own is never flagged, a file whose length matches the title is never flagged by the running-time or short-clip checks, and flagged rows show the reason with "Check again" and "Check all downloads" actions without counting as downloaded or removing existing files automatically ([#50](https://github.com/mischief-merged/NuvioRoid/pull/50)).
- Custom download folders and multiple storage locations on desktop: pick where downloads are stored, move all or chosen titles safely to another folder or drive (verified copy before moving the original to the Trash, with recovery after a crash or unplugged drive), and view used and free space per location on the Downloads screen ([#54](https://github.com/mischief-merged/NuvioRoid/pull/54)).
- Automatic download pause on hotspots and Low Data Mode networks on macOS: downloads wait without losing progress and resume automatically on a normal connection (including after a restart), with a setting to turn the pause off and a "Download anyway" override that ends when the network changes ([#56](https://github.com/mischief-merged/NuvioRoid/pull/56)).

### Changed

- Finished downloads are checked frame by frame only when their size and running time cannot already confirm them, one at a time, so completing several downloads no longer slows the app; a file that fails its check goes to the Trash ([#130](https://github.com/mischief-merged/NuvioRoid/pull/130)).
- The app window appears and responds sooner at startup: loading the downloads list and saving the window position no longer run on the interface thread ([#129](https://github.com/mischief-merged/NuvioRoid/pull/129)).
- Updated to the latest upstream Nuvio Desktop, adding Stremio tracking add-ons, keyboard and gamepad navigation, Plex and Silo servers, the redesigned downloads screen, library filters, subtitle improvements, precise seeking, more translations, and memory settings that let an idle app hand memory back ([#128](https://github.com/mischief-merged/NuvioRoid/pull/128)).
- Source ranking now checks release names for title, episode, film year, and audio language: sources that clearly belong to another title, episode, or year are moved to the back of the list (never hidden), subtitle languages are separated from audio languages, and untagged foreign-looking sources no longer count as the chosen audio language ([#46](https://github.com/mischief-merged/NuvioRoid/pull/46)).
- Widened playback placeholder detection to catch clips under ten minutes and under a fifth of the title's running time ([#50](https://github.com/mischief-merged/NuvioRoid/pull/50)).

### Fixed

- Finished downloaded episodes now count as watched, so automatic downloads move on to the next episodes; only episodes finished on this device count, and unwatching an episode cancels its pending removal ([#118](https://github.com/mischief-merged/NuvioRoid/pull/118)).
- Centred the current episode in the episode page navigation trail to match the links beside it ([#42](https://github.com/mischief-merged/NuvioRoid/pull/42)).
- Avoided a dead-end state on show and episode pages while saved offline details are still loading ([#50](https://github.com/mischief-merged/NuvioRoid/pull/50)).
- Preserved raw binary HTTP responses on desktop so posters and episode thumbnails saved beside downloads are no longer corrupted, and recorded why missing artwork failed so temporary errors retry sooner ([#52](https://github.com/mischief-merged/NuvioRoid/pull/52)).
- Stopped looking up skip times when no skip-intro service is configured, and loaded show details once across a show's episodes with retries if an earlier pass was not ready ([#52](https://github.com/mischief-merged/NuvioRoid/pull/52)).
- Kept the downloads folder and move journals intact when app data is cleared on sign-out, a lost session, or account deletion ([#54](https://github.com/mischief-merged/NuvioRoid/pull/54)).
- Prevented multiple copies of the app from writing to the same data folder at once, kept partial downloads until replacement space checks pass, and prevented downloads, cancellations, or moves from overwriting or permanently deleting user files ([#54](https://github.com/mischief-merged/NuvioRoid/pull/54)).
- Automatically moved on from debrid "not cached yet" notice clips during playback (including manually chosen sources and playback without a known running time), flagged short downloads from uncached sources as invalid, preferred cached sources over uncached ones, prevented notice clips from counting as watched, and kept playing the last source with a notice if all sources were skipped ([#58](https://github.com/mischief-merged/NuvioRoid/pull/58)).

### Security

- Opened download files, partial files, temporary files, sidecar folders, and storage-location markers without following symbolic links, and refused to modify or remove files and folders whose recorded ownership cannot be verified ([#54](https://github.com/mischief-merged/NuvioRoid/pull/54)).

## [1.1.36.1] - 2026-11-15

First downloadable alpha build of NuvioRoid for macOS (Apple silicon), based on upstream Nuvio Desktop 1.1.36 (`Dev` at `c8ee7621`). Installs alongside the official Nuvio app with its own name, icon, and data folder; does not auto-update; not signed with an Apple developer certificate; packaged build does not include Nuvio account sign-in/sync or a Trakt connection.

### Added

- Season download: download every released episode of a season in one action, two at a time, with progress and Stop on the button, ranking sources per episode and trying the next source if a file is bad ([#6](https://github.com/mischief-merged/NuvioRoid/pull/6)).
- Saved download preferences per profile (quality, maximum bitrate, and audio language), with dialogs noting that choices are saved and a "Just this time" option for one-off downloads ([#8](https://github.com/mischief-merged/NuvioRoid/pull/8), [#36](https://github.com/mischief-merged/NuvioRoid/pull/36)).
- Movie and movie collection downloads using the same download dialog, with a checklist for choosing titles in a collection ([#10](https://github.com/mischief-merged/NuvioRoid/pull/10), [#12](https://github.com/mischief-merged/NuvioRoid/pull/12)).
- Global download speed limit ([#14](https://github.com/mischief-merged/NuvioRoid/pull/14)).
- Trakt offline watch queue that saves watches recorded offline and sends them once reconnected, with pending count, "Sync now", and a disconnect warning in Settings > Tracking ([#16](https://github.com/mischief-merged/NuvioRoid/pull/16), [#30](https://github.com/mischief-merged/NuvioRoid/pull/30)).
- Per-show auto-download rules to keep the next N unwatched episodes on disk within a dedicated storage limit for automatic downloads ([#18](https://github.com/mischief-merged/NuvioRoid/pull/18), [#20](https://github.com/mischief-merged/NuvioRoid/pull/20)).
- Optional per-show "Delete after watching" for auto-downloaded episodes, moving watched episodes to the Trash after a delay with Keep, a row countdown, and a "Recently deleted" list ([#22](https://github.com/mischief-merged/NuvioRoid/pull/22)).
- Download file verification before marking a download complete: checks file size, container type, running time, server hash or digest, audio languages, and sample frames near the start and end to catch truncated, blank, or still-image files ([#24](https://github.com/mischief-merged/NuvioRoid/pull/24)).
- "Best match for my preferences" auto-play mode using saved quality, bitrate, and audio language, naming the chosen source on the loading screen and opening the source list with an explanation and preferences link if nothing fits (manual stays the default) ([#26](https://github.com/mischief-merged/NuvioRoid/pull/26), [#36](https://github.com/mischief-merged/NuvioRoid/pull/36)).
- Quality and language preferences in Settings > Playback and a one-time setup prompt for new profiles ([#28](https://github.com/mischief-merged/NuvioRoid/pull/28)).
- Persistent summaries of the last season, movie collection, or movie download run on the Downloads screen until dismissed, showing counts (added, already present, not found, paused, failed) and "Choose another source" for missing or failed titles ([#36](https://github.com/mischief-merged/NuvioRoid/pull/36)).
- Episode pages with still, title, air date, runtime, rating, summary, Play or Resume, mark watched, previous and next episode, season list, and a `Show > Season > Episode` trail linked from downloads, Continue Watching, and the player title ([#38](https://github.com/mischief-merged/NuvioRoid/pull/38)).

### Changed

- Redesigned Downloads screen rows to show what each item is doing, how finished files were verified, plain failure reasons with a next step (including "Choose another source"), named Trash confirmations, unreadable-list errors instead of empty screens, and accessibility labels ([#32](https://github.com/mischief-merged/NuvioRoid/pull/32)).
- Clarified auto-download behaviour: deleting an auto-downloaded episode does not re-download it, rule rows open their settings, results are shown only after saving, and closing the window while downloads are running asks first ([#34](https://github.com/mischief-merged/NuvioRoid/pull/34)).
- Unified English wording across sources, downloads, errors, and settings into one plainer vocabulary ([#36](https://github.com/mischief-merged/NuvioRoid/pull/36)).
- Made the download speed limit easier to find and understand: the Downloads screen displays any active limit with a link to the setting, the setting uses the standard picker with a plain description, and bitrate options name megabits per second with the file-size/quality trade-off ([#36](https://github.com/mischief-merged/NuvioRoid/pull/36)).
- Separated "Playback audio language" and "Download audio language" with an explanation of how they differ, and prefilled the initial download audio language from the playback language for profiles without saved download choices (shown without saving or replacing existing choices) ([#36](https://github.com/mischief-merged/NuvioRoid/pull/36)).

### Fixed

- Kept the refreshed Trakt token when switching profiles mid-refresh instead of signing that profile out ([#2](https://github.com/mischief-merged/NuvioRoid/pull/2)).
- Serialised download state updates across threads, saved the downloads list atomically with a fallback copy and tolerant decoding, and resumed or retried interrupted downloads on launch and reconnect ([#4](https://github.com/mischief-merged/NuvioRoid/pull/4)).
- Showed the "Add Profile" tile and guidance on the first-run profile picker when continuing without an account, with labels and keyboard navigation ([#40](https://github.com/mischief-merged/NuvioRoid/pull/40)).

[Unreleased]: https://github.com/mischief-merged/NuvioRoid/compare/nuvioroid-1.1.36.1...HEAD
[1.1.36.1]: https://github.com/mischief-merged/NuvioRoid/releases/tag/nuvioroid-1.1.36.1
