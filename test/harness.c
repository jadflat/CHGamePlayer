/*
 * A minimal libretro front end for testing the core without RetroArch:
 * loads the core library, runs a game for some seconds with scripted
 * presses, and writes frames (PPM), the sound (WAV) and the speed.
 *
 *   harness core.dll game.chg seconds outdir [--sys dir] [--save dir]
 *           [--press btn@sec[:dur]]... [--state sec]
 *
 * --state: at that time save a state, run one second, load it back, and check
 * the next frame matches the one after the save.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdbool.h>
#ifdef _WIN32
#include <windows.h>
#define LIB HMODULE
#define lib_open(p) LoadLibraryA(p)
#define lib_sym(l, n) (void *)GetProcAddress(l, n)
static double now_s(void)
{
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (double)c.QuadPart / (double)f.QuadPart;
}
#else
#include <dlfcn.h>
#include <time.h>
#define LIB void *
#define lib_open(p) dlopen(p, RTLD_NOW)
#define lib_sym(l, n) dlsym(l, n)
static double now_s(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}
#endif
#include "libretro.h"

static const char *sys_dir = ".", *save_dir = ".";
static uint32_t frame[128 * 128];
static uint32_t full[768 * 768];  /* the whole picture as sent, for a scaled one */
static char opt_key[8][64], opt_val[8][64];
static int nopt;
static unsigned fw, fh;
static FILE *wav;
static uint32_t wav_frames;
static double peak;
static uint16_t held;       /* RETRO_DEVICE_ID_JOYPAD_* bits */
static enum retro_pixel_format pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;

static void log_printf(enum retro_log_level level, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stdout, fmt, ap);
    va_end(ap);
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
        pixfmt = *(enum retro_pixel_format *)data;
        return pixfmt == RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable *v = data;
        for (int i = 0; i < nopt; i++)
            if (!strcmp(v->key, opt_key[i])) { v->value = opt_val[i]; return true; }
        return false;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = false;
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
    if (!data) return;
    fw = w;
    fh = h;
    /* frame: the 128x128 picture (every scale-th pixel of a scaled one); full: as sent */
    const unsigned s = w / 128 ? w / 128 : 1;
    for (unsigned y = 0; y < h && y < 768; y++)
        memcpy(full + y * w, (const uint8_t *)data + y * pitch, (w < 768 ? w : 768) * 4);
    for (unsigned y = 0; y < 128; y++)
        for (unsigned x = 0; x < 128; x++)
            frame[y * 128 + x] = full[y * s * w + x * s];
}

static size_t audio_batch(const int16_t *data, size_t frames)
{
    for (size_t i = 0; i < frames * 2; i++) {
        const double v = data[i] < 0 ? -data[i] / 32768.0 : data[i] / 32767.0;
        if (v > peak) peak = v;
    }
    if (wav) fwrite(data, 4, frames, wav);
    wav_frames += (uint32_t)frames;
    return frames;
}

static void audio_sample(int16_t l, int16_t r) { (void)l; (void)r; }
static void input_poll(void) {}
static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id)
{
    if (port || device != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)held;
    return (held >> id) & 1;
}

static void write_ppm(const char *path)
{
    FILE *f = fopen(path, "wb");
    if (!f) return;
    fprintf(f, "P6\n128 128\n255\n");
    for (int i = 0; i < 128 * 128; i++) {
        const uint8_t rgb[3] = { (uint8_t)(frame[i] >> 16), (uint8_t)(frame[i] >> 8), (uint8_t)frame[i] };
        fwrite(rgb, 1, 3, f);
    }
    fclose(f);
}

static unsigned btn_id(const char *s)
{
    static const struct { const char *n; unsigned id; } t[] = {
        { "up", RETRO_DEVICE_ID_JOYPAD_UP }, { "down", RETRO_DEVICE_ID_JOYPAD_DOWN },
        { "left", RETRO_DEVICE_ID_JOYPAD_LEFT }, { "right", RETRO_DEVICE_ID_JOYPAD_RIGHT },
        { "a", RETRO_DEVICE_ID_JOYPAD_A }, { "b", RETRO_DEVICE_ID_JOYPAD_B },
        { "select", RETRO_DEVICE_ID_JOYPAD_SELECT }, { "start", RETRO_DEVICE_ID_JOYPAD_START },
    };
    for (size_t i = 0; i < sizeof t / sizeof t[0]; i++)
        if (!strcmp(s, t[i].n)) return t[i].id;
    fprintf(stderr, "unknown button %s\n", s);
    exit(2);
}

