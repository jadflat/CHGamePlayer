#!/bin/sh
# Builds the core with zig cc (no make needed). ./build.sh [windows|harness|android|all]
set -e
cd "$(dirname "$0")"
ZIG=${ZIG:-zig}
CFLAGS="-std=c11 -O3 -Wall -Wno-unused-parameter -Iemu/src -Ilibretro -DSDCARD_LABEL='\"CHGAME\"'"
SRC="libretro/libretro.c emu/src/rv32.c emu/src/bus.c emu/src/machine.c emu/src/st7735.c emu/src/audio.c
     emu/src/piezo_filter.c emu/src/loader.c emu/src/sdspi.c emu/src/sdcard.c emu/src/bootloader_image.c"
mkdir -p build
what=${1:-windows}
if [ "$what" = windows ] || [ "$what" = all ]; then
    eval $ZIG cc -target x86_64-windows-gnu -shared $CFLAGS -o build/chgame_libretro.dll $SRC
    echo "build/chgame_libretro.dll"
fi
if [ "$what" = harness ] || [ "$what" = all ]; then
    $ZIG cc -target x86_64-windows-gnu -O2 -Ilibretro -o build/harness.exe test/harness.c
    echo "build/harness.exe"
fi
if [ "$what" = android ] || [ "$what" = all ]; then
    # zig ships no bionic, so: musl's headers, no libc linked, and empty
    # libc/libm/libdl stubs for DT_NEEDED; Android's linker resolves the rest
    A=build/android
    mkdir -p $A/stubs
    echo 'void __stub_unused(void) {}' > $A/stub.c
    for l in c m dl; do
        $ZIG cc -target aarch64-linux-musl -nostdlib -shared -fPIC $A/stub.c -Wl,-soname,lib$l.so -o $A/stubs/lib$l.so
    done
    OBJS=""
    for s in $SRC libretro/android_shim.c; do
        o=$A/$(basename "${s%.c}").o
        $ZIG cc -target aarch64-linux-musl -std=c11 -fPIC -O3 -fvisibility=hidden -ffunction-sections -fdata-sections \
            -fno-stack-protector -D_GNU_SOURCE -Wall -Wno-unused-parameter -Iemu/src -Ilibretro \
            '-DSDCARD_LABEL="CHGAME"' -c $s -o $o
        OBJS="$OBJS $o"
    done
    $ZIG cc -target aarch64-linux-musl -nodefaultlibs -shared -O3 -s $OBJS -o build/chgame_libretro_android.so \
        -Wl,--no-as-needed $A/stubs/libc.so $A/stubs/libm.so $A/stubs/libdl.so \
        -Wl,-soname,chgame_libretro_android.so -Wl,--hash-style=both -Wl,-z,max-page-size=16384 \
        -Wl,-z,now -Wl,-z,relro -Wl,-z,noexecstack -Wl,--gc-sections -Wl,--build-id=sha1
    echo "build/chgame_libretro_android.so"
fi
