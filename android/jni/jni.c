/*
 * JNI for com.jadflat.chgame.Native: the Java side's door into frontend.c.
 * The core's log goes to a file in the app's private folder (no liblog).
 */
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include "frontend.h"

#define AUDIO_MAX 4096      /* stereo samples a frame can return; a frame makes 800 */

static FILE *log_file;
static uint32_t px[128 * 128];
static int16_t audio[AUDIO_MAX * 2];

static void log_line(int level, const char *msg)
{
    if (!log_file) return;
    fprintf(log_file, "%d %s", level, msg);
    fflush(log_file);
}

/* a Java string as a C path; false for null or too long */
static bool get_path(JNIEnv *env, jstring s, char *out, size_t outlen)
{
    if (!s) return false;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    if (!c) return false;
    const bool ok = strlen(c) < outlen;
    if (ok) strcpy(out, c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return ok;
}

JNIEXPORT jboolean JNICALL Java_com_jadflat_chgame_Native_start(JNIEnv *env, jclass cls,
    jstring game, jstring sys, jstring saves, jstring log)
{
    char g[1024], s[1024], v[1024], l[1024];
    if (!get_path(env, game, g, sizeof g) || !get_path(env, sys, s, sizeof s) || !get_path(env, saves, v, sizeof v))
        return JNI_FALSE;
    if (log_file) fclose(log_file);
    log_file = get_path(env, log, l, sizeof l) ? fopen(l, "w") : NULL;
    fe_set_log(log_line);
    return fe_start(g, s, v) ? JNI_TRUE : JNI_FALSE;
}

/* a core option, e.g. chgame_volume = 80% */
JNIEXPORT void JNICALL Java_com_jadflat_chgame_Native_setOption(JNIEnv *env, jclass cls,
    jstring key, jstring value)
{
    char k[32], v[32];
    if (get_path(env, key, k, sizeof k) && get_path(env, value, v, sizeof v)) fe_set_option(k, v);
}

/* pixels: int[128 * 128] ARGB; sound: short[] stereo; returns the stereo samples written */
JNIEXPORT jint JNICALL Java_com_jadflat_chgame_Native_frame(JNIEnv *env, jclass cls,
    jint buttons, jintArray pixels, jshortArray sound)
{
    if (!pixels || !sound || (*env)->GetArrayLength(env, pixels) < 128 * 128) return 0;
    int max = (*env)->GetArrayLength(env, sound) / 2;
    if (max > AUDIO_MAX) max = AUDIO_MAX;
    const int n = fe_frame((uint16_t)buttons, px, audio, max);
    (*env)->SetIntArrayRegion(env, pixels, 0, 128 * 128, (const jint *)px);
    if (n > 0) (*env)->SetShortArrayRegion(env, sound, 0, n * 2, audio);
    return n;
}

JNIEXPORT jboolean JNICALL Java_com_jadflat_chgame_Native_screenReady(JNIEnv *env, jclass cls)
{
    return fe_screen_ready() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_jadflat_chgame_Native_flush(JNIEnv *env, jclass cls)
{
    fe_flush();
}

JNIEXPORT jboolean JNICALL Java_com_jadflat_chgame_Native_saveState(JNIEnv *env, jclass cls, jstring path)
{
    char p[1024];
    return get_path(env, path, p, sizeof p) && fe_save_state(p) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_jadflat_chgame_Native_loadState(JNIEnv *env, jclass cls, jstring path)
{
    char p[1024];
    return get_path(env, path, p, sizeof p) && fe_load_state(p) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_jadflat_chgame_Native_stop(JNIEnv *env, jclass cls)
{
    fe_stop();
    if (log_file) {
        fclose(log_file);
        log_file = NULL;
    }
}
