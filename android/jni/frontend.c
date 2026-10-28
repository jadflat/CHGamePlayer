/*
 * The app's front end for the CHGame core: the same libretro core RetroArch
 * loads (libretro/libretro.c), linked in and driven directly. Plain C with no
 * JNI, so tests on the PC drive it the same way the app does (jni.c).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include "libretro.h"
#include "frontend.h"

void chgame_flush(void);
bool chgame_screen_ready(void);

static char sys_dir[1024], save_dir[1024];
static uint32_t *out_px;            /* where this frame's picture goes */
static int16_t *out_audio;
static int out_audio_max, out_audio_n;
static uint16_t pad;                /* RETRO_DEVICE_ID_JOYPAD_* bits held */
static bool running;
static fe_log_fn log_fn;

/* core options the app sets (libretro.c's chgame_* variables); the core's defaults otherwise */
#define MAX_OPTS 8
static char opt_key[MAX_OPTS][32], opt_val[MAX_OPTS][32];
static int nopt;
static bool opts_changed;

static void log_printf(enum retro_log_level level, const char *fmt, ...)
{
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (log_fn) log_fn((int)level, buf);
}

static bool env(unsigned cmd, void *data)
{
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((struct retro_log_callback *)data)->log = log_printf;
        return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char **)data = sys_dir;
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = save_dir;
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        return *(enum retro_pixel_format *)data == RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable *v = data;
        for (int i = 0; i < nopt; i++)
            if (!strcmp(v->key, opt_key[i])) { v->value = opt_val[i]; return true; }
        return false;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = opts_changed;
        opts_changed = false;
        return true;
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        return true;
    }
    return false;
}

static void video(const void *data, unsigned w, unsigned h, size_t pitch)
{
    if (!data || !out_px || w != 128 || h != 128) return;
    for (unsigned y = 0; y < 128; y++) {
        const uint32_t *src = (const uint32_t *)((const uint8_t *)data + y * pitch);
        for (unsigned x = 0; x < 128; x++) out_px[y * 128 + x] = src[x] | 0xff000000u;
    }
}

static size_t audio_batch(const int16_t *data, size_t frames)
{
    size_t room = (size_t)(out_audio_max - out_audio_n);
    if (frames > room) frames = room;
    if (out_audio) memcpy(out_audio + out_audio_n * 2, data, frames * 4);
    out_audio_n += (int)frames;
    return frames;
}

static void audio_sample(int16_t l, int16_t r) { (void)l; (void)r; }
static void input_poll(void) {}

static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id)
{
    if (port || device != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)pad;
    return id < 16 ? (pad >> id) & 1 : 0;
}

void fe_set_log(fe_log_fn fn) { log_fn = fn; }

void fe_set_option(const char *key, const char *value)
{
    int i = 0;
    while (i < nopt && strcmp(opt_key[i], key)) i++;
    if (i == MAX_OPTS) return;
    if (i == nopt) {
        snprintf(opt_key[i], sizeof opt_key[i], "%s", key);
        nopt++;
    }
    snprintf(opt_val[i], sizeof opt_val[i], "%s", value);
    opts_changed = true;    /* a running game picks it up next frame */
}

bool fe_start(const char *game, const char *sys, const char *saves)
{
    if (running) fe_stop();
    snprintf(sys_dir, sizeof sys_dir, "%s", sys);
    snprintf(save_dir, sizeof save_dir, "%s", saves);
    retro_set_environment(env);
    retro_set_video_refresh(video);
    retro_set_audio_sample(audio_sample);
    retro_set_audio_sample_batch(audio_batch);
    retro_set_input_poll(input_poll);
    retro_set_input_state(input_state);
    retro_init();
    struct retro_game_info gi = { game, NULL, 0, NULL };
    if (!retro_load_game(&gi)) {
        retro_deinit();
        return false;
    }
    running = true;
    return true;
}

int fe_frame(uint16_t buttons, uint32_t *argb, int16_t *stereo, int max_frames)
{
    if (!running) return 0;
    pad = buttons;
    out_px = argb;
    out_audio = stereo;
    out_audio_max = max_frames;
    out_audio_n = 0;
    retro_run();
    out_px = NULL;
    out_audio = NULL;
    return out_audio_n;
}

bool fe_screen_ready(void)
{
    return running && chgame_screen_ready();
}

void fe_flush(void)
{
    if (running) chgame_flush();
}

bool fe_save_state(const char *path)
{
    if (!running) return false;
    const size_t n = retro_serialize_size();
    void *buf = malloc(n);
    bool ok = buf && retro_serialize(buf, n);
    if (ok) {
        FILE *f = fopen(path, "wb");
        ok = f && fwrite(buf, 1, n, f) == n;
        if (f) fclose(f);
    }
    free(buf);
    return ok;
}

bool fe_load_state(const char *path)
{
    if (!running) return false;
    const size_t n = retro_serialize_size();
    void *buf = malloc(n);
    FILE *f = fopen(path, "rb");
    bool ok = buf && f && fread(buf, 1, n, f) == n;
    if (f) fclose(f);
    ok = ok && retro_unserialize(buf, n);
    free(buf);
    return ok;
}

void fe_stop(void)
{
    if (!running) return;
    retro_unload_game();
    retro_deinit();
    running = false;
}
