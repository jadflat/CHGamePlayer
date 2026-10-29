#ifndef AKA_SDCARD_H
#define AKA_SDCARD_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * The card's storage: 512-byte sectors, from one of
 *   an image file   read and written in place (a whole-card image, as a card
 *                   reader dumps it, MBR and all)
 *   a folder        a FAT32 card built in memory from the folder's files;
 *                   sdcard_sync writes what the program changed back
 */
typedef struct SdCard SdCard;

/* A card from an image file or a folder. An image file that does not exist is
   created, formatted FAT32 at size_mb (0: the default, 256 MB). */
SdCard  *sdcard_open(const char *path, uint32_t size_mb, char *err, size_t errlen);
void     sdcard_close(SdCard *c);           /* syncs first */
uint64_t sdcard_sectors(const SdCard *c);
bool     sdcard_read(SdCard *c, uint64_t lba, uint8_t *buf, uint32_t count);
bool     sdcard_write(SdCard *c, uint64_t lba, const uint8_t *buf, uint32_t count);
/* Where a folder card put its files, for a save state: the cluster each file
   and directory starts at, and the card's size. The program (FatFs) caches
   sector numbers, so a state resumed on a card laid out differently (another
   file in the folder, a different total size) would read the wrong sectors.
   sdcard_layout_apply rebuilds the card so every entry sits where it did. */
#define SDLAYOUT_MAX  40
#define SDLAYOUT_PATH 56
typedef struct {
    char     path[SDLAYOUT_PATH];   /* relative to the folder; "" for the root */
    uint32_t start, nclus;
} SdLayoutEntry;
typedef struct {
    uint64_t      card_size;        /* 0: no layout (an image card) */
    uint32_t      n, overflow;      /* overflow: entries that did not fit */
    SdLayoutEntry e[SDLAYOUT_MAX];
} SdLayout;
bool     sdcard_layout_get(SdCard *c, SdLayout *out);
/* true if the card now matches the layout (or already did) */
bool     sdcard_layout_apply(SdCard *c, const SdLayout *l);

/* a folder card: the files written back into the folder; an image: flushed */
bool     sdcard_sync(SdCard *c);

/* Writes a card image of size_mb (0: big enough for the folder, at least
   64 MB) holding folder's files, or an empty one when folder is NULL. */
bool     sdcard_make_image(const char *out, const char *folder, uint32_t size_mb, char *err, size_t errlen);

#endif
