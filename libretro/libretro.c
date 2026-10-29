/*
 * CHGame libretro core: a thin RetroArch front end over joyrider3774's
 * chgame_emulator (emu/, MIT). Everything that emulates the handheld is the
 * emulator's own code, unchanged; this file only does what emu/src/main.c
 * does with SDL: input, video, audio, the save file and the SD card.
 *
 *   video   128x128 XRGB8888 (or 2x-6x, nearest neighbour), 60 frames a second, aspect 1
 *   audio   the buzzer pin at 48 kHz, mono float -> int16 stereo
 *   frame   48,000,000 / 60 = 800,000 CPU cycles a retro_run
 *   saves   the program's flash pages, as the emulator's .sav, in the save directory
 *   SD      <system>/chgame/sdcard/ (a folder), or an image file set in the options
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <sys/stat.h>
#include <dirent.h>
#ifdef _WIN32
#include <direct.h>
#define make_dir(p) _mkdir(p)
#else
#define make_dir(p) mkdir(p, 0755)
#endif

#include "libretro.h"
#include "machine.h"
#include "loader.h"
#include "audio.h"
#include "sdcard.h"

#define CORE_VERSION    "0.3"
#define AUDIO_RATE      48000
#define FPS             60
#define FRAME_CYCLES    (CHG_HCLK / FPS)
#define FRAME_SAMPLES   (AUDIO_RATE / FPS)
#define MAX_SCALE       6
#define SAVE_STATE_MAGIC_V1 0x43484731u   /* "CHG1": the machine and audio only */
#define SAVE_STATE_MAGIC 0x43484732u      /* "CHG2": and where the card's files sit */

static retro_environment_t        env_cb;
static retro_video_refresh_t      video_cb;
static retro_audio_sample_batch_t audio_batch_cb;
static retro_input_poll_t         input_poll_cb;
static retro_input_state_t        input_state_cb;
static retro_log_printf_t         log_cb;

static ChgMachine m;
static ChgAudio   audio;
static uint32_t   pixels[128 * 128];
static uint32_t   scaled[128 * MAX_SCALE * 128 * MAX_SCALE];
static unsigned   scale = 1;        /* the picture is sent at 128 * scale, nearest neighbour */
static float      samples[AUDIO_RATE];
static int16_t    stereo[AUDIO_RATE * 2];
static bool       loaded;
static bool       use_mask;         /* RETRO_DEVICE_ID_JOYPAD_MASK is supported */
static char       save_path[1100];
static char       sd_path[1100];
static uint64_t   last_sync;        /* m.cycles when the SD card was last written back */

static void core_log(enum retro_log_level level, const char *fmt, ...)
{
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (log_cb) log_cb(level, "[CHGame] %s\n", buf);
    else fprintf(stderr, "[CHGame] %s\n", buf);
}

/* ------------------------------------------------------------------------ */
/* Options                                                                   */
/* ------------------------------------------------------------------------ */

static const struct retro_variable vars[] = {
    { "chgame_sound",  "Sound; bare pin|piezo" },
    { "chgame_volume", "Volume; 50%|60%|70%|80%|90%|100%|120%|150%|10%|20%|30%|40%" },
    { "chgame_sd",     "microSD card; folder|off" },
    { "chgame_scale",  "Internal upscale (nearest); 1x|2x|3x|4x|5x|6x" },
    { NULL, NULL },
};

static void apply_options(void)
{
    struct retro_variable v = { "chgame_sound", NULL };
    if (env_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &v) && v.value)
        audio.piezo = !strcmp(v.value, "piezo");
    v.key = "chgame_volume";
    v.value = NULL;
    if (env_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &v) && v.value)
        audio.volume = (float)atoi(v.value) / 100.0f;
    v.key = "chgame_scale";
    v.value = NULL;
    unsigned want = 1;
    if (env_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &v) && v.value) want = (unsigned)atoi(v.value);
    if (want < 1 || want > MAX_SCALE) want = 1;
    if (want != scale) {
        scale = want;
        struct retro_game_geometry g = { 128 * scale, 128 * scale, 128 * MAX_SCALE, 128 * MAX_SCALE, 1.0f };
        if (loaded) env_cb(RETRO_ENVIRONMENT_SET_GEOMETRY, &g);  /* at load, av_info reports it */
    }
}

static bool sd_enabled(void)
{
    struct retro_variable v = { "chgame_sd", NULL };
    return !(env_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &v) && v.value && !strcmp(v.value, "off"));
}

