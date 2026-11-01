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
#include <math.h>
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

#define CORE_VERSION    "0.5.1"
#define AUDIO_RATE      48000
#define FPS             60
#define FRAME_CYCLES    (CHG_HCLK / FPS)
#define FRAME_SAMPLES   (AUDIO_RATE / FPS)
#define MAX_SCALE       6
#define MAX_DATS        8      /* .DAT files put on the card for a game */
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
/* Sound models and tone options: a chain of up to 5 biquads (RBJ cookbook) on
   the mono signal, after the emulator's own audio. Bare pin and Piezo with the
   other options off add nothing, so they match the emulator's output. */
typedef struct { float b0, b1, b2, a1, a2, z1, z2; } Biquad;
static Biquad     chain[5];
static int        nchain;
static bool       soft_clip;        /* volume above 100%: round the peaks instead of clipping */
static float      bq_x(Biquad *q, float x)
{
    const float y = q->b0 * x + q->z1;
    q->z1 = q->b1 * x - q->a1 * y + q->z2;
    q->z2 = q->b2 * x - q->a2 * y;
    return y;
}
/* kind: 'l' low-pass, 'h' high-pass, 'p' peaking, 's' low shelf, 'S' high shelf */
static void bq_add(char kind, float f0, float q, float db)
{
    if (nchain >= 5) return;
    const float w = 6.2831853f * f0 / AUDIO_RATE, cs = cosf(w), sn = sinf(w), al = sn / (2 * q);
    const float A = powf(10.0f, db / 40.0f), sa = 2 * sqrtf(A) * al;
    float b0, b1, b2, a0, a1, a2;
    switch (kind) {
    case 'l': b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = b0; a0 = 1 + al; a1 = -2 * cs; a2 = 1 - al; break;
    case 'h': b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = b0; a0 = 1 + al; a1 = -2 * cs; a2 = 1 - al; break;
    case 'p': b0 = 1 + al * A; b1 = -2 * cs; b2 = 1 - al * A; a0 = 1 + al / A; a1 = -2 * cs; a2 = 1 - al / A; break;
    case 's': b0 = A * ((A + 1) - (A - 1) * cs + sa); b1 = 2 * A * ((A - 1) - (A + 1) * cs);
              b2 = A * ((A + 1) - (A - 1) * cs - sa); a0 = (A + 1) + (A - 1) * cs + sa;
              a1 = -2 * ((A - 1) + (A + 1) * cs); a2 = (A + 1) + (A - 1) * cs - sa; break;
    default:  b0 = A * ((A + 1) + (A - 1) * cs + sa); b1 = -2 * A * ((A - 1) + (A + 1) * cs);
              b2 = A * ((A + 1) + (A - 1) * cs - sa); a0 = (A + 1) - (A - 1) * cs + sa;
              a1 = 2 * ((A - 1) - (A + 1) * cs); a2 = (A + 1) - (A - 1) * cs - sa; break;
    }
    chain[nchain++] = (Biquad){ b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0, 0, 0 };
}
static float      sound_x(float x)
{
    for (int i = 0; i < nchain; i++) x = bq_x(&chain[i], x);
    if (soft_clip) {        /* linear to 0.8, then a smooth knee to 1.0 */
        const float a = x < 0 ? -x : x;
        if (a > 0.8f) { const float y = 0.8f + 0.2f * tanhf((a - 0.8f) / 0.2f); x = x < 0 ? -y : y; }
    }
    return x;
}
static bool       lcd_colour;       /* colour "LCD" */
static bool       fps_osd;          /* the frame counter overlay */
static bool       dat_all;          /* every .DAT in the game's folder goes on the card */
static int        fps_value = -1;   /* the frames a second shown */
static int        fps_frames;
static uint32_t   fps_starts;
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
    { "chgame_sound",  "Sound model; bare pin|piezo|Speaker|Soft|Warm|Crisp" },
    { "chgame_volume", "Volume; 50%|25%|75%|100%|150%|Off" },
    { "chgame_bass",   "Bass; Off|Low|High" },
    { "chgame_lowpass", "Low-pass; Off|Light|Strong" },
    { "chgame_dc_block", "DC block; On|Off" },
    { "chgame_sd",     "microSD card (on load); folder|Image|off" },
    { "chgame_dat_all", "Data files put on the card (on load); Off|All .DAT in folder" },
    { "chgame_cpu",    "CPU speed (frame rate unchanged); 100%|150%|200%|300%" },
