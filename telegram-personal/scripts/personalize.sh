#!/usr/bin/env bash
#
# Gives the build its own identity instead of inheriting Telegram's.
#
# Out of the box a source build of Telegram carries Telegram's own application id
# (org.telegram.messenger.web, which is what telegram.org hands out for direct download) and is
# signed with the sample keystore committed to the upstream repository. Two consequences:
#
#   * Android refuses to install it next to an existing Telegram installed from telegram.org -
#     same application id, different signature, "App not installed" with no further explanation.
#   * The certificate claims O=Telegram, which is not something to publish under.
#
# This script rewrites the application id, generates a private keystore, renames the app so both
# icons are distinguishable on the launcher, and restricts packaging to the one ABI that actually
# carries the native library.
#
# Set TG_API_ID and TG_API_HASH to your own credentials from my.telegram.org before building.
# Without them the build keeps Telegram's public api_id 4, which their servers refuse to sign in
# with from a third-party build - the number screen simply never advances.
#
# Usage: TG_API_ID=... TG_API_HASH=... personalize.sh <checkout> [application-id] [app-name] [abi]

set -euo pipefail

SRC="${1:?usage: personalize.sh <telegram-checkout> [application-id] [app-name] [abi]}"
APP_ID="${2:-io.personal.tgclient}"
APP_NAME="${3:-Telegram Personal}"
ABI="${4:-arm64-v8a}"
TG_API_ID="${TG_API_ID:-}"
TG_API_HASH="${TG_API_HASH:-}"

STANDALONE="$SRC/TMessagesProj_AppStandalone"
KEYSTORE="$SRC/TMessagesProj/config/release.keystore"

echo "==> application id: $APP_ID"
sed -i "s|^APP_PACKAGE=.*|APP_PACKAGE=$APP_ID|" "$SRC/gradle.properties"

# Both build types append ".web" to match Telegram's direct-download id. Ours is already unique.
echo "==> dropping the .web suffix"
sed -i 's|applicationIdSuffix "\.web"|applicationIdSuffix ""|g' "$STANDALONE/build.gradle"

# The google-services plugin aborts the build unless a client in the json matches the id it is
# building. Point the existing standalone client at the new one.
echo "==> retargeting google-services.json"
python3 - "$STANDALONE/google-services.json" "$APP_ID" <<'PY'
import json, sys

path, app_id = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    config = json.load(handle)
for client in config["client"]:
    info = client["client_info"]["android_client_info"]
    if info["package_name"].endswith(".web"):
        info["package_name"] = app_id
        break
else:
    raise SystemExit("no .web client to retarget in %s" % path)
with open(path, "w", encoding="utf-8") as handle:
    json.dump(config, handle, indent=2)
PY

echo "==> app name: $APP_NAME"
sed -i "s|<string name=\"AppName\">[^<]*</string>|<string name=\"AppName\">$APP_NAME</string>|" \
    "$SRC/TMessagesProj/src/main/res/values/strings.xml"

# Only arm64-v8a gets libtmessages built here, but unrelated AAR dependencies drop stub libraries
# into the other three folders, which makes the APK advertise ABIs it cannot actually run on.
echo "==> restricting packaging to $ABI"
sed -i "s|abiFilters \"armeabi-v7a\", \"arm64-v8a\", \"x86\", \"x86_64\"|abiFilters \"$ABI\"|" \
    "$STANDALONE/build.gradle"

# A release build loads native libraries straight out of the APK instead of unpacking them, so on
# an Android 15/16 device with 16 KB memory pages the library has to be built for that page size or
# it will not load. Upstream ships this flag commented out.
echo "==> enabling 16 KB page size support"
sed -i "s|'-DANDROID_PLATFORM=android-21' //, '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON'|'-DANDROID_PLATFORM=android-21', '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON'|" \
    "$SRC/TMessagesProj/build.gradle"

if [ -n "$TG_API_ID" ] && [ -n "$TG_API_HASH" ]; then
    echo "==> using api_id $TG_API_ID"
    BUILDVARS="$SRC/TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java"
    sed -i "s|public static int APP_ID = .*;|public static int APP_ID = $TG_API_ID;|" "$BUILDVARS"
    sed -i "s|public static String APP_HASH = \".*\";|public static String APP_HASH = \"$TG_API_HASH\";|" "$BUILDVARS"
else
    echo "!! TG_API_ID / TG_API_HASH not set - keeping Telegram's public api_id 4."
    echo "!! Sign-in will be refused with API_ID_PUBLISHED_FLOOD. Get your own at my.telegram.org."
fi

echo "==> generating a private signing key"
rm -f "$KEYSTORE"
keytool -genkeypair -noprompt \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias androidkey -storepass android -keypass android \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=$APP_NAME, OU=Personal Build, O=Personal Build, C=US" >/dev/null

echo "done - now build the standalone variant:"
echo "    ./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone"
echo "(the debug variant is marked debuggable, which some vendor ROMs refuse to sideload)"
