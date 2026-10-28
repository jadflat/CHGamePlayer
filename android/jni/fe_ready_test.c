/*
 * The list preview's "screen ready" on the PC: for each game, the first frame
 * fe_screen_ready() is true (the preview hides every frame before it), that the
 * first frame shown is not blank, and that none of the next 5 s is blank white.
 *
 *   fe_ready_test sysdir savedir game.chg...
 */
#include <stdio.h>
#include <string.h>
#include "frontend.h"

static int all_white(const uint32_t *px)
{
    for (int i = 0; i < 128 * 128; i++)
        if ((px[i] & 0xffffff) != 0xffffff) return 0;
    return 1;
}

int main(int argc, char **argv)
{
    static uint32_t px[128 * 128];
    static int16_t snd[4096 * 2];
    int bad = 0;
    for (int g = 3; g < argc; g++) {
        if (!fe_start(argv[g], argv[1], argv[2])) { printf("start failed: %s\n", argv[g]); return 1; }
        int ready_at = -1, white_before = 0, frames_before = 0, first_blank = 0;
        for (int f = 0; f < 600 && ready_at < 0; f++) {
            fe_frame(0, px, snd, 4096);
            if (fe_screen_ready()) {
                ready_at = f;
                first_blank = all_white(px);
            } else {
                frames_before++;
                white_before += all_white(px);
            }
        }
        int white_after = 0;    /* blank frames once shown: the preview would show these */
        for (int f = 0; f < 300; f++) {
            fe_frame(0, px, snd, 4096);
            white_after += all_white(px);
        }
        fe_stop();
        const char *name = strrchr(argv[g], '/');
        name = name ? name + 1 : argv[g];
        const int ok = ready_at >= 0 && !first_blank && white_after == 0;
        bad += !ok;
        printf("%-22s ready at %.2f s; %3d frames hidden (%d white); first shown blank: %s; blank in the next 5 s: %d  %s\n",
               name, ready_at / 60.0, frames_before, white_before, first_blank ? "yes" : "no", white_after,
               ok ? "ok" : "CHECK");
    }
    return bad != 0;
}
