# Slooop fork notes

Slooop is a personal fork of [andrewpozdnakov7-jpg/Dashchan_2](https://github.com/andrewpozdnakov7-jpg/Dashchan_2),
which is itself a fork of Mishiranu's Dashchan. Upstream cannot be pushed to from this account,
so day-to-day work happens on `fork` in this checkout and on `lachlanc/Dashchan_2`.

`CODEX.md` still defines the working modes and `SOURCE PATCH ONLY` is still the default. A build
or a release is `FAST LOCAL TEST` / `PUBLIC RELEASE` and needs the user to say so.

## What the fork adds

- TikTok mode prefetching: a filter-aware plan for the neighbouring attachments, a reverse
  window, swipe-direction priority, two concurrent transfers (next and previous), and early
  starts while the visible video is still streaming.
  `unitTests/TIKTOK_PREFETCH.md` describes the behaviour and the device checks; the pure selection
  logic is in `src/com/mishiranu/dashchan/ui/gallery/GalleryPrefetchPlan.java`.

## Building and publishing

Everything goes through `scripts/fork-release.sh`:

```sh
scripts/fork-release.sh build          # arm64-v8a release APK -> ~/slooop-share/
scripts/fork-release.sh build x86      # another ABI, same keystore
scripts/fork-release.sh sync           # publish the staged APK to the private F-Droid repo
```

`build` runs `assembleGithubRelease` with `-PnativePlayerFfmpegFlavor=ffmpeg8 -PnativeAbis=<abi>`,
signs the unsigned APK with `apksigner`, checks the package name and ABI, then stages
`Slooop-<versionName>-<abi>.apk` plus `SHA256SUMS` in `~/slooop-share/`. `sync` copies the staged
APK to `~/fdroid/repo/`, refreshes the signed index with `fdroid update --create-metadata --pretty`
and verifies that the served file matches the staged hash.

The F-Droid repository is the only hosted download path: `http://<LAN IP>:8099/fdroid/repo`. The
existing `apk-repo.service` serves it through `~/apk-repo/fdroid/repo`, a symlink to
`~/fdroid/repo/`. Never start a second APK server or create per-app download folders.
`~/fdroid/metadata/io.dashchan2.yml` holds the listing metadata.

### Versioning

Every local build gets a new version from `fork-version.properties` (`forkVersion=N`): the APK
becomes `<versionName>-f<N>` with `versionCode = base + N * 10 + 2`. The script bumps the counter
before building and rewinds it if the build fails. Commit the resulting `fork-version.properties`
with the release. Release metadata in `metadata/versions.json` is still validated against the base
version, so a local build does not require editing it; `CODEX.md`'s changelog rules apply when the
base version itself is raised.

Only one ABI is published at a time (arm64-v8a by default). Publishing a second ABI under the same
version would be rejected: rebuild and sync instead. Old versions stay in `~/fdroid/repo/` (the
Seal builds accumulate the same way); delete the APKs you no longer want and run `fdroid update`.

Fork builds set `ALLOW_APPLICATION_SELF_UPDATE=false`: updates come from the private F-Droid
repository, because the upstream GitHub release is signed with another key and could never install
over a fork build. Upstream checkouts, where `fork-version.properties` does not exist, keep their
normal update behaviour.

### Signing key: never regenerate it

- Release builds are signed with `~/.android-keystores/slooop-fork.jks` (alias `slooop`).
- `keystore.properties` in the repo root points at it and is gitignored; never commit it.
- A backup copy is at `~/.android-keystores/slooop-fork.keystore.properties`.
- If `keystore.properties` is missing, copy the backup into the repo root.
- Never create a new key. Builds signed with a different key cannot update the installed app.
- The upstream GitHub release is signed with a different key, so moving from it to a local build
  needs one uninstall (and loses app data). Say so before publishing a first build.

## One-time machine setup

Only needed when the build fails because tools are missing:

- **JDK 21** (Gradle and AGP require it): `sudo apt-get install -y openjdk-21-jdk-headless`
- **Android SDK** in `~/android-sdk`, with the command-line tools in
  `~/android-sdk/cmdline-tools/latest`, then:
  `sdkmanager --sdk_root=$HOME/android-sdk "platform-tools" "platforms;android-37.0" "build-tools;36.0.0" "ndk;29.0.14206865"`
- **Native build tools** for the bundled FFmpeg/dav1d libraries:
  `sudo apt-get install -y meson nasm bzip2 xz-utils pkg-config cmake ninja-build`
- **`local.properties`** in the repo root (gitignored) with `sdk.dir=/home/lachlan/android-sdk`

The first build downloads the pinned dav1d/ffmpeg sources and compiles them, so it takes tens of
minutes; later builds reuse `Dashchan-Webm/build`.

## Tests

```sh
./gradlew testGithubDebugUnitTest -PnativePlayerFfmpegFlavor=ffmpeg8 -PnativeAbis=arm64-v8a
```

`unitTests/src` holds the JVM fixtures, including `GalleryPrefetchPlanTest`.

## Syncing with upstream

```sh
git fetch upstream && git rebase upstream/master    # upstream = andrewpozdnakov7-jpg/Dashchan_2
```

Fork-only files (`scripts/`, `fork-version.properties`, `FORK.md`, the prefetch classes) rebase
cleanly. The touched upstream files are `build.gradle` (the fork version block at the end),
`PagerUnit.java`, `MediaPreloader.java`, `PreloadMediaTask.java`, `Preferences.java`,
`VideoPreloadFragment.java`, `SettingsSearchIndex.java` and the two `strings.xml` files; re-check
them for conflicts.
