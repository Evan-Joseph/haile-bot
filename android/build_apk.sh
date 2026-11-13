#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
LATEST_BT="$(ls -1 "$SDK_DIR/build-tools" 2>/dev/null | sort -V | tail -n 1)"
LATEST_PLAT="$(ls -1 "$SDK_DIR/platforms" 2>/dev/null | sort -V | tail -n 1)"
BUILD_TOOLS="$SDK_DIR/build-tools/${LATEST_BT:-35.0.0}"
ANDROID_JAR="$SDK_DIR/platforms/${LATEST_PLAT:-android-35}/android.jar"
if [ -z "${JAVA_HOME:-}" ] && [ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]; then
  JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$BUILD_TOOLS:$PATH"
else
  export PATH="$BUILD_TOOLS:$PATH"
fi

WORK_DIR="$(mktemp -d /tmp/haile-apk-build.XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$ANDROID_DIR/res/mipmap-xxxhdpi"
cp -f "$ROOT_DIR/static/icon-192.png" "$ANDROID_DIR/res/mipmap-xxxhdpi/ic_launcher.png"

mkdir -p "$WORK_DIR/gen" "$WORK_DIR/classes" "$WORK_DIR/dex" "$WORK_DIR/assets/static/css" "$WORK_DIR/assets/static/js"

cat > "$ROOT_DIR/static/ota.json" <<'EOF'
{
  "ok": true,
  "hasUpdate": true,
  "versionCode": 420,
  "versionName": "4.2.0",
  "apkUrl": "https://haile-bot.pages.dev/haile-bot-v4.apk",
  "releaseNotes": "v4.2 通用选楼与常用设备升级：新增交互式地图拖动选点与全国高校/城市检索、GPS 精准坐标转换、自定义点位 ID/机身码解析，以及常用宿舍楼与最近使用设备一键直达",
  "notes": "v4.2 通用选楼与常用设备升级：新增交互式地图拖动选点与全国高校/城市检索、GPS 精准坐标转换、自定义点位 ID/机身码解析，以及常用宿舍楼与最近使用设备一键直达"
}
EOF

cp -f "$ROOT_DIR/static/index.html" "$WORK_DIR/assets/static/index.html"
cp -f "$ROOT_DIR/static/ota.json" "$WORK_DIR/assets/static/ota.json"
cp -f "$ROOT_DIR/static/icon.svg" "$WORK_DIR/assets/static/icon.svg"
cp -f "$ROOT_DIR/static/icon-180.png" "$WORK_DIR/assets/static/icon-180.png"
cp -f "$ROOT_DIR/static/icon-192.png" "$WORK_DIR/assets/static/icon-192.png"
cp -f "$ROOT_DIR/static/icon-512.png" "$WORK_DIR/assets/static/icon-512.png"
cp -f "$ROOT_DIR/static/manifest.json" "$WORK_DIR/assets/static/manifest.json"
cp -f "$ROOT_DIR/static/sw.js" "$WORK_DIR/assets/static/sw.js"
cp -f "$ROOT_DIR/static/css/"*.css "$WORK_DIR/assets/static/css/"
cp -f "$ROOT_DIR/static/js/"*.js "$WORK_DIR/assets/static/js/"

"$BUILD_TOOLS/aapt2" compile --dir "$ANDROID_DIR/res" -o "$WORK_DIR/res.zip"
"$BUILD_TOOLS/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$ANDROID_DIR/AndroidManifest.xml" \
  -A "$WORK_DIR/assets" \
  -o "$WORK_DIR/unsigned-unaligned.apk" \
  --java "$WORK_DIR/gen" \
  "$WORK_DIR/res.zip"

find "$ANDROID_DIR/src" "$WORK_DIR/gen" -name "*.java" > "$WORK_DIR/sources.txt"
  javac \
    --release 11 \
    -encoding UTF-8 \
    -cp "$ANDROID_JAR" \
    -d "$WORK_DIR/classes" \
    @"$WORK_DIR/sources.txt"

find "$WORK_DIR/classes" -name "*.class" > "$WORK_DIR/classes.txt"
"$BUILD_TOOLS/d8" \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$WORK_DIR/dex" \
  @"$WORK_DIR/classes.txt"

(
  cd "$WORK_DIR/dex"
  zip -q "$WORK_DIR/unsigned-unaligned.apk" classes.dex
)

"$BUILD_TOOLS/zipalign" -f -p 4 "$WORK_DIR/unsigned-unaligned.apk" "$WORK_DIR/unsigned.apk"

OUT_APK="$ROOT_DIR/HaileBot-v4.apk"
if [ -f "$HOME/.android/debug.keystore" ]; then
  "$BUILD_TOOLS/apksigner" sign \
    --v4-signing-enabled false \
    --ks "$HOME/.android/debug.keystore" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUT_APK" \
    "$WORK_DIR/unsigned.apk"
else
  KEYSTORE="$WORK_DIR/release-signing.jks"
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -alias hailebot \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass hailebot123 \
    -keypass hailebot123 \
    -dname "CN=HaileBot, OU=OpenSource, O=HaileBot, L=Qingdao, ST=Shandong, C=CN" >/dev/null 2>&1
  "$BUILD_TOOLS/apksigner" sign \
    --v4-signing-enabled false \
    --ks "$KEYSTORE" \
    --ks-key-alias hailebot \
    --ks-pass pass:hailebot123 \
    --key-pass pass:hailebot123 \
    --out "$OUT_APK" \
    "$WORK_DIR/unsigned.apk"
fi

cp -f "$OUT_APK" "$ROOT_DIR/static/haile-bot-v4.apk"
echo "Built and signed APK: $OUT_APK ($(wc -c < "$OUT_APK" | tr -d ' ') bytes)"