#ifdef CHG_JIT
    { "chgame_jit",    "JIT recompiler (aarch64, same result as the interpreter but faster); On|Off" },
#endif
    { "chgame_colour", "Colour; Raw|LCD" },
    { "chgame_fps",    "FPS counter (game frames a second, top right); Off|On" },
    { "chgame_scale",  "Internal upscale (nearest); 1x|2x|3x|4x|5x|6x" },
    { NULL, NULL },
};

static const char *opt(const char *key)
{
    struct retro_variable v = { key, NULL };
    return (env_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &v) && v.value) ? v.value : "";
}

static void apply_options(void)
{
    const char *snd = opt("chgame_sound");
    audio.piezo = !strcmp(snd, "piezo");
    chg_audio_dc_block = strcmp(opt("chgame_dc_block"), "Off") != 0;
    nchain = 0;
    memset(chain, 0, sizeof chain);
    if (!strcmp(snd, "Speaker")) { bq_add('h', 300, 0.7f, 0); bq_add('p', 2500, 1.2f, 5); }
    else if (!strcmp(snd, "Soft")) bq_add('l', 3000, 0.7f, 0);
    else if (!strcmp(snd, "Warm")) { bq_add('l', 5000, 0.7f, 0); bq_add('s', 200, 0.7f, 3); }
    else if (!strcmp(snd, "Crisp")) bq_add('S', 4000, 0.7f, 4);
    const char *bass = opt("chgame_bass");
    if (bass[0] == 'L') bq_add('s', 200, 0.7f, 4);
    else if (bass[0] == 'H') bq_add('s', 200, 0.7f, 8);
    const char *lp = opt("chgame_lowpass");
    if (lp[0] == 'L') bq_add('l', 9000, 0.7f, 0);
    else if (lp[0] == 'S') bq_add('l', 4000, 0.7f, 0);
    soft_clip = nchain > 0 || audio.volume > 1.0f;
    const int cpu = atoi(opt("chgame_cpu"));
    chg_cpu_pct = (cpu >= 100 && cpu <= 300) ? cpu : 100;
#ifdef CHG_JIT
    chg_jit = strcmp(opt("chgame_jit"), "Off") != 0;
#endif
    fps_osd = !strcmp(opt("chgame_fps"), "On");
    lcd_colour = !strcmp(opt("chgame_colour"), "LCD");
    dat_all = opt("chgame_dat_all")[0] == 'A';
    struct retro_variable v;
    v.key = "chgame_volume";
    v.value = NULL;
    if (env_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &v) && v.value)
        audio.volume = (float)atoi(v.value) / 100.0f;
    soft_clip = nchain > 0 || audio.volume > 1.0f;
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

/* 0 generated folder card, 1 off, 2 an image file beside the game */
static int sd_mode(void)
{
    const char *v = opt("chgame_sd");
    return !strcmp(v, "off") ? 1 : !strcmp(v, "Image") ? 2 : 0;
}

static bool file_exists(const char *p)
{
    FILE *f = fopen(p, "rb");
    if (f) fclose(f);
    return f != NULL;
}

/* <game>.img, else chgame.img in the game's folder */
static bool find_image(const char *game, char *out, size_t n)
{
    snprintf(out, n, "%s", game);
    char *dot = strrchr(out, '.');
    char *sl = strrchr(out, '/'), *bs = strrchr(out, '\\');
    if (bs > sl) sl = bs;
    if (dot && dot > sl) snprintf(dot, n - (size_t)(dot - out), ".img");
    else snprintf(out + strlen(out), n - strlen(out), ".img");
    if (file_exists(out)) return true;
    if (sl) { snprintf(sl + 1, n - (size_t)(sl + 1 - out), "chgame.img"); return file_exists(out); }
    snprintf(out, n, "chgame.img");
    return file_exists(out);
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

/* The .DAT names the game file asks for */
static int game_dat_names(const char *path, char names[][16])
{
    FILE *f = fopen(path, "rb");
    if (!f) return 0;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0 || size > 16 << 20) { fclose(f); return 0; }
    uint8_t *data = malloc((size_t)size);
    if (!data) { fclose(f); return 0; }
    size_t n = fread(data, 1, (size_t)size, f);
    fclose(f);

    size_t from = 0, to = n;
    if (n >= 512 && !memcmp(data, "CHG1", 4)) {
        const uint32_t len = data[0x10] | data[0x11] << 8 | data[0x12] << 16 | (uint32_t)data[0x13] << 24;
        from = 512;
        to = (len < n - 512) ? 512 + len : n;
    }

    const int count = find_dat_names(data + from, to - from, names);
    free(data);
    return count;
}

