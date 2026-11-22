<div align="center">

<img src="assets/fork-banner.png" alt="NuvioRoid: the Nuvio play-button icon with two flexing cartoon arms, surrounded by lightning" width="100%" />

# NuvioRoid

### Nuvio Desktop, on steroids. 💪

**Whole seasons in one click. The next episodes always ready. Fakes caught before you press Play.**

**No source picker. No babysitting. It just plays.**

![Platform](https://img.shields.io/badge/macOS-Apple%20silicon-111111?logo=apple&logoColor=white)
![Status](https://img.shields.io/badge/status-alpha%20%C2%B7%20fast%20development-8A2BE2)
![Tests](https://img.shields.io/badge/desktop%20tests-3%2C356-2EA3F2)
![License](https://img.shields.io/badge/license-GPLv3-3DA639)
![Based on](https://img.shields.io/badge/based%20on-Nuvio%20Desktop-5B5BD6)

[What's different](FORK.md) · [Download](#-download) · [Build it yourself](#-build-from-source) · [Credits](#-credits)

</div>

---

## ⚡ What you get

| | |
|---|---|
| 📥 **Season download** | One click fetches every released episode of a season, two at a time, with progress and Stop on the button. |
| 🔁 **Auto-download** | Per show: keep the next N unwatched episodes on disk, inside a storage limit you set. |
| 🧹 **Delete after watching** | Watched episodes move to the Trash after a delay, with Keep and a "Recently deleted" list. |
| ✈️ **Complete offline** | Downloads keep their subtitles, skip-intro times, next-episode list and artwork, so everything works with no connection. |
| 🔍 **File checks** | Every download is checked before it counts: size, type, running time, hash, audio language, and a look at the frames to catch fakes, blanks and cut-off files. |
| ▶️ **Play by preferences** | Set quality, bitrate and language once. Play picks the best matching source instead of asking every time. |
| 🔀 **Source failover** | When a source fails to start, dies mid-play or turns out to be a placeholder clip, the player moves to the next one and resumes where you were. |
| 🧭 **Episode pages** | Plex-style navigation: every episode has its own page, with a `Show › Season › Episode` trail, previous and next, and the rest of the season. |
| 🎬 **Movies and collections** | The same download flow for a single movie or a whole collection, with a checklist. |
| 🚦 **Speed limit** | Cap download speed so the rest of the house keeps streaming. |
| 📡 **Trakt offline queue** | Watches recorded offline are kept and sent when the connection returns. |
| ♿ **Clearer and more accessible** | Rows say what is happening and why, failures give a next step, and controls are named for assistive technology. |

## 🥊 Nuvio vs NuvioRoid

| | Nuvio Desktop | NuvioRoid |
|---|:---:|:---:|
| Download one episode or movie | ✅ | ✅ |
| Download a whole season in one click | — | ✅ |
| Keep the next N episodes downloaded automatically | — | ✅ |
| Delete after watching, with undo | — | ✅ |
| Frame check for fake or broken files | — | ✅ |
| Play the best source without asking | — | ✅ |
| Episode pages with a trail back to season and show | — | ✅ |
| Switch source by itself when one fails mid-play | — | ✅ |
| Skip intro, subtitles and next episode work offline | — | ✅ |
| Download speed limit | — | ✅ |
| Windows and Linux builds | ✅ | not yet |

Every row is described, with its branch and what was tested, in **[FORK.md](FORK.md)**.

See **[CHANGELOG.md](CHANGELOG.md)** for what changed in each release and what is currently unreleased.

## 📦 Download

**macOS (Apple silicon):** [**Download NuvioRoid 1.1.36.1 (alpha)**](https://github.com/mischief-merged/NuvioRoid/releases/tag/nuvioroid-1.1.36.1) · [all releases](https://github.com/mischief-merged/NuvioRoid/releases)

1. Open the DMG and drag NuvioRoid to Applications.
2. The build is not signed with an Apple developer certificate, so macOS blocks the first launch. Open it once, then go to System Settings > Privacy & Security and choose "Open Anyway".
3. Choose "Continue Without Account" and add a profile. The download does not include Nuvio account sign-in, sync or a Trakt connection.

NuvioRoid installs beside the official Nuvio app. It has its own name, icon and data folder, and it does not update itself.

## 🧪 Status

Alpha software under **fast development**. Expect breaking changes, and do not rely on it as your only media app.

- Built and tested on macOS (Apple silicon). The desktop test suite runs 3,356 tests; the three that fail also fail on upstream without any of these changes.
- Android, iOS, Windows and Linux builds of this fork have not been run.
- NuvioRoid is a client only. You bring your own sources, as with Nuvio.

## 🛠 Build from source

```bash
git clone https://github.com/mischief-merged/NuvioRoid.git
cd NuvioRoid
touch local.properties
./gradlew :composeApp:run
```

Package a macOS app:

```bash
./gradlew :composeApp:createDistributable
```

The app is built with Kotlin Multiplatform and Compose Multiplatform. The `nuvioroid` branch carries the NuvioRoid name and icon; `downloads-suite` is the same code under the original name, and each change lives on its own `pr/` or `fix/` branch.

See **[docs/RELEASING.md](docs/RELEASING.md)** for version numbering and the release process.

## 🙏 Credits

NuvioRoid is a fork of **[Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop)** by NuvioMedia, and would not exist without it. It is an independent project and is not affiliated with or endorsed by NuvioMedia. Everything here is offered back to the upstream project.

## License

[GNU General Public License v3.0](./LICENSE), the same as upstream.
