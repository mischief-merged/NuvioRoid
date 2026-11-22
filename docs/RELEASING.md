# Releasing NuvioRoid

This guide describes how NuvioRoid versions are numbered and the steps required to publish a release.

## Version scheme

NuvioRoid uses a four-part version number made up of the upstream Nuvio Desktop version plus a fourth fork release number:

```text
<upstream-major>.<upstream-minor>.<upstream-patch>.<fork-release>
```

Examples:

- `1.1.36.1` — first NuvioRoid release based on upstream `1.1.36`
- `1.1.36.2` — second NuvioRoid release based on upstream `1.1.36`
- `1.1.37.1` — first NuvioRoid release after rebasing onto upstream `1.1.37`

The git tag for each release is `nuvioroid-<version>` (for example, `nuvioroid-1.1.36.2`).

## Release branches

- `downloads-suite` is the default branch and combines the fork's changes under the upstream app name.
- `nuvioroid` merges `downloads-suite` and carries the NuvioRoid app name, icon, and macOS bundle identifier. Release tags and macOS DMGs are built from `nuvioroid`.

## Release checklist

Every step below must pass before publishing. If any check fails or cannot run, stop and do not publish the release.

### 1. Merge and approve target pull requests

- Confirm every pull request planned for the release has been reviewed, approved, and merged into `downloads-suite`.
- Keep risky changes (sign-in, storage, migrations) small and separate rather than bundling them with unrelated features.
- Merge `downloads-suite` into `nuvioroid`.

### 2. Run the full desktop test suite

Run the desktop test suite on macOS (Apple silicon):

```bash
./gradlew :composeApp:desktopTest
```

Confirm there are no new failures. Only the three upstream failures listed in [FORK.md](../FORK.md) (`DesktopWindowInsetsTest`, `SettingsNavigationTest`, `WatchProgressIdentityTest`) are expected.

### 3. Pass the pre-install release check

Run the full release check tracked in [issue #113](https://github.com/mischief-merged/NuvioRoid/issues/113) on a separate test machine before installing on a daily-use machine or publishing:

1. Back up the current app and data folder (settings files, not videos).
2. Start the new build on a copy of a real data folder, signed in when account sign-in is enabled, upgrading from the currently installed build.
3. Verify on first start, after a normal quit and reopen, and after a forced quit that sign-in state, library, downloads list, watch progress, auto-download rules, profiles, and settings are all present and that downloaded videos still play.
4. Click through every path the release adds or changes from start to finish.
5. Repeat the changed paths under constrained networks (hotspot, slow, lossy, one host blocked, offline), then restore the network.
6. Record what was exercised and anything that could not be verified. If any required step cannot run, do not publish.

### 4. Update `CHANGELOG.md` and `README.md`

- In [CHANGELOG.md](../CHANGELOG.md), move the entries under `## [Unreleased]` into a new dated section `## [<version>] - YYYY-MM-DD`, leave an empty `## [Unreleased]` section above it, and update the comparison links at the bottom of the file.
- Update the download link in [README.md](../README.md) so it points to the new `nuvioroid-<version>` release tag.
- Commit these updates on `downloads-suite` and merge them into `nuvioroid`.

### 5. Tag the release

Tag the release commit on `nuvioroid`:

```bash
git tag -a nuvioroid-<version> -m "NuvioRoid <version>"
git push origin nuvioroid nuvioroid-<version>
```

### 6. Build the macOS DMG and checksums

Build the macOS (Apple silicon) DMG from the tagged `nuvioroid` commit:

```bash
./gradlew :composeApp:packageDmg
```

Rename the resulting DMG to `NuvioRoid-<version>-macos-arm64.dmg` and generate its SHA-256 checksum file:

```bash
shasum -a 256 NuvioRoid-<version>-macos-arm64.dmg > SHA256SUMS.txt
```

**Every release must replace the downloadable DMG.** Always build and attach a fresh `NuvioRoid-<version>-macos-arm64.dmg` and matching `SHA256SUMS.txt` so the latest release always provides the current build.

### 7. Publish the GitHub release

Publish a GitHub release for tag `nuvioroid-<version>`:

- Use the new version section from `CHANGELOG.md` as the release notes (along with the macOS install instructions and SHA-256 checksum).
- Attach both `NuvioRoid-<version>-macos-arm64.dmg` and `SHA256SUMS.txt`.
- Mark the release as a **pre-release** while NuvioRoid is in alpha.

Example command:

```bash
gh release create nuvioroid-<version> \
  NuvioRoid-<version>-macos-arm64.dmg \
  SHA256SUMS.txt \
  --title "NuvioRoid <version>" \
  --notes-file release-notes.md \
  --prerelease
```
