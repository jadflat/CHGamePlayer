# CHGame Player

An Android app for playing CHGame handheld games (`.CHG` files) on Android handhelds such as the
Retroid Pocket, and phones. It runs joyrider3774's cycle-accurate CHGame Emulator, so games
play as they do on the real board.

- A game list with each game's cartridge picture, credits and version, in sections:
  favourites, last played, CHGame originals and ports.
- A live preview of the selected game, with its sound.
- Save states (4 slots a game), resume where you left off, the games' own saves with export
  and import, fast-forward, screenshots, button mapping, per-game sound settings.
- ES-DE support (start games from ES-DE, quit back with M2 + START) and an ES-DE media maker:
  a video, a screenshot and a cover for every game, made on the device.
- No permissions: the app sees only the folders you choose.

No games are included: put your `.CHG` files (and any `.DAT` files they use) in a folder and
choose it in the app.

An illustrated how-to with screenshots: [docs/HOW-TO.md](docs/HOW-TO.md).

**Download:** the APK is on the [releases page](../../releases). Setup, ES-DE and every option:
[docs/GUIDE.md](docs/GUIDE.md).

The same emulator as a RetroArch core: [CHGame-libretro](https://github.com/jadflat/CHGame-libretro).

## Building

On Windows with `zig`, a JDK 21 and the Android SDK (build-tools 37.0.0, platform 36) in
`~/tools/android` (or the folder in `ANDROID_TOOLS`):

    ./android/build_apk.sh          # build/CHGamePlayer-<version>.apk

The native part is the emulator and the libretro wrapper (`emu/`, `libretro/`) compiled for
arm64 with zig against musl's headers, linked to Android's own C library at run time
(`libretro/android_shim.c`). `android/jni/fe_*.c` run the app's native layer on a PC.

## Credits and licences

- **CHGame Emulator** by joyrider3774, MIT licence (`emu/LICENSE`): all of the emulation, unchanged
  at the commit in `EMULATOR_VERSION.txt`.
- **CHGame** by Kevin Bates (bateske), https://chgame.website, Apache-2.0. The bootloader embedded
  in the emulator is MIT.
- **Press Start 2P** by CodeMan38, SIL Open Font License 1.1 (`android/assets/fonts/OFL.txt`).
- `libretro/libretro.h` from libretro-common, by the RetroArch team.
- The app: jadflat, MIT licence (`LICENSE`). See `NOTICE`.