typedef struct { unsigned id; double at, dur; } Press;

#define SYM(name) name##_t name = (name##_t)lib_sym(lib, #name); \
    if (!name) { fprintf(stderr, "missing %s\n", #name); return 1; }

typedef void (*retro_set_environment_t)(retro_environment_t);
typedef void (*retro_set_video_refresh_t)(retro_video_refresh_t);
typedef void (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t);
typedef void (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void (*retro_set_input_state_t)(retro_input_state_t);
typedef void (*retro_init_t)(void);
typedef void (*retro_deinit_t)(void);
typedef void (*retro_run_t)(void);
typedef void (*retro_get_system_info_t)(struct retro_system_info *);
typedef void (*retro_get_system_av_info_t)(struct retro_system_av_info *);
typedef bool (*retro_load_game_t)(const struct retro_game_info *);
typedef void (*retro_unload_game_t)(void);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool (*retro_serialize_t)(void *, size_t);
typedef bool (*retro_unserialize_t)(const void *, size_t);
typedef unsigned (*retro_api_version_t)(void);

int main(int argc, char **argv)
{
    if (argc < 5) {
        fprintf(stderr, "usage: harness core game seconds outdir [--sys dir] [--save dir] [--press btn@sec[:dur]]... [--state sec]\n");
        return 2;
    }
    const char *core = argv[1], *game = argv[2], *outdir = argv[4];
    const double seconds = atof(argv[3]);
    Press presses[64];
    int npress = 0;
    double state_at = -1;
    for (int i = 5; i < argc; i++) {
        if (!strcmp(argv[i], "--sys") && i + 1 < argc) sys_dir = argv[++i];
        else if (!strcmp(argv[i], "--save") && i + 1 < argc) save_dir = argv[++i];
        else if (!strcmp(argv[i], "--state") && i + 1 < argc) state_at = atof(argv[++i]);
        else if (!strcmp(argv[i], "--opt") && i + 1 < argc && nopt < 8) {
            /* --opt chgame_scale=3x */
            const char *eq = strchr(argv[++i], '=');
            if (!eq) continue;
            snprintf(opt_key[nopt], 64, "%.*s", (int)(eq - argv[i]), argv[i]);
            snprintf(opt_val[nopt], 64, "%s", eq + 1);
            nopt++;
        }
        else if (!strcmp(argv[i], "--press") && i + 1 < argc) {
            char name[32];
            double at = 0, dur = 0.15;
            const char *spec = argv[++i];
            const char *atp = strchr(spec, '@');
            if (!atp) continue;
            snprintf(name, sizeof name, "%.*s", (int)(atp - spec), spec);
            sscanf(atp + 1, "%lf:%lf", &at, &dur);
            if (npress < 64) presses[npress++] = (Press){ btn_id(name), at, dur };
        }
    }

    LIB lib = lib_open(core);
    if (!lib) { fprintf(stderr, "cannot load %s\n", core); return 1; }
    SYM(retro_set_environment) SYM(retro_set_video_refresh) SYM(retro_set_audio_sample)
    SYM(retro_set_audio_sample_batch) SYM(retro_set_input_poll) SYM(retro_set_input_state)
    SYM(retro_init) SYM(retro_deinit) SYM(retro_run) SYM(retro_get_system_info)
    SYM(retro_get_system_av_info) SYM(retro_load_game) SYM(retro_unload_game)
    SYM(retro_serialize_size) SYM(retro_serialize) SYM(retro_unserialize) SYM(retro_api_version)

    struct retro_system_info si;
    retro_get_system_info(&si);
    printf("core %s %s, api %u, extensions %s, need_fullpath %d\n", si.library_name, si.library_version,
           retro_api_version(), si.valid_extensions, si.need_fullpath);

    retro_set_environment(env);
    retro_set_video_refresh(video);
    retro_set_audio_sample(audio_sample);
    retro_set_audio_sample_batch(audio_batch);
    retro_set_input_poll(input_poll);
    retro_set_input_state(input_state);
    retro_init();
    struct retro_game_info gi = { game, NULL, 0, NULL };
    if (!retro_load_game(&gi)) { fprintf(stderr, "load failed\n"); return 1; }
    struct retro_system_av_info av;
    retro_get_system_av_info(&av);
    printf("av %ux%u aspect %.3f fps %.2f rate %.0f\n", av.geometry.base_width, av.geometry.base_height,
           av.geometry.aspect_ratio, av.timing.fps, av.timing.sample_rate);

    char path[1024];
    snprintf(path, sizeof path, "%s/sound.wav", outdir);
    wav = fopen(path, "wb");
    static const uint8_t blank[44] = { 0 };
    if (wav) fwrite(blank, 1, 44, wav);

    const int frames = (int)(seconds * 60);
    double run_time = 0;
    for (int f = 0; f < frames; f++) {
        const double t = f / 60.0;
        held = 0;
        for (int i = 0; i < npress; i++)
            if (t >= presses[i].at && t < presses[i].at + presses[i].dur) held |= (uint16_t)(1u << presses[i].id);
        const double t0 = now_s();
        retro_run();
        run_time += now_s() - t0;
        if ((f + 1) % 60 == 0) {
            snprintf(path, sizeof path, "%s/frame_%03d.ppm", outdir, (f + 1) / 60);
            write_ppm(path);
        }
        if (state_at >= 0 && f == (int)(state_at * 60)) {
            const size_t n = retro_serialize_size();
            void *st = malloc(n);
            static uint32_t after[128 * 128];
            const bool ok = retro_serialize(st, n);
            retro_run();
            memcpy(after, frame, sizeof frame);
            for (int k = 0; k < 60; k++) retro_run();
            const bool back = retro_unserialize(st, n);
            retro_run();
            printf("state: %zu bytes, save %d load %d, frame after load %s\n", n, ok, back,
                   memcmp(after, frame, sizeof frame) ? "DIFFERS" : "matches");
            free(st);
            state_at = -1;
        }
    }
    if (fw != 128) {
        /* a scaled picture: check it is the 128x128 one, each pixel a scale x scale block */
        const unsigned s = fw / 128;
        int bad = 0;
        for (unsigned y = 0; y < fh; y++)
            for (unsigned x = 0; x < fw; x++)
                if (full[y * fw + x] != frame[(y / s) * 128 + x / s]) bad++;
        printf("last frame %ux%u (%ux): %d pixels differ from the 128x128 picture\n", fw, fh, s, bad);
    }
    printf("ran %d frames (%.1f s emulated) in %.3f s: %.1fx real time, %.2f ms a frame; audio %u frames (%.2f s), peak %.3f\n",
           frames, frames / 60.0, run_time, (frames / 60.0) / run_time, run_time * 1000 / frames,
           wav_frames, wav_frames / 48000.0, peak);

    if (wav) {
        uint8_t h[44];
        const uint32_t bytes = wav_frames * 4;
        const uint32_t fields[] = { 36 + bytes, 16, 1 | 2u << 16, 48000, 48000 * 4, 4 | 16u << 16, bytes };
        memcpy(h, "RIFF", 4); memcpy(h + 8, "WAVEfmt ", 8); memcpy(h + 36, "data", 4);
        const int at[] = { 4, 16, 20, 24, 28, 32, 40 };
        for (int k = 0; k < 7; k++)
            for (int b = 0; b < 4; b++) h[at[k] + b] = (uint8_t)(fields[k] >> (8 * b));
        fseek(wav, 0, SEEK_SET);
        fwrite(h, 1, 44, wav);
        fclose(wav);
    }
    retro_unload_game();
    retro_deinit();
    return 0;
}
