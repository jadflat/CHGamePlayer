/*
 * The app's "resume where you left off" on the PC: play into a game, write the
 * resume point, stop the game as leaving it does, start it again, continue from
 * the resume point, and check the next frame is the one the first session showed.
 *
 *   fe_resume_test game.chg sysdir savedir seconds
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "frontend.h"

int main(int argc, char **argv)
{
    if (argc < 5) {
        fprintf(stderr, "usage: fe_resume_test game.chg sysdir savedir seconds\n");
        return 2;
    }
    static uint32_t px[128 * 128], want[128 * 128];
    static int16_t snd[4096 * 2];
    char resume[1100];
    snprintf(resume, sizeof resume, "%s/test.auto.state", argv[3]);
    const int frames = atoi(argv[4]) * 60;

    if (!fe_start(argv[1], argv[2], argv[3])) return 1;
    for (int f = 0; f < frames; f++) {
        const double t = f / 60.0;
        uint16_t b = 0;
        if (t > 3 && t < 3.2) b |= FE_START;
        if (t > 4 && t < 4.2) b |= FE_A;
        if (t > 5) b |= (f / 20) % 2 ? FE_RIGHT : FE_A;
        fe_frame(b, px, snd, 4096);
    }
    if (!fe_save_state(resume)) { printf("resume point not written\n"); return 1; }
    fe_frame(0, want, snd, 4096);       /* what would have come next */
    fe_flush();
    fe_stop();                          /* leaving the game */

    if (!fe_start(argv[1], argv[2], argv[3])) return 1;     /* coming back */
    fe_frame(0, px, snd, 4096);         /* a fresh boot's first frame, before the choice */
    if (!fe_load_state(resume)) { printf("resume point not loaded\n"); return 1; }
    const int n = fe_frame(0, px, snd, 4096);
    fe_stop();
    int differ = 0;
    for (int i = 0; i < 128 * 128; i++) differ += px[i] != want[i];
    printf("resume after %d s: %s (%d pixels differ), %d samples in the first frame back\n",
           frames / 60, differ ? "DIFFERS" : "the next frame matches", differ, n);
    return differ != 0;
}