static bool ends_dat(const char *n)
{
    const size_t l = strlen(n);
    return l > 4 && same_name_ci(n + l - 4, ".DAT");
}

/* length of the common prefix, ignoring case */
static int common_prefix(const char *a, const char *b)
{
    int n = 0;
    for (; a[n] && b[n] && a[n] != '.' && b[n] != '.'; n++) {
        int x = a[n], y = b[n];
        if (x >= 'a' && x <= 'z') x -= 32;
        if (y >= 'a' && y <= 'z') y -= 32;
        if (x != y) break;
    }
    return n;
}

static void copy_file(const char *from, const char *to)
{
    FILE *a = fopen(from, "rb"), *b = a ? fopen(to, "wb") : NULL;
    if (b) {
        static uint8_t buf[1 << 16];
        for (size_t n; (n = fread(buf, 1, sizeof buf, a)) > 0; ) fwrite(buf, 1, n, b);
    }
    if (a) fclose(a);
    if (b) fclose(b);
}

/* Up to MAX_DATS .DAT files from the game's folder onto the card (a file
   already there is left alone: a game may write its own): the ones the game
   names first, then others sharing at least 3 letters of prefix with the game's
   name, or every one with the option "All .DAT in folder". */
static void mount_dats(const char *game, const char *card_dir)
{
    char dir[1100], stem[300];
    snprintf(dir, sizeof dir, "%s", game);
    char *sl = strrchr(dir, '/'), *bs = strrchr(dir, '\\');
    if (bs > sl) sl = bs;
    if (sl) *sl = 0; else snprintf(dir, sizeof dir, ".");
    snprintf(stem, sizeof stem, "%s", base_name(game));
    DIR *d = opendir(dir);
    if (!d) return;
    char own[DAT_MAX][16];
    const int nown = game_dat_names(game, own);
    char picked[MAX_DATS][300];
    int n = 0;
    for (int pass = 0; pass < 2; pass++) {
        rewinddir(d);
        for (struct dirent *e; (e = readdir(d)) != NULL; ) {
            if (!ends_dat(e->d_name)) continue;
            bool is_own = false;
            for (int k = 0; k < nown; k++) is_own |= same_name_ci(own[k], e->d_name);
            if (pass == 0 ? !is_own : (is_own || !(dat_all || common_prefix(stem, e->d_name) >= 3))) continue;
            if (n < MAX_DATS) snprintf(picked[n++], sizeof picked[0], "%s", e->d_name);
            else core_log(RETRO_LOG_WARN, "more than %d .DAT files: %s left off", MAX_DATS, e->d_name);
        }
    }
    closedir(d);
    for (int k = 0; k < n; k++) {
        if (sd_has_file(card_dir, picked[k])) continue;
        char from[4096], to[4096];
        snprintf(from, sizeof from, "%s/%s", dir, picked[k]);
        snprintf(to, sizeof to, "%s/%s", card_dir, picked[k]);
        copy_file(from, to);
        core_log(RETRO_LOG_INFO, "put %s on the SD card", picked[k]);
    }
}

