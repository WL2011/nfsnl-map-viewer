#!/bin/bash
# Build NFSNLMinimap.apk with the plain SDK tools (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

python3 ../tools/export_minimap.py assets/maps.json

"$BT/aapt2" compile --dir res -o "$OUT/res"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 -A assets --java "$OUT/gen" "$OUT"/res/*.flat

javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -classpath "$BT/core-lambda-stubs.jar" -encoding UTF-8 -Xlint:-options \
  -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
D8="$(command -v d8 || echo "$BT/d8")"
"$D8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS=minimap.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass nfsnlminimap -keypass nfsnlminimap \
  -alias minimap -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NFSNL Minimap" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:nfsnlminimap --ks-key-alias minimap \
  --out NFSNLMinimap.apk "$OUT/aligned.apk"
echo "built $(pwd)/NFSNLMinimap.apk ($(du -h NFSNLMinimap.apk | cut -f1))"
