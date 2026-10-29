# Repository Instructions

Before making changes in this repository, read `CODEX.md` completely and
follow the working-mode rules defined there.

The default mode is `SOURCE PATCH ONLY`. Do not run Gradle, compile, build or
install APKs, execute packaging scripts, publish releases, push tags, or upload
artifacts unless the user explicitly switches to the corresponding mode
described in `CODEX.md`.

Never switch modes automatically. Preserve existing user changes and treat
this directory as the active Dashchan_2 checkout.

# Fork: Slooop

This checkout is the `Slooop` fork; `FORK.md` covers what it adds, how to build
and publish it, versioning and the signing key. Read it before a release.

- Build and publish with `scripts/fork-release.sh build` then
  `scripts/fork-release.sh sync`. `sync` updates the private F-Droid repository
  and verifies the served APK hash; a build alone is not a release.
- Never regenerate `~/.android-keystores/slooop-fork.jks`. If it is missing,
  stop and ask the user.
- Commit the `fork-version.properties` bump together with the release work.
- Keep fork changes isolated so `git rebase upstream/master` stays cheap.

