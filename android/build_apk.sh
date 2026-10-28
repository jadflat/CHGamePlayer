#!/bin/sh
# Builds the CHGame Player APK (arm64) without Gradle: zig for the native
# library, then aapt2, javac, d8, zipalign and apksigner from the Android SDK.
#
#   android/build_apk.sh            -> build/CHGamePlayer-<version>.apk
#
# Tools: $ANDROID_TOOLS (default ~/tools/android) holds jdk-21*, sdk/ and keys/.
# The signing key lives outside the project (KEYS below), made on first use
# with a random password kept beside it. Never commit or share either file.
set -e
cd "$(dirname "$0")/.."
# Windows tools want C:/ paths, not Git Bash /c/ ones
HOMEDIR=$(cygpath -m "$HOME" 2>/dev/null || echo "$HOME")
TOOLS=${ANDROID_TOOLS:-$HOMEDIR/tools/android}
JAVA_HOME=${JAVA_HOME:-$(ls -d "$TOOLS"/jdk-21* | head -1)}
export JAVA_HOME
SDK=$TOOLS/sdk
BT=$SDK/build-tools/37.0.0
JAR=$SDK/platforms/android-36/android.jar
KEYS=$TOOLS/keys
ZIG=${ZIG:-zig}
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' android/AndroidManifest.xml)
OUT=build/apk
rm -rf $OUT
mkdir -p $OUT/obj $OUT/stubs $OUT/res $OUT/classes $OUT/dex $OUT/lib/arm64-v8a

# 1. libchgame.so: the core + the app's front end, for bionic via musl's headers
#    (see build.sh: empty libc/libm/libdl stubs, errno shim)
echo 'void __stub_unused(void) {}' > $OUT/stub.c
for l in c m dl; do
    $ZIG cc -target aarch64-linux-musl -nostdlib -shared -fPIC $OUT/stub.c -Wl,-soname,lib$l.so -o $OUT/stubs/lib$l.so
done
SRC="libretro/libretro.c emu/src/rv32.c emu/src/bus.c emu/src/machine.c emu/src/st7735.c emu/src/audio.c
     emu/src/piezo_filter.c emu/src/loader.c emu/src/sdspi.c emu/src/sdcard.c emu/src/bootloader_image.c emu/src/gif.c
     libretro/android_shim.c android/jni/frontend.c android/jni/jni.c"
OBJS=""
for s in $SRC; do
    o=$OUT/obj/$(basename "${s%.c}").o
    $ZIG cc -target aarch64-linux-musl -std=c11 -fPIC -O3 -fvisibility=hidden -ffunction-sections -fdata-sections \
        -fno-stack-protector -D_GNU_SOURCE -Wall -Wno-unused-parameter -Iemu/src -Ilibretro -Iandroid/jni \
        -Iandroid/jni/include -I"$JAVA_HOME/include" '-DSDCARD_LABEL="CHGAME"' -c $s -o $o
    OBJS="$OBJS $o"
done
$ZIG cc -target aarch64-linux-musl -nodefaultlibs -shared -O3 -s $OBJS -o $OUT/lib/arm64-v8a/libchgame.so \
    -Wl,--no-as-needed $OUT/stubs/libc.so $OUT/stubs/libm.so $OUT/stubs/libdl.so \
    -Wl,-soname,libchgame.so -Wl,--hash-style=both -Wl,-z,max-page-size=16384 \
    -Wl,-z,now -Wl,-z,relro -Wl,-z,noexecstack -Wl,--gc-sections -Wl,--build-id=sha1

# 2. resources and manifest
"$BT/aapt2.exe" compile --dir android/res -o $OUT/res.zip
"$BT/aapt2.exe" link -I "$JAR" --manifest android/AndroidManifest.xml -o $OUT/base.apk $OUT/res.zip -A android/assets \
    --min-sdk-version 26 --target-sdk-version 36 --version-name "$VERSION"

# 3. Java -> dex
"$JAVA_HOME/bin/javac" -nowarn -Xlint:-options -source 11 -target 11 -classpath "$JAR" -d $OUT/classes \
    $(find android/java -name '*.java')
"$BT/d8.bat" --release --min-api 26 --lib "$JAR" --output $OUT/dex $(find $OUT/classes -name '*.class')

# 4. dex and the library into the APK (the library stored, so it maps straight from the APK)
python -I android/add_to_apk.py $OUT/base.apk $OUT/unaligned.apk \
    classes.dex=$OUT/dex/classes.dex lib/arm64-v8a/libchgame.so=$OUT/lib/arm64-v8a/libchgame.so
"$BT/zipalign.exe" -f -P 16 4 $OUT/unaligned.apk $OUT/aligned.apk

# 5. sign with the private key
mkdir -p "$KEYS"
if [ ! -f "$KEYS/chgame-player.jks" ]; then
    python -I -c "import secrets; print(secrets.token_urlsafe(24))" > "$KEYS/chgame-player.pass"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYS/chgame-player.jks" -alias chgame -keyalg RSA -keysize 4096 \
        -validity 10000 -dname "CN=CHGame Player" -storepass:file "$KEYS/chgame-player.pass" -keypass:file "$KEYS/chgame-player.pass"
fi
APK=build/CHGamePlayer-$VERSION.apk
"$BT/apksigner.bat" sign --ks "$KEYS/chgame-player.jks" --ks-key-alias chgame \
    --ks-pass file:"$KEYS/chgame-player.pass" \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out $APK $OUT/aligned.apk
"$BT/apksigner.bat" verify $APK
echo "$APK"