/* Tells the player about .DAT files the game needs that are not on the card */
static void check_dat_files(const char *path, const char *card_dir)
{
    char names[DAT_MAX][16];
    const int count = game_dat_names(path, names);
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

    static char shown[400];
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
    const int mode = sd_mode();
    bool folder_card = false;
    char img[1100];
    if (mode == 2 && find_image(game->path, img, sizeof img)) {
        if (!(m.sd = sdcard_open(img, 0, err, sizeof err)))
            core_log(RETRO_LOG_WARN, "SD image %s: %s", img, err);
        else
            core_log(RETRO_LOG_INFO, "SD card image: %s", img);
    } else if (mode != 1) {
        if (mode == 2) core_log(RETRO_LOG_WARN, "no <game>.img or chgame.img beside the game: using the generated card");
        dat_all = opt("chgame_dat_all")[0] == 'A';
        if (env_cb(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY, &sys) && sys) {
            char chg_dir[1024];
            join_dir(chg_dir, sizeof chg_dir, sys, "chgame");
            join_dir(sd_path, sizeof sd_path, chg_dir, "sdcard");
            mount_dats(game->path, sd_path);
            folder_card = true;
            if (!(m.sd = sdcard_open(sd_path, 0, err, sizeof err)))
                core_log(RETRO_LOG_WARN, "SD card %s: %s", sd_path, err);
            else
                core_log(RETRO_LOG_INFO, "SD card: %s", sd_path);
        }
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
    fps_value = -1;
    if (m.sd && folder_card) check_dat_files(game->path, sd_path);

    /* the machine's RAM for cheats and achievements: 20 KB at 0x20000000 */
    static struct retro_memory_descriptor md;
    md.flags = RETRO_MEMDESC_SYSTEM_RAM;
    md.ptr = m.ram;
    md.start = CHG_RAM_BASE;
    md.len = CHG_RAM_SIZE;
    struct retro_memory_map mm = { &md, 1 };
    env_cb(RETRO_ENVIRONMENT_SET_MEMORY_MAPS, &mm);
    /* the card's files are not in a state, and the layout is the machine's own struct */
    uint64_t quirks = RETRO_SERIALIZATION_QUIRK_ENDIAN_DEPENDENT |
                      RETRO_SERIALIZATION_QUIRK_PLATFORM_DEPENDENT;
    env_cb(RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS, &quirks);
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
RETRO_API unsigned chgame_pc(void) { return m.cpu.pc; }

RETRO_API unsigned long long chgame_instret(void) { return m.instret; }

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
        { RETRO_DEVICE_ID_JOYPAD_A,      CHG_BTN_B },          /* east: CHGame has A on the left, B on the right */
        { RETRO_DEVICE_ID_JOYPAD_B,      CHG_BTN_A },          /* south */
        { RETRO_DEVICE_ID_JOYPAD_SELECT, CHG_BTN_START },        /* CHGame has Start on the left */
        { RETRO_DEVICE_ID_JOYPAD_START,  CHG_BTN_SELECT },
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

/* colour "LCD": a cheap TFT's lifted blacks, dimmer whites and a cool cast */
static void tint_lcd(uint32_t *px)
{
    for (int i = 0; i < 128 * 128; i++) {
        const uint32_t c = px[i];
        const uint32_t r = ((c >> 16) & 255) * 212 / 256 + 22;
        const uint32_t g = ((c >> 8) & 255) * 220 / 256 + 28;
        const uint32_t b = (c & 255) * 232 / 256 + 30;
        px[i] = (r << 16) | (g << 8) | (b > 255 ? 255 : b);
    }
}

/* The FPS counter: new frames the game put on the LCD (RAMWR window restarts)
   per second of emulated time, refreshed once a second. */
static void draw_fps(uint32_t *px)
{
    static const uint16_t font[10] = {      /* 3x5 digits; 15 bits, row major */
        0x7B6F, 0x2C97, 0x73E7, 0x73CF, 0x5BC9, 0x79CF, 0x79EF, 0x7249, 0x7BEF, 0x7BCF };
    if (fps_frames++ % FPS == 0 || fps_value < 0) {
        if (fps_value >= 0) fps_value = (int)(m.lcd.frame_starts - fps_starts);
        else fps_value = 0;
        fps_starts = m.lcd.frame_starts;
    }
    char t[8];
    const int len = snprintf(t, sizeof t, "%d", fps_value);
    const int x0 = 128 - (len * 4 + 1) - 1;
    for (int y = 0; y < 7; y++)
        for (int x = 0; x < len * 4 + 1; x++) px[(1 + y) * 128 + x0 + x] = 0;
    for (int k = 0; k < len; k++) {
        const uint16_t g = font[t[k] - '0'];
        for (int y = 0; y < 5; y++)
            for (int x = 0; x < 3; x++)
                if (g >> (14 - (y * 3 + x)) & 1) px[(2 + y) * 128 + x0 + 1 + k * 4 + x] = 0xFFFFFF;
    }
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
        float x = samples[i];
        x = sound_x(x);
        float v = x * 32767.0f;
        v = v > 32767.0f ? 32767.0f : v < -32768.0f ? -32768.0f : v;
        stereo[2 * i] = stereo[2 * i + 1] = (int16_t)v;
    }
    if (n) audio_batch_cb(stereo, (size_t)n);

    st7735_render(&m.lcd, pixels);
    if (lcd_colour) tint_lcd(pixels);
    if (fps_osd) draw_fps(pixels);
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
    memset(s, 0, sizeof *s);     /* padding too, so the same machine always gives the same bytes */
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
#ifdef CHG_JIT
    jit_machine_replaced(&m);
#endif
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

