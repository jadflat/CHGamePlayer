# CHGame libretro core. Builds with zig (zig cc) on Windows; make, or run
# the commands by hand:
#
#   make            build/chgame_libretro.dll       (Windows x86_64)
#   make android    build/chgame_libretro_android.so (Android arm64)
#   make harness    build/harness.exe               (test front end)

ZIG     ?= zig
CFLAGS  := -std=c11 -O3 -Wall -Wno-unused-parameter -Iemu/src -Ilibretro -DSDCARD_LABEL=\"CHGAME\"
EMU_SRC := emu/src/rv32.c emu/src/bus.c emu/src/machine.c emu/src/st7735.c emu/src/audio.c \
           emu/src/piezo_filter.c emu/src/loader.c emu/src/sdspi.c emu/src/sdcard.c \
           emu/src/bootloader_image.c
SRC     := libretro/libretro.c $(EMU_SRC)

all: build/chgame_libretro.dll

build/chgame_libretro.dll: $(SRC)
	@mkdir -p build
	$(ZIG) cc -target x86_64-windows-gnu -shared $(CFLAGS) -o $@ $(SRC)

build/harness.exe: test/harness.c
	@mkdir -p build
	$(ZIG) cc -target x86_64-windows-gnu -O2 -Ilibretro -o $@ test/harness.c

harness: build/harness.exe

.PHONY: all harness android
