/*
 * The buzzer, turned into samples.
 *
 * bus.c logs every change to what drives PB10 with the cycle it happened
 * on: a steady level, or a PWM wave from TIM1 described by its period, its
 * high time and where a period started. Each output sample is the average
 * of the pin over exactly the cycles that sample covers, worked out in
 * closed form, so a 20 kHz tone and a tone at a frequency that does not
 * divide the sample rate come out without aliasing and without stepping the
 * CPU a cycle at a time. A DC blocker then does what the piezo's AC coupling
 * does, and a filter fitted to a recording of a real CHGame what the piezo
 * itself does: low tones much quieter, a resonance around 4-6 kHz
 * (piezo_filter.c, tools/fit_piezo.py). It is off by default: the bare pin
 * signal, unless the front end switches the filter on (F7, --piezo).
 */
#include <math.h>
#include <string.h>
#include "machine.h"
#include "audio.h"

int chg_audio_dc_block = 1;    /* 0: the pin's DC offset is left in */

void chg_audio_init(ChgAudio *a, int rate, uint64_t now)
{
    memset(a, 0, sizeof(*a));
    a->rate = rate;
    a->cycles_per_sample = (double)CHG_HCLK / rate;
    a->pos = (double)now;
    a->volume = 0.5f;
    a->piezo = false;           /* the bare pin signal unless asked for */
    a->state.cycle = now;
}

/* the PWM's active time up to t (cumulative, from the origin) */
static inline double pwm_cum(const ChgBuzzState *s, double t)
{
    const double P = s->period, H = s->high, d = t - (double)s->origin;
    const double k = floor(d / P);
    return k * H + fmin(d - P * k, H);
}

/* F(t0) of the sample that follows is F(t1) of this one while the state holds */
static double  cum_t, cum_v;
static bool    cum_ok;
static double  cum_P, cum_H;
static uint64_t cum_o;

/* how long the pin spends at level 1 in [t0, t1) under state s */
static double high_time(const ChgBuzzState *s, double t0, double t1)
{
    if (!s->pwm)
        return s->level ? t1 - t0 : 0.0;
    const double f1 = pwm_cum(s, t1);
    const double f0 = (cum_ok && cum_t == t0 && cum_P == s->period && cum_H == s->high && cum_o == (uint64_t)s->origin) ? cum_v : pwm_cum(s, t0);
    cum_t = t1; cum_v = f1; cum_ok = true; cum_P = s->period; cum_H = s->high; cum_o = (uint64_t)s->origin;
    const double activeTime = f1 - f0;
    return s->level ? activeTime : (t1 - t0) - activeTime;
}

int chg_audio_render(ChgAudio *a, ChgMachine *m, float *out, int max)
{
    ChgBuzzer *b = &m->buzzer;
    int n = 0;
    const double end = (double)m->cycles;
    while (n < max && a->pos + a->cycles_per_sample <= end) {
        const double t0 = a->pos, t1 = t0 + a->cycles_per_sample;
        double high = 0.0, t = t0;
        /* every logged change that falls inside this sample splits it */
        while (b->tail != b->head && (double)b->log[b->tail].cycle < t1) {
            const ChgBuzzState *next = &b->log[b->tail];
            const double at = (double)next->cycle > t ? (double)next->cycle : t;
            high += high_time(&a->state, t, at);
            t = at;
            a->state = *next; cum_ok = false;
            b->tail = (b->tail + 1) % CHG_BUZZ_LOG;
        }
        high += high_time(&a->state, t, t1);
        const float level = (float)(high / a->cycles_per_sample);

        /* AC coupling: a first order high pass at about 20 Hz */
        a->dc += (level - a->dc) * (float)(2.0 * 3.14159265 * 20.0 / a->rate);
        float v = level - (chg_audio_dc_block ? a->dc : 0.0f);
        /* what the piezo makes of it: its response measured on a real
           CHGame. The filter is fitted at 48 kHz, so other rates skip it */
        a->fir[a->fir_pos] = v;
        if (a->piezo && a->rate == 48000) {
            float acc = 0.0f;
            int k = a->fir_pos;
            for (int i = 0; i < chg_piezo_taps; i++) {
                acc += chg_piezo_fir[i] * a->fir[k];
                k = k ? k - 1 : chg_piezo_taps - 1;
            }
            v = acc;
        }
        a->fir_pos = a->fir_pos + 1 < chg_piezo_taps ? a->fir_pos + 1 : 0;
        out[n++] = v * a->volume;
        a->pos = t1;
    }
    return n;
}

void chg_audio_skip(ChgAudio *a, ChgMachine *m)
{
    /* throws away what was logged without playing it, e.g. while fast forwarding */
    ChgBuzzer *b = &m->buzzer;
    while (b->tail != b->head) {
        a->state = b->log[b->tail]; cum_ok = false;
        b->tail = (b->tail + 1) % CHG_BUZZ_LOG;
    }
    a->pos = (double)m->cycles;
}