/* ------------------------------------------------------------------------ */
/* The libretro API                                                          */
/* ------------------------------------------------------------------------ */

RETRO_API void retro_set_environment(retro_environment_t cb)
{
    env_cb = cb;
    bool no_content = false;
    cb(RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME, &no_content);
    cb(RETRO_ENVIRONMENT_SET_VARIABLES, (void *)vars);
    struct retro_log_callback log;
    if (cb(RETRO_ENVIRONMENT_GET_LOG_INTERFACE, &log)) log_cb = log.log;
}

RETRO_API void retro_set_video_refresh(retro_video_refresh_t cb) { video_cb = cb; }
RETRO_API void retro_set_audio_sample(retro_audio_sample_t cb) { (void)cb; }
RETRO_API void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) { audio_batch_cb = cb; }
RETRO_API void retro_set_input_poll(retro_input_poll_t cb) { input_poll_cb = cb; }
RETRO_API void retro_set_input_state(retro_input_state_t cb) { input_state_cb = cb; }
RETRO_API void retro_set_controller_port_device(unsigned port, unsigned device) { (void)port; (void)device; }
RETRO_API unsigned retro_api_version(void) { return RETRO_API_VERSION; }
RETRO_API unsigned retro_get_region(void) { return RETRO_REGION_NTSC; }

RETRO_API void retro_get_system_info(struct retro_system_info *info)
{
    memset(info, 0, sizeof *info);
    info->library_name     = "CHGame";
    info->library_version  = CORE_VERSION;
    info->valid_extensions = "chg|bin|hex|elf";
    info->need_fullpath    = true;      /* the emulator's loader reads the file itself */
    info->block_extract    = false;
}

RETRO_API void retro_get_system_av_info(struct retro_system_av_info *info)
{
    memset(info, 0, sizeof *info);
    info->geometry.base_width   = 128 * scale;
    info->geometry.base_height  = 128 * scale;
    info->geometry.max_width    = 128 * MAX_SCALE;
    info->geometry.max_height   = 128 * MAX_SCALE;
    info->geometry.aspect_ratio = 1.0f;
    info->timing.fps            = FPS;
    info->timing.sample_rate    = AUDIO_RATE;
}

RETRO_API void retro_init(void)
{
    chg_init(&m);
    unsigned level = 4;
    env_cb(RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL, &level);
    use_mask = env_cb(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, NULL);
}

static void write_save(void)
{
    if (loaded && save_path[0] && m.flash_written && chg_write_save(&m, save_path))
        core_log(RETRO_LOG_INFO, "saved %s", save_path);
}

static void sync_sd(void)
{
    if (m.sd) sdcard_sync(m.sd);
    last_sync = m.cycles;
}

RETRO_API void retro_deinit(void)
{
    retro_unload_game();
}

/* <dir>/<name>, with the directory made if it is missing */
static void join_dir(char *out, size_t outlen, const char *dir, const char *name)
{
    snprintf(out, outlen, "%s/%s", dir, name);
    make_dir(out);
}

static const char *base_name(const char *path)
{
    const char *s = strrchr(path, '/');
    const char *b = strrchr(path, '\\');
    if (b > s) s = b;
    return s ? s + 1 : path;
}

/* The .DAT names a program asks for, found the way the CHGame Player app
   finds them: NAME.DAT with NAME of 1-8 of [A-Z0-9_], not part of a longer
   word. A .CHG (CHG1) is searched over its program only, from byte 512 for the
   length at 0x10; any other file over all of its bytes. */
