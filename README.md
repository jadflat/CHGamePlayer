# CHGame libretro core

**Step-by-step setup for the Retroid (app, RetroArch, ES-DE): [docs/GUIDE.md](docs/GUIDE.md).**

Version 0.2. Plays CHGame handheld games (`.CHG`) in RetroArch on Windows and Android, including
the Retroid Pocket. It is a thin libretro front end, `libretro/libretro.c`, around
[joyrider3774's chgame_emulator](https://github.com/joyrider3774/chgame_emulator). That emulator
models the whole board at the cycle level: the CH32X035 RISC-V CPU, interrupts, timers, SPI and
DMA, the ST7735 screen, the buzzer, the microSD card, and the real bootloader. Its code is in
`emu/` unchanged; the commit is recorded in `EMULATOR_VERSION.txt`.

## What the core does

| | |
|---|---|
| Content | `.chg` (also `.bin`, `.hex`, `.elf`), started through the emulator's built-in bootloader as on the device |
| Video | 128 x 128, XRGB8888, aspect 1:1, 60 frames a second; option *Internal upscale* sends it at 2x to 6x (256 to 768 pixels), nearest neighbour, for shaders that want real pixels. For plain sharp pixels, RetroArch's own *Integer Scale* does the same at 1x |
| Timing | 800,000 CPU cycles (48 MHz / 60) each frame |
| Audio | the buzzer pin at 48 kHz, as int16 stereo; options for the bare pin signal (the emulator's default) or a piezo-like filter, and the volume |
| Saves | the flash pages a game writes, as `<game>.sav` (the emulator's own format) in RetroArch's save folder. They are written one second after the game stops writing, and again on exit |
| Save states | yes, about 1 MB each. A state holds the whole machine but not the SD card's files |
| SD card | the folder `<system>/chgame/sdcard/`, shared by every game, the way one card is shared on the device |

### Buttons

| CHGame | RetroPad (position) |
|---|---|
| D-pad | D-pad or left stick |
| A | A (right face button) |
| B | B (bottom face button) |
| START | Start |
| SELECT | Select |

This is the same layout as the emulator's own gamepad mapping. Labels on a handheld's buttons
differ (Nintendo or Xbox style); RetroArch maps by position. The core starts single games, not
the CHGame SD menu, so leave a game through RetroArch's menu.

### The SD card folder

Games that keep data on the card (STARLIFT needs `STARLIFT.DAT`; Another World, Dank Tomb and
others have their own `.DAT`) read it from:

    <RetroArch system/BIOS folder>/chgame/sdcard/

The core makes this folder the first time it runs. Copy the `.DAT` files into it. The folder
becomes a FAT32 card that the emulator builds in memory, and anything a game writes is written
back to the folder every 5 seconds and on exit. The option *microSD card: off* runs with no card.

## Installing on a Retroid Pocket (Android)

Nothing below has been tried on a device yet. The steps follow RetroArch's and ES-DE's
documentation and RetroArch's source; check the folder names under *Settings > Directory*.

You need an arm64 Retroid: the RP2+ or anything newer. The original RP2 is 32-bit.

1. **RetroArch from the website, not the Play Store.** retroarch.com marks the Play Store
   listings as outdated and recommends the APK from its own site. Get `RetroArch_aarch64.apk`
   (the 64-bit build, package `com.retroarch.aarch64`) from
   <https://www.retroarch.com/?page=platforms>. 1.22.2 was current on 2026-10-28. *RetroArch
   Plus* from the Play Store uses the same package name, so uninstall it first. Start
   RetroArch once and allow storage access.
2. **The info file.** RetroArch's own info folder is inside the app and needs root, so point it
   somewhere you can write:
   - *Settings > Directory > Core Info*: choose `/storage/emulated/0/RetroArch/info`
     (make the folder first);
   - copy `chgame_libretro.info` into it;
   - *Main Menu > Online Updater > Update Core Info Files*, so the other cores get their
     info files back in the new folder.
3. **The core.** Copy `chgame_libretro_android.so` (keep that exact name) into
   `/storage/emulated/0/RetroArch/downloads/`. Then go to *Main Menu > Load Core > Install or
   Restore a Core* and pick it. RetroArch copies it to
   `/data/data/com.retroarch.aarch64/cores/`. It copies only the `.so`, not the info file.
4. **The SD card folder.** Copy the `.DAT` files your games need, such as `STARLIFT.DAT`, into
   `/storage/emulated/0/RetroArch/system/chgame/sdcard/`. If you don't make the folder, the
   core makes it the first time a game starts.
5. **Games.** Put the `.CHG` files anywhere, for example `/storage/emulated/0/ROMs/chgame/`.
   *Load Core > CHGame*, then *Load Content*. Saves go to
   `/storage/emulated/0/RetroArch/saves/` as `<game>.sav`.

### ES-DE and Daijisho

ES-DE can start CHGame games with either RetroArch or the CHGame Player app: `frontends/es_systems.xml` and `frontends/es_find_rules.xml`, set up step by step in [docs/GUIDE.md](docs/GUIDE.md#c-es-de-full-setup). `frontends/CHGame.daijisho.json` is a Daijisho platform for RetroArch, in the format of Daijisho's own platform files.

## CHGame Player: the standalone Android app

Version 0.20, `build/CHGamePlayer-0.20.apk` (arm64), for private use. It links in the same core
RetroArch uses, so games play the same in both. **It has not been installed on any device yet.**

**Using it**
1. Copy the APK to the Retroid and open it. Android asks you to allow installs from the file
   manager the first time.
2. The first time, press *Choose folder* and pick the folder that holds your `.CHG` games and any `.DAT`
   files. A copy of your SD card works too, since subfolders are searched 4 levels deep.
3. Pick a game with the d-pad and press A.
4. In a game, BACK, the pad's home button, or a tap on the screen opens a menu: resume, save
   state, load state, or quit.

**Buttons:** d-pad or left stick; CHGame A is the right face button and B the bottom one.
*Options → Map buttons* remaps any of them. START and SELECT are as labelled.

**The SD card:** before a game starts, the app copies every file in the folder except the games
into its own private SD card folder, skipping files that haven't changed. After play, it copies
back the files the game changed or made. It never deletes anything in your folder.

**Saves:** the game's own saves (`<game>.sav`) and the save states stay in the app's private
storage. Uninstalling the app deletes them.

**Security**
- **No permissions at all:** no internet, and no storage permission. The app sees only the one
  folder you pick, through Android's folder picker. Picking a new folder gives up access to
  the old one.
- **Nothing leaves the device:** cloud backup and device-to-device transfer are both turned off.
- **Only the list screen can be opened by other apps;** the game screen is private.
- **File names from the folder are checked** before use, so they can't point outside the
  app's own folders. Files over 256 MB are skipped.
- **Signed with your own key:** a 4096-bit key in `~/tools/android/keys/`
  (`chgame-player.jks`, with its password in `chgame-player.pass`). It lives outside the
  project; keep it private and don't commit it. Later versions must be signed with the same
  key to update the installed app.

**Building:** `android/build_apk.sh` builds the APK without Gradle. It needs the JDK 21 and
the Android SDK (build-tools 37.0.0, platform 36) in `~/tools/android` (or the folder named by `ANDROID_TOOLS`).
`android/jni/fe_test.c` runs the app's native layer on a PC.

## Speed

Measured on a PC (Intel i5-11320H, one core, Windows build), over 60 seconds of gameplay:

| Game | Speed | Time a frame |
|---|---|---|
| STARLIFT | 6.0x real time | 2.8 ms of the 16.7 ms frame |
| CHix | 7.3x real time | 2.3 ms |

The upscale option adds up to about 0.3 ms a frame (6x). On a Retroid Pocket Nova (Snapdragon 8 Gen 2 class) a running game uses about half of one
CPU core.

## Building

Windows, with `zig` on the PATH (no make needed; `Makefile` holds the same Windows recipe):

    ./build.sh windows    # build/chgame_libretro.dll
    ./build.sh android    # build/chgame_libretro_android.so (Android arm64)
    ./build.sh harness    # build/harness.exe, a test front end

zig has no Android C library (bionic). The Android build therefore compiles against zig's musl
headers. It links no C library of its own: it records `libc.so`, `libm.so` and `libdl.so` as
dependencies through empty stub libraries, and Android's linker supplies the real functions. A
two-line shim, `libretro/android_shim.c`, maps musl's `__errno_location` to bionic's `__errno`.
`tools/elfcheck.py` checks the result: AArch64, the three dependencies, no glibc symbols, and
only `retro_*` exported. Android 6 (API 23) or newer is needed.

Testing without RetroArch:

    build/harness.exe build/chgame_libretro.dll game.CHG 10 outdir --sys sysdir --save savedir \
        --press start@3 --press a@5:0.5 --state 6

This writes `outdir/frame_NNN.ppm` once a second and `outdir/sound.wav`, and prints the speed.
`--state` saves a state, runs on, loads it back and checks that the frames match.

## Windows (RetroArch on a PC)

Copy `build/chgame_libretro.dll` into RetroArch's `cores` folder and `chgame_libretro.info` into its
`info` folder. The SD card folder is `<RetroArch>/system/chgame/sdcard/`.

## Credits and licences

- **CHGame Emulator** by joyrider3774, MIT licence (`emu/LICENSE`). All of the emulation is theirs.
- **CHGame** by Kevin Bates (bateske), https://chgame.website, Apache-2.0. The bootloader embedded in the emulator is MIT.
- **Press Start 2P** by CodeMan38, SIL Open Font License 1.1 (`android/assets/fonts/OFL.txt`).
- `libretro/libretro.h` is from libretro-common (the RetroArch team, MIT-style licence in the file).
- The libretro wrapper, harness and scripts here: jadflat.
