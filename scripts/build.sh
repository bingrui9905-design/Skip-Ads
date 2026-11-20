#!/usr/bin/env bash
# 不依赖 Gradle 的最小构建脚本。需要：Android SDK（build-tools 34、platforms/android-34）、JDK 17。
# 用法：
#   export ANDROID_HOME=~/Library/Android/sdk     # 或你的 SDK 路径
#   KEYSTORE=path/to/your.jks KS_PASS=xxx KEY_ALIAS=xxx ./scripts/build.sh
# 不传 KEYSTORE 时会自动生成一个本地调试签名（每次不同，不能覆盖安装别人发的版本）。
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANDROID_HOME:?请先设置 ANDROID_HOME}"
BT="$ANDROID_HOME/build-tools/${BUILD_TOOLS:-34.0.0}"
AJ="$ANDROID_HOME/platforms/android-34/android.jar"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/cls" "$OUT/dex"

"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$AJ" --manifest app/AndroidManifest.xml \
  -o "$OUT/base.apk" --java "$OUT/gen" "$OUT/res.zip"

javac -Xlint:-options -source 8 -target 8 \
  -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" \
  -d "$OUT/cls" $(find app/src "$OUT/gen" -name '*.java')

"$BT/d8" --release --min-api 26 --lib "$AJ" --output "$OUT/dex" $(find "$OUT/cls" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ -z "${KEYSTORE:-}" ]; then
  KEYSTORE="$OUT/debug.jks"; KS_PASS=android; KEY_ALIAS=debug
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname CN=debug >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out "$OUT/skip-ads.apk" "$OUT/aligned.apk"
echo "完成：$OUT/skip-ads.apk"