#define DAT_MAX  16
static bool is_word_ch(int c)  { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_'; }
static bool is_alnum_ch(int c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'); }
static bool is_name_ch(int c)  { return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'; }

static int find_dat_names(const uint8_t *d, size_t n, char out[][16])
{
    int count = 0;
    for (size_t i = 0; i < n && count < DAT_MAX; i++) {
        if (i > 0 && is_word_ch(d[i - 1])) continue;
        for (size_t len = 1; len <= 8 && i + len + 4 <= n; len++) {
            if (!is_name_ch(d[i + len - 1])) break;
            if (d[i + len] != '.' || memcmp(d + i + len, ".DAT", 4) != 0) continue;
            if (i + len + 4 < n && is_alnum_ch(d[i + len + 4])) break;
            char name[16];
            memcpy(name, d + i, len);
            memcpy(name + len, ".DAT", 5);
            int dup = 0;
            for (int k = 0; k < count && !dup; k++) dup = !strcmp(out[k], name);
            if (!dup) memcpy(out[count++], name, len + 5);
            break;
        }
    }
    return count;
}

static bool same_name_ci(const char *a, const char *b)
{
    for (; *a && *b; a++, b++) {
        int x = *a, y = *b;
        if (x >= 'a' && x <= 'z') x -= 32;
        if (y >= 'a' && y <= 'z') y -= 32;
        if (x != y) return false;
    }
    return *a == *b;
}

static bool sd_has_file(const char *dir, const char *name)
{
    DIR *d = opendir(dir);
    if (!d) return false;
    bool found = false;
    for (struct dirent *e; !found && (e = readdir(d)) != NULL; )
        found = same_name_ci(e->d_name, name);
    closedir(d);
    return found;
}

/* Tells the player about .DAT files the game needs that are not on the card */
static void check_dat_files(const char *path, const char *card_dir)
{
    FILE *f = fopen(path, "rb");
    if (!f) return;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0 || size > 16 << 20) { fclose(f); return; }
    uint8_t *data = malloc((size_t)size);
    if (!data) { fclose(f); return; }
    size_t n = fread(data, 1, (size_t)size, f);
    fclose(f);

    size_t from = 0, to = n;
    if (n >= 512 && !memcmp(data, "CHG1", 4)) {
        const uint32_t len = data[0x10] | data[0x11] << 8 | data[0x12] << 16 | (uint32_t)data[0x13] << 24;
        from = 512;
        to = (len < n - 512) ? 512 + len : n;
    }

    char names[DAT_MAX][16];
    const int count = find_dat_names(data + from, to - from, names);
    free(data);

    char msg[256];
    size_t used = 0;
    int missing = 0;
    for (int k = 0; k < count; k++) {
        if (sd_has_file(card_dir, names[k])) continue;
        int w = snprintf(msg + used, sizeof msg - used, "%s%s", missing ? ", " : "", names[k]);
        if (w < 0 || (size_t)w >= sizeof msg - used) break;
        used += (size_t)w;
        missing++;
    }
    if (!missing) return;

    static char shown[300];
    snprintf(shown, sizeof shown, "Missing on the SD card: %s (put %s in system/chgame/sdcard)",
             msg, missing > 1 ? "them" : "it");
    core_log(RETRO_LOG_WARN, "%s", shown);
    struct retro_message rm = { shown, 600 };
    env_cb(RETRO_ENVIRONMENT_SET_MESSAGE, &rm);
}

RETRO_API bool retro_load_game(const struct retro_game_info *game)
{
    if (!game || !game->path) return false;

    enum retro_pixel_format fmt = RETRO_PIXEL_FORMAT_XRGB8888;
    if (!env_cb(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &fmt)) {
        core_log(RETRO_LOG_ERROR, "XRGB8888 is not supported");
        return false;
    }

    static const struct retro_input_descriptor desc[] = {
        { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_UP,     "Up" },
        { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_DOWN,   "Down" },
        { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_LEFT,   "Left" },
        { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_RIGHT,  "Right" },
        { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A,      "A" },
        { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B,      "B" },
        { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_SELECT, "Select" },
        { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START,  "Start" },
        { 0 },
    };
    env_cb(RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS, (void *)desc);

    chg_init(&m);
    char err[512];

    /* the microSD card: <system>/chgame/sdcard, one folder for every game, as one card on the device */
    const char *sys = NULL;
    sd_path[0] = 0;
    if (sd_enabled() && env_cb(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY, &sys) && sys) {
        char chg_dir[1024];
        join_dir(chg_dir, sizeof chg_dir, sys, "chgame");
        join_dir(sd_path, sizeof sd_path, chg_dir, "sdcard");
        if (!(m.sd = sdcard_open(sd_path, 0, err, sizeof err)))
            core_log(RETRO_LOG_WARN, "SD card %s: %s", sd_path, err);
        else
            core_log(RETRO_LOG_INFO, "SD card: %s", sd_path);
    }

    if (!chg_load_program(&m, game->path, err, sizeof err)) {
        core_log(RETRO_LOG_ERROR, "%s: %s", game->path, err);
        sdcard_close(m.sd);
        m.sd = NULL;
        return false;
    }

    /* the flash pages a game writes: <save>/<game>.sav, the emulator's own format */
    const char *save_dir = NULL;
    save_path[0] = 0;
    if (env_cb(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY, &save_dir) && save_dir) {
        char tmp[1100];
        snprintf(tmp, sizeof tmp, "%s/%s", save_dir, base_name(game->path));
        chg_save_path(tmp, save_path, sizeof save_path);
    } else {
        chg_save_path(game->path, save_path, sizeof save_path);
    }
    if (chg_load_save(&m, save_path)) core_log(RETRO_LOG_INFO, "loaded %s", save_path);

    chg_reset(&m, true);
    chg_audio_init(&audio, AUDIO_RATE, m.cycles);
    audio.piezo = false;        /* the emulator's default: the bare pin signal */
    apply_options();
    last_sync = m.cycles;
    loaded = true;
    if (m.sd) check_dat_files(game->path, sd_path);
    return true;
}

RETRO_API bool retro_load_game_special(unsigned type, const struct retro_game_info *info, size_t num)
{
    (void)type; (void)info; (void)num;
    return false;
}

RETRO_API void retro_unload_game(void)
{
    if (!loaded) return;
    write_save();
    sdcard_close(m.sd);     /* syncs first */
    m.sd = NULL;
    loaded = false;
}

/* Not part of libretro: for the Android app, which may be killed once it is
   in the background, so the save and the card are written out there */
RETRO_API void chgame_flush(void)
{
    write_save();
    sync_sd();
}

/* Not part of libretro: true once the game has the panel on and has drawn a
   whole frame. Before that the panel shows white (as the real one does at
   power-on), which the app's preview keeps off the screen */
RETRO_API bool chgame_screen_ready(void)
{
    return loaded && m.lcd.display_on && !m.lcd.sleeping && m.lcd.rst_level && m.lcd.frames_written > 0;
}

/* Not part of libretro: SD blocks read so far (a count that stops rising: loading is done) */
RETRO_API unsigned long long chgame_sd_reads(void)
{
    return (unsigned long long)sdspi_blocks_read;
}

RETRO_API void retro_reset(void)
{
    write_save();
    chg_reset(&m, true);
    chg_audio_init(&audio, AUDIO_RATE, m.cycles);
    apply_options();
}

static uint8_t read_buttons(void)
{
    static const struct { unsigned id; uint8_t btn; } map[] = {
        { RETRO_DEVICE_ID_JOYPAD_UP,     CHG_BTN_UP },
        { RETRO_DEVICE_ID_JOYPAD_DOWN,   CHG_BTN_DOWN },
        { RETRO_DEVICE_ID_JOYPAD_LEFT,   CHG_BTN_LEFT },
        { RETRO_DEVICE_ID_JOYPAD_RIGHT,  CHG_BTN_RIGHT },
        { RETRO_DEVICE_ID_JOYPAD_A,      CHG_BTN_A },          /* east, as the emulator's gamepad A */
        { RETRO_DEVICE_ID_JOYPAD_B,      CHG_BTN_B },          /* south */
        { RETRO_DEVICE_ID_JOYPAD_SELECT, CHG_BTN_SELECT },
        { RETRO_DEVICE_ID_JOYPAD_START,  CHG_BTN_START },
    };
    uint8_t b = 0;
    if (use_mask) {
        const int16_t mask = input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK);
        for (size_t i = 0; i < sizeof map / sizeof map[0]; i++)
            if (mask & (1 << map[i].id)) b |= map[i].btn;
    } else {
        for (size_t i = 0; i < sizeof map / sizeof map[0]; i++)
            if (input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, map[i].id)) b |= map[i].btn;
    }
    /* the left stick as the d-pad, with the emulator's threshold */
    const int16_t lx = input_state_cb(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_X);
    const int16_t ly = input_state_cb(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_Y);
    if (lx < -16000) b |= CHG_BTN_LEFT;
    if (lx > 16000) b |= CHG_BTN_RIGHT;
    if (ly < -16000) b |= CHG_BTN_UP;
    if (ly > 16000) b |= CHG_BTN_DOWN;
    return b;
}

