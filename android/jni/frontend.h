#ifndef CHG_FRONTEND_H
#define CHG_FRONTEND_H

#include <stdbool.h>
#include <stdint.h>

/* buttons for fe_frame: 1 << RETRO_DEVICE_ID_JOYPAD_* */
enum {
    FE_B = 1 << 0, FE_SELECT = 1 << 2, FE_START = 1 << 3, FE_UP = 1 << 4, FE_DOWN = 1 << 5,
    FE_LEFT = 1 << 6, FE_RIGHT = 1 << 7, FE_A = 1 << 8,
};

typedef void (*fe_log_fn)(int level, const char *msg);

void fe_set_log(fe_log_fn fn);
/* a core option (chgame_sound, chgame_volume, ...), before fe_start or while running */
void fe_set_option(const char *key, const char *value);
/* game: a .CHG path; sys: the folder holding chgame/sdcard; saves: where <game>.sav goes */
bool fe_start(const char *game, const char *sys, const char *saves);
/* one 1/60 s frame: 128x128 ARGB into argb, up to max_frames stereo samples
   (48 kHz) into stereo; returns how many samples */
int  fe_frame(uint16_t buttons, uint32_t *argb, int16_t *stereo, int max_frames);
void fe_flush(void);
/* the game has the screen on and has drawn a whole frame (before that it is white) */
bool fe_screen_ready(void);
bool fe_save_state(const char *path);
bool fe_load_state(const char *path);
void fe_stop(void);

#endif
