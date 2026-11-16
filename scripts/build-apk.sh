#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to Android SDK}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK r27d or newer}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}"
export PATH="$JAVA_HOME/bin:$PATH"
bt="$ANDROID_HOME/build-tools/35.0.0"
jar="$ANDROID_HOME/platforms/android-35/android.jar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang"
export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
cargo test --locked --manifest-path telemetry/Cargo.toml
cargo build --locked --release --target aarch64-linux-android --manifest-path telemetry/Cargo.toml
mkdir -p build/manual/{classes,lib/arm64-v8a} downloads
cp telemetry/target/aarch64-linux-android/release/libpitwall_telemetry.so build/manual/lib/arm64-v8a/
python3 - <<'PY'
from pathlib import Path
s=Path('app/src/main/AndroidManifest.xml').read_text().replace('<manifest ', '<manifest package="com.torquescope" android:versionCode="3" android:versionName="0.3" ',1).replace('<uses-permission','<uses-sdk android:minSdkVersion="23" android:targetSdkVersion="35"/><uses-permission',1)
Path('build/manual/AndroidManifest.xml').write_text(s)
PY
"$bt/aapt" package -f -M build/manual/AndroidManifest.xml -I "$jar" -F build/manual/unsigned.apk
javac --release 8 -cp "$jar" -d build/manual/classes app/src/main/java/com/torquescope/*.java
"$bt/d8" --min-api 23 --lib "$jar" --output build/manual build/manual/classes/com/torquescope/*.class
(cd build/manual && zip -q unsigned.apk classes.dex lib/arm64-v8a/libpitwall_telemetry.so)
"$bt/zipalign" -f -P 16 4 build/manual/unsigned.apk build/manual/aligned.apk
# Development key stays outside tracked files; pass a prior key to preserve upgrades.
key="${PITWALL_KEYSTORE:-build/manual/debug.keystore}"
if [ ! -f "$key" ]; then
 keytool -genkeypair -keystore "$key" -storepass android -keypass android -alias prototype -dname 'CN=Prototype' -keyalg RSA -keysize 2048 -validity 10000
fi
"$bt/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android --out downloads/pitwall-prototype.apk build/manual/aligned.apk
"$bt/apksigner" verify --verbose downloads/pitwall-prototype.apk
"$bt/zipalign" -c -P 16 4 downloads/pitwall-prototype.apk