RETRO_API void retro_run(void)
{
    bool updated = false;
    if (env_cb(RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE, &updated) && updated) apply_options();

    input_poll_cb();
    chg_set_buttons(&m, read_buttons());
    chg_run(&m, m.cycles + FRAME_CYCLES);

    int n = chg_audio_render(&audio, &m, samples, AUDIO_RATE);
    for (int i = 0; i < n; i++) {
        float v = samples[i] * 32767.0f;
        v = v > 32767.0f ? 32767.0f : v < -32768.0f ? -32768.0f : v;
        stereo[2 * i] = stereo[2 * i + 1] = (int16_t)v;
    }
    if (n) audio_batch_cb(stereo, (size_t)n);

    st7735_render(&m.lcd, pixels);
    if (scale == 1) {
        video_cb(pixels, 128, 128, 128 * sizeof(uint32_t));
    } else {
        const unsigned w = 128 * scale;
        for (unsigned y = 0; y < 128; y++) {
            uint32_t *row = scaled + y * scale * w;
            for (unsigned x = 0; x < 128; x++)
                for (unsigned k = 0; k < scale; k++) row[x * scale + k] = pixels[y * 128 + x];
            for (unsigned k = 1; k < scale; k++) memcpy(row + k * w, row, w * sizeof(uint32_t));
        }
        video_cb(scaled, w, w, w * sizeof(uint32_t));
    }

    /* a game's save is written once its flash writes have stopped for a
       second; what it wrote to the card goes back into the folder every 5 s */
    if (m.flash_written && m.cycles - m.flash_write_cycle > CHG_HCLK) write_save();
    if (m.sd && m.cycles - last_sync > 5ull * CHG_HCLK) sync_sd();
}

