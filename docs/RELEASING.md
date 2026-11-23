# Releasing NuvioRoid

This guide describes how NuvioRoid versions are numbered and the steps required to publish a release.

## Version scheme

NuvioRoid tracks three coordinated version values for every release:

1. **User-visible fork version (four parts):** `<upstream-major>.<upstream-minor>.<upstream-patch>.<fork-release>`
   - Used for the git tag (`nuvioroid-<fork-version>`), GitHub release title and notes, [CHANGELOG.md](../CHANGELOG.md), [README.md](../README.md), and the published DMG filename (`NuvioRoid-<fork-version>-macos-arm64.dmg`).
   - Upstream Nuvio Desktop `0.1.36-alpha` maps to base `1.1.36` because `jpackageCompatibleVersion` clamps a leading `0` to `1` for macOS packaging, so the first fork release is `1.1.36.1`.

2. **Three-part numeric package version (`VERSION_NAME` in `composeApp/Configuration/DesktopVersion.properties`):**
   ```text
   <upstream-major>.<upstream-minor>.<upstream-patch * 100 + fork-release>
   ```
   - `composeApp/build.gradle.kts` reads `VERSION_NAME` from `composeApp/Configuration/DesktopVersion.properties` (overridable via `-Pnuvio.desktop.versionName`, `NUVIO_DESKTOP_VERSION_NAME`, or `local.properties`) into `desktopReleaseVersionName`, compiles it into `AppVersionConfig.DESKTOP_VERSION_NAME`, and passes it through `jpackageCompatibleVersion()` to set `nativeDistributions.packageVersion` (which writes `CFBundleShortVersionString` and `CFBundleVersion` into `NuvioRoid.app/Contents/Info.plist`).
   - `jpackageCompatibleVersion()` strips any `-` or `+` suffix, splits on `.`, requires **1 to 3 non-negative numeric components**, pads to 3 components, and clamps the first component to at least `1`. Passing a four-part numeric version such as `1.1.36.2` fails the Gradle build, and renaming the DMG file alone does not change the internal version inside the `.app` bundle.
   - Encoding the fork release in the third component (`<upstream-patch * 100 + fork-release>`) keeps a valid three-part numeric package version (`major >= 1`) that strictly increases on every fork release and upstream bump (supporting up to 99 fork releases per upstream patch).

3. **Version code (`VERSION_CODE` in `composeApp/Configuration/DesktopVersion.properties`):**
   ```text
   <upstream-version-code * 100 + fork-release>
   ```
   - `composeApp/build.gradle.kts` reads `VERSION_CODE` (overridable via `-Pnuvio.desktop.versionCode`, `NUVIO_DESKTOP_VERSION_CODE`, or `local.properties`) into `desktopReleaseVersionCode` and compiles it into `AppVersionConfig.DESKTOP_VERSION_CODE` (shown as the build number in Settings > About and diagnostics).
   - Multiplying the upstream code (currently `36`) by `100` and adding `<fork-release>` guarantees a strictly increasing positive integer that stays above upstream codes and never collides when rebasing onto a newer upstream release.

### Version mapping examples

| User-visible fork version | Git tag | `VERSION_NAME` (package version) | `VERSION_CODE` | Published DMG filename |
|---|---|---|---|---|
| `1.1.36.1` | `nuvioroid-1.1.36.1` | `1.1.3601`\* | `3601`\* | `NuvioRoid-1.1.36.1-macos-arm64.dmg` |
| `1.1.36.2` | `nuvioroid-1.1.36.2` | `1.1.3602` | `3602` | `NuvioRoid-1.1.36.2-macos-arm64.dmg` |
| `1.1.36.3` | `nuvioroid-1.1.36.3` | `1.1.3603` | `3603` | `NuvioRoid-1.1.36.3-macos-arm64.dmg` |
| `1.1.37.1` | `nuvioroid-1.1.37.1` | `1.1.3701` | `3701` | `NuvioRoid-1.1.37.1-macos-arm64.dmg` |

\* `1.1.36.1` was built before this release bump process was introduced, while `DesktopVersion.properties` still held upstream `0.1.36-alpha` / `36`. Starting with `1.1.36.2`, every release must update `DesktopVersion.properties` before testing, packaging, and tagging.

### Files to update per release

Update and commit these files before running the release tests, candidate DMG build, and tag:

1. `composeApp/Configuration/DesktopVersion.properties` — set `VERSION_NAME` to the three-part numeric package version and `VERSION_CODE` to the monotonic integer code (directly or with `./scripts/set-version.sh --desktop <package-version> --desktop-code <version-code>`).
2. `CHANGELOG.md` — move the entries under `## [Unreleased]` into a new dated section `## [<fork-version>] - YYYY-MM-DD`, leave an empty `## [Unreleased]` section above it, and update the comparison links at the bottom of the file.
3. `README.md` — update the macOS download link text and target URL so they point to tag `nuvioroid-<fork-version>`.
4. `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/ForkAttribution.kt` *(on the `nuvioroid` branch only)* — update `ForkIdentity.BUILD` to `<fork-release>`. Note that `ForkIdentity.version()` currently appends `".$BUILD"` directly to `AppVersionPolicy.displayVersionName`, which would format `1.1.3602` as `1.1.3602.2`; the first release under this scheme (`1.1.36.2`) must change that formatting before testing (decode the encoded patch or keep the display version separately) so Settings shows `1.1.36.2` rather than `1.1.3602.2`.

## Release branches

- `downloads-suite` is the default branch and combines the fork's changes under the upstream app name.
- `nuvioroid` merges `downloads-suite` and carries the NuvioRoid app name, icon, Settings footer attribution, and macOS bundle identifier. Release tags and macOS DMGs are built from `nuvioroid`.

## Release checklist

Every step below must pass in order before publishing. If any check fails or cannot run, stop and do not publish the release.

### 1. Merge and approve target pull requests

- Confirm every pull request planned for the release has been reviewed, approved, and merged into `downloads-suite`.
- Keep risky changes (sign-in, storage, migrations) small and separate rather than bundling them with unrelated features.

### 2. Update version files and release docs

Before running the release test pass or building the candidate DMG:

1. Update `composeApp/Configuration/DesktopVersion.properties` on `downloads-suite`:
   ```bash
   ./scripts/set-version.sh --desktop <package-version> --desktop-code <version-code>
   ./scripts/set-version.sh --show
   ```
   (For example, for `1.1.36.2`: `--desktop 1.1.3602 --desktop-code 3602`.)
2. Update [CHANGELOG.md](../CHANGELOG.md) (move `## [Unreleased]` into `## [<fork-version>] - YYYY-MM-DD`, leave an empty `## [Unreleased]` above it, and update the comparison links).
3. Update the download link and version label in [README.md](../README.md) to point to tag `nuvioroid-<fork-version>`.
4. Commit these changes on `downloads-suite` and merge `downloads-suite` into `nuvioroid`.
5. On `nuvioroid`, update `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/ForkAttribution.kt` and commit before running Step 3:
   - Set `ForkIdentity.BUILD` to `<fork-release>`.
   - **First release under this scheme (`1.1.36.2`):** `ForkIdentity.version()` on `nuvioroid` currently appends `".$BUILD"` to `AppVersionPolicy.displayVersionName`, so `1.1.3602` would display as `1.1.3602.2`. Before testing, change `ForkIdentity.version()` (decode the encoded patch back into `<upstream-major>.<upstream-minor>.<upstream-patch>.<fork-release>`, or keep the four-part display version separately) so Settings shows `<fork-version>` (e.g. `1.1.36.2`), not `1.1.3602.2`.

### 3. Run the full desktop test suite

Run the desktop test suite on macOS (Apple silicon) against the version-bumped release candidate commit:

```bash
./gradlew :composeApp:desktopTest
```

Confirm there are no new failures. Only the three upstream failures listed in [FORK.md](../FORK.md) (`DesktopWindowInsetsTest`, `SettingsNavigationTest`, `WatchProgressIdentityTest`) are expected.

### 4. Build the candidate macOS DMG and checksums

Build the macOS (Apple silicon) DMG from the version-bumped `nuvioroid` release candidate commit:

```bash
./gradlew :composeApp:packageDmg
```

(Or `./gradlew :composeApp:packageReleaseDmg` for a release distribution.)

1. Confirm the packaged app bundle carries the updated package version in `Contents/Info.plist` (`CFBundleShortVersionString` and `CFBundleVersion` equal `<package-version>`, e.g. `1.1.3602`), not an older upstream version.
2. Copy or rename the resulting DMG to `NuvioRoid-<fork-version>-macos-arm64.dmg` and generate its SHA-256 checksum file:
   ```bash
   shasum -a 256 NuvioRoid-<fork-version>-macos-arm64.dmg > SHA256SUMS.txt
   ```

