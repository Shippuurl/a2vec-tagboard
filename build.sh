#!/usr/bin/env bash
# 手工构建 TagBoard APK（不用 Gradle）。
# 依赖：~/android-sdk 下的 build-tools/34.0.0 + platforms/android-34/android.jar，以及 JDK 21。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$PLATFORM"; do
  [ -e "$f" ] || { echo "缺少 $f" >&2; exit 1; }
done

OUT="$HERE/build"
rm -rf "$OUT"
mkdir -p "$OUT/compiled_res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] 编译资源"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/compiled_res/res.zip"

echo "[2/6] 链接资源 + 生成 R.java"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$HERE/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code 2 --version-name 1.1 \
  "$OUT/compiled_res/res.zip"

echo "[3/6] 编译 java"
find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" --release 8 -encoding UTF-8 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" @"$OUT/sources.txt"
[ -f "$OUT/classes/com/a2vec/tagboard/MainActivity.class" ] || { echo "javac 失败" >&2; exit 1; }

echo "[4/6] dex"
"$BT/d8" --release --min-api 24 --lib "$PLATFORM" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[5/6] 打包"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && "$BT/aapt2" add "$OUT/unsigned.apk" classes.dex >/dev/null 2>&1 ) \
  || ( cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex )

KS="$HERE/keystore/debug.keystore"
if [ ! -f "$KS" ]; then
  echo "      生成签名密钥"
  mkdir -p "$(dirname "$KS")"
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KS" \
    -alias tagboard -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=a2vec TagBoard, OU=calib, O=a2vec, L=, S=, C=CN" >/dev/null 2>&1
fi

echo "[6/6] zipalign + 签名"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias tagboard \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/TagBoard.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/TagBoard.apk" | head -3

ls -la "$OUT/TagBoard.apk"
echo "OK -> $OUT/TagBoard.apk"