/* ------------------------------------------------------------------------ */
/* Save states: the machine is one plain struct apart from the card pointer  */
/* ------------------------------------------------------------------------ */

typedef struct {
    uint32_t magic;
    uint32_t size;
    ChgMachine m;
    ChgAudio audio;
} StateV1;

/* The card is not copied (the front end owns it, and it can be 44 MB of a game's
   sprites), but where its files sit is: the game's FatFs holds sector numbers,
   so on a card built differently (another file in the folder) they read the wrong
   data. The SPI and SD protocol state, a read in flight included, is in m. */
typedef struct {
    uint32_t magic;
    uint32_t size;
    ChgMachine m;
    ChgAudio audio;
    SdLayout layout;
} State;

RETRO_API size_t retro_serialize_size(void) { return sizeof(State); }

RETRO_API bool retro_serialize(void *data, size_t size)
{
    if (size < sizeof(State)) return false;
    State *s = (State *)data;
    memset(&s->layout, 0, sizeof s->layout);
    s->magic = SAVE_STATE_MAGIC;
    s->size = (uint32_t)sizeof(State);
    memcpy(&s->m, &m, sizeof m);
    s->m.sd = NULL;
    memcpy(&s->audio, &audio, sizeof audio);
    if (m.sd) sdcard_layout_get(m.sd, &s->layout);
    return true;
}

RETRO_API bool retro_unserialize(const void *data, size_t size)
{
    const State *s = (const State *)data;
    const ChgMachine *sm;
    const ChgAudio *sa;
    const SdLayout *layout = NULL;
    if (size >= sizeof(State) && s->magic == SAVE_STATE_MAGIC && s->size == sizeof(State)) {
        sm = &s->m; sa = &s->audio; layout = &s->layout;
    } else if (size >= sizeof(StateV1) && s->magic == SAVE_STATE_MAGIC_V1 && s->size == sizeof(StateV1)) {
        /* an older state: no layout, the card is taken as it is */
        const StateV1 *o = (const StateV1 *)data;
        sm = &o->m; sa = &o->audio;
    } else {
        return false;
    }
    struct SdCard *sd = m.sd;
    const float volume = audio.volume;
    const bool piezo = audio.piezo;
    /* the card first, then the machine: it may be reading mid-block */
    if (sd && layout && layout->card_size) {
        if (!sdcard_layout_apply(sd, layout))
            core_log(RETRO_LOG_WARN, "SD card: could not restore the saved layout");
    }
    memcpy(&m, sm, sizeof m);
    memcpy(&audio, sa, sizeof audio);
    m.sd = sd;
    audio.volume = volume;
    audio.piezo = piezo;
    return true;
}

RETRO_API void retro_cheat_reset(void) {}
RETRO_API void retro_cheat_set(unsigned index, bool enabled, const char *code) { (void)index; (void)enabled; (void)code; }

RETRO_API void *retro_get_memory_data(unsigned id)
{
    return id == RETRO_MEMORY_SYSTEM_RAM ? m.ram : NULL;
}

RETRO_API size_t retro_get_memory_size(unsigned id)
{
    return id == RETRO_MEMORY_SYSTEM_RAM ? CHG_RAM_SIZE : 0;
}