**Every release must replace the downloadable DMG.** Always build and verify a fresh `NuvioRoid-<fork-version>-macos-arm64.dmg` and matching `SHA256SUMS.txt` from the release candidate commit—never reuse an earlier DMG.

### 5. Pass the pre-install release check on the candidate DMG

Run the full release check tracked in [issue #113](https://github.com/mischief-merged/NuvioRoid/issues/113) on a separate test machine **using the actual candidate `NuvioRoid-<fork-version>-macos-arm64.dmg` built in Step 4** before installing on a daily-use machine, tagging, or publishing:

1. Back up the current app and data folder (settings files, not videos).
2. Install `NuvioRoid.app` from the candidate DMG over the currently installed build and start it on a copy of a real data folder (signed in when account sign-in is enabled).
3. Confirm the installed app's `Contents/Info.plist` version (`<package-version>`, e.g. `1.1.3602`), Settings display version (`<fork-version>`, e.g. `1.1.36.2`, not `1.1.3602.2`), and build code (`<version-code>`, e.g. `3602`) match the release candidate.
4. Verify on first start, after a normal quit and reopen, and after a forced quit that sign-in state, library, downloads list, watch progress, auto-download rules, profiles, and settings are all present and that downloaded videos still play.
5. Click through every path the release adds or changes from start to finish.
6. Repeat the changed paths under constrained networks (hotspot, slow, lossy, one host blocked, offline), then restore the network.
7. Record what was exercised and anything that could not be verified. If any required step fails or cannot run, stop—if a code or packaging fix is needed, commit it, rebuild the candidate DMG, and repeat Steps 3–5 before tagging.

### 6. Tag the release

Tag the exact `nuvioroid` commit that produced the verified candidate DMG:

```bash
git tag -a nuvioroid-<fork-version> -m "NuvioRoid <fork-version>"
git push origin downloads-suite nuvioroid nuvioroid-<fork-version>
```

### 7. Publish the GitHub release

Publish a GitHub release for tag `nuvioroid-<fork-version>` using the exact DMG and checksum file verified in Steps 4–5:

- Use the new version section from `CHANGELOG.md` as the release notes (along with the macOS install instructions and SHA-256 checksum).
- Attach both `NuvioRoid-<fork-version>-macos-arm64.dmg` and `SHA256SUMS.txt`.
- Mark the release as a **pre-release** while NuvioRoid is in alpha.

Example command:

```bash
gh release create nuvioroid-<fork-version> \
  NuvioRoid-<fork-version>-macos-arm64.dmg \
  SHA256SUMS.txt \
  --title "NuvioRoid <fork-version>" \
  --notes-file release-notes.md \
  --prerelease
```

### 8. Verify the published release and download

Before announcing the release or considering it done, verify what users actually download:

1. Download both published assets into a clean temporary directory:
   ```bash
   mkdir -p verify-release && cd verify-release
   gh release download nuvioroid-<fork-version> \
     -R mischief-merged/NuvioRoid \
     -p "NuvioRoid-<fork-version>-macos-arm64.dmg" \
     -p "SHA256SUMS.txt"
   ```
2. Verify the SHA-256 checksum of the downloaded DMG:
   ```bash
   shasum -a 256 -c SHA256SUMS.txt
   ```
   Confirm the output reports `OK`.
3. Mount the downloaded DMG and verify that the `.app` inside matches the tagged release:
   ```bash
   hdiutil attach NuvioRoid-<fork-version>-macos-arm64.dmg -nobrowse -readonly
   plutil -p /Volumes/NuvioRoid/NuvioRoid.app/Contents/Info.plist | grep -E 'CFBundle(ShortVersionString|Version)'
   ```
   Confirm `CFBundleShortVersionString` and `CFBundleVersion` match `<package-version>` (e.g. `1.1.3602`), and after installing and opening the app on the test machine, confirm Settings shows `<fork-version>` (e.g. `1.1.36.2`, not `1.1.3602.2`) and build `<version-code>` (e.g. `3602`). Detach the volume when done.
4. Open [README.md](../README.md) on GitHub and click the download link to confirm it resolves to tag `nuvioroid-<fork-version>` and lists the newly uploaded `NuvioRoid-<fork-version>-macos-arm64.dmg` and `SHA256SUMS.txt`.

If any post-publish check fails, fix and re-upload the corrected assets (or delete the broken release) before pointing users to it.
