/*
 * The list's live preview on the PC: start a game, run it a second with no
 * buttons, stop, next game, the way scrolling through the list does. Prints
 * how long a start and a stop take (the card image is built on every start).
 *
 *   fe_preview_test sysdir savedir rounds game.chg...
 */
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#ifdef _WIN32
#include <windows.h>
static double now_s(void)
{
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (double)c.QuadPart / (double)f.QuadPart;
}
#endif
#include "frontend.h"

int main(int argc, char **argv)
{
    if (argc < 5) return 2;
    static uint32_t px[128 * 128];
    static int16_t snd[4096 * 2];
    const int rounds = atoi(argv[3]);
    double worst_start = 0, worst_stop = 0, total_start = 0, total_stop = 0;
    int n = 0;
    for (int r = 0; r < rounds; r++)
        for (int g = 4; g < argc; g++) {
            double t0 = now_s();
            if (!fe_start(argv[g], argv[1], argv[2])) { printf("start failed: %s\n", argv[g]); return 1; }
            double t1 = now_s();
            for (int f = 0; f < 60; f++) fe_frame(0, px, snd, 4096);
            double t2 = now_s();
            fe_stop();
            double t3 = now_s();
            const double st = (t1 - t0) * 1000, sp = (t3 - t2) * 1000;
            total_start += st; total_stop += sp; n++;
            if (st > worst_start) worst_start = st;
            if (sp > worst_stop) worst_stop = sp;
        }
    printf("%d previews: start avg %.1f ms (worst %.1f), stop avg %.1f ms (worst %.1f)\n",
           n, total_start / n, worst_start, total_stop / n, worst_stop);
    return 0;
}
