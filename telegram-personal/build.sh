#!/usr/bin/env bash
#
# Builds the personal Telegram APK: fetches the official Android client, applies the changes in
# this directory, and runs Gradle.
#
# Requirements: JDK 17+, Android SDK with platform 36, build-tools 36.0.0, NDK 27.2.12479018 and
# CMake 3.22.1, ~15 GB of free disk and a working network connection.
#
#   ANDROID_HOME=~/Android/Sdk ./build.sh
#
# Environment:
#   ANDROID_HOME   Android SDK location (required unless ANDROID_SDK_ROOT is set)
#   WORK_DIR       where the Telegram checkout goes            (default: ./.work)
#   TG_REF         upstream commit, tag or branch to build     (default: pinned below)
#   ABI            native ABI to build                         (default: arm64-v8a, "all" for every ABI)
#   VARIANT        Gradle variant                              (default: Debug)

set -euo pipefail

# Upstream release this patch set was written against: v12.10.1 (7038).
DEFAULT_TG_REF="62b56a07ca7e30e39f7fd00a6728d6bbd716ca1c"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="${WORK_DIR:-$HERE/.work}"
TG_REF="${TG_REF:-$DEFAULT_TG_REF}"
ABI="${ABI:-arm64-v8a}"
VARIANT="${VARIANT:-Debug}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [ -z "$SDK" ]; then
    echo "error: set ANDROID_HOME (or ANDROID_SDK_ROOT) to your Android SDK directory" >&2
    exit 1
fi

SRC="$WORK_DIR/Telegram"

if [ ! -d "$SRC/.git" ]; then
    echo "==> cloning Telegram-Android into $SRC"
    mkdir -p "$WORK_DIR"
    git clone https://github.com/DrKLO/Telegram.git "$SRC"
fi

echo "==> checking out $TG_REF"
git -C "$SRC" fetch --all --tags
git -C "$SRC" checkout --force "$TG_REF"
git -C "$SRC" clean -fd -- TMessagesProj/src/main/java
git -C "$SRC" checkout -- .

# ffmpeg, libvpx, dav1d, jlatexmath and friends. Without these the Gradle build fails on an
# unresolvable :jlatexmath project and a missing native tree.
echo "==> fetching submodules (several GB on first run)"
git -C "$SRC" submodule update --init --recursive --depth 1

echo "==> applying personal patches"
python3 "$HERE/scripts/apply_patches.py" "$SRC"

echo "==> writing local.properties"
{
    echo "sdk.dir=$SDK"
    if [ -d "$SDK/ndk/27.2.12479018" ]; then
        echo "ndk.dir=$SDK/ndk/27.2.12479018"
    fi
} > "$SRC/local.properties"

GRADLE_ARGS=(":TMessagesProj_AppStandalone:assemble${VARIANT}")
if [ "$ABI" != "all" ]; then
    GRADLE_ARGS+=("-Pandroid.injected.build.abi=$ABI")
fi

echo "==> building ${GRADLE_ARGS[*]}"
( cd "$SRC" && ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" ./gradlew "${GRADLE_ARGS[@]}" )

echo
echo "==> APK:"
find "$SRC/TMessagesProj_AppStandalone/build/outputs/apk" -name "*.apk" -print
