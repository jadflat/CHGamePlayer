/*
 * The app's native front end run on the PC, the way the app's game loop
 * drives it: start, frames with buttons, a state saved and loaded, flush, stop.
 *
 *   fe_test game.chg sysdir savedir seconds out.ppm
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "frontend.h"

static void log_line(int level, const char *msg) { printf("log %d %s", level, msg); }

static void write_ppm(const char *path, const uint32_t *px)
{
    FILE *f = fopen(path, "wb");
    if (!f) return;
    fprintf(f, "P6\n128 128\n255\n");
    for (int i = 0; i < 128 * 128; i++) {
        const uint8_t rgb[3] = { (uint8_t)(px[i] >> 16), (uint8_t)(px[i] >> 8), (uint8_t)px[i] };
        fwrite(rgb, 1, 3, f);
    }
    fclose(f);
}

int main(int argc, char **argv)
{
    if (argc < 6) {
        fprintf(stderr, "usage: fe_test game.chg sysdir savedir seconds out.ppm\n");
        return 2;
    }
    static uint32_t px[128 * 128];
    static int16_t snd[4096 * 2];
    fe_set_log(log_line);
    /* the app's options: FE_VOLUME=80% FE_SOUND=piezo */
    if (getenv("FE_VOLUME")) fe_set_option("chgame_volume", getenv("FE_VOLUME"));
    if (getenv("FE_SOUND")) fe_set_option("chgame_sound", getenv("FE_SOUND"));
    if (!fe_start(argv[1], argv[2], argv[3])) { fprintf(stderr, "start failed\n"); return 1; }
    const int frames = atoi(argv[4]) * 60;
    long samples = 0;
    int alpha_ok = 1, peak = 0;
    char state[1100];
    snprintf(state, sizeof state, "%s/test.state", argv[3]);
    static uint32_t want[128 * 128];
    for (int f = 0; f < frames; f++) {
        const double t = f / 60.0;
        uint16_t b = 0;
        if (t > 3 && t < 3.2) b |= FE_START;
        if (t > 4 && t < 4.2) b |= FE_A;
        if (t > 5 && t < 6) b |= FE_RIGHT;
        const int got = fe_frame(b, px, snd, 4096);
        for (int k = 0; k < got * 2; k++) if (abs(snd[k]) > peak) peak = abs(snd[k]);
        samples += got;
        if (f == frames / 2) {
            if (!fe_save_state(state)) printf("save state failed\n");
            fe_frame(0, want, snd, 4096);
            for (int k = 0; k < 30; k++) fe_frame(0, px, snd, 4096);
            if (!fe_load_state(state)) printf("load state failed\n");
            fe_frame(0, px, snd, 4096);
            printf("state round trip: %s\n", memcmp(want, px, sizeof px) ? "DIFFERS" : "matches");
        }
    }
    for (int i = 0; i < 128 * 128; i++) if ((px[i] >> 24) != 0xff) alpha_ok = 0;
    fe_flush();
    fe_stop();
    write_ppm(argv[5], px);
    printf("%d frames, %ld samples (%.2f s), alpha %s, audio peak %d\n", frames, samples, samples / 48000.0,
           alpha_ok ? "ok" : "BAD", peak);
    return 0;
}
