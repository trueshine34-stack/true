#!/usr/bin/env bash
# Собирает APK приложения Sos.
#
# Приложение — WebView-обёртка: вся логика лежит в index.html, а скомпилированные
# артефакты (AndroidManifest.xml, classes.dex, resources.arsc, иконки) берутся из
# исходного APK без изменений. Поэтому сборка = подмена assets/index.html
# + выравнивание + подпись. Android Gradle/SDK для этого не нужен.
#
# Требуется: Android build-tools (apksigner, zipalign), keytool, python3.
#
#   ./build-apk.sh <исходный.apk> [выходной.apk]

set -euo pipefail

BASE_APK="${1:?укажите исходный APK (донор манифеста/dex/ресурсов)}"
OUT_APK="${2:-Sos-v1.1-auth.apk}"
HTML="$(dirname "$0")/index.html"
BT="${ANDROID_BUILD_TOOLS:?установите ANDROID_BUILD_TOOLS=/path/to/build-tools/34.0.0}"

KS="${KEYSTORE:-sos.jks}"
KS_PASS="${KEYSTORE_PASS:-sospass}"
KS_ALIAS="${KEYSTORE_ALIAS:-sos}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# 1. пересобрать zip: всё из донора, index.html — новый.
#    resources.arsc и PNG обязаны лежать без сжатия (targetSdk >= 30).
python3 - "$BASE_APK" "$HTML" "$work/unsigned.apk" <<'PY'
import sys, zipfile
base, html_path, out_path = sys.argv[1:4]
STORED = {'resources.arsc'}
src = zipfile.ZipFile(base)
new_html = open(html_path, 'rb').read()
out = zipfile.ZipFile(out_path, 'w')
for zi_src in src.infolist():
    name = zi_src.filename
    if name.startswith('META-INF/'):        # старая подпись отбрасывается
        continue
    data = new_html if name == 'assets/index.html' else src.read(name)
    zi = zipfile.ZipInfo(name, date_time=(2026, 8, 15, 8, 0, 0))
    stored = name in STORED or name.endswith('.png')
    zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    out.writestr(zi, data)
out.close(); src.close()
PY

# 2. выравнивание
"$BT/zipalign" -p -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$BT/zipalign" -c 4 "$work/aligned.apk"

# 3. ключ (создаётся один раз; храните его — иначе обновления не установятся поверх)
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Sos App, O=Sos, C=RU"
fi

# 4. подпись. targetSdk 34 => схема v2 обязательна, только v1 не установится.
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --min-sdk-version 21 --max-sdk-version 34 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT_APK" "$work/aligned.apk"

"$BT/apksigner" verify --min-sdk-version 21 --max-sdk-version 34 -v "$OUT_APK"
echo "готово: $OUT_APK"
