#!/usr/bin/env bash
# Build, sign, stage and publish the Slooop fork APK to the private F-Droid repo.
#
#   scripts/fork-release.sh build [abi]  # assemble, sign and stage a release APK
#                                        # (default arm64-v8a; every build gets a new -f<N> version)
#   scripts/fork-release.sh sync         # publish the staged build to the private F-Droid repo
#
# See FORK.md for one-time machine setup and the signing rules.
set -euo pipefail

REPO="${FORK_REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SHARE_DIR="${SHARE_DIR:-$HOME/slooop-share}"
FDROID_DIR="${FDROID_DIR:-$HOME/fdroid}"
FDROID_BASE_URL="${FDROID_BASE_URL:-http://127.0.0.1:8099/fdroid/repo}"
GRADLEW="${GRADLEW:-./gradlew}"
FORK_VERSION_FILE="$REPO/fork-version.properties"
KEYSTORE_PROPERTIES="$REPO/keystore.properties"
EXPECTED_PACKAGE="io.dashchan2"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

die() { echo "error: $*" >&2; exit 1; }

sdk_dir() {
    local dir
    dir="$(sed -n 's/^sdk\.dir=//p' "$REPO/local.properties" 2>/dev/null || true)"
    [[ -n "$dir" ]] || dir="${ANDROID_HOME:-}"
    [[ -d "$dir" ]] || die "Android SDK not found; create local.properties with sdk.dir=... (see FORK.md)"
    echo "$dir"
}

build_tool() {
    local tools
    tools="$(ls -d "$(sdk_dir)"/build-tools/* | sort -V | tail -1)"
    echo "$tools/$1"
}

# Reads one key from keystore.properties without sourcing it (the keys contain dots).
keystore_property() {
    awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit }' "$KEYSTORE_PROPERTIES"
}

lan_ip() {
    ip -4 -o route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p'
}

# Every fork build gets its own version so the private repo always updates over the last one.
# A failed build rolls the number back, so retries do not churn the released version.
fork_version() {
    sed -n 's/^forkVersion=//p' "$FORK_VERSION_FILE" 2>/dev/null | head -1
}

set_fork_version() {
    printf 'forkVersion=%s\n' "$1" >"$FORK_VERSION_FILE"
}

cmd_build() {
    local abi="${1:-arm64-v8a}"
    [[ -x "$JAVA_HOME/bin/java" ]] || die "JDK 21 not found at $JAVA_HOME (see FORK.md)"
    [[ -f "$KEYSTORE_PROPERTIES" ]] ||
        die "keystore.properties missing. Restore it from ~/.android-keystores/slooop-fork.keystore.properties; never generate a new key (see FORK.md)"
    sdk_dir >/dev/null

    local store_file store_password key_alias key_password
    store_file="$(keystore_property store.file)"
    store_password="$(keystore_property store.password)"
    key_alias="$(keystore_property key.alias)"
    key_password="$(keystore_property key.password)"
    [[ -n "$store_file" && -n "$store_password" && -n "$key_alias" && -n "$key_password" ]] ||
        die "keystore.properties must define store.file, store.password, key.alias and key.password"
    [[ -f "$store_file" ]] || die "keystore file not found: $store_file"

    local previous_fork_version next_fork_version
    previous_fork_version="$(fork_version)"
    [[ "$previous_fork_version" =~ ^[0-9]+$ ]] || previous_fork_version=0
    next_fork_version=$((previous_fork_version + 1))
    set_fork_version "$next_fork_version"
    echo "Fork version ${next_fork_version}"

    local out="$REPO/build/outputs/apk/github/release"
    rm -f "$out"/*.apk
    if ! (cd "$REPO" && "$GRADLEW" :assembleGithubRelease \
            -PnativePlayerFfmpegFlavor=ffmpeg8 -PnativeAbis="$abi" \
            --max-workers=2 --console=plain); then
        set_fork_version "$previous_fork_version"
        die "build failed; fork version was reverted to ${previous_fork_version}"
    fi

    local unsigned
    unsigned="$(ls "$out"/*-unsigned.apk 2>/dev/null | head -1 || true)"
    [[ -n "$unsigned" ]] || die "no unsigned APK in $out"

    local aligned signed version
    mkdir -p "$SHARE_DIR" /tmp/opencode
    aligned="$(mktemp /tmp/opencode/slooop-aligned-XXXXXX.apk)"
    signed="$(mktemp /tmp/opencode/slooop-signed-XXXXXX.apk)"
    "$(build_tool zipalign)" -p -f 4 "$unsigned" "$aligned"
    "$(build_tool apksigner)" sign --ks "$store_file" --ks-key-alias "$key_alias" \
        --ks-pass "pass:$store_password" --key-pass "pass:$key_password" \
        --out "$signed" "$aligned"
    rm -f "$aligned"
    "$(build_tool apksigner)" verify "$signed" || die "APK signature check failed"

    local badging
    badging="$("$(build_tool aapt2)" dump badging "$signed")"
    grep -q "package: name='$EXPECTED_PACKAGE'" <<<"$badging" || die "unexpected package name in staged APK"
    version="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
    [[ -n "$version" ]] || die "could not read the version name from the staged APK"
    grep -q "native-code:.*'$abi'" <<<"$badging" || die "staged APK does not contain $abi"

    rm -f "$SHARE_DIR"/*.apk "$SHARE_DIR/SHA256SUMS" "$SHARE_DIR/version.properties"
    mv "$signed" "$SHARE_DIR/Slooop-$version-$abi.apk"
    printf 'versionName=%s\nabi=%s\n' "$version" "$abi" >"$SHARE_DIR/version.properties"
    (cd "$SHARE_DIR" && sha256sum *.apk >SHA256SUMS)
    echo "Staged in $SHARE_DIR:"
    ls -lh "$SHARE_DIR"
    echo "Include fork-version.properties in the release commit."
}

# Publishes the staged single-ABI build to F-Droid and verifies the served bytes.
cmd_sync() {
    ls "$SHARE_DIR"/*.apk >/dev/null 2>&1 || die "nothing staged; run '$0 build' first"
    local count
    count="$(ls "$SHARE_DIR"/*.apk | wc -l)"
    [[ "$count" == 1 ]] || die "$count APKs staged; keep only the build you want to publish"
    local apk version
    apk="$(ls "$SHARE_DIR"/*.apk | tail -1)"
    version="$(sed -n 's/^versionName=//p' "$SHARE_DIR/version.properties" 2>/dev/null | head -1)"
    [[ -n "$version" ]] || die "missing version.properties next to the staged APK"
    [[ "$(basename "$apk")" == "Slooop-$version-"*.apk ]] ||
        die "staged APK name does not match version $version"
    [[ -d "$FDROID_DIR/repo" ]] || die "F-Droid repo not found at $FDROID_DIR/repo"
    local name="Slooop-$version.apk"
    cp "$apk" "$FDROID_DIR/repo/$name"
    (cd "$FDROID_DIR" && fdroid update --create-metadata --pretty)
    local expected served
    expected="$(sha256sum "$apk" | cut -d' ' -f1)"
    served="$(curl --fail --silent --show-error --max-time 120 "$FDROID_BASE_URL/$name" | sha256sum | cut -d' ' -f1)"
    [[ "$served" == "$expected" ]] || die "F-Droid checksum mismatch for $name"
    echo "Published as $FDROID_DIR/repo/$name"
    echo "F-Droid repository: http://$(lan_ip):8099/fdroid/repo"
}

case "${1:-}" in
    build) cmd_build "${2:-}" ;;
    sync) cmd_sync ;;
    *) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
